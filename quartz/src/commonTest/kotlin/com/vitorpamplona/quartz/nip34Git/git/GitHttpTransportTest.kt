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

import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The git clients over a [GitHttpTransport] that answers like a protocol-v2 server, in memory: what
 * an app's transport (OkHttp, Ktor) is asked to send, and what the clients make of the answers.
 */
class GitHttpTransportTest {
    private val readme = GitObjectWriter.blob("hello\n".encodeToByteArray())
    private val code = GitObjectWriter.blob("fun main() {}\n// the needle is here\n".encodeToByteArray())
    private val src = GitObjectWriter.tree(listOf(GitTreeEntry("100644", "a.kt", code.oid)))
    private val root = GitObjectWriter.tree(listOf(GitTreeEntry("100644", "README", readme.oid), GitTreeEntry("40000", "src", src.oid)))
    private val ann = GitPerson("Ann", "ann@x.org", 1_700_000_000)
    private val tip = GitObjectWriter.commit(root.oid, emptyList(), ann, ann, "first commit")
    private val objects = listOf(tip, root, src, readme, code).associateBy { it.oid }

    private class Call(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val contentType: String?,
        val body: ByteArray?,
    )

    private inner class FakeServer(
        val status: Int = 200,
    ) : GitHttpTransport {
        val calls = ArrayList<Call>()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
        ): GitHttpResponse {
            calls.add(Call("GET", url, headers, null, null))
            if (status != 200) return GitHttpResponse(status, "not here".encodeToByteArray())
            return when {
                url.endsWith("/info/refs?service=git-upload-pack") ->
                    GitHttpResponse(
                        200,
                        PktLines.build {
                            write(PktLines.dataLine("version 2\n"))
                            write(PktLines.dataLine("agent=git/2.45\n"))
                            write(PktLines.dataLine("ls-refs\n"))
                            write(PktLines.dataLine("fetch=shallow filter\n"))
                            write(PktLines.dataLine("object-format=sha1\n"))
                            write(PktLines.FLUSH)
                        },
                    )
                url.endsWith("/info/refs?service=git-receive-pack") ->
                    GitHttpResponse(
                        200,
                        PktLines.build {
                            write(PktLines.dataLine("# service=git-receive-pack\n"))
                            write(PktLines.FLUSH)
                            write(PktLines.dataLine("${tip.oid} refs/heads/main\u0000report-status side-band-64k agent=git/2.45\n"))
                            write(PktLines.FLUSH)
                        },
                    )
                else -> GitHttpResponse(404, ByteArray(0))
            }
        }

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            contentType: String,
            body: ByteArray,
        ): GitHttpResponse {
            calls.add(Call("POST", url, headers, contentType, body))
            if (status != 200) return GitHttpResponse(status, "refused".encodeToByteArray())
            if (url.endsWith("/git-receive-pack")) return GitHttpResponse(200, receiveReport())
            return GitHttpResponse(200, answer(body))
        }

        override suspend fun <T> postStreaming(
            url: String,
            headers: Map<String, String>,
            contentType: String,
            body: ByteArray,
            read: (status: Int, body: GitByteSource) -> T,
        ): T {
            calls.add(Call("STREAM", url, headers, contentType, body))
            return read(status, GitByteSource.of(if (status == 200) answer(body) else ByteArray(0)))
        }

        private fun answer(request: ByteArray): ByteArray {
            val lines = PktLines.parse(request).filterIsInstance<PktLine.Data>().map { it.text() }
            return if ("command=ls-refs" in lines) {
                PktLines.build {
                    write(PktLines.dataLine("${tip.oid} HEAD symref-target:refs/heads/main\n"))
                    write(PktLines.dataLine("${tip.oid} refs/heads/main\n"))
                    write(PktLines.FLUSH)
                }
            } else {
                val wants = lines.filter { it.startsWith("want ") }.map { it.removePrefix("want ") }
                val sent =
                    if ("filter blob:none" in lines && tip.oid in wants) {
                        listOf(tip, root, src)
                    } else {
                        wants.map { objects.getValue(it) }
                    }
                PktLines.build {
                    write(PktLines.dataLine("packfile\n"))
                    write(PktLines.dataLine(byteArrayOf(1) + PackfileWriter.write(sent)))
                    write(PktLines.FLUSH)
                }
            }
        }

        private fun receiveReport(): ByteArray {
            val report =
                PktLines.build {
                    write(PktLines.dataLine("unpack ok\n"))
                    write(PktLines.dataLine("ok refs/heads/main\n"))
                    write(PktLines.FLUSH)
                }
            return PktLines.build {
                write(PktLines.dataLine(byteArrayOf(1) + report))
                write(PktLines.FLUSH)
            }
        }
    }

    /** An auth that signs nothing: a fixed header, so the test sees where it goes. */
    private class FixedAuth : GitHttpAuth(NostrSignerInternal(KeyPair())) {
        override suspend fun header(cloneUrl: String): String = "Nostr test-token"
    }

    private val url = "https://git.example.com/npub1abc/repo.git/"

    @Test
    fun opensBrowsesAndSearchesARepository() =
        runTest {
            val server = FakeServer()
            val snapshot = GitHttpClient(server).open(url)

            assertEquals(tip.oid, snapshot.headCommit)
            assertEquals("main", snapshot.branch)
            assertEquals(listOf("main"), snapshot.branches)
            assertEquals(listOf("src", "README"), snapshot.rootEntries().map { it.name })
            assertEquals("first commit", snapshot.tipCommit?.summary)
            assertEquals(listOf("README", "src/a.kt"), snapshot.walkFileNames().sorted())

            assertEquals("hello\n", snapshot.readBlob(readme.oid).decodeToString())

            val matches = snapshot.searchContents("NEEDLE")
            assertEquals(1, matches.size)
            assertEquals(listOf("src", "a.kt"), matches[0].path)
            assertEquals(2, matches[0].line)

            // The first request asks for protocol v2 at the trimmed URL; fetches post a v2 body.
            val first = server.calls.first()
            assertEquals("GET", first.method)
            assertEquals("https://git.example.com/npub1abc/repo.git/info/refs?service=git-upload-pack", first.url)
            assertEquals(mapOf("Git-Protocol" to "version=2", "Accept" to "*/*"), first.headers)
            val posts = server.calls.filter { it.method != "GET" }
            assertTrue(posts.isNotEmpty())
            posts.forEach {
                assertEquals("https://git.example.com/npub1abc/repo.git/git-upload-pack", it.url)
                assertEquals("application/x-git-upload-pack-request", it.contentType)
                assertEquals(mapOf("Git-Protocol" to "version=2", "Accept" to "application/x-git-upload-pack-result"), it.headers)
            }
            // The search streamed its batch.
            assertTrue(server.calls.any { it.method == "STREAM" })
        }

    @Test
    fun aPrivateRepositoryGetsTheAuthorizationHeaderOnEveryRequest() =
        runTest {
            val server = FakeServer()
            val transport = GitSmartHttpTransport(server, FixedAuth())
            val caps = transport.fetchCapabilities(url)
            transport.lsRefs(url, caps)
            transport.fetchPack(url, caps, listOf(readme.oid), deepen = null, filter = null)
            transport.fetchPackSource(url, caps, listOf(readme.oid), deepen = null, filter = null) { GitPackReader.read(it) { } }
            assertEquals(4, server.calls.size)
            server.calls.forEach { assertEquals("Nostr test-token", it.headers["Authorization"]) }
        }

    @Test
    fun aFailedRequestIsAGitHttpExceptionWithItsStatus() =
        runTest {
            val server = FakeServer(status = 404)
            val error = assertFailsWith<GitHttpException> { GitHttpClient(server).open(url) }
            assertEquals(404, error.httpCode)

            val caps = GitCapabilities("git", "sha1", true, setOf("shallow"))
            val streamed = assertFailsWith<GitHttpException> { GitSmartHttpTransport(server).fetchPackSource(url, caps, listOf(readme.oid), null, null) { } }
            assertEquals(404, streamed.httpCode)

            val push = assertFailsWith<GitHttpException> { GitPushClient(server).advertisement(url) }
            assertEquals(404, push.httpCode)
            assertTrue(push.message!!.endsWith(": not here"), push.message)
        }

    @Test
    fun pushesRefUpdatesAndAPack() =
        runTest {
            val server = FakeServer()
            val next = GitObjectWriter.commit(root.oid, listOf(tip.oid), ann, ann, "second")
            val report = GitPushClient(server, "quartz-test", FixedAuth()).push(url, listOf(GitRefUpdate("refs/heads/main", tip.oid, next.oid)), listOf(next))

            assertTrue(report.isSuccess, report.describe())
            assertEquals(listOf("refs/heads/main"), report.accepted)

            val get = server.calls[0]
            assertEquals("https://git.example.com/npub1abc/repo.git/info/refs?service=git-receive-pack", get.url)
            assertEquals(mapOf("Accept" to "*/*", "Authorization" to "Nostr test-token"), get.headers)
            val post = server.calls[1]
            assertEquals("https://git.example.com/npub1abc/repo.git/git-receive-pack", post.url)
            assertEquals("application/x-git-receive-pack-request", post.contentType)
            assertEquals(mapOf("Accept" to "application/x-git-receive-pack-result", "Authorization" to "Nostr test-token"), post.headers)
            // The commands as pkt-lines up to a flush, then the pack.
            val body = post.body!!
            val packAt = body.decodeToString().indexOf("PACK")
            assertTrue(packAt > 0)
            val commands = PktLines.parse(body.copyOfRange(0, packAt))
            assertEquals("${tip.oid} ${next.oid} refs/heads/main\u0000report-status side-band-64k agent=quartz-test", assertIsData(commands.first()).text())
            assertEquals(PktLine.Flush, commands.last())
            assertEquals(listOf(next.oid), Packfile.parse(body.copyOfRange(packAt, body.size)).keys.toList())
        }

    private fun assertIsData(line: PktLine): PktLine.Data {
        assertTrue(line is PktLine.Data)
        return line
    }
}
