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
package com.vitorpamplona.quartz.nip34Git.git

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What [GitFileHistory] downloads, over real git: the path trees only, and a fallback for a
 * server that won't send trees by id.
 */
class GitFetchCostsGitTest {
    /** Records each `git-upload-pack` fetch: its request and the objects its pack held. */
    private class Counting(
        git: GitHttpBackendFixture,
        refuse: ((String) -> Response?)? = null,
    ) {
        val fetches = ArrayList<String>()
        var objects = 0L

        val client: OkHttpClient =
            OkHttpClient
                .Builder()
                .addInterceptor(
                    Interceptor { chain ->
                        val request = chain.request()
                        val body = Buffer().also { request.body?.writeTo(it) }.readByteArray().decodeToString()
                        if (!body.contains("command=fetch")) return@Interceptor chain.proceed(request)
                        fetches.add(body)
                        val response =
                            refuse
                                ?.invoke(body)
                                ?.newBuilder()
                                ?.request(request)
                                ?.build() ?: chain.proceed(request)
                        val bytes = response.body.bytes()
                        val pack = runCatching { GitUploadPackV2.extractPack(PktLineCodec.parse(bytes)) }.getOrNull()
                        if (pack != null) objects += readUInt32(pack, 8)
                        response.newBuilder().body(bytes.toResponseBody(response.body.contentType())).build()
                    },
                ).also { builder -> git.client.interceptors.forEach { builder.addInterceptor(it) } }
                .build()

        fun reset() {
            fetches.clear()
            objects = 0
        }

        private fun readUInt32(
            data: ByteArray,
            offset: Int,
        ): Long {
            var v = 0L
            for (i in 0 until 4) v = (v shl 8) or (data[offset + i].toLong() and 0xFF)
            return v
        }
    }

    private var clock = 1_000_000L

    /** Runs git with the next author and committer date, so history order is known. */
    private fun gitAt(
        work: File,
        vararg args: String,
    ): String {
        val process = ProcessBuilder(listOf("git") + args).directory(work).redirectErrorStream(true)
        clock += 100
        process.environment()["GIT_AUTHOR_DATE"] = "@$clock +0000"
        process.environment()["GIT_COMMITTER_DATE"] = "@$clock +0000"
        val running = process.start()
        val out = running.inputStream.readBytes().decodeToString()
        running.waitFor(30, TimeUnit.SECONDS)
        return out.trim()
    }

    private fun commitAll(
        work: File,
        message: String,
    ) {
        gitAt(work, "add", ".")
        gitAt(work, "commit", "-q", "-m", message)
    }

    private val deepPath = listOf("a", "b", "c", "d", "e", "f.txt")

    /** A history where the deep file changes now and then, a merge included, among many other folders. */
    private fun deepRepo(
        git: GitHttpBackendFixture,
        work: File,
    ): String {
        val file = File(work, deepPath.joinToString("/")).also { it.parentFile.mkdirs() }
        file.writeText("line 1\n")
        commitAll(work, "add f")
        repeat(16) { i ->
            File(work, "folder$i/inner/x.txt").also { it.parentFile.mkdirs() }.writeText("x $i\n")
            // Folders off the path that change with every commit: trees the old fetch brought too.
            repeat(4) { j -> File(work, "lib$j/src/main/x.txt").also { it.parentFile.mkdirs() }.writeText("x $i $j\n") }
            File(work, "a/b/c/side$i.txt").writeText("side $i\n")
            if (i % 4 == 0) file.appendText("line ${i + 2}\n")
            commitAll(work, "change $i")
        }
        gitAt(work, "checkout", "-q", "-b", "side")
        file.writeText("line 0\n" + file.readText())
        commitAll(work, "side edits f")
        gitAt(work, "checkout", "-q", "main")
        File(work, "folder0/other.txt").writeText("main\n")
        commitAll(work, "main moves on")
        gitAt(work, "merge", "-q", "--no-edit", "side")
        file.appendText("last\n")
        commitAll(work, "after the merge")
        git.git(work, "push", "-q", "origin", "HEAD:main")
        return git.git(work, "rev-parse", "HEAD")
    }

    private fun gitBlame(
        git: GitHttpBackendFixture,
        work: File,
        file: String,
    ) = git
        .git(work, "blame", "--line-porcelain", file)
        .lines()
        .filter { it.matches(Regex("^[0-9a-f]{40} .*")) }
        .map { it.substringBefore(' ') }

