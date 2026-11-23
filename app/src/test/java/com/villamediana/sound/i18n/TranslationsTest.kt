package com.villamediana.sound.i18n

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every word of the app (Portuguese, in values/) is also in English and in Spanish: a string left out
 * would show in Portuguese to someone who chose another language.
 */
class TranslationsTest {
    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
    private val name = Regex("""<(string|plurals|string-array) name="([^"]+)"([^>]*)>""")

    private fun names(folder: String): Map<String, Set<String>> =
        File(res, folder).listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty().associate { f ->
            f.name to name.findAll(f.readText()).filter { "translatable=\"false\"" !in it.groupValues[3] }.map { it.groupValues[2] }.toSet()
        }

    @Test
    fun everyStringIsInEnglishAndInSpanish() {
        val pt = names("values")
        assertTrue("no strings found under $res", pt.isNotEmpty())
        for (lang in listOf("values-en", "values-es")) {
            val other = names(lang).values.flatten().toSet()
            val missing = pt.flatMap { (file, set) -> (set - other).map { "$file: $it" } }
            assertTrue("$lang is missing: $missing", missing.isEmpty())
        }
    }
}
