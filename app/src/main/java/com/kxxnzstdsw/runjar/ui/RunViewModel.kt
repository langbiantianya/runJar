package com.kxxnzstdsw.runjar.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kxxnzstdsw.runjar.jvm.JarManifest
import com.kxxnzstdsw.runjar.jvm.JreInstaller
import com.kxxnzstdsw.runjar.jvm.JreRelease
import com.kxxnzstdsw.runjar.jvm.JreRuntime
import com.kxxnzstdsw.runjar.jvm.JvmProtocol
import com.kxxnzstdsw.runjar.jvm.JvmService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

private const val TAG = "RunViewModel"

/** Main class declared by the sample JAR in `assets/hello.jar`. */
private const val SAMPLE_MAIN_CLASS = "hello.Hello"

/** Shown when a guest from an earlier session is found still running. */
private const val ADOPTED_GUEST =
    "A guest JVM is still running — press Stop to shut it down"

/**
 * Drives one JAR run: resolve the file, read its manifest, make sure the guest
 * runtime is present, hand off to [JvmService], then stream the guest's output
 * into the console.
 */
class RunViewModel(application: Application) : AndroidViewModel(application) {

    private val installer = JreInstaller(application)

    private val _state = MutableStateFlow(RunUiState())
    val state: StateFlow<RunUiState> = _state.asStateFlow()

    /** Where the guest's System.out is redirected for the current run. */
    private val logFile: File get() = File(getApplication<Application>().cacheDir, "jre-run.log")

    private var tailJob: Job? = null
    private var runReceiver: BroadcastReceiver? = null

    /** The service process being watched, and the death watch on it. */
    private var boundBinder: IBinder? = null
    private var boundDeath: IBinder.DeathRecipient? = null

