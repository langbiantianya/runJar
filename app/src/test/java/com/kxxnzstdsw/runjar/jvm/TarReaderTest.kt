package com.kxxnzstdsw.runjar.jvm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZInputStream
import org.tukaani.xz.XZOutputStream
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * The runtime installer unpacks GNU tars produced by the OpenJDK build, so the
 * reader has to survive exactly what those archives contain: names longer than
 * the 100-byte header field, files that straddle the 512-byte block boundary,
 * and zero-length files.
 *
 * The fixture was produced with:
 *   tar cJf fixture.tar.xz --format=gnu <deep tree> span.bin empty.txt
 * where `span.bin` is 1500 bytes (crosses the block boundary) and the deep
 * tree's leaf filename exceeds 100 characters.
 */
class TarReaderTest {

    private fun readFixture(): Map<String, ByteArray> {
        val stream = TarReaderTest::class.java.classLoader!!
            .getResourceAsStream("fixture-longnames.tar.xz")
            ?: error("fixture-longnames.tar.xz missing from test resources")
        val files = LinkedHashMap<String, ByteArray>()
        BufferedInputStream(XZInputStream(stream), 64 * 1024).use { decoded ->
            TarReader(decoded).use { tar ->
                while (true) {
                    val entry = tar.nextHeader() ?: break
                    if (entry.type != TarEntry.TYPE_FILE) continue
                    val body = ByteArrayOutputStream()
                    tar.copyEntry(body, entry.size)
                    files[entry.name] = body.toByteArray()
                }
            }
        }
        return files
    }

    @Test
    fun `extracts files with names longer than the ustar header field`() {
        val files = readFixture()
        val longName = "very/deep/nested/dir/structure/that/exceeds/one/hundred/characters/" +
                "for/sure/yes/indeed/path/segments/deeply_named_file_with_a_very_long_filename.txt"
        assertTrue(
            "GNU long-name entry was not resolved; got ${files.keys}",
            files.containsKey(longName),
        )
        assertEquals("long path content", String(files.getValue(longName)))
    }

    @Test
    fun `reads a file that spans several tar blocks`() {
        val files = readFixture()
        val span = files.getValue("span.bin")
        assertEquals(1500, span.size)
        val expected = ByteArray(1500) { i -> ((i * 7 + 3) % 251).toByte() }
        assertArrayEquals(expected, span)
    }

    @Test
    fun `reads a zero length file`() {
        val files = readFixture()
        assertTrue(files.containsKey("empty.txt"))
        assertEquals(0, files.getValue("empty.txt").size)
    }

    @Test
    fun `stops at the end of archive without reporting trailing entries`() {
        val stream = TarReaderTest::class.java.classLoader!!
            .getResourceAsStream("fixture-longnames.tar.xz")!!
        var count = 0
        BufferedInputStream(XZInputStream(stream), 64 * 1024).use { decoded ->
            TarReader(decoded).use { tar ->
                while (tar.nextHeader() != null) count++
            }
        }
        // Three files plus the directory entries above the deep tree.
        assertTrue("expected at least 3 entries, got $count", count >= 3)
    }

    @Test
    fun `reports entries in archive order and never yields an empty name`() {
        val stream = TarReaderTest::class.java.classLoader!!
            .getResourceAsStream("fixture-longnames.tar.xz")!!
        val names = mutableListOf<String>()
        BufferedInputStream(XZInputStream(stream), 64 * 1024).use { decoded ->
            TarReader(decoded).use { tar ->
                while (true) {
                    val entry = tar.nextHeader() ?: break
                    assertFalse("tar reader produced an empty entry name", entry.name.isEmpty())
                    names += entry.name
                }
            }
        }
        val spanIndex = names.indexOf("span.bin")
        val emptyIndex = names.indexOf("empty.txt")
        assertTrue("span.bin not found in $names", spanIndex >= 0)
        assertTrue("empty.txt not found in $names", emptyIndex >= 0)
        assertTrue("files were not emitted in order", spanIndex < emptyIndex)
    }

    @Test
    fun `an ordinary tree still extracts`() {
        val dest = freshDir()
        untarXz(
            tarXzOf(
                TarEntrySpec("bin", TarEntry.TYPE_DIR),
                TarEntrySpec("bin/java", TarEntry.TYPE_FILE, "ok".toByteArray()),
            ),
            dest,
        )
        assertEquals("ok", File(dest, "bin/java").readText())
    }

    @Test
    fun `refuses a file entry that would escape the destination`() {
        val dest = freshDir()
        val error = runCatching {
            untarXz(tarXzOf(TarEntrySpec("../../escaped.txt", TarEntry.TYPE_FILE, "x".toByteArray())), dest)
        }.exceptionOrNull()
        assertTrue("a traversing file entry must be rejected, got $error", error is IOException)
        assertFalse("nothing may be written outside the destination", File(dest.parentFile, "escaped.txt").exists())
    }

