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

/**
 * Real git behind an OkHttp interceptor: every request runs `git http-backend`, the CGI program git
 * servers run, over repositories under a temporary [root]. [available] is false where git's
 * http-backend isn't installed, and tests using it skip.
 */
class GitHttpBackendFixture : AutoCloseable {
    private val backend = listOf("/usr/lib/git-core/git-http-backend", "/usr/libexec/git-core/git-http-backend").map(::File).firstOrNull { it.canExecute() }

    fun git(
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
                                    // http-backend reads the protocol version from the header, as a CGI variable.
                                    request.header("Git-Protocol")?.let { put("HTTP_GIT_PROTOCOL", it) }
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

    val available: Boolean get() = backend != null
    val root: File = Files.createTempDirectory("quartz-git").toFile()
    val client: OkHttpClient by lazy { clientFor(root) }

    /** A bare repository that accepts pushes, served at the returned URL. */
    fun bare(name: String): Pair<File, String> {
        val bare = File(root, "$name.git").also { it.mkdirs() }
        git(bare, "init", "--bare", "-q", "-b", "main")
        git(bare, "config", "http.receivepack", "true")
        git(bare, "config", "uploadpack.allowAnySHA1InWant", "true")
        git(bare, "config", "uploadpack.allowFilter", "true")
        return bare to "http://git.test/$name.git"
    }

    /** A working clone of [bare] with an identity, for building history with real git. */
    fun work(
        bare: File,
        name: String,
    ): File {
        val dir = File(root, name)
        git(root, "clone", "-q", bare.path, dir.path)
        git(dir, "config", "user.name", "Ann")
        git(dir, "config", "user.email", "ann@x.org")
        git(dir, "config", "commit.gpgsign", "false")
        return dir
    }

    override fun close() {
        root.deleteRecursively()
    }
}
