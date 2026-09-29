// In-process JVM bootstrap.
//
// The app process is ART, which cannot execute a plain JAR (it consumes DEX).
// To run one we dlopen() the OpenJDK runtime unpacked into the app's private
// storage and ask it for a JavaVM through the JNI invocation API. That VM is
// independent of ART: its classes come from the JRE's own `lib/modules` image,
// resolved by its own application class loader.
//
// There are two pieces of platform friction this file exists to handle, both
// discovered by the MojoLauncher/PojavLauncher ports:
//
//  1. Android's linker caches its library search path when the process starts.
//     The guest VM dlopens libjli.so, libjava.so and the JNI agents by bare
//     name, so the runtime's lib directory has to be injected through Bionic's
//     hidden android_update_LD_LIBRARY_PATH — setting the environment variable
//     has no effect. See update_ld_library_path().
//
//  2. OpenJDK allows one VM per process and its shutdown is a no-op on the
//     Android port, so the VM is created once and reused for every run.
//
// The guest is started on the thread that calls in, so callers drive it from a
// background thread of their own.

#include <jni.h>
#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "log_bridge.h"

typedef jint (*create_java_vm_fn)(JavaVM **, void **, const void *);
typedef void (*update_ld_library_path_fn)(const char *);

typedef struct {
    JavaVM *vm;
    void *libjvm;
    pthread_mutex_t lock;
} vm_state_t;

static vm_state_t g_state = {
        .vm = NULL,
        .libjvm = NULL,
        .lock = PTHREAD_MUTEX_INITIALIZER,
};

static char *heap_dup(const char *src) {
    size_t len = strlen(src);
    char *copy = malloc(len + 1);
    if (copy != NULL) memcpy(copy, src, len + 1);
    return copy;
}

static char *heap_fmt(const char *fmt, ...) __attribute__((format(printf, 1, 2)));

static char *heap_fmt(const char *fmt, ...) {
    char buf[PATH_MAX * 2];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    return heap_dup(buf);
}

static void read_jstring(JNIEnv *env, jstring value, char *out, size_t out_size) {
    if (out_size == 0) return;
    out[0] = '\0';
    if (value == NULL) return;
    const char *chars = (*env)->GetStringUTFChars(env, value, NULL);
    if (chars == NULL) return;
    snprintf(out, out_size, "%s", chars);
    (*env)->ReleaseStringUTFChars(env, value, chars);
}

/**
 * Hands a heap-allocated C string to Java and releases it.
 *
 * Passing the `char *` back as the return value would compile — a C string
 * converts to `void *`, which is what `jstring` decays to — but the VM would
 * then be handed a pointer to text where it expects a managed object.
 */
static jstring take_jstring(JNIEnv *env, char *owned) {
    if (owned == NULL) return NULL;
    jstring result = (*env)->NewStringUTF(env, owned);
    free(owned);
    return result;
}

// ---------------------------------------------------------------------------
// Guest termination hooks
// ---------------------------------------------------------------------------

// Guest termination hooks.
//
// HotSpot calls these immediately before it terminates the process. They can
// report why the guest is going away, but they cannot cancel the exit: the VM
// runs its own shutdown once the hook returns. A JAR that calls System.exit
// therefore ends the guest process — which is what a JVM is supposed to do. The
// caller learns the run ended by observing that process, not by getting a
// result back.
//
// The reason goes to the log, which is where the user will look for it.
static void vm_exit(jint code) {
    fprintf(stdout, "=== the JAR called System.exit(%d); the VM has stopped ===\n", (int) code);
    fflush(stdout);
}

static void vm_abort(void) {
    fprintf(stdout, "=== the JAR aborted the VM; the VM has stopped ===\n");
    fflush(stdout);
}

// ---------------------------------------------------------------------------
// Platform setup
// ---------------------------------------------------------------------------

/**
 * Adds the guest runtime's directories to Bionic's library search path.
 *
 * `android_update_LD_LIBRARY_PATH` is a hidden export of libdl that rewrites
 * the linker's search path in place; the process environment variable is read
 * once at startup and changing it later does nothing. Without this, the VM
 * starts and then fails to dlopen its own libjli.so.
 *
 * The path is kept in a static buffer because Bionic may retain the pointer.
 */
