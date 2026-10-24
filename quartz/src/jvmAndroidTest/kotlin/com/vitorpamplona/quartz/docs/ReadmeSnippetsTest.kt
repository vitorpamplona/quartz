/*
 * Copyright (c) 2025 Vitor Pamplona
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.vitorpamplona.quartz.docs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Keeps the root README.md honest: every Kotlin code block in it must appear, line for line, in
 * one of the compiled sample files, so a README example that stops compiling fails the build.
 *
 * Indentation and blank lines around a block are ignored. A block whose info string mentions
 * `gradle` (```` ```kotlin build.gradle.kts ````) is a build script and is skipped.
 */
class ReadmeSnippetsTest {
    private val repoRoot: File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, "README.md").isFile }
            ?: error("Could not find the repository root from ${System.getProperty("user.dir")}")

    private val sampleFiles =
        listOf(
            "quartz/src/jvmAndroidTest/kotlin/com/vitorpamplona/quartz/docs/ReadmeSamples.kt",
            "quartz/src/androidHostTest/kotlin/com/vitorpamplona/quartz/docs/ReadmeAndroidSamples.kt",
        )

    private class Block(
        val line: Int,
        val code: List<String>,
    )

    private fun kotlinBlocks(markdown: List<String>): List<Block> {
        val blocks = mutableListOf<Block>()
        var start = -1
        var checked = false
        val code = mutableListOf<String>()
        markdown.forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (start < 0) {
                if (trimmed.startsWith("```")) {
                    val info = trimmed.removePrefix("```").trim()
                    val language = info.substringBefore(' ')
                    start = index
                    checked = (language == "kt" || language == "kotlin") && !info.contains("gradle")
                    code.clear()
                }
            } else if (trimmed == "```") {
                if (checked) blocks.add(Block(start + 1, code.toList()))
                start = -1
            } else {
                code.add(line)
            }
        }
        return blocks
    }

    private fun normalize(lines: List<String>): List<String> = lines.map { it.trim() }.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }

    private fun List<String>.containsRun(run: List<String>): Boolean = run.isNotEmpty() && windowed(run.size).any { it == run }

    @Test
    fun everyReadmeKotlinBlockIsCompiled() {
        val samples = sampleFiles.map { normalize(File(repoRoot, it).readLines()) }
        val blocks = kotlinBlocks(File(repoRoot, "README.md").readLines())

        assertTrue(blocks.isNotEmpty(), "Found no Kotlin code blocks in README.md")

        val missing = blocks.filter { block -> samples.none { it.containsRun(normalize(block.code)) } }
        if (missing.isNotEmpty()) {
            fail(
                "README.md has Kotlin blocks that no sample file compiles (starting at line " +
                    missing.joinToString { it.line.toString() } +
                    "). Put the code in ${sampleFiles.first()}, run spotlessApply and copy the " +
                    "formatted code back into the README.",
            )
        }
    }
}
