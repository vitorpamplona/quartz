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
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [GitPushClient] against real git: every request goes through an interceptor into
 * `git http-backend`, the CGI program git servers run, over a bare repository on disk. Skipped
 * where git's http-backend isn't installed.
 */
class GitPushClientGitTest {
    private val backend = listOf("/usr/lib/git-core/git-http-backend", "/usr/libexec/git-core/git-http-backend").map(::File).firstOrNull { it.canExecute() }

    private fun git(
        dir: File,
        vararg args: String,
    ): String {
        val process = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
        val out = process.inputStream.readBytes().decodeToString()
        process.waitFor(30, TimeUnit.SECONDS)
        return out.trim()
    }

    private fun clientFor(root: File): OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    val body = Buffer().also { request.body?.writeTo(it) }.readByteArray()
                    val process =
                        ProcessBuilder(backend!!.path)
                            .apply {
                                environment().apply {
                                    put("GIT_PROJECT_ROOT", root.path)
                                    put("GIT_HTTP_EXPORT_ALL", "1")
                                    put("REMOTE_USER", "tester")
                                    put("REQUEST_METHOD", request.method)
                                    put("PATH_INFO", request.url.encodedPath)
                                    put("QUERY_STRING", request.url.encodedQuery ?: "")
                                    put("CONTENT_TYPE", request.body?.contentType()?.toString() ?: "")
                                    put("CONTENT_LENGTH", body.size.toString())
                                }
                            }.start()
                    process.outputStream.use { it.write(body) }
                    val output = process.inputStream.readBytes()
                    process.waitFor(30, TimeUnit.SECONDS)
                    val split = (0..output.size - 4).first { output[it] == '\r'.code.toByte() && output[it + 1] == '\n'.code.toByte() && output[it + 2] == '\r'.code.toByte() && output[it + 3] == '\n'.code.toByte() }
                    val headers = output.decodeToString(0, split).lines()
                    val status =
                        headers
                            .firstOrNull { it.startsWith("Status:") }
                            ?.removePrefix("Status:")
                            ?.trim()
                            ?.substringBefore(' ')
                            ?.toInt() ?: 200
                    val type = headers.firstOrNull { it.startsWith("Content-Type:") }?.removePrefix("Content-Type:")?.trim() ?: "application/octet-stream"
                    Response
                        .Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(status)
                        .message("")
                        .body(output.copyOfRange(split + 4, output.size).toResponseBody(type.toMediaType()))
                        .build()
                },
            ).build()

    @Test
    fun pushesNewObjectsAndMovesABranchAsGitReceivePackAccepts() {
        if (backend == null) return
        val root = Files.createTempDirectory("quartz-push").toFile()
        try {
            val bare = File(root, "repo.git").also { it.mkdirs() }
            git(bare, "init", "--bare", "-q", "-b", "main")
            git(bare, "config", "http.receivepack", "true")

            val client = GitPushClient({ clientFor(root) }, agent = "quartz-test")
            val url = "http://git.test/repo.git"

            // An empty repository: create main from nothing.
            val blob = GitObjectWriter.blob("hello\n".encodeToByteArray())
            val tree = GitObjectWriter.tree(listOf(GitTreeEntry("100644", "README", blob.oid)))
            val first = GitObjectWriter.commit(tree.oid, emptyList(), GitPerson("Ann", "ann@x.org", 1700000000), GitPerson("Ann", "ann@x.org", 1700000000), "first")
            val created = runBlocking { client.push(url, listOf(GitRefUpdate("refs/heads/main", GIT_ZERO_OID, first.oid)), listOf(blob, tree, first)) }
            assertTrue(created.isSuccess, "create: ${created.unpackError} ${created.refErrors}")
            assertEquals(first.oid, git(bare, "rev-parse", "refs/heads/main"))
            assertEquals("hello", git(bare, "show", "main:README"))

            // A fast-forward that sends only the new commit (the tree is already there).
            val second = GitObjectWriter.commit(tree.oid, listOf(first.oid), GitPerson("Bob", "bob@x.org", 1700000100), GitPerson("Bob", "bob@x.org", 1700000100), "second")
            val moved = runBlocking { client.push(url, listOf(GitRefUpdate("refs/heads/main", first.oid, second.oid)), listOf(second)) }
            assertTrue(moved.isSuccess, "fast-forward: ${moved.unpackError} ${moved.refErrors}")
            assertEquals(second.oid, git(bare, "rev-parse", "refs/heads/main"))
            assertEquals("ok", git(bare, "fsck", "--no-progress").ifEmpty { "ok" })

            // A stale old id is refused, not overwritten: someone moved main meanwhile.
            val third = GitObjectWriter.commit(tree.oid, listOf(first.oid), GitPerson("Cy", "cy@x.org", 1700000200), GitPerson("Cy", "cy@x.org", 1700000200), "third")
            val stale = runBlocking { client.push(url, listOf(GitRefUpdate("refs/heads/main", first.oid, third.oid)), listOf(third)) }
            assertFalse(stale.isSuccess)
            assertTrue("refs/heads/main" in stale.refErrors, "expected main to be refused: ${stale.refErrors}")
            assertEquals(second.oid, git(bare, "rev-parse", "refs/heads/main"))

            // The advertisement lists the branch at its new place.
            assertEquals(second.oid, runBlocking { client.advertisement(url) }.refs["refs/heads/main"])
        } finally {
            root.deleteRecursively()
        }
    }
}