static void update_ld_library_path(const char *path) {
    static char retained[PATH_MAX * 4];
    snprintf(retained, sizeof(retained), "%s", path);

    void *libdl = dlopen("libdl.so", RTLD_LAZY);
    if (libdl == NULL) {
        LOGW("cannot open libdl.so: %s", dlerror());
        return;
    }
    void *symbol = dlsym(libdl, "android_update_LD_LIBRARY_PATH");
    if (symbol == NULL) {
        symbol = dlsym(libdl, "__loader_android_update_LD_LIBRARY_PATH");
    }
    if (symbol == NULL) {
        LOGW("libdl.so has no android_update_LD_LIBRARY_PATH; the guest runtime "
             "will not be able to dlopen its own libraries");
        return;
    }
    ((update_ld_library_path_fn) symbol)(retained);
    LOGI("linker search path updated: %s", retained);
}

/**
 * Points the process's stdout and stderr at [path].
 *
 * Done by descriptor rather than by handing the guest a Java PrintStream, so
 * that everything the JAR touches reaches the log: `System.out` (which writes
 * to fd 1), uncaught-exception traces (fd 2), and any native library the JAR
 * loads that writes to stdout itself.
 *
 * @return null on success, otherwise a short reason.
 */
static const char *redirect_stdio(const char *path) {
    int fd = open(path, O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd < 0) return "cannot open the log file";

    // Only valid before stdout has been written to, so it is done once even if
    // several runs share the process.
    static bool buffers_configured = false;
    if (!buffers_configured) {
        // A file is block buffered by default, and a JAR that prints and then
        // blocks would appear to have printed nothing at all.
        setvbuf(stdout, NULL, _IOLBF, 0);
        setvbuf(stderr, NULL, _IONBF, 0);
        buffers_configured = true;
    }

    if (dup2(fd, STDOUT_FILENO) < 0 || dup2(fd, STDERR_FILENO) < 0) {
        close(fd);
        return "cannot redirect stdout and stderr";
    }
    if (fd > STDERR_FILENO) close(fd);
    return NULL;
}

/**
 * Makes [path] the process's working directory.
 *
 * A JAR that touches the filesystem resolves relative paths there, and the VM
 * reports it as `user.dir` — the property `java.io` resolves against — from the
 * directory the process was in when the VM was created. An app process starts
 * in `/`, which is read-only, so a JAR that writes anything relative — a server
 * its world, a framework a generated file — fails with a permission error
 * unless the run is given somewhere of its own to be.
 *
 * The caller is expected to have created [path]; failing to enter it is
 * reported rather than fatal, since a run whose JAR touches nothing still
 * works.
 *
 * @return null on success, otherwise a short reason.
 */
static const char *enter_working_directory(const char *path) {
    if (path[0] == '\0') return NULL;
    if (chdir(path) != 0) return "cannot enter the run directory";
    return NULL;
}

/**
 * Hands HotSpot a clean signal slate.
 *
 * It installs its own crash and polling handlers only for signals it finds
 * unclaimed; Bionic and ART have already taken most of them, and a stale
 * handler makes the VM mis-detect the signal model it is running on. SIGSEGV is
 * set to SIG_IGN rather than SIG_DFL because Bionic specifically treats a
 * SIG_DFL SIGSEGV as an application error.
 */
static void prepare_signal_handlers(void) {
    struct sigaction clean;
    memset(&clean, 0, sizeof(clean));
    for (int sig = 1; sig < NSIG; sig++) {
        clean.sa_handler = (sig == SIGSEGV) ? SIG_IGN : SIG_DFL;
        sigaction(sig, &clean, NULL);
    }
}

// ---------------------------------------------------------------------------
// VM lifecycle
// ---------------------------------------------------------------------------

