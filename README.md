# runJar

Runs an arbitrary Java JAR on Android.

Android's own runtime is ART, which consumes DEX bytecode and cannot execute a
standard JAR. This app does not try to make it do so. Instead it starts a
complete OpenJDK virtual machine **inside the app process** through the JNI
invocation API, and hands the JAR to that VM.

```
┌─ MainActivity (Compose) ──────────┐
│  pick JAR · main class · args     │
├─ RunViewModel ────────────────────┤   prepares the runtime, shows output
├─ JvmService  (process ":jvm") ────┤   isolates the guest from the UI process
├─ JNI bridge (librunjar_jni.so) ───┤   dlopen + JNI_CreateJavaVM
├─ libjvm.so (OpenJDK for Android) ─┤   the guest VM
└─ the target JAR ──────────────────┘
```

## How it works

1. **Runtime acquisition** — `JreInstaller` downloads
   `jre<major>-pojav.zip` from MojoLauncher's `android-openjdk-build-17-25`
   release into the app's private storage.

   The published archive is *not* an exploded JRE. It is a flat container of
   six members (`zip -j`): a `universal.tar.xz` with the architecture
   independent files, and one `bin-<abi>.tar.xz` per Android ABI. A usable
   runtime is both layers unpacked on top of each other, ABI layer last.

   The archive members are xz-compressed tars. Android ships neither, so the
   app depends on `org.tukaani:xz` (the same reader MojoLauncher uses) and
   carries a small `TarReader` for the subset GNU tar emits for these archives.

   The runtime must live in the app's data directory: since Android 10 (API
   29) binaries in shared storage cannot be executed, and the guest's
   libraries are loaded by absolute path regardless.

2. **Loading the VM** — the JNI bridge in `src/main/cpp` makes the runtime's
   libraries reachable, then `dlopen`s `libjvm.so` from it.

   Two things are needed, and neither is optional:

   - **The linker search path is updated** through Bionic's hidden
     `android_update_LD_LIBRARY_PATH`. Android's linker reads the search path
     once, when the process starts, so setting `LD_LIBRARY_PATH` with `setenv`
     afterwards does nothing.
   - **Every library in the runtime is preloaded** by absolute path with
     `RTLD_GLOBAL`, repeatedly until a pass makes no progress. Updating the
     search path is *not sufficient*: the guest VM `dlopen`s its own libraries
     by bare name (`libjava`, `libzip`, `libnet`, `libnio`, ...) and so does the
     class loader machinery underneath it, and those lookups go through the
     app's classloader namespace, which does not consult that path. Once a
     library is resident under its SONAME a later bare-name `dlopen` finds it
     instead of searching — which is what makes the guest start.

   On a device this shows up as `libnio.so: dlopen failed: library "libnet.so"
   not found ... in namespace clns-9`, from a guest that otherwise came up
   fine, and it only appears when the first class is loaded.

   `libjvm.so` then creates a second, fully independent JavaVM. Its classes
   come from the JRE's own `lib/modules` image, loaded by its own class loader —
   entirely separate from the app's ART world.

3. **Running the JAR** — the bridge finds the main class, builds a `String[]`,
   and calls `main`. Everything the JAR prints is captured by pointing the
   process's stdout and stderr at a log file with `dup2` before the VM is
   created, so `System.out`, uncaught-exception traces and any native library
   that writes to stdout all land in one stream the UI tails.

### Details that matter

- **One VM per process, and one process per run.** OpenJDK permits a single VM
  per process and its shutdown is a no-op on Android, and a JVM accumulates
  state that can only be set once — `URL.setURLStreamHandlerFactory` is the one
  Spring Boot's embedded Tomcat trips over. A VM that has already run something
  is therefore not a clean place to run the next JAR, so the *process* is the
  unit of reuse: the app ends the guest process before starting another run, and
  each run gets a fresh JVM, exactly as `java -jar` does.
- **A run is over when the JVM would exit.** `main` returning is not the end: a
  web application's `main` returns as soon as its server is listening. The
  bridge checks the JVM's own exit rule — whether any non-daemon thread is still
  running — and reports either "the JAR finished" (the process is then retired)
  or "main returned, the JVM is still running", which leaves the app serving
  until Stop is pressed.
- **The guest, not `main`, is what the screen follows.** A JAR whose `main`
  returned is still running, so the run button stays on "Running…" and Stop
  stays live for as long as the guest process exists: Stop ends that process,
  which is meaningful whether or not `main` is on the stack, and the
  notification's Stop action does the same. The screen asks the service process
  whether it holds a guest whenever it binds, so a UI that has been restarted —
  Android may end the UI process while the foreground guest keeps serving — shows
  the run as running again and offers the Stop that ends it, instead of an idle
  app whose JAR still holds its port.
