package com.kxxnzstdsw.runjar.jvm

import java.io.File
import java.io.IOException
import java.util.jar.Attributes
import java.util.jar.JarFile

/**
 * Reads the `Main-Class` entry out of a JAR's manifest.
 *
 * This runs on the host (ART) side because the guest JVM has no way to answer
 * the question until it is already running — and it is running because the
 * caller needed to know which class to launch.
 */
object JarManifest {

    /** Returns the declared main class, or null when the manifest omits it. */
    fun mainClassOf(jar: File): String? {
        if (!jar.isFile) throw IOException("not a file: $jar")
        return try {
            JarFile(jar).use { it.manifest?.mainAttributes?.getValue(Attributes.Name.MAIN_CLASS) }
        } catch (e: IOException) {
            // A jar with no manifest at all is legal, just not runnable via -jar.
            null
        }
    }

    /**
     * The class path the guest JVM should see: the jar itself plus any
     * `Class-Path` the manifest declares, resolved against the jar's directory.
     */
    fun classPathOf(jar: File): String {
        val entries = try {
            JarFile(jar).use { file ->
                file.manifest?.mainAttributes?.getValue(Attributes.Name.CLASS_PATH)
            }
        } catch (e: IOException) {
            null
        } ?: return jar.absolutePath

        val parts = LinkedHashSet<String>()
        parts += jar.absolutePath
        val base = jar.parentFile ?: return jar.absolutePath
        for (token in entries.split(Regex("\\s+")).filter { it.isNotBlank() }) {
            val resolved = File(token)
            val file = if (resolved.isAbsolute) resolved else File(base, token)
            if (file.exists()) parts += file.absolutePath
        }
        return parts.joinToString(File.pathSeparator)
    }
}
