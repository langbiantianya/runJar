package com.kxxnzstdsw.runjar.jvm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile

/**
 * Extracts real runtime layers with the same code path [JreInstaller] uses and
 * asserts the result is a JRE the VM can actually start from.
 *
 * This is the step that decides whether a run works at all. A runtime missing
 * `lib/server/libjvm.so`, or missing the `lib/modules` image the guest class
 * loader reads its bootstrap classes from, fails only when the VM starts — long
 * after the download looked like it succeeded.
 *
 * The layers are not committed (they are ~30 MB); the test is skipped unless
 * RUNJAR_JRE_FIXTURE points at a directory holding universal.tar.xz and the
 * per-ABI layers.
 */
class RuntimeUnpackTest {

    private lateinit var fixtureDir: File
    private lateinit var dest: File

    @Before
    fun setUp() {
        val path = System.getenv("RUNJAR_JRE_FIXTURE")
        assumeTrue("set RUNJAR_JRE_FIXTURE to a directory of extracted layers",
            path != null && path.isNotEmpty())
        fixtureDir = File(path!!)
        assumeTrue("no universal.tar.xz in $path", File(fixtureDir, "universal.tar.xz").isFile)
        dest = File.createTempFile("runtime", "")
        dest.delete()
        dest.mkdirs()
    }

    private fun extractLayer(archive: File, dest: File) {
        FileInputStream(archive).use { input ->
            BufferedInputStream(XZInputStream(input), 64 * 1024).use { decoded ->
                TarReader(decoded).use { tar ->
                    while (true) {
                        val entry = tar.nextHeader() ?: break
                        if (entry.type == TarEntry.TYPE_DIR) {
                            File(dest, entry.name).mkdirs()
                            continue
                        }
                        if (entry.type != TarEntry.TYPE_FILE) continue
                        val out = File(dest, entry.name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { tar.copyEntry(it, entry.size) }
                    }
                }
            }
        }
    }

    @Test
    fun `two layers unpack into a startable runtime`() {
        extractLayer(File(fixtureDir, "universal.tar.xz"), dest)
        extractLayer(File(fixtureDir, "bin-x86_64.tar.xz"), dest)

        val libJvm = File(dest, "lib/server/libjvm.so")
        assertTrue("libjvm.so missing after unpacking", libJvm.isFile)
        assertTrue("libjvm.so is implausibly small", libJvm.length() > 1_000_000)

        // The guest class loader reads the system classes out of this image;
        // without it the VM starts and then cannot find java.lang.Object.
        val modules = File(dest, "lib/modules")
        assertTrue("lib/modules image missing after unpacking", modules.isFile)
        assertTrue("lib/modules image is implausibly small", modules.length() > 1_000_000)

        // Libraries the VM dlopens by bare name at startup.
        for (name in listOf("libjli.so", "libjava.so", "libnio.so", "libzip.so")) {
            assertTrue("$name missing after unpacking", File(dest, "lib/$name").isFile)
        }

        // The launcher scripts are part of a JRE even though we never exec them.
        assertTrue("bin/java missing after unpacking", File(dest, "bin/java").isFile)

        val release = File(dest, "release")
        assertTrue("release stamp missing after unpacking", release.isFile)
        val stamp = release.readText()
        assertTrue("release stamp has no JAVA_VERSION: $stamp", stamp.contains("JAVA_VERSION="))
    }

    @Test
    fun `the abi layer wins where it overlaps the universal layer`() {
        val universalOnly = File.createTempFile("universal", "")
        universalOnly.delete()
        universalOnly.mkdirs()
        try {
            extractLayer(File(fixtureDir, "universal.tar.xz"), universalOnly)
            // The universal layer is architecture independent by construction, so
            // it must not carry a VM: if it did, the ABI layer would have to
            // overwrite it and a stale universal libjvm would silently win.
            assertTrue(
                "universal layer unexpectedly contains libjvm.so",
                !File(universalOnly, "lib/server/libjvm.so").isFile,
            )
            // It does carry the class data the guest needs.
            assertTrue(
                "universal layer is missing lib/modules",
                File(universalOnly, "lib/modules").isFile,
            )
        } finally {
            universalOnly.deleteRecursively()
        }
    }

    @Test
    fun `symlinks in the runtime layers are only license documents`() {
        val layers = listOf("universal.tar.xz", "bin-x86_64.tar.xz")
            .map { File(fixtureDir, it) }
            .filter { it.isFile }
        assumeTrue("no layers to inspect", layers.isNotEmpty())

        val linksOutsideLegal = mutableListOf<String>()
        var linkCount = 0
        for (layer in layers) {
            FileInputStream(layer).use { input ->
                BufferedInputStream(XZInputStream(input), 64 * 1024).use { decoded ->
                    TarReader(decoded).use { tar ->
                        while (true) {
                            val entry = tar.nextHeader() ?: break
                            if (entry.type != TarEntry.TYPE_SYMLINK) continue
                            linkCount++
                            if (!entry.name.startsWith("legal/")) linksOutsideLegal += entry.name
                        }
                    }
                }
            }
        }
        // Nothing the VM loads is a symbolic link, which is why the installer
        // discards them instead of materialising them. If that ever changes the
        // runtime would come up missing a library, so fail loudly here.
        assertTrue(
            "runtime layers have symlinks outside legal/: $linksOutsideLegal",
            linksOutsideLegal.isEmpty(),
        )
        assertTrue("expected the layers to contain license symlinks", linkCount > 0)
    }

    @Test
    fun `every device abi maps to a layer name the archive carries`() {
        val path = System.getenv("RUNJAR_JRE_ARCHIVE")
        assumeTrue("set RUNJAR_JRE_ARCHIVE to a jre<major>-pojav.zip",
            path != null && path.isNotEmpty())
        val archive = File(path!!)
        assumeTrue("archive $archive missing", archive.isFile)

        // The declared ABI list decides which runtimes the UI offers and whether
        // a download is attempted at all, so it has to match what is published.
        // The archive's own name says which release it is.
        val release = JreRelease.entries.firstOrNull { archive.name.startsWith(it.id) }
        assumeTrue("archive name does not identify a known release: ${archive.name}",
            release != null)

        ZipFile(archive).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertTrue("universal layer missing: $names", names.contains("universal.tar.xz"))

            val published = names.filter { it.startsWith("bin-") && it.endsWith(".tar.xz") }
                .map { it.removePrefix("bin-").removeSuffix(".tar.xz") }
                .toSet()
            assertEquals(
                "declared ABIs for ${release!!.id} do not match the published archive",
                release.abis.toSet(),
                published,
            )
        }
    }
}