/**
 * Makes the runtime's shared libraries resolvable by bare name.
 *
 * The guest VM dlopens its own libraries by name (`libjava`, `libzip`,
 * `libnet`, `libnio`, ...) and so does the class loading machinery underneath
 * it. Those names are not on any search path the app's linker namespace
 * consults — updating the search path is not enough on newer Android, which is
 * why preloading is what actually works: once a library is resident under its
 * SONAME, a later bare-name dlopen finds it instead of searching.
 *
 * Each library is loaded with RTLD_GLOBAL so its symbols are visible to the
 * ones loaded after it. A library whose own dependencies are not resident yet
 * fails on the first pass and succeeds on a later one, so the passes repeat
 * until one of them makes no progress.
 *
 * The handles are deliberately never closed: the runtime stays resident for the
 * life of the VM.
 */
static void preload_runtime_libraries(const char *home) {
    char dirs[3][PATH_MAX];
    snprintf(dirs[0], sizeof(dirs[0]), "%s/lib", home);
    snprintf(dirs[1], sizeof(dirs[1]), "%s/lib/server", home);
    snprintf(dirs[2], sizeof(dirs[2]), "%s/lib/jli", home);

    int total = 0;
    for (int pass = 0; pass < 4; pass++) {
        int loaded_this_pass = 0;
        for (int d = 0; d < 3; d++) {
            DIR *dir = opendir(dirs[d]);
            if (dir == NULL) continue;
            struct dirent *entry;
            while ((entry = readdir(dir)) != NULL) {
                const char *name = entry->d_name;
                size_t len = strlen(name);
                if (len < 4 || strcmp(name + len - 3, ".so") != 0) continue;

                char path[PATH_MAX];
                int written = snprintf(path, sizeof(path), "%s/%s", dirs[d], name);
                if (written < 0 || (size_t) written >= sizeof(path)) continue;
                // Already resident from this or an earlier pass.
                if (dlopen(path, RTLD_LAZY | RTLD_NOLOAD) != NULL) continue;

                if (dlopen(path, RTLD_LAZY | RTLD_GLOBAL) != NULL) {
                    loaded_this_pass++;
                }
            }
            closedir(dir);
        }
        total += loaded_this_pass;
        if (loaded_this_pass == 0) break;
    }
    LOGI("preloaded %d runtime libraries from %s", total, home);
}

static JavaVM *create_vm(JNIEnv *env, jstring jvm_path, const char *java_home,
                         const char *ld_path, jobjectArray vm_args,
                         char *err, size_t err_size) {
    char jvm_so[PATH_MAX];
    read_jstring(env, jvm_path, jvm_so, sizeof(jvm_so));

    update_ld_library_path(ld_path);
    preload_runtime_libraries(java_home);

    void *handle = dlopen(jvm_so, RTLD_NOW | RTLD_GLOBAL);
    if (handle == NULL) {
        const char *e = dlerror();
        snprintf(err, err_size, "dlopen(%s) failed: %s", jvm_so, e != NULL ? e : "unknown");
        return NULL;
    }
    create_java_vm_fn create = (create_java_vm_fn) dlsym(handle, "JNI_CreateJavaVM");
    if (create == NULL) {
        snprintf(err, err_size, "JNI_CreateJavaVM is missing from %s", jvm_so);
        dlclose(handle);
        return NULL;
    }

    jsize arg_count = (*env)->GetArrayLength(env, vm_args);
    // Two extra slots for the "exit" and "abort" hooks HotSpot consults instead
    // of terminating the process.
    JavaVMOption *options = calloc((size_t) arg_count + 2, sizeof(JavaVMOption));
    if (options == NULL) {
        snprintf(err, err_size, "out of memory building VM options");
        dlclose(handle);
        return NULL;
    }
    jsize copied = 0;
    for (jsize i = 0; i < arg_count; i++) {
        jstring item = (jstring) (*env)->GetObjectArrayElement(env, vm_args, i);
        if (item == NULL) continue;
        const char *chars = (*env)->GetStringUTFChars(env, item, NULL);
        if (chars == NULL) continue;
        options[copied].optionString = heap_dup(chars);
        (*env)->ReleaseStringUTFChars(env, item, chars);
        if (options[copied].optionString == NULL) continue;
        LOGI("VM option: %s", options[copied].optionString);
        copied++;
    }
    options[copied].optionString = (char *) "exit";
    options[copied].extraInfo = (void *) vm_exit;
    copied++;
    options[copied].optionString = (char *) "abort";
    options[copied].extraInfo = (void *) vm_abort;
    copied++;

    prepare_signal_handlers();

    JavaVMInitArgs init_args = {
            .version = JNI_VERSION_1_6,
            .nOptions = copied,
            .options = options,
            .ignoreUnrecognized = JNI_TRUE,
    };

    JavaVM *vm = NULL;
    void *vm_env = NULL;
    jint status = create(&vm, &vm_env, &init_args);

    // Only the caller's options are heap allocated; the two hook slots are
    // string literals.
    for (jsize i = 0; i < arg_count; i++) free((void *) options[i].optionString);
    free(options);

    if (status != JNI_OK || vm == NULL) {
        snprintf(err, err_size, "JNI_CreateJavaVM failed with status %d", (int) status);
        dlclose(handle);
        return NULL;
    }
    g_state.libjvm = handle;
    LOGI("guest VM created");
    return vm;
}

