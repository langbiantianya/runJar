package com.kxxnzstdsw.runjar.jvm

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import org.tukaani.xz.XZInputStream

/**
 * Extracts an xz-compressed tar into a directory.
 *
 * Kept out of [JreInstaller] so that the one part of unpacking which is a
 * security decision — where an entry is allowed to land — can be tested without
 * an Android `Context` to construct.
 */
internal fun untarXz(input: InputStream, dest: File) {
    // Resolved once: the check below compares against it for every entry, and
    // resolving a path per entry on a tree of thousands is wasted work.
    val root = dest.canonicalPath
    val decoded = BufferedInputStream(XZInputStream(input), 64 * 1024)
    TarReader(decoded).use { tar ->
        while (true) {
            val entry = tar.nextHeader() ?: break
            if (entry.type != TarEntry.TYPE_DIR && entry.type != TarEntry.TYPE_FILE) continue
            // Both kinds are checked, not just the ones that write bytes: a
            // directory entry is a name the archive gets to choose too, and
            // `mkdirs` on a traversing one creates directories outside the
            // destination before a single file is ever written.
            val out = containedIn(dest, root, entry.name)
            if (entry.type == TarEntry.TYPE_DIR) {
                out.mkdirs()
                continue
            }
            out.parentFile?.mkdirs()
            out.outputStream().use { output -> tar.copyEntry(output, entry.size) }
        }
    }
}

/**
 * Resolves [name] under [dest], or throws if it would land outside.
 *
 * The comparison is made on canonical paths, so a name that traverses with
 * `..`, or whose parent is a symlink, is caught rather than merely looking
 * harmless. [root] is [dest]'s already-resolved path, passed in so the caller
 * resolves it once for the whole archive.
 */
private fun containedIn(dest: File, root: String, name: String): File {
    val candidate = File(dest, name).canonicalFile
    if (candidate.path != root && !candidate.path.startsWith(root + File.separator)) {
        throw IOException("archive entry escapes the destination directory: $name")
    }
    return candidate
}