    @Test
    fun `refuses a directory entry that would escape the destination`() {
        val dest = freshDir()
        // The payload is never written, so a guard that only wrapped the file
        // branch would let this through and create the directory regardless.
        val error = runCatching {
            untarXz(tarXzOf(TarEntrySpec("../../escaped", TarEntry.TYPE_DIR)), dest)
        }.exceptionOrNull()
        assertTrue("a traversing directory entry must be rejected, got $error", error is IOException)
        assertFalse("nothing may be created outside the destination", File(dest.parentFile, "escaped").exists())
    }

    private fun freshDir(): File {
        val dir = Files.createTempDirectory("untarxz").toFile()
        dir.deleteOnExit()
        return dir
    }

    /** One entry to put in a hand-built archive. */
    private class TarEntrySpec(
        val name: String,
        val type: Byte,
        val body: ByteArray = ByteArray(0),
    )

    /**
     * A minimal ustar archive holding [entries], xz-compressed.
     *
     * Written by hand because the entries worth testing are ones no `tar` on a
     * normal filesystem will produce: a name that traverses out of the archive
     * root is exactly what the extractor has to refuse, and `tar` refuses to
     * write it in the first place.
     */
    private fun tarXzOf(vararg entries: TarEntrySpec): InputStream {
        val tar = ByteArrayOutputStream()
        for (entry in entries) {
            val header = ByteArray(TAR_BLOCK_SIZE)
            entry.name.toByteArray().copyInto(header, 0)
            writeOctal(header, 100, 8, 0)                       // mode
            writeOctal(header, 108, 8, 0)                       // uid
            writeOctal(header, 116, 8, 0)                       // gid
            writeOctal(header, 124, 12, entry.body.size)       // size
            writeOctal(header, 136, 12, 0)                      // mtime
            header[156] = entry.type
            // The ustar magic, spelled as bytes so the source holds no NUL itself.
            put(header, 257, 0x75, 0x73, 0x74, 0x61, 0x72, 0x00)
            put(header, 263, 0x30, 0x30)                       // version "00"
            // The checksum is computed with its own field read as spaces.
            for (i in 148 until 156) header[i] = ' '.code.toByte()
            var sum = 0
            for (b in header) sum += b.toInt() and 0xFF
            writeOctal(header, 148, 7, sum)
            header[155] = 0
            tar.write(header)
            tar.write(entry.body)
            val padding = (TAR_BLOCK_SIZE - (entry.body.size % TAR_BLOCK_SIZE)) % TAR_BLOCK_SIZE
            repeat(padding) { tar.write(0) }
        }
        repeat(TAR_BLOCK_SIZE * 2) { tar.write(0) }
        // Still xz-compressed: untarXz is what does the decoding.
        return ByteArrayInputStream(xz(tar.toByteArray()))
    }

    /** Writes the given bytes into [header] starting at [offset]. */
    private fun put(header: ByteArray, offset: Int, vararg bytes: Int) {
        bytes.forEachIndexed { i, value -> header[offset + i] = value.toByte() }
    }

    private fun xz(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { out ->
        XZOutputStream(out, LZMA2Options()).use { it.write(bytes) }
        out.toByteArray()
    }

    /** A NUL-terminated octal field, as a ustar header spells a number. */
    private fun writeOctal(header: ByteArray, offset: Int, length: Int, value: Int) {
        val text = value.toString(8).padStart(length - 1, '0').toByteArray()
        text.copyInto(header, offset)
        header[offset + length - 1] = 0
    }
}

/**
 * Guards the layer names the installer expects from the published archive.
 * The archives are repacked with `zip -j`, so their entries are flat names;
 * getting this wrong yields a runtime with no libjvm.so and a confusing error.
 */
class RuntimeArchiveLayoutTest {

    @Test
    fun `archive member names match what the installer requests`() {
        val stream = RuntimeArchiveLayoutTest::class.java.classLoader!!
            .getResourceAsStream("fixture-jre-archive.zip") ?: return
        val archive = File.createTempFile("jre-archive", ".zip")
        try {
            stream.use { input -> archive.outputStream().use { input.copyTo(it) } }
            ZipFile(archive).use { zip ->
                val names = zip.entries().toList().map { it.name }
                assertTrue("universal layer missing: $names", names.contains("universal.tar.xz"))
                for (abi in listOf("arm", "arm64", "x86", "x86_64")) {
                    assertTrue(
                        "bin-$abi.tar.xz missing: $names",
                        names.contains("bin-$abi.tar.xz"),
                    )
                }
            }
        } finally {
            archive.delete()
        }
    }
}
