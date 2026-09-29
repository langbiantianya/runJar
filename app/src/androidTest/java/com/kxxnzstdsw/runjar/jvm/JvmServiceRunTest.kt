package com.kxxnzstdsw.runjar.jvm

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * End-to-end test of the path the app actually takes: a run is handed to
 * [JvmService], which lives in its own process, loads the guest VM and reports
 * back by broadcast.
 *
 * This is deliberately not the same thing as [JarExecutionTest], which calls
 * the bridge directly: that covers the bridge, while this covers the wiring
 * around it — the cross-process handoff, the outcome broadcast, and the log
 * file the UI tails.
 */
@RunWith(AndroidJUnit4::class)
class JvmServiceRunTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun runsAJarInTheServiceProcessAndReportsItsOutcome() {
        assumeTrue("could not obtain the guest runtime", ensureRuntime())
        val jar = sampleJar()

        val outcome = runThroughService(jar, "hello.Hello", arrayOf("from-service"))
        assertEquals("the run failed: ${outcome.message}", JvmProtocol.STATUS_OK, outcome.status)

        val printed = logFile().readText()
        Log.i(TAG, "captured through the service:\n$printed")
        assertTrue(
            "the guest's output did not reach the log: '$printed'",
            printed.contains("Hello World from a JAR running on Android!"),
        )
        assertTrue(
            "the arguments did not reach main: '$printed'",
            printed.contains("arg: from-service"),
        )
    }

    /**
     * A JVM holds state that can only be set once, and an application that runs
     * a server leaves the JVM up after `main` returns. Running a second JAR must
     * therefore not reuse that VM: it would fail on the first set-once
     * registration the new JAR attempts.
     *
     * `hello.Server` claims the URL stream handler factory and then leaves a
     * non-daemon thread running, which is precisely the shape of a Spring Boot
     * application with an embedded web server.
     */
    @Test
    fun runsAJarAgainAfterOneThatLeftTheJvmBusy() {
        assumeTrue("could not obtain the guest runtime", ensureRuntime())
        val jar = sampleJar()

        val first = runThroughService(jar, "hello.Server", emptyArray())
        assertEquals(
            "the first run should have left the JVM busy: ${first.message}",
            JvmProtocol.STATUS_STILL_RUNNING,
            first.status,
        )
        assertTrue(
            "the guest did not report starting its worker: ${logFile().readText()}",
            logFile().readText().contains("worker started"),
        )

        // The hazard itself, pinned: that VM has taken the set-once
        // registration, so a run reusing it cannot get past its own.
        val reused = runThroughService(jar, "hello.Server", emptyArray())
        assertTrue(
            "expected the spent VM to refuse the second run, got: ${reused.status}",
            reused.status.startsWith(JvmProtocol.STATUS_ERROR),
        )
        assertTrue(
            "the failure should be the spent set-once registration, got: ${reused.message}",
            reused.message.contains("factory already defined"),
        )

        // And the remedy: a fresh process runs it again.
        retireGuestProcess()
        val fresh = runThroughService(jar, "hello.Server", emptyArray())
        assertEquals(
            "the run after retiring the spent guest failed: ${fresh.message}",
            JvmProtocol.STATUS_STILL_RUNNING,
            fresh.status,
        )
    }

    // ---------------------------------------------------------------------

    private data class Outcome(val status: String, val message: String)

    /** Starts a run and waits for the service to report how it ended. */
    private fun runThroughService(jar: File, mainClass: String, args: Array<String>): Outcome {
        val finished = CountDownLatch(1)
        val status = AtomicReference<String?>(null)
        val message = AtomicReference<String?>(null)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                status.set(intent?.getStringExtra(JvmProtocol.EXTRA_STATUS))
                message.set(intent?.getStringExtra(JvmProtocol.EXTRA_MESSAGE))
                finished.countDown()
            }
        }
        registerReceiver(receiver)
        try {
            logFile().delete()
            context.startService(
                Intent(context, JvmService::class.java).apply {
                    action = JvmProtocol.CMD_RUN
                    putExtra(JvmService.EXTRA_JRE, JreRelease.JRE_21.id)
                    putExtra(JvmService.EXTRA_JAR, jar.absolutePath)
                    putExtra(JvmService.EXTRA_MAIN, mainClass)
                    putExtra(JvmService.EXTRA_ARGS, args)
                    putExtra(JvmService.EXTRA_LOG, logFile().absolutePath)
                    putExtra(JvmService.EXTRA_HEAP_MB, 256)
                },
            )
            assertTrue(
                "the service never reported an outcome for $mainClass",
                finished.await(180, TimeUnit.SECONDS),
            )
            return Outcome(
                status.get() ?: error("no status reported"),
                message.get().orEmpty(),
            )
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    /**
     * Ends the guest process the way the app does before starting another run,
     * and waits until no service process answers.
     *
     * Waiting on a binder to die is not enough on its own: the shutdown request
     * can reach a process that is already on its way out, and a request sent
     * when nothing is running starts a service that then shuts itself down. So
     * this asks, then confirms the service is gone.
     */
    private fun retireGuestProcess() {
        val intent = Intent(context, JvmService::class.java)
        context.startService(intent.setAction(JvmProtocol.CMD_SHUTDOWN))
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (!serviceIsRunning(intent)) return
            Thread.sleep(250)
        }
        error("the guest process was still running after the shutdown request")
    }

    /** Whether a service process currently answers a probe binding. */
    private fun serviceIsRunning(intent: Intent): Boolean {
        val answered = CompletableFuture<Unit>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                answered.complete(Unit)
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        // No BIND_AUTO_CREATE: this asks whether the service is already there.
        if (!context.bindService(intent, connection, 0)) return false
        return try {
            answered.get(1, TimeUnit.SECONDS)
            true
        } catch (e: Exception) {
            false
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }

    private fun logFile() = File(context.cacheDir, "service-run.log")

    private fun sampleJar(): File = File(context.filesDir, "user-jars/hello.jar").apply {
        parentFile?.mkdirs()
        context.assets.open("hello.jar").use { input ->
            outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun ensureRuntime(): Boolean = try {
        JreInstaller(context).ensureInstalled(JreRelease.JRE_21)
        true
    } catch (e: Exception) {
        Log.w(TAG, "runtime unavailable, skipping: ${e.message}")
        false
    }

    private fun registerReceiver(receiver: BroadcastReceiver) {
        val filter = IntentFilter(JvmProtocol.ACT_RUN_FINISHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    private companion object {
        const val TAG = "JvmServiceRunTest"
    }
}
