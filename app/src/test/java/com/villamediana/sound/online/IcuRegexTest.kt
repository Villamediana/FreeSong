package com.villamediana.sound.online

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import org.junit.Assert.assertEquals

/**
 * Every regex in the app must compile on Android, whose engine is ICU, not java.util.regex.
 * The search broke on the phone (PatternSyntaxException on a bare "}") while every JVM test
 * passed. Runs tools/icu_regex_check.py against the sources; skipped where ICU isn't installed.
 */
class IcuRegexTest {
    @Test
    fun allRegexLiteralsCompileWithIcu() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "tools/icu_regex_check.py").isFile }
        assumeTrue(root != null && File("/usr/lib/x86_64-linux-gnu/libicui18n.so.74").exists())
        val sources = File(root, "app/src/main/java").walkTopDown().filter { it.extension == "kt" }.map { it.path }.toList()
        val process = ProcessBuilder(listOf("python3", File(root, "tools/icu_regex_check.py").path) + sources).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        println(output.lines().last { it.isNotBlank() })
        assertEquals(output, 0, code)
    }
}
