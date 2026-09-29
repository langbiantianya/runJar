package com.kxxnzstdsw.runjar.jvm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

private const val TAG = "JreInstaller"

/**
 * Fetches an OpenJDK-for-Android runtime into the app's private storage.
 *
 * The published `jre<major>-pojav.zip` is not an exploded JRE: it holds six
 * flat entries — a `universal.tar.xz` with the architecture-independent files
 * (class libraries, `conf/`, the `release` stamp) and one `bin-<abi>.tar.xz`
 * per supported architecture, each carrying that ABI's `bin/` and its own
 * shared libraries. A usable runtime is the two layers unpacked on top of each
 * other, with the ABI layer winning where they overlap.
 *
 * The result must live in the app's data directory: Android 10 (API 29) forbids
 * executing binaries from shared storage, and the guest's libraries are loaded
 * by absolute path regardless.
 */
class JreInstaller(context: Context) {

    private val appContext = context.applicationContext

    /** Parent of all unpacked runtimes. */
    fun rootDir(): File = File(appContext.filesDir, "jre").apply { mkdirs() }

    fun homeDir(release: JreRelease): File = File(rootDir(), release.id)

    fun isInstalled(release: JreRelease): Boolean = libJvmIn(homeDir(release)).isFile

    /** The guest's libjvm.so. HotSpot keeps it in a "flavor" directory. */
    fun libJvm(release: JreRelease): File = libJvmIn(homeDir(release))

    /** Same lookup, for a home directory already known to hold a runtime. */
    fun libJvmIn(home: File): File =
        sequenceOf("server", "client")
            .map { File(home, "lib/$it/libjvm.so") }
            .firstOrNull { it.isFile }
            ?: File(home, "lib/server/libjvm.so")

    /**
     * Downloads [release] unless already unpacked and returns its home
     * directory. [onProgress] receives a 0..1 fraction of the transfer.
     */
    suspend fun install(
        release: JreRelease,
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        if (isInstalled(release)) return@withContext homeDir(release)

        val archive = File(rootDir(), release.assetName)
        try {
            if (!archive.isFile || archive.length() == 0L) {
                download(release.assetUrl, archive, onProgress)
            }
            val abi = JreRuntime.abiForDevice()
            if (!release.supports(abi)) {
                throw IOException("Java ${release.majorVersion} is not published for $abi")
            }
            val home = unpack(archive, release, abi)
            if (!libJvmIn(home).isFile) {
                home.deleteRecursively()
                throw IOException("${release.assetName} has no libjvm.so for $abi")
            }
            prepareHome(home)
            onProgress(1f)
            home
        } finally {
            archive.delete()
        }
    }

    /**
     * Blocking install for callers already off the main thread, such as the
     * background service that has no UI to report progress to.
     */
    fun ensureInstalled(release: JreRelease, onProgress: (Float) -> Unit = {}): File {
        if (isInstalled(release)) return homeDir(release)
        return runBlocking(Dispatchers.IO) { install(release, onProgress) }
    }

    private fun download(url: String, target: File, onProgress: (Float) -> Unit) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("HTTP $status downloading $url")
            val total = connection.contentLengthLong
            var written = 0L
            target.outputStream().buffered().use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) onProgress(written.toFloat() / total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Unpacks the universal layer, then the ABI layer, into the runtime home.
     *
     * The ABI layer is written last and overwrites, because it holds the
     * architecture-specific `lib/server/libjvm.so` that the universal layer
     * deliberately omits.
     */
    private fun unpack(archive: File, release: JreRelease, abi: String): File {
        val home = homeDir(release)
        val staging = File(rootDir(), ".staging-${release.id}")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            ZipFile(archive).use { zip ->
                val universal = zip.getEntry("universal.tar.xz")
                    ?: throw IOException("${archive.name} has no universal.tar.xz")
                zip.getInputStream(universal).use { input ->
                    untarXz(input, staging)
                }

                val abiEntry = zip.getEntry("bin-$abi.tar.xz")
                    ?: throw IOException("${archive.name} has no bin-$abi.tar.xz")
                zip.getInputStream(abiEntry).use { input ->
                    untarXz(input, staging)
                }
            }
            home.deleteRecursively()
            if (!staging.renameTo(home)) {
                staging.copyRecursively(home, overwrite = true)
                staging.deleteRecursively()
            }
            Log.i(TAG, "unpacked ${archive.name} for $abi into $home")
            return home
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Extracts an xz-compressed tar into [dest], rejecting entries that would
     * escape it.
     */
    private fun untarXz(input: java.io.InputStream, dest: File) {
        val decoded = BufferedInputStream(XZInputStream(input), 64 * 1024)
        TarReader(decoded).use { tar ->
            while (true) {
                val entry = tar.nextHeader() ?: break
                if (entry.type == TarEntry.TYPE_DIR) {
                    File(dest, entry.name).mkdirs()
                    continue
                }
                if (entry.type != TarEntry.TYPE_FILE) continue
                val out = File(dest, entry.name)
                if (!out.canonicalPath.startsWith(dest.canonicalPath + File.separator)) {
                    throw IOException("archive entry escapes the runtime directory: ${entry.name}")
                }
                out.parentFile?.mkdirs()
                out.outputStream().use { output -> tar.copyEntry(output, entry.size) }
            }
        }
    }

    /**
     * Post-extraction fixes the guest runtime needs before it will start.
     *
     * Mirrors MojoLauncher's `MultiRTUtils.postPrepare`: the freetype library is
     * published with a versioned SONAME that nothing else references, so the
     * loader would not find it under the name the AWT font manager asks for.
     */
    private fun prepareHome(home: File) {
        val libDir = libJvmIn(home).parentFile?.parentFile ?: return
        val ftIn = File(libDir, "libfreetype.so.6")
        val ftOut = File(libDir, "libfreetype.so")
        if (ftIn.isFile && (!ftOut.isFile || ftIn.length() != ftOut.length())) {
            if (!ftIn.renameTo(ftOut)) ftIn.copyTo(ftOut, overwrite = true)
        }
    }
}