/** Attaches the calling thread to the guest VM and returns its JNIEnv. */
static JNIEnv *attach_vm(void) {
    // The attachment is deliberately never released. JNI requires a native
    // thread to detach before it exits, and callers are expected to drive runs
    // from a thread they keep alive for the process's lifetime — which is what
    // the service's single worker thread is. Detaching the thread that created
    // the VM would be worse still: it is the guest's main thread.
    // The JNI entry points take a `void **` out-parameter, so the environment
    // goes through a void pointer rather than a JNIEnv* whose address would
    // have the wrong type.
    void *raw_env = NULL;
    jint status = (*g_state.vm)->GetEnv(g_state.vm, &raw_env, JNI_VERSION_1_6);
    if (status == JNI_OK) return (JNIEnv *) raw_env;
    if (status == JNI_EDETACHED) {
        JavaVMAttachArgs args = {
                .version = JNI_VERSION_1_6,
                .name = (char *) "runjar",
                .group = NULL,
        };
        raw_env = NULL;
        if ((*g_state.vm)->AttachCurrentThreadAsDaemon(g_state.vm, &raw_env, &args) == JNI_OK) {
            return (JNIEnv *) raw_env;
        }
    }
    return NULL;
}

/** Renders a throwable as "class: message" for the console. */
static void describe_throwable(JNIEnv *env, jthrowable ex, char *out, size_t out_size) {
    out[0] = '\0';
    if (ex == NULL) {
        snprintf(out, out_size, "no exception details");
        return;
    }
    jclass throwable_cls = (*env)->FindClass(env, "java/lang/Throwable");
    if (throwable_cls == NULL) {
        (*env)->ExceptionClear(env);
        snprintf(out, out_size, "throwable of unknown type");
        return;
    }
    jmethodID to_string = (*env)->GetMethodID(env, throwable_cls, "toString",
                                              "()Ljava/lang/String;");
    if (to_string != NULL) {
        jstring text = (jstring) (*env)->CallObjectMethod(env, ex, to_string);
        if (!(*env)->ExceptionCheck(env)) {
            read_jstring(env, text, out, out_size);
        } else {
            (*env)->ExceptionClear(env);
        }
    }
    if (out[0] == '\0') snprintf(out, out_size, "throwable without message");
    (*env)->DeleteLocalRef(env, throwable_cls);
}

/**
 * Whether the guest still has work of its own, using the JVM's own exit rule:
 * it stays up while any non-daemon thread is running.
 *
 * A web application's `main` returns as soon as its server is listening, so a
 * run is not necessarily over when main does. Our calling thread is attached as
 * a daemon, so it does not count itself.
 */
