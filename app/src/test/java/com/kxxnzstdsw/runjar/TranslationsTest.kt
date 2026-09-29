package com.kxxnzstdsw.runjar

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Every language the app ships is the same app.
 *
 * The default resources are English, and each folder under `values-<locale>/` is
 * a translation of them. A key that is missing from one of those folders is not
 * a crash — it is a screen that drops back to English in the middle of a
 * sentence, which is harder to notice than a crash and just as wrong. A format
 * specifier that is missing is a run of the wrong text: a message still handed
 * an argument it no longer names, or one that names an argument it is not given.
 * Both are held to the default resources here, so a translation cannot drift
 * from the strings the code actually asks for.
 *
 * The languages are the ones the app declares in `locales_config.xml`, and they
 * are named here as the script-qualified tags the resource folders use: the two
 * Chinese folders name the script rather than a region, which is what makes
 * every Chinese locale — zh-CN, zh-SG, zh-TW, zh-HK — resolve to one of them.
 * English has no folder of its own because `values/` is English already.
 */
class TranslationsTest {

    /** The resources as they sit in the module; unit tests run from it. */
    private val resourceDir = File("src/main/res")

    /** The default resources: English, and the key set every language answers to. */
    private val defaultFile = File(resourceDir, "values/strings.xml")

    /**
     * The languages the app promises, as `res/values-<qualifier>` folders.
     *
     * English is absent by construction — it is the default resources — so the
     * set is the five translations on top of them.
     */
    private val promisedLocales = setOf(
        "b+zh+Hans",
        "b+zh+Hant",
        "ja",
        "ru",
        "de",
    )

    @Test
    fun everyPromisedLanguageIsTranslated() {
        assertTrue("the default resources are English", defaultFile.isFile)

        val present = translated().map { it.name.removePrefix("values-") }.toSet()
        assertEquals(
            "each language the app promises has a folder of its own",
            emptySet<String>(),
            promisedLocales - present,
        )
    }

    @Test
    fun everyTranslatableKeyIsInEveryLanguage() {
        val expected = entriesIn(defaultFile)
            .filter { it.translatable }
            .map { it.key }
            .sorted()
        assertTrue("the default resources carry the app's strings", expected.isNotEmpty())

        translated().forEach { dir ->
            assertEquals(
                "the keys in ${dir.name} are the translatable keys of values/strings.xml",
                expected,
                entriesIn(File(dir, "strings.xml")).map { it.key }.sorted(),
            )
        }
    }

    @Test
    fun everyTranslationNamesTheSameArguments() {
        val english = entriesIn(defaultFile).associateBy { it.key }

        translated().forEach { dir ->
            entriesIn(File(dir, "strings.xml")).forEach { entry ->
                val source = english.getValue(entry.key)
                assertEquals(
                    "${entry.key} in ${dir.name} names the same format arguments as English",
                    argumentsIn(source.text),
                    argumentsIn(entry.text),
                )
            }
        }
    }

    /** Every resource folder that carries a `strings.xml`, in a stable order. */
    private fun translated(): List<File> = resourceDir.listFiles { file ->
        file.isDirectory && file.name.startsWith("values-") && File(file, "strings.xml").isFile
    }.orEmpty().sortedBy { it.name }

    /**
     * The specifiers a formatted string is given, in the order they are named:
     * `%1$s` and `%2$d` are the arguments, and their position is part of the
     * contract because the caller passes them positionally.
     */
    private fun argumentsIn(text: String): List<String> =
        ARGUMENT.findAll(text).map { it.value }.sorted().toList()

    /** One `<string>` entry, as the resources declare it. */
    private data class Entry(val key: String, val text: String, val translatable: Boolean)

    private fun entriesIn(file: File): List<Entry> {
        val nodes = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
            .getElementsByTagName("string")
        return (0 until nodes.length).map { index ->
            val element = nodes.item(index) as Element
            Entry(
                key = element.getAttribute("name"),
                text = element.textContent,
                // Absent means translatable: the attribute marks the exceptions.
                translatable = element.getAttribute("translatable") != "false",
            )
        }
    }

    private companion object {
        /** A positional specifier, e.g. `%1$s` or `%12$d`. */
        val ARGUMENT = Regex("""%\d+\$[a-zA-Z]""")
    }
}
