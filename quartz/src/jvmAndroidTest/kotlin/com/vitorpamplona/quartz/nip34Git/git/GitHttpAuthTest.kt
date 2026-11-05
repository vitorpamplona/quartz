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

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip98HttpAuth.HTTPAuthorizationEvent
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitHttpAuthTest {
    @Test
    fun theRepositoryUrlDropsWhatGraspDoesNotSign() {
        assertEquals("https://relay.ngit.dev/npub1x/repo.git", GitHttpAuth.repositoryUrl("https://user:pw@relay.ngit.dev/npub1x/repo.git/?a=1#frag"))
    }

    @Test
    fun everyGitRequestOfAPrivateRepositoryCarriesTheSameSignedGet() =
        GitHttpBackendFixture().use { git ->
            if (!git.available) return@use
            val (bare, url) = git.bare("private")
            val work = git.work(bare, "work")
            File(work, "f.txt").writeText("x\n")
            git.git(work, "add", ".")
            git.git(work, "commit", "-q", "-m", "x")
            git.git(work, "push", "-q", "origin", "HEAD:main")

            val seen = ArrayList<String?>()
            val auth = GitHttpAuth(NostrSignerInternal(KeyPair()))
            val wrapped =
                git.client
                    .newBuilder()
                    .apply {
                        interceptors().add(
                            0,
                            Interceptor { chain ->
                                seen.add(chain.request().header("Authorization"))
                                chain.proceed(chain.request())
                            },
                        )
                    }.build()
            runBlocking { GitHttpClient({ wrapped }, auth::header).open(url) }

            assertTrue(seen.size >= 2)
            assertEquals(1, seen.distinct().size, "one event serves every request")
            val token = seen.first()!!
            assertTrue(token.startsWith("Nostr "))
            val event = Event.fromJson(Base64.decode(token.removePrefix("Nostr ")).decodeToString()) as HTTPAuthorizationEvent
            assertEquals("GET", event.method())
            assertEquals(url, event.url())
            assertTrue(event.verify())
        }
}