- **A separate process, hosted as a foreground service.** `JvmService` runs in
  `:jvm`, so a JAR that ends the guest VM, trips a VM assertion or corrupts its
  heap takes down only that process and leaves the UI running. While a guest
  exists the service is a foreground service, because Android freezes a process
  that is merely backgrounded: its listening sockets stay open but nothing
  accepts on them, so every request hangs. That was observable directly — with
  the app in the foreground the guest answered `HTTP 404`, and 25 seconds after
  pressing Home the same request timed out. It is also how the UI can stop a run
  at all (below).
- **`System.exit` ends the guest process.** HotSpot calls the `exit` and
  `abort` option hooks just before it terminates, and they report the reason
  into the log, but they cannot cancel the exit: the VM runs its own shutdown
  once they return. That is correct JVM behaviour, so the app treats it as the
  end of the process rather than of the run: the UI watches the guest process
  and reports the outcome when it goes away. The next run starts a fresh
  process, and so a fresh VM.
- **Stopping a run ends its process.** There is no way to interrupt arbitrary
  Java code: the guest is a real VM running its own threads, and OpenJDK's
  shutdown path is a no-op on the Android port. Stop therefore kills the `:jvm`
  process, which is another reason the guest has a process of its own.
- **Signal handlers are reset** before VM creation. HotSpot installs its own
  crash and polling handlers only for signals it finds unclaimed; Bionic and ART
  have already taken most of them, and a stale handler makes the VM mis-detect
  the signal model it is running on. `SIGSEGV` is set to `SIG_IGN` rather than
  `SIG_DFL`, because Bionic treats a default-disposition `SIGSEGV` as an
  application error.
- **stdout is made line buffered.** Redirected to a file it would be block
  buffered, and a JAR that prints and then does slow work would appear to print
  nothing at all.