static bool has_live_non_daemon_threads(JNIEnv *env) {
    jclass thread_cls = (*env)->FindClass(env, "java/lang/Thread");
    if (thread_cls == NULL) {
        (*env)->ExceptionClear(env);
        return false;
    }
    jmethodID active_count = (*env)->GetStaticMethodID(env, thread_cls, "activeCount", "()I");
    jmethodID enumerate = (*env)->GetStaticMethodID(env, thread_cls, "enumerate",
                                                    "([Ljava/lang/Thread;)I");
    jmethodID is_daemon = (*env)->GetMethodID(env, thread_cls, "isDaemon", "()Z");
    if (active_count == NULL || enumerate == NULL || is_daemon == NULL) {
        (*env)->ExceptionClear(env);
        return false;
    }

    jmethodID current_thread = (*env)->GetStaticMethodID(env, thread_cls, "currentThread",
                                                         "()Ljava/lang/Thread;");
    if (current_thread == NULL) {
        (*env)->ExceptionClear(env);
        return false;
    }
    jobject self = (*env)->CallStaticObjectMethod(env, thread_cls, current_thread);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return false;
    }

    jint estimated = (*env)->CallStaticIntMethod(env, thread_cls, active_count);
    // activeCount is documented as an estimate, so leave room for it to grow.
    jint capacity = estimated * 2 + 8;
    jobjectArray threads = (*env)->NewObjectArray(env, capacity, thread_cls, NULL);
    if (threads == NULL) {
        (*env)->ExceptionClear(env);
        return false;
    }

    bool live = false;
    jint found = (*env)->CallStaticIntMethod(env, thread_cls, enumerate, threads);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return false;
    }
    for (jint i = 0; i < found && !live; i++) {
        jobject thread = (*env)->GetObjectArrayElement(env, threads, i);
        if (thread == NULL) continue;
        // The calling thread is the VM's main thread, which is never a daemon;
        // counting it would make every run look like it is still going.
        if ((*env)->IsSameObject(env, thread, self)) continue;
        if (!(*env)->CallBooleanMethod(env, thread, is_daemon)) live = true;
    }
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    return live;
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

