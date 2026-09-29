package com.kxxnzstdsw.runjar.jvm

/**
 * Binding for the native bridge in `src/main/cpp/jni_bridge.c`.
 *
 * The native side owns one guest JavaVM per process: OpenJDK permits a single
 * VM per process and its shutdown is a no-op on the Android port, so a run is
 * "invoke main and collect what it printed", not "start and stop a VM".
 */
object JavaRunner {

    /**
     * Ensures the guest VM exists, then invokes `mainClass.main(appArgs)` on it.
     *
     * Everything the guest writes to stdout/stderr is redirected to [outPath]
     * (appending) before the VM is touched, so the caller can tail the file.
     * [nativeLibDir] is the app's own native library directory, which goes on
     * the guest's linker search path alongside the runtime's.
     *
     * @return `OK` when main returned normally, otherwise `ERROR <message>`.
     */
    fun bootstrap(
        jvmPath: String,
        javaHome: String,
        nativeLibDir: String,
        vmArgs: Array<String>,
        appArgs: Array<String>,
        mainClass: String,
        outPath: String,
    ): String = nativeBootstrap(
        jvmPath, javaHome, nativeLibDir, vmArgs, appArgs, mainClass, outPath,
    )

    private external fun nativeBootstrap(
        jvmPath: String,
        javaHome: String,
        nativeLibDir: String,
        vmArgs: Array<String>,
        appArgs: Array<String>,
        mainClass: String,
        outPath: String,
    ): String
}