    @Test
    fun deepPathHistoryFetchesOnlyThePathTrees() =
        GitHttpBackendFixture().use { git ->
            if (!git.available) return@use
            val (bare, url) = git.bare("repo")
            val work = git.work(bare, "work")
            val tip = deepRepo(git, work)
            val joined = deepPath.joinToString("/")

            // What the whole-window fetch used to bring.
            val counting = Counting(git)
            val transport = GitSmartHttpTransport({ counting.client })
            runBlocking {
                val caps = transport.fetchCapabilities(url)
                Packfile.parse(transport.fetchPack(url, caps, listOf(tip), deepen = GitFileHistory.DEFAULT_DEPTH, filter = "blob:none"))
            }
            val before = counting.objects
            counting.reset()

            val files = GitFileHistory(transport)
            val history = runBlocking { files.history(url, tip, deepPath) }
            assertEquals(git.git(work, "log", "--format=%H", "--", joined).lines(), history.map { it.commit.oid })
            // Commits, then one request per level of the path.
            assertEquals(1 + deepPath.size, counting.fetches.size)
            assertTrue(counting.fetches.drop(1).all { it.contains("filter tree:0") && !it.contains("deepen") })
            val commits = git.git(work, "rev-list", "--count", tip).toLong()
            val pathTrees = deepPath.indices.sumOf { level -> pathTreesAt(git, work, deepPath.take(level)) }
            assertEquals(commits + pathTrees, counting.objects)
            assertTrue(counting.objects * 2 < before, "path trees ${counting.objects} vs every tree $before")

            // Blame of the same file reads the cached window: only the file's versions are fetched.
            counting.reset()
            val blame = runBlocking { files.blame(url, tip, deepPath) }!!
            assertEquals(1, counting.fetches.size)
            assertEquals(gitBlame(git, work, joined), blame.lines.map { blame.versions[it].commit.oid })
            assertFalse(blame.truncated)

            // A file beside it needs only the last level's trees again: the rest are cached.
            counting.reset()
            val side = listOf("a", "b", "c", "side3.txt")
            val sideHistory = runBlocking { files.history(url, tip, side) }
            assertEquals(git.git(work, "log", "--format=%H", "--", side.joinToString("/")).lines(), sideHistory.map { it.commit.oid })
            assertEquals(0, counting.fetches.size)
        }

    /** The distinct trees at [prefix] across every commit, as git sees them. */
    private fun pathTreesAt(
        git: GitHttpBackendFixture,
        work: File,
        prefix: List<String>,
    ): Long =
        git
            .git(work, "rev-list", "HEAD")
            .lines()
            .mapNotNull { commit ->
                val spec = if (prefix.isEmpty()) "$commit^{tree}" else "$commit:${prefix.joinToString("/")}"
                git.git(work, "rev-parse", "--verify", "-q", spec).takeIf { it.length == 40 }
            }.toSet()
            .size
            .toLong()

    private fun packfileResponse(pack: ByteArray): Response =
        Response
            .Builder()
            .request(Request.Builder().url("http://git.test/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("")
            .body(
                PktLineCodec
                    .build {
                        write(PktLineCodec.dataLine("packfile\n"))
                        write(PktLineCodec.dataLine(byteArrayOf(1) + pack))
                        write(PktLineCodec.FLUSH)
                    }.toResponseBody("application/x-git-upload-pack-result".toMediaType()),
            ).build()

    private fun errResponse(): Response =
        Response
            .Builder()
            .request(Request.Builder().url("http://git.test/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("")
            .body(
                PktLineCodec
                    .build { write(PktLineCodec.dataLine("ERR upload-pack: not our ref\n")) }
                    .toResponseBody("application/x-git-upload-pack-result".toMediaType()),
            ).build()

    private fun emptyPack(): ByteArray {
        val header = "PACK".encodeToByteArray() + byteArrayOf(0, 0, 0, 2, 0, 0, 0, 0)
        return header + MessageDigest.getInstance("SHA-1").digest(header)
    }

    private fun isTreeWant(body: String) = body.contains("filter tree:0") && !body.contains("deepen")

    @Test
    fun historyFallsBackWhenTheServerRefusesTreeWants() =
        GitHttpBackendFixture().use { git ->
            if (!git.available) return@use
            val (bare, url) = git.bare("repo")
            val work = git.work(bare, "work")
            val tip = deepRepo(git, work)
            val joined = deepPath.joinToString("/")
            val expected = git.git(work, "log", "--format=%H", "--", joined).lines()

            for (refusal in listOf({ errResponse() }, { packfileResponse(emptyPack()) })) {
                val counting = Counting(git) { body -> if (isTreeWant(body)) refusal() else null }
                val files = GitFileHistory(GitSmartHttpTransport({ counting.client }))
                val history = runBlocking { files.history(url, tip, deepPath) }
                assertEquals(expected, history.map { it.commit.oid })
                assertTrue(counting.fetches.any { it.contains("filter blob:none") && it.contains("deepen") })

                val blame = runBlocking { files.blame(url, tip, deepPath) }!!
                assertEquals(gitBlame(git, work, joined), blame.lines.map { blame.versions[it].commit.oid })

                // Once refused, a later path goes straight to the whole-window fetch.
                counting.reset()
                val side = listOf("a", "b", "c", "side3.txt")
                val sideHistory = runBlocking { files.history(url, tip, side) }
                assertEquals(git.git(work, "log", "--format=%H", "--", side.joinToString("/")).lines(), sideHistory.map { it.commit.oid })
                assertTrue(counting.fetches.none { isTreeWant(it) })
            }
        }
}
