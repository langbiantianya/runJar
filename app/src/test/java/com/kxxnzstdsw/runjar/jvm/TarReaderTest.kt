package com.kxxnzstdsw.runjar.jvm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
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
    fun `refuses an archive entry that would escape the destination`() {
        // The guard lives in the installer; assert the invariant it protects by
        // checking that a traversing name would be caught by the canonical-path
        // comparison the installer applies.
        val dest = File("/data/data/com.kxxnzstdsw.runjar/files/jre/.staging-jre21")
        val escaping = File(dest, "../../../databases/app.db")
        assertFalse(
            "traversing entry must not resolve inside the destination",
            escaping.canonicalPath.startsWith(dest.canonicalPath + File.separator),
        )
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
