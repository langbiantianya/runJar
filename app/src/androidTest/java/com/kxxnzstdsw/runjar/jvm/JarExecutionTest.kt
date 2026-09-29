package com.kxxnzstdsw.runjar.jvm

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end proof that a real JAR runs on a real guest JVM inside the app
 * process: unpack the published runtime, boot the VM through the JNI bridge,
 * invoke a main method, and read back what it printed.
 *
 * The runtime download is ~44 MB, so the test installs it once and reuses the
 * unpacked copy; it is skipped when the device has no network access rather
 * than failing the suite.
 */
@RunWith(AndroidJUnit4::class)
class JarExecutionTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    companion object {
        init {
            // Instrumentation runs in the app's main process, whereas the app
            // loads this library in the guest process it starts. Calling the
            // bridge directly therefore needs its own load.
            System.loadLibrary("runjar_jni")
        }
    }

    @Test
    fun runsAMainMethodFromAJarAndCapturesItsOutput() {
        val installer = JreInstaller(context)
        val release = JreRelease.JRE_21
        val home = try {
            installer.ensureInstalled(release)
        } catch (e: Exception) {
            Log.w("JarExecutionTest", "runtime unavailable, skipping: ${e.message}")
            assumeTrue("could not obtain the guest runtime", false)
            return
        }

        val libJvm = installer.libJvm(release)
        assertTrue("libjvm.so missing at $libJvm", libJvm.isFile)
        Log.i("JarExecutionTest", "runtime at $home, libjvm at $libJvm")

        val jar = copyAsset("hello.jar")
        assertTrue("sample jar not packaged", jar.isFile)

        val mainClass = JarManifest.mainClassOf(jar)
        assertEquals("hello.Hello", mainClass)

        val log = File(context.cacheDir, "instrumentation-run.log")
        log.delete()
        val workDir = runDir("hello")

        val outcome = JavaRunner.bootstrap(
            jvmPath = libJvm.absolutePath,
            javaHome = home.absolutePath,
            nativeLibDir = context.applicationInfo.nativeLibraryDir,
            vmArgs = arrayOf(
                "-Djava.home=${home.absolutePath}",
                "-Djava.class.path=${jar.absolutePath}",
                "-Djava.library.path=${home.absolutePath}/lib",
                "-Djava.awt.headless=true",
                "-Xmx256M",
            ),
            appArgs = arrayOf("alpha", "beta"),
            mainClass = mainClass!!,
            workDir = workDir.absolutePath,
            outPath = log.absolutePath,
        )
        Log.i("JarExecutionTest", "bootstrap returned: $outcome")
        assertEquals("guest reported a failure", "OK", outcome)

        val printed = log.readText()
        Log.i("JarExecutionTest", "captured output:\n$printed")
        assertTrue("main() output was not captured: '$printed'",
            printed.contains("Hello World from a JAR running on Android!"))
        assertTrue("java.version missing from output: '$printed'",
            printed.contains("java.version="))
        assertTrue("program arguments were not passed through: '$printed'",
            printed.contains("arg: alpha") && printed.contains("arg: beta"))
        // A JAR resolves relative paths against this, and an app process starts
        // in `/`, where writes fail; the run has to be somewhere of its own.
        // Canonical, because the VM reports the path the kernel resolves —
        // `/data/data/...` on a device where the app's directory is reached
        // through a link.
        assertTrue(
            "the guest did not work in the directory it was given: '$printed'",
            printed.contains("user.dir=${workDir.canonicalPath}"),
        )
    }

    @Test
    fun reportsAMissingMainClassInsteadOfCrashing() {
        val installer = JreInstaller(context)
        val release = JreRelease.JRE_21
        val home = try {
            installer.ensureInstalled(release)
        } catch (e: Exception) {
            assumeTrue("could not obtain the guest runtime", false)
            return
        }
        val jar = copyAsset("hello.jar")
        val log = File(context.cacheDir, "instrumentation-missing.log")
        log.delete()

        val outcome = JavaRunner.bootstrap(
            jvmPath = installer.libJvm(release).absolutePath,
            javaHome = home.absolutePath,
            nativeLibDir = context.applicationInfo.nativeLibraryDir,
            vmArgs = arrayOf("-Djava.home=${home.absolutePath}", "-Xmx256M"),
            appArgs = emptyArray(),
            mainClass = "does.not.Exist",
            workDir = runDir("missing").absolutePath,
            outPath = log.absolutePath,
        )
        Log.i("JarExecutionTest", "missing-class outcome: $outcome")
        assertTrue("expected a described failure, got: $outcome",
            outcome.startsWith("ERROR"))
        assertTrue("failure should name the class, got: $outcome",
            outcome.contains("does/not/Exist"))
    }

    @Test
    fun capturesAnUncaughtExceptionFromMain() {
        val installer = JreInstaller(context)
        val release = JreRelease.JRE_21
        val home = try {
            installer.ensureInstalled(release)
        } catch (e: Exception) {
            assumeTrue("could not obtain the guest runtime", false)
            return
        }
        val jar = copyAsset("hello.jar")
        val log = File(context.cacheDir, "instrumentation-boom.log")
        log.delete()

        val outcome = JavaRunner.bootstrap(
            jvmPath = installer.libJvm(release).absolutePath,
            javaHome = home.absolutePath,
            nativeLibDir = context.applicationInfo.nativeLibraryDir,
            vmArgs = arrayOf(
                "-Djava.home=${home.absolutePath}",
                "-Djava.class.path=${jar.absolutePath}",
                "-Xmx256M",
            ),
            appArgs = emptyArray(),
            mainClass = "hello.Boom",
            workDir = runDir("boom").absolutePath,
            outPath = log.absolutePath,
        )
        Log.i("JarExecutionTest", "throwing-main outcome: $outcome")

        // The caller does not get an exception to catch: the failure has to come
        // back as a described outcome, or the UI would have nothing to show.
        assertTrue("expected a described failure, got: $outcome", outcome.startsWith("ERROR"))
        assertTrue("outcome should name the exception: $outcome",
            outcome.contains("IllegalStateException"))

        // stderr is captured alongside stdout, so the trace the JAR printed on
        // its way out is part of the console.
        val printed = log.readText()
        assertTrue("the stack trace was not captured: '$printed'",
            printed.contains("hello.Boom.main"))
    }

    /** A directory of its own for a run, as the service gives every run. */
    private fun runDir(name: String): File = File(context.cacheDir, "run/$name").apply { mkdirs() }

    private fun copyAsset(name: String): File {
        val target = File(context.filesDir, "user-jars/$name")
        target.parentFile?.mkdirs()
        context.assets.open(name).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }
}
