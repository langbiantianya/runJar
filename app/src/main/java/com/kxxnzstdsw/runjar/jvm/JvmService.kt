package com.kxxnzstdsw.runjar.jvm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.util.Log
import com.kxxnzstdsw.runjar.MainActivity
import com.kxxnzstdsw.runjar.R
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "JvmService"

/**
 * Hosts the guest JVM in a process of its own.
 *
 * Two reasons this is not the UI process: a JAR that calls `System.exit`,
 * trips a JVM assertion or corrupts its heap takes down only this process, and
 * a 64-bit guest heap needs a 64-bit host process, which the UI process may
 * not be (ART picks the app's primary ABI, and 32-bit apps cannot map a 64-bit
 * libjvm.so at all).
 *
 * The guest runtime is loaded once per process and reused, so a second run
 * costs only the time `main` takes.
 */
class JvmService : Service() {

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "runjar-jvm").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)

    /**
     * Whether this process holds a guest VM.
     *
     * OpenJDK builds one VM per process and cannot undo it, so a process that
     * has hosted a guest hosts exactly that guest for the rest of its life. The
     * flag is therefore set as the VM is asked for and never cleared: it is
     * what Stop acts on, because a guest that has outlived its `main` is still
     * a guest — the process is the only thing that can end it — and what a UI
     * that has just been restarted asks about.
     */
    private val guestHosted = AtomicBoolean(false)

    private var installer: JreInstaller? = null

    override fun onCreate() {
        super.onCreate()
        installer = JreInstaller(this)
        System.loadLibrary("runjar_jni")
        createNotificationChannel()
    }

    /**
     * A running JAR may be a server, and Android freezes a process that is
     * merely backgrounded — its listening sockets stay open but nothing accepts
     * on them, so requests hang. A foreground service is exempt from that, which
     * is the difference between a server that keeps serving and one that only
     * answers while the app is on screen.
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun showRunningNotification(jarName: String) {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, JvmService::class.java).setAction(JvmProtocol.CMD_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_running, jarName))
            .setContentText(getString(R.string.notification_running_detail))
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(Notification.Action.Builder(null, getString(R.string.action_stop), stop).build())
            .build()

        // The three-argument form arrived in API 29, which is this app's floor.
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    /**
     * Returns a binder so callers can watch this process, and ask what is in it.
     *
     * The guest VM cannot outlive the process it lives in, and a JAR is free to
     * end that process with `System.exit`. There is no result to return in that
     * case, so the UI learns a run ended by observing the death of this
     * process — see `RunViewModel`'s death recipient.
     *
     * Its one call answers whether the process holds a guest, which is how a UI
     * that has been restarted — and so has forgotten the run it started — takes
     * charge of the guest that is still serving.
     */
    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == JvmProtocol.TRANSACTION_HOSTS_GUEST) {
                reply?.writeInt(if (guestHosted.get()) 1 else 0)
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            JvmProtocol.CMD_RUN -> {
                val release = JreRelease.byId(intent.getStringExtra(EXTRA_JRE))
                val jarPath = intent.getStringExtra(EXTRA_JAR).orEmpty()
                val mainClass = intent.getStringExtra(EXTRA_MAIN).orEmpty()
                val args = intent.getStringArrayExtra(EXTRA_ARGS) ?: emptyArray()
                val logPath = intent.getStringExtra(EXTRA_LOG).orEmpty()
                val heapMb = intent.getIntExtra(EXTRA_HEAP_MB, DEFAULT_HEAP_MB)
                handleRun(release, jarPath, mainClass, args, logPath, heapMb, startId)
            }

            JvmProtocol.CMD_STOP -> handleStop()

            JvmProtocol.CMD_SHUTDOWN -> terminateGuestProcess()

            else -> stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    /**
     * Ends the process hosting the guest VM.
     *
     * A JVM has state that can only be set once — `URL.setURLStreamHandlerFactory`
     * is the one Spring Boot's embedded Tomcat trips over — so a VM that has
     * already run something is not a clean place to run the next JAR. `java -jar`
     * gives every invocation a fresh JVM, and so does this: the process is the
     * unit of reuse, not the VM.
     *
     * Ending it is the only way to stop one, too: OpenJDK's shutdown path is a
     * no-op on the Android port and arbitrary Java code cannot be interrupted.
     */
    private fun terminateGuestProcess() {
        Log.i(TAG, "ending the guest process")
        Handler(Looper.getMainLooper()).postDelayed({
            Process.killProcess(Process.myPid())
        }, BROADCAST_GRACE_MS)
    }

    /**
     * Abandons the current run.
     *
     * There is no way to ask arbitrary Java code to stop: the guest is a real
     * VM running its own threads, and OpenJDK's own shutdown path is a no-op on
     * the Android port. The only honest stop is to end the process the VM lives
     * in, which is why the guest has one of its own.
     *
     * That is true for as long as the guest exists, not only while its `main`
     * is on the stack: a web application's `main` returns as soon as its server
     * is listening, and what serves afterwards is the guest. Nothing here
     * depends on a run being "in flight", because the process the request
     * reaches exists to hold a guest — a request that finds none ends it
     * anyway rather than leaving an empty process behind.
     *
     * The outcome broadcast goes out first: the UI should hear why the run
     * ended, and it cannot do that once the process is gone.
     */
    private fun handleStop() {
        if (guestHosted.get()) {
            notifyRunFinished(JvmProtocol.STATUS_ERROR, getString(R.string.outcome_stopped))
        } else {
            Log.i(TAG, "stop requested with no guest in this process")
        }
        terminateGuestProcess()
    }

    private fun handleRun(
        release: JreRelease?,
        jarPath: String,
        mainClass: String,
        args: Array<String>,
        logPath: String,
        heapMb: Int,
        startId: Int,
    ) {
        if (release == null) {
            Log.e(TAG, "run requested without a known JRE")
            stopSelfResult(startId)
            return
        }
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "a JAR is already running in this process")
            stopSelfResult(startId)
            return
        }
        val installer = this.installer
        if (installer == null) {
            Log.e(TAG, "installer not ready")
            stopSelfResult(startId)
            return
        }
        val jar = File(jarPath)
        val log = File(logPath)

        worker.execute {
            // First, so the process is exempt from freezing for the whole run.
            showRunningNotification(jar.name)
            var keepAlive = false
            try {
                val home = installer.ensureInstalled(release)
                Log.i(TAG, "runtime $home ready, launching $mainClass")

                val runDir = runDirectory(jar)
                Log.i(TAG, "working directory: $runDir")
                log.parentFile?.mkdirs()
                // Said in the console, not just in logcat: a JAR that writes
                // files is the reason the run has a directory of its own, and
                // the user has to know where those files went.
                log.appendText(
                    getString(R.string.console_run_directory, runDir.absolutePath) + "\n",
                )

                // From here on the process holds a VM. OpenJDK builds one per
                // process and cannot undo it, so this is what Stop acts on and
                // what the next UI asks about, whether or not `main` returns.
                guestHosted.set(true)
                val outcome = JavaRunner.bootstrap(
                    jvmPath = installer.libJvm(release).absolutePath,
                    javaHome = home.absolutePath,
                    nativeLibDir = applicationInfo.nativeLibraryDir,
                    vmArgs = buildVmArgs(home, jar, runDir, heapMb),
                    appArgs = args,
                    mainClass = mainClass,
                    workDir = runDir.absolutePath,
                    outPath = log.absolutePath,
                )
                when {
                    outcome.startsWith(JvmProtocol.STATUS_ERROR) -> {
                        Log.e(TAG, "run failed: $outcome")
                        notifyRunFinished(outcome, outcome.removePrefix("${JvmProtocol.STATUS_ERROR} "))
                        // Nothing of the JAR is running, and the VM now carries
                        // whatever it registered; the next run gets a new one.
                        terminateGuestProcess()
                    }

                    outcome == JvmProtocol.STATUS_STILL_RUNNING -> {
                        Log.i(TAG, "main returned, the guest is still running")
                        notifyRunFinished(
                            outcome,
                            getString(R.string.outcome_main_returned),
                        )
                        // It is still serving, so it stays a foreground service
                        // and stays started: the app may be swiped away next.
                        keepAlive = true
                    }

                    else -> {
                        Log.i(TAG, "run completed")
                        notifyRunFinished(JvmProtocol.STATUS_OK, getString(R.string.outcome_jar_finished))
                        terminateGuestProcess()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "run aborted: ${e.message}", e)
                notifyRunFinished(JvmProtocol.STATUS_ERROR, e.message.orEmpty())
                terminateGuestProcess()
            } finally {
                running.set(false)
                if (!keepAlive) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                }
            }
        }
    }

    /**
     * Tells the UI process how the run ended. The service runs in its own
     * process, so it cannot hand a value back directly; the UI also watches the
     * log file, and this broadcast is what lets it stop doing so.
     */
    private fun notifyRunFinished(status: String, message: String) {
        sendBroadcast(
            Intent(JvmProtocol.ACT_RUN_FINISHED)
                .setPackage(packageName)
                .putExtra(JvmProtocol.EXTRA_STATUS, status)
                .putExtra(JvmProtocol.EXTRA_MESSAGE, message),
        )
    }

    /**
     * The directory a run's JAR works in.
     *
     * An app process starts in `/`, which is read-only: a JAR that writes
     * anything relative to its working directory — a server its world, a
     * framework a generated file — fails there, and one that writes to `~`
     * lands somewhere the user cannot see. So each JAR gets a directory named
     * after it inside the app's own storage, which is a real path the guest can
     * write and which is the same from run to run, because a server's data has
     * to survive being restarted.
     *
     * The external one is preferred over the internal one: both are the app's,
     * neither needs a permission, but the external one sits on shared storage,
     * so the files a JAR writes can also be reached over USB or `adb pull`
     * rather than only through the app. Download itself is not used because
     * reaching it by path needs "All files access" (`MANAGE_EXTERNAL_STORAGE`),
     * which is a restricted permission an app that is neither a file manager
     * nor a backup tool cannot publish with — and a JAR that serves already
     * has its own way out: the port it listens on.
     */
    private fun runDirectory(jar: File): File {
        val name = jar.nameWithoutExtension
        val candidates = listOfNotNull(
            getExternalFilesDir(null)?.let { File(it, "runJar/$name") },
            File(filesDir, "runJar/$name"),
        )
        val chosen = candidates.firstOrNull { it.isDirectory || it.mkdirs() }
        if (chosen == null) Log.w(TAG, "no usable run directory for ${jar.name}")
        return chosen ?: filesDir
    }

    private fun buildVmArgs(home: File, jar: File, runDir: File, heapMb: Int): Array<String> = arrayOf(
        "-Djava.home=${home.absolutePath}",
        "-Djava.class.path=${JarManifest.classPathOf(jar)}",
        "-Djava.library.path=${home.absolutePath}/lib",
        "-Djava.io.tmpdir=${cacheDir.absolutePath}",
        // `~` is the run's directory too, so everything a JAR writes for itself
        // — its data, its caches — lands in the one place the user can reach.
        // The temp directory stays private: it is scratch by definition, and
        // Download is not the place for it.
        "-Duser.home=${runDir.absolutePath}",
        "-Dfile.encoding=UTF-8",
        // The guest has no X11 and no window manager, so AWT must not try to
        // reach a display even for a JAR that never opens a window.
        "-Djava.awt.headless=true",
        // The guest sees an Android kernel; report it as Linux, which is what
        // Java libraries branch on (os.name=Android makes several refuse to
        // load their native code).
        "-Dos.name=Linux",
        "-Dos.version=Android-${Build.VERSION.RELEASE}",
        // POSIX_SPAWN needs jspawnhelper, which Android does not ship, so any
        // JAR that shells out would fail to start a process without this.
        "-Djdk.lang.Process.launchMechanism=FORK",
        // HotSpot would otherwise size its thread pools from the host's core
        // count, which on a phone is both wrong and unhelpful.
        "-XX:ActiveProcessorCount=${Runtime.getRuntime().availableProcessors().coerceAtMost(4)}",
        // -Xms reserves the whole heap up front, which a phone cannot spare for
        // a JAR that may only print a line; start small and let it grow.
        "-Xms16M",
        "-Xmx${heapMb}M",
        // Serial GC keeps the guest's footprint flat on a phone, where a
        // parallel collector only adds threads and RSS without adding throughput.
        "-XX:+UseSerialGC",
    )

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "runjar.guest"
        private const val NOTIFICATION_ID = 1

        const val EXTRA_JRE = "jre"
        const val EXTRA_JAR = "jar"
        const val EXTRA_MAIN = "main"
        const val EXTRA_ARGS = "args"
        const val EXTRA_LOG = "log"
        const val EXTRA_HEAP_MB = "heapMb"

        const val DEFAULT_HEAP_MB = 256

        /**
         * Delay between announcing that a run was stopped and killing the
         * process, so the announcement reaches the UI before the sender is gone.
         */
        private const val BROADCAST_GRACE_MS = 300L
    }
}