- **A run works in a directory of its own, inside the app's storage.** An app
  process starts in `/`, which is read-only, so a JAR that writes anything
  relative — a server its world, a framework a file it generates at startup —
  fails there; the Ktor sample in this README's own testing fails with `Failed
  to create OpenAPI output directory: docs`. Every run therefore gets a
  directory named after its JAR, created on demand and reused from run to run so
  the data a JAR writes survives being restarted. That directory is the
  process's working directory before the VM is created — which is what the VM
  reports as `user.dir`, and so what every relative path resolves against — and
  it is `user.home` as well, so what a JAR writes for itself lands beside its
  other files instead of in the app's private data. `java.io.tmpdir` stays
  private: scratch files are not the user's. The console says which directory
  the run used.
  The directory is the app's *external* one
  (`Android/data/<id>/files/runJar/<jar name>`), so the files are on shared
  storage and can be taken off the device with `adb pull` or over USB. It is not
  the Download folder: reaching that by path needs "All files access"
  (`MANAGE_EXTERNAL_STORAGE`), which is a restricted permission a published app
  that is neither a file manager nor a backup tool cannot hold. When external
  storage is unavailable the internal directory is used instead, and the run
  behaves the same way.
- **`libfreetype.so.6` is renamed to `libfreetype.so`** after unpacking,
  because the guest's libraries reference the unversioned name.
- **`-Djdk.lang.Process.launchMechanism=FORK`** because `POSIX_SPAWN` needs
  `jspawnhelper`, which Android does not ship; a JAR that shells out would
  otherwise fail to start a process.

## Choosing a runtime

| Release | JDK  | Published ABIs             | Notes |
|---------|------|----------------------------|-------|
| `jre17` | 17   | arm, arm64, x86, x86_64    | Widest JAR compatibility |
| `jre21` | 21   | arm, arm64, x86, x86_64    | Default |
| `jre25` | 25   | arm, arm64, x86_64         | Newest; no 32-bit x86 build |

The picker only offers the releases published for the device's ABI, and the
installer refuses one that is not, rather than downloading an archive that
turns out to have no layer for it.

A JAR must be compiled for a class file version the chosen runtime accepts
(≤ 61 for Java 17, ≤ 65 for Java 21, ≤ 69 for Java 25). The bundled sample JAR
is version 61 and runs on all three.

The app itself is built for all four ABIs. A 64-bit guest needs a 64-bit host
process, and on a 64-bit device Android installs the 64-bit build, but note
that on a 32-bit-only device the guest is 32-bit too and the heap it can
address is correspondingly smaller.

## Day and night

There is no appearance setting: the app follows the device, in all three of its
windows. Two different things have to follow it for that to be true, and only
one of them is Compose's.

- **What the app draws** follows the system through the Compose theme:
  `RunJarTheme` takes its scheme from `isSystemInDarkTheme()`, and on Android 12
  and up from the system's dynamic palette, so the app is drawn in the device's
  own colours — the dark ones while the device is dark.
- **The window underneath** is the platform's, and it exists before Compose
  does: it is what the launcher fills with the starting window while the process
  comes up. It is a resource, so it follows the system through the night
  qualifier — `values/themes.xml` and `values-night/themes.xml` are the light
  and dark halves of `Theme.RunJar` — and its background is the colour the
  Compose scheme draws for that mode: the Material 3 baseline background, or
  from API 34 the framework's own `system_background` token, which is the very
  colour `dynamicDarkColorScheme` puts behind the app on a device whose palette
  follows its wallpaper.

A light-only window theme is what this looked like before: the app came up dark
on a dark device while the starting window was white, so the launch was a flash
of the wrong colour. Everything else about a launch is unchanged, including the
icon on the starting window, which is the launcher's.

## About and permissions

The app explains itself in two screens, both reached from the run screen's top
bar — and the permission guide opens by itself on the first launch, before there
is a JAR in flight and a run that depends on the answer.

- **Permissions** — every permission the app declares, what it does with it, and
  whether the device has granted it, with a button that asks for the ones it has
  not. The list is rendered from the same `RunPermissions` a run asks from, and a
  unit test holds it to the manifest, so neither can drift from the other.
- **About** — what the app is for, how a JAR comes to run on Android at all, the
  credit for the runtime packaging and startup recipe (MojoLauncher, and
  PojavLauncher before it), and the components the app ships or fetches with
  their licences.

Neither screen carries a close button, and neither registers a back callback:
each is an activity of its own. Back from them is therefore a real
cross-activity back, and the platform is what previews and performs it — the run
screen, which is genuinely the window behind them, slides in as the swipe is
made, and slides back if the user lets go. The app draws none of that; all it
declares is `enableOnBackInvokedCallback`, so back is routed through the
dispatcher rather than the legacy callback. (Being states of the run screen
instead would have the platform preview the home screen — the run activity is
the root of its task — while the commit stayed inside the app.)

## Permissions

A JAR's own manifest permissions are **not** merged into the APK. Anything the
JAR needs — network, a listening port — must be declared by the app, and
requested at runtime where the platform asks.

The app declares five permissions: `INTERNET` (the runtime download, and every
socket the guest opens — its traffic is the app's), `ACCESS_LOCAL_NETWORK`,
`FOREGROUND_SERVICE` with `FOREGROUND_SERVICE_SPECIAL_USE` (the guest is hosted
in a foreground service, or a run would stop accepting the moment the app is
left), and `POST_NOTIFICATIONS` (the ongoing notification for a run, with its
Stop action). Nothing else is declared: no storage, contacts, location or camera
access.

A JAR that serves other devices needs `ACCESS_LOCAL_NETWORK` on Android 17
(API 37) and up, where the platform gates an app's local-network traffic behind
that runtime permission. Without it the guest binds its port and answers the
phone itself — loopback, and the phone's own LAN address, which the kernel
delivers locally — while every connection from another device is dropped
without a reply, which reads as a firewall rather than a permission. The app
declares it and asks for it when a run starts, next to `POST_NOTIFICATIONS`.

A run's working directory is inside the app's own storage, so it needs no
storage permission at all: `Android/data/<id>/files/runJar/<jar name>`, which
the JAR can write and which can be taken off the device with `adb pull` or over
USB. Download itself is deliberately not used — reaching it by path requires
"All files access" (`MANAGE_EXTERNAL_STORAGE`), a restricted permission that a
published app which is neither a file manager nor a backup tool cannot hold.

## Building

```bash
./gradlew :app:assembleDebug
```

The native bridge is built by CMake through the NDK for all four ABIs
(`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`), because a 64-bit guest needs a
64-bit host process and the app's primary ABI decides which that is.

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

Unit tests cover the tar reader against a fixture with GNU long names,
block-spanning payloads and zero-length files, assert the runtime archive
carries a layer for every packaged ABI, and pin the permission guide to the
manifest's permission set.

The extraction tests additionally verify that unpacking the real layers yields
a startable runtime. They need the archive and skip without it:

```bash
RUNJAR_JRE_FIXTURE=/path/to/dir/with/layers \
RUNJAR_JRE_ARCHIVE=/path/to/jre21-pojav.zip \
./gradlew :app:testDebugUnitTest
```

`JarExecutionTest` (androidTest) is the end-to-end check: it runs a real JAR
through the whole pipeline — booting the guest VM through the JNI bridge,
invoking `main`, and reading back what it printed — and asserts the failure
paths report rather than crash. `JvmServiceRunTest` covers the path the app
actually takes, handing the run to the service in its own process and waiting
for the outcome broadcast; it also pins the reason each run gets a fresh
process, by running a JAR that claims a set-once JVM registration and leaves a
thread behind, showing that reusing that VM fails and that a retired one works.
Both download the runtime on first run, so they need a device or emulator with
network access:

```bash
./gradlew :app:connectedDebugAndroidTest
```

Both suites pass on a real arm64 device running Android 17: 14 unit tests and
6 instrumentation tests, none skipped.

The bridge itself has no unit-testable surface off-device: it dlopens an
Android-built `libjvm.so`, which links against Bionic and cannot load on a
desktop libc. Anything that changes `src/main/cpp` needs the instrumentation
test to be meaningful.