    /**
     * Whether the service has already said how the current run ended.
     *
     * Until it has, the death of the guest process is the only thing that can
     * say the run is over, so it also has to word the outcome. Once the service
     * has reported one — "Stopped", "The JAR finished", a failure — that report
     * stands, because the process dying is part of it: for a JAR whose `main`
     * returned while its threads kept running, the service's report is only
     * that it is still up, and the death is the outcome.
     */
    private var outcomeReported = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) {
                onGuestProcessDied(boundBinder)
                return
            }
            // A death watch belongs to one binding: the previous run's process
            // is retired before the next run's is created, and a watch left
            // over from it must not be read as the current guest going away.
            val watch = IBinder.DeathRecipient { onGuestProcessDied(binder) }
            try {
                binder.linkToDeath(watch, 0)
            } catch (e: RemoteException) {
                // The process was already gone by the time we bound to it.
                onGuestProcessDied(boundBinder)
                return
            }
            boundBinder = binder
            boundDeath = watch

            // The process is there; whether it holds a guest is for the service
            // to say, and a guest found this way is one this instance never
            // started. That is the app being reopened on a run whose UI process
            // did not survive it, which is exactly when the screen would
            // otherwise claim nothing is running — and offer nothing to stop.
            // A run of our own is already in flight in that case, and its state
            // is the one to keep.
            val current = _state.value
            val ours = current.state is RunState.Running || current.state is RunState.Preparing
            if (!ours && hostsGuest(binder)) {
                outcomeReported = false
                _state.update {
                    it.copy(guestRunning = true, state = RunState.Finished(ADOPTED_GUEST, true))
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // Only called for an explicit disconnect; a crash is reported to the
            // death recipient instead.
        }
    }

    /** Asks the service process whether it holds a guest VM. */
    private fun hostsGuest(binder: IBinder): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            binder.transact(JvmProtocol.TRANSACTION_HOSTS_GUEST, data, reply, 0)
            reply.readInt() != 0
        } catch (e: RemoteException) {
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Takes note of the process [dead] was watching going away.
     *
     * A death only counts for the binding currently held: a binding that was
     * released, or replaced by a later one, has nothing to say about the guest
     * this instance is running now.
     */
    private fun onGuestProcessDied(dead: IBinder?) {
        if (dead != null && boundBinder !== dead) return
        releaseServiceBinding()
        viewModelScope.launch {
            drainRemainingOutput()
            _state.update { state ->
                // Nothing has explained this death, so the state has to: either
                // the run was still executing, or it had returned from `main`
                // leaving the guest up and the guest has now gone. A state that
                // never saw a run at all is left alone.
                val settled = when {
                    outcomeReported -> state.state
                    state.state is RunState.Idle -> state.state
                    else -> RunState.Finished(
                        "The guest JVM ended — see the console",
                        success = false,
                    )
                }
                state.copy(state = settled, guestRunning = false)
            }
        }
    }

    private fun releaseServiceBinding() {
        boundBinder?.let { binder ->
            boundDeath?.let { watch -> runCatching { binder.unlinkToDeath(watch, 0) } }
        }
        boundBinder = null
        boundDeath = null
        runCatching { getApplication<Application>().unbindService(serviceConnection) }
    }

    init {
        refreshInstalledRuntimes()
        registerRunReceiver()
        adoptRunningGuest()
    }

    /**
     * Picks up a guest that is still running from before this instance existed.
     *
     * The run outlives the UI: the guest is a foreground service in its own
     * process, while the screen — and the process it draws in — can be ended at
     * any time. Bound without `BIND_AUTO_CREATE`, this asks whether such a
     * process is there at all and pays nothing when it is not; if it is, the
     * connection reports what the service holds and the screen shows the run as
     * running, with the Stop that ends it, instead of an idle app whose JAR is
     * still occupying its port.
     */
    private fun adoptRunningGuest() {
        val app = getApplication<Application>()
        val intent = Intent(app, JvmService::class.java)
        // Flags 0: ask about a service that exists, never start one.
        runCatching { app.bindService(intent, serviceConnection, 0) }
    }

    /**
     * The service runs in its own process, so it reports completion by
     * broadcast rather than by return value.
     */
    private fun registerRunReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val status = intent?.getStringExtra(JvmProtocol.EXTRA_STATUS) ?: return
                val message = intent.getStringExtra(JvmProtocol.EXTRA_MESSAGE).orEmpty()
                onRunFinished(status, message)
            }
        }
        val filter = IntentFilter(JvmProtocol.ACT_RUN_FINISHED)
        val app = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }
        runReceiver = receiver
    }

    private fun onRunFinished(status: String, message: String) {
        val failed = status.startsWith(JvmProtocol.STATUS_ERROR)
        // "Still running" is not an outcome: the JAR's threads are up and the
        // service keeps its process for them, so the run is over when Stop ends
        // it or the guest dies — and that death is what words it.
        outcomeReported = status != JvmProtocol.STATUS_STILL_RUNNING
        tailJob?.cancel()
        // The binding is deliberately kept: while it is held the guest process
        // still exists, which is what tells the next run that its VM is stale.
        viewModelScope.launch {
            drainRemainingOutput()
            _state.update {
                it.copy(
                    state = if (failed) {
                        RunState.Failed(message.ifEmpty { status })
                    } else {
                        RunState.Finished(message.ifEmpty { "Run finished" }, success = true)
                    },
                )
            }
        }
    }

    /** Reads whatever the guest wrote after the last tail poll. */
    private suspend fun drainRemainingOutput() {
        val text = withContext(Dispatchers.IO) {
            if (!logFile.isFile) return@withContext null
            logFile.readText()
        } ?: return
        val lines = text.split('\n').filter { it.isNotBlank() }
        _state.update { state ->
            val known = state.console.toSet()
            val fresh = lines.filter { it !in known }
            if (fresh.isEmpty()) state else state.copy(console = state.console + fresh)
        }
    }

    override fun onCleared() {
        super.onCleared()
        releaseServiceBinding()
        runReceiver?.let { runApplication().unregisterReceiver(it) }
        runReceiver = null
    }

    private fun runApplication(): Application = getApplication()

    private fun refreshInstalledRuntimes() {
        val installed = JreRelease.entries
            .filter { installer.isInstalled(it) }
            .map { it.id }
            .toSet()
        _state.update { it.copy(installedReleases = installed) }
    }

    /**
     * Local copy of the selected JAR. Kept as a path rather than re-derived
     * from the URI, because a content:// URI's path segment is an opaque
     * document id, not a file name.
     */
    private var localJar: File? = null

    /** Picks a JAR and pre-fills the main class from its manifest. */
    fun onJarPicked(uri: Uri) = viewModelScope.launch {
        val name = queryDisplayName(uri) ?: "app.jar"
        val local = withContext(Dispatchers.IO) { copyToCache(uri, name) }
        if (local == null) {
            _state.update { it.copy(state = RunState.Failed("Could not read the selected file")) }
            return@launch
        }
        val mainClass = withContext(Dispatchers.IO) {
            runCatching { JarManifest.mainClassOf(local) }.getOrNull()
        }
        localJar = local
        _state.update {
            it.copy(
                jarUri = uri,
                jarName = name,
                mainClass = mainClass.orEmpty(),
                state = RunState.Idle,
                console = listOf("Selected $name"),
            )
        }
    }

    fun onMainClassChange(value: String) = _state.update { it.copy(mainClass = value) }

    fun onArgsChange(value: String) = _state.update { it.copy(argsInput = value) }

    fun onHeapChange(value: Int) = _state.update { it.copy(heapMb = value.coerceIn(64, 1024)) }

    fun onReleaseChange(option: JreReleaseOption) = _state.update { it.copy(release = option) }

    /** Materialises a trivial JAR in app storage, for verifying the pipeline. */
    fun onUseSampleJar() = viewModelScope.launch {
        val file = withContext(Dispatchers.IO) { buildSampleJar() }
        if (file == null) {
            _state.update { it.copy(state = RunState.Failed("Could not load the sample JAR")) }
            return@launch
        }
        localJar = file
        _state.update {
            it.copy(
                jarUri = FileProvider.getUriForFile(
                    getApplication(), "${getApplication<Application>().packageName}.files", file,
                ),
                jarName = file.name,
                mainClass = SAMPLE_MAIN_CLASS,
                state = RunState.Idle,
                console = listOf("Loaded ${file.name} with main class $SAMPLE_MAIN_CLASS"),
            )
        }
    }

    fun run() {
        val current = _state.value
        val release = JreRelease.byId(current.release.id) ?: JreRelease.DEFAULT
        val jar = localJar
        if (jar == null || !jar.isFile) {
            _state.update { it.copy(state = RunState.Failed("Choose a JAR first")) }
            return
        }
        if (current.mainClass.isBlank()) {
            _state.update { it.copy(state = RunState.Failed("Enter a main class")) }
            return
        }

        tailJob?.cancel()
        logFile.delete()
        outcomeReported = false
        _state.update { it.copy(console = emptyList(), state = RunState.Preparing(0f)) }

        val args = current.argsInput.split(' ', '\t')
            .filter { it.isNotBlank() }

        viewModelScope.launch {

            // Unpack the runtime in the UI process so the download progress and
            // any failure are reported here; the service only starts the VM.
            val home = runCatching {
                installer.install(release) { fraction ->
                    _state.update { it.copy(state = RunState.Preparing(fraction)) }
                }
            }
            home.onFailure { error ->
                Log.e(TAG, "runtime install failed", error)
                _state.update {
                    it.copy(state = RunState.Failed("Runtime install failed: ${error.message}"))
                }
                return@launch
            }
            refreshInstalledRuntimes()

            logFile.parentFile?.mkdirs()
            val intent = Intent(getApplication(), JvmService::class.java).apply {
                action = JvmProtocol.CMD_RUN
                putExtra(JvmService.EXTRA_JRE, release.id)
                putExtra(JvmService.EXTRA_JAR, jar.absolutePath)
                putExtra(JvmService.EXTRA_MAIN, current.mainClass)
                putExtra(JvmService.EXTRA_ARGS, args.toTypedArray())
                putExtra(JvmService.EXTRA_LOG, logFile.absolutePath)
                putExtra(JvmService.EXTRA_HEAP_MB, current.heapMb)
            }
            val app = getApplication<Application>()
            retireStaleGuest()
            // Bound first, so the guest's process is already being watched by
            // the time it can start work: a JAR is free to end that process
            // immediately, and the run would otherwise never be seen to finish.
            app.bindService(
                Intent(app, JvmService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE,
            )
            app.startService(intent)

            _state.update { it.copy(state = RunState.Running, guestRunning = true) }
            tailLog()
        }
    }

    /**
     * Follows the guest's redirected stdout until the run ends.
     *
     * The file is read by byte offset, not by line: a read can land in the
     * middle of a UTF-8 sequence or before a newline, and a partial line must
     * be held back until the rest of it arrives rather than shown truncated.
     */
    private suspend fun tailLog() {
        var consumed = 0L
        val pending = StringBuilder()
        while (viewModelScope.isActive && _state.value.state is RunState.Running) {
            val chunk = withContext(Dispatchers.IO) { readNewBytes(logFile, consumed) }
            if (chunk == null) {
                delay(120)
                continue
            }
            consumed += chunk.size
            pending.append(String(chunk, Charsets.UTF_8))
            val lines = mutableListOf<String>()
            while (true) {
                val newline = pending.indexOf("\n")
                if (newline < 0) break
                lines += pending.substring(0, newline).trimEnd('\r')
                pending.delete(0, newline + 1)
            }
            if (lines.any { it.isNotBlank() }) {
                _state.update { state -> state.copy(console = state.console + lines) }
            }
        }
    }

    /**
     * Reads everything written to [file] after [from] bytes. Returns null when
     * there is nothing new, so the caller can back off instead of spinning.
     */
    private fun readNewBytes(file: File, from: Long): ByteArray? {
        if (!file.isFile) return null
        val length = file.length()
        if (length <= from) return null
        return java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(from)
            val buffer = ByteArray((length - from).toInt())
            raf.readFully(buffer)
            buffer
        }
    }

    fun clearConsole() = _state.update { it.copy(console = emptyList()) }

    /**
     * Ends a guest process left over from an earlier run, and waits for it.
     *
     * A JVM carries state that can only be set once — `URL.setURLStreamHandlerFactory`
     * is the one Spring Boot's embedded Tomcat trips over — so a VM that has
     * already run something cannot run the next JAR. `java -jar` gives each
     * invocation a fresh JVM; so does this, by making the process the unit of
     * reuse rather than the VM.
     *
     * The probe binds *without* creating the service, so a run that finds no
     * guest pays nothing, and it is authoritative: a process left behind by an
     * earlier session is found even though this instance never met it.
     */
    private suspend fun retireStaleGuest() {
        val app = getApplication<Application>()
        val intent = Intent(app, JvmService::class.java)
        val probe = GuestProcessProbe()
        if (!app.bindService(intent, probe, 0)) return
        try {
            val binder = withTimeoutOrNull(PROBE_TIMEOUT_MS) { probe.connected.await() }
            if (binder == null) return
            app.startService(intent.setAction(JvmProtocol.CMD_SHUTDOWN))
            withTimeoutOrNull(GUEST_SHUTDOWN_TIMEOUT_MS) { probe.died.await() }
                ?: Log.w(TAG, "the previous guest process did not end in time")
        } finally {
            runCatching { app.unbindService(probe) }
        }
    }

    /** Watches for a service process without requiring one to exist. */
    private inner class GuestProcessProbe : ServiceConnection, IBinder.DeathRecipient {
        val connected = CompletableDeferred<Unit>()
        val died = CompletableDeferred<Unit>()

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) {
                died.complete(Unit)
                return
            }
            try {
                binder.linkToDeath(this, 0)
                connected.complete(Unit)
            } catch (e: RemoteException) {
                died.complete(Unit)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) = Unit

        override fun binderDied() {
            died.complete(Unit)
        }
    }

    /**
     * Abandons the current run.
     *
     * The service cannot interrupt arbitrary Java code, so it ends its own
     * process; the outcome arrives as a broadcast like any other.
     */
    fun stop() {
        val current = _state.value
        // Also meaningful once main has returned: a JAR that left a server
        // running is stopped the same way.
        if (current.state !is RunState.Running && !current.guestRunning) return
        getApplication<Application>().startService(
            Intent(getApplication(), JvmService::class.java).setAction(JvmProtocol.CMD_STOP),
        )
    }

    private suspend fun copyToCache(uri: Uri, name: String): File? = withContext(Dispatchers.IO) {
        runCatching {
            val target = File(getApplication<Application>().filesDir, "user-jars/$name")
            target.parentFile?.mkdirs()
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching null
            target
        }.getOrNull()
    }

    private fun queryDisplayName(uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        return resolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        } ?: uri.lastPathSegment
    }

    /**
     * Compiles a hello-world JAR using the device's own ART: `javac` is not
     * available, but the sample only needs a class file the guest can load.
     */
    private fun buildSampleJar(): File? = runCatching {
        val dir = File(getApplication<Application>().cacheDir, "sample").apply { mkdirs() }
        val out = File(dir, "hello.jar")
        // The class is shipped as pre-compiled bytecode; see assets.
        val template = assetBytes("hello.jar") ?: return@runCatching null
        out.outputStream().use { it.write(template) }
        out
    }.getOrNull()

    private fun assetBytes(name: String): ByteArray? = runCatching {
        getApplication<Application>().assets.open(name).use { it.readBytes() }
    }.getOrNull()

    companion object {
        /** How long to wait for a stale guest process to go away before running anyway. */
        private const val GUEST_SHUTDOWN_TIMEOUT_MS = 5_000L

        /** How long to wait for the probe to report whether a guest process exists. */
        private const val PROBE_TIMEOUT_MS = 2_000L

        /** Only the runtimes this device can actually run. */
        val RELEASE_OPTIONS: List<JreReleaseOption> =
            JreRelease.availableForDevice().map { JreReleaseOption.of(it) }

        fun heapDefaultsMb(): Int = if (JreRuntime.is64BitDevice()) 512 else 256

        fun deviceLabel(): String =
            "${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"} / API ${Build.VERSION.SDK_INT}"
    }
}