JNIEXPORT jstring JNICALL
Java_com_kxxnzstdsw_runjar_jvm_JavaRunner_nativeBootstrap(JNIEnv *env, jclass clazz,
                                                          jstring jvm_path,
                                                          jstring java_home,
                                                          jstring native_lib_dir,
                                                          jobjectArray vm_args,
                                                          jobjectArray app_args,
                                                          jstring main_class,
                                                          jstring work_dir,
                                                          jstring out_path) {
    (void) clazz;
    char home[PATH_MAX];
    char native_dir[PATH_MAX];
    char run_dir[PATH_MAX];
    char out_file[PATH_MAX];
    read_jstring(env, java_home, home, sizeof(home));
    read_jstring(env, native_lib_dir, native_dir, sizeof(native_dir));
    read_jstring(env, work_dir, run_dir, sizeof(run_dir));
    read_jstring(env, out_path, out_file, sizeof(out_file));

    pthread_mutex_lock(&g_state.lock);

    // The guest's libraries live beside libjvm.so; the app's own native library
    // directory goes on the path too, because the guest dlopens the platform's
    // shared libraries by bare name as well.
    char ld_path[PATH_MAX * 2];
    int ld_len = snprintf(ld_path, sizeof(ld_path), "%s/lib/server:%s/lib:%s/lib/jli:%s",
                          home, home, home, native_dir);
    if (ld_len < 0 || (size_t) ld_len >= sizeof(ld_path)) {
        pthread_mutex_unlock(&g_state.lock);
        return take_jstring(env, heap_dup("ERROR the guest runtime path is too long"));
    }

    if (g_state.vm == NULL) {
        // Done before the VM exists: HotSpot reads the working directory when
        // it builds `user.dir`, and every relative path the JAR uses resolves
        // against that property for the life of the VM.
        const char *dir_error = enter_working_directory(run_dir);
        if (dir_error != NULL) LOGW("%s: %s", dir_error, run_dir);

        char err[PATH_MAX];
        g_state.vm = create_vm(env, jvm_path, home, ld_path, vm_args, err, sizeof(err));
        if (g_state.vm == NULL) {
            pthread_mutex_unlock(&g_state.lock);
            return take_jstring(env, heap_fmt("ERROR %s", err));
        }
    }

    const char *redirect_error = redirect_stdio(out_file);
    if (redirect_error != NULL) {
        pthread_mutex_unlock(&g_state.lock);
        return take_jstring(env, heap_fmt("ERROR %s (%s)", redirect_error, out_file));
    }

    JNIEnv *vm_env = attach_vm();
    if (vm_env == NULL) {
        pthread_mutex_unlock(&g_state.lock);
        return take_jstring(env, heap_dup("ERROR cannot attach the calling thread to the guest VM"));
    }

    // Accept both dotted and slashed class names.
    char slashed[1024];
    read_jstring(env, main_class, slashed, sizeof(slashed));
    for (char *p = slashed; *p != '\0'; p++) {
        if (*p == '.') *p = '/';
    }

    jclass main_cls = (*vm_env)->FindClass(vm_env, slashed);
    if ((*vm_env)->ExceptionCheck(vm_env) || main_cls == NULL) {
        jthrowable ex = (*vm_env)->ExceptionOccurred(vm_env);
        (*vm_env)->ExceptionClear(vm_env);
        char desc[PATH_MAX];
        describe_throwable(vm_env, ex, desc, sizeof(desc));
        pthread_mutex_unlock(&g_state.lock);
        return take_jstring(env, heap_fmt("ERROR main class %s not found: %s", slashed, desc));
    }

    jmethodID main_method = (*vm_env)->GetStaticMethodID(vm_env, main_cls, "main",
                                                         "([Ljava/lang/String;)V");
    if (main_method == NULL || (*vm_env)->ExceptionCheck(vm_env)) {
        (*vm_env)->ExceptionClear(vm_env);
        pthread_mutex_unlock(&g_state.lock);
        return take_jstring(env, heap_fmt("ERROR %s has no public static void main(String[])", slashed));
    }

    jsize arg_count = (*env)->GetArrayLength(env, app_args);
    jclass string_cls = (*vm_env)->FindClass(vm_env, "java/lang/String");
    jobjectArray argv = (*vm_env)->NewObjectArray(vm_env, arg_count, string_cls, NULL);
    for (jsize i = 0; i < arg_count; i++) {
        jstring src = (jstring) (*env)->GetObjectArrayElement(env, app_args, i);
        if (src == NULL) continue;
        const char *chars = (*env)->GetStringUTFChars(env, src, NULL);
        if (chars == NULL) continue;
        jstring dst = (*vm_env)->NewStringUTF(vm_env, chars);
        (*env)->ReleaseStringUTFChars(env, src, chars);
        if (dst != NULL) (*vm_env)->SetObjectArrayElement(vm_env, argv, i, dst);
    }
    if ((*vm_env)->ExceptionCheck(vm_env)) (*vm_env)->ExceptionClear(vm_env);

    (*vm_env)->CallStaticVoidMethod(vm_env, main_cls, main_method, argv);

    char *result;
    if ((*vm_env)->ExceptionCheck(vm_env)) {
        jthrowable ex = (*vm_env)->ExceptionOccurred(vm_env);
        (*vm_env)->ExceptionClear(vm_env);
        // The trace itself goes to stderr, which is already the log file; this
        // summary is what the caller shows as the run's outcome.
        jclass throwable_cls = (*vm_env)->FindClass(vm_env, "java/lang/Throwable");
        if (throwable_cls != NULL) {
            jmethodID print = (*vm_env)->GetMethodID(vm_env, throwable_cls, "printStackTrace", "()V");
            if (print != NULL) (*vm_env)->CallVoidMethod(vm_env, ex, print);
            if ((*vm_env)->ExceptionCheck(vm_env)) (*vm_env)->ExceptionClear(vm_env);
        }
        char desc[PATH_MAX];
        describe_throwable(vm_env, ex, desc, sizeof(desc));
        result = heap_fmt("ERROR %s", desc);
    } else if (has_live_non_daemon_threads(vm_env)) {
        // main returned, but the application left threads of its own running —
        // a web server, a scheduler, a thread pool. `java -jar` would stay up
        // here, and so does the guest.
        result = heap_dup("OK:STILL_RUNNING");
    } else {
        result = heap_dup("OK");
    }

    // Push anything the guest still holds in a buffer out to the log.
    fflush(stdout);
    fflush(stderr);

    pthread_mutex_unlock(&g_state.lock);
    return take_jstring(env, result);
}
