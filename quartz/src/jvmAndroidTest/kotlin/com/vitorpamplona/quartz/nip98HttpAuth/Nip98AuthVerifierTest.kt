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
package com.vitorpamplona.quartz.nip98HttpAuth

import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class Nip98AuthVerifierTest {
    private val verifier = Nip98AuthVerifier(now = { 1_000L })

    private fun signedToken(
        url: String,
        method: String,
        body: ByteArray? = null,
        signer: NostrSignerSync = NostrSignerSync(KeyPair()),
        createdAt: Long = 1_000L,
    ): Pair<String, String> {
        val template = HTTPAuthorizationEvent.build(url = url, method = method, file = body, createdAt = createdAt)
        val signed = signer.sign(template)
        return signer.pubKey to signed.toAuthToken()
    }

    @Test
    fun verifiesAValidPostWithBody() =
        runBlocking {
            val body = "hello".encodeToByteArray()
            val (pubkey, header) = signedToken("http://x/", "POST", body)
            val r = verifier.verify(header, "POST", "http://x/", body)
            assertIs<Nip98AuthVerifier.Result.Verified>(r)
            assertEquals(pubkey, r.pubkey)
        }

    @Test
    fun missingHeaderReturnsMissing() {
        runBlocking {
            val r = verifier.verify(null, "POST", "http://x/", null)
            assertIs<Nip98AuthVerifier.Result.Missing>(r)
        }
    }

    @Test
    fun wrongSchemeIsMalformed() {
        runBlocking {
            val r = verifier.verify("Bearer abc", "POST", "http://x/", null)
            assertIs<Nip98AuthVerifier.Result.Malformed>(r)
            assertTrue(r.reason.contains("Nostr"))
        }
    }

    @Test
    fun urlMismatchIsMalformed() {
        runBlocking {
            val (_, header) = signedToken("http://x/", "POST")
            val r = verifier.verify(header, "POST", "http://y/", null)
            assertIs<Nip98AuthVerifier.Result.Malformed>(r)
            assertTrue(r.reason.contains("url mismatch"))
        }
    }

    @Test
    fun methodMismatchIsMalformed() {
        runBlocking {
            val (_, header) = signedToken("http://x/", "POST")
            val r = verifier.verify(header, "GET", "http://x/", null)
            assertIs<Nip98AuthVerifier.Result.Malformed>(r)
            assertTrue(r.reason.contains("method mismatch"))
        }
    }

    @Test
    fun payloadHashMismatchIsMalformed() {
        runBlocking {
            val (_, header) = signedToken("http://x/", "POST", "alpha".encodeToByteArray())
            val r = verifier.verify(header, "POST", "http://x/", "beta".encodeToByteArray())
            assertIs<Nip98AuthVerifier.Result.Malformed>(r)
            assertTrue(r.reason.contains("payload hash"))
        }
    }

    @Test
    fun staleCreatedAtIsMalformed() {
        runBlocking {
            // Verifier's clock is fixed at 1_000; sign a token created 5
            // minutes earlier — outside the 60s tolerance.
            val (_, header) = signedToken("http://x/", "POST", createdAt = 1_000L - 600)
            val r = verifier.verify(header, "POST", "http://x/", null)
            assertIs<Nip98AuthVerifier.Result.Malformed>(r)
            assertTrue(r.reason.contains("created_at"))
        }
    }

    @Test
    fun nonAuthEventKindIsMalformed() {
        runBlocking {
            // Build a kind-1 event by hand and shove it into the header — it
            // must be rejected because NIP-98 specifically uses kind 27235.
            val signer = NostrSignerSync(KeyPair())
            val template =
                com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
                    .build("not an auth event")
            val signed = signer.sign(template)
            val token =
                "Nostr " +
                    kotlin.io.encoding.Base64
                        .encode(signed.toJson().encodeToByteArray())
            val r = verifier.verify(token, "POST", "http://x/", null)
            assertIs<Nip98AuthVerifier.Result.Malformed>(r)
            assertTrue(r.reason.contains("kind"))
        }
    }

    @Test
    fun aFloodOfRefusedAuthorsCannotEvictAnAcceptedAuthorsToken(): Unit =
        runBlocking {
            val admin = NostrSignerSync(KeyPair())
            val accepts: (String) -> Boolean = { it == admin.pubKey }
            val (_, used) = signedToken("http://x/", "GET", signer = admin)
            assertIs<Nip98AuthVerifier.Result.Verified>(verifier.verify(used, "GET", "http://x/", null, accepts))

            // Distinct urls, so each is a distinct event id, each verified and each remembered.
            repeat(Nip98AuthVerifier.MAX_REPLAY_ENTRIES + 64) { i ->
                val (_, flood) = signedToken("http://x/?n=$i", "GET")
                assertIs<Nip98AuthVerifier.Result.Verified>(verifier.verify(flood, "GET", "http://x/?n=$i", null, accepts))
            }

            val replay = verifier.verify(used, "GET", "http://x/", null, accepts)
            assertIs<Nip98AuthVerifier.Result.Malformed>(replay, "a replayed accepted token must not verify")
            assertTrue(replay.reason.contains("replay"))
        }

    @Test
    fun aRefusedAuthorsTokenIsStillSingleUse(): Unit =
        runBlocking {
            val (pubkey, token) = signedToken("http://x/", "GET")
            val first = verifier.verify(token, "GET", "http://x/", null) { false }
            assertEquals(pubkey, assertIs<Nip98AuthVerifier.Result.Verified>(first).pubkey)
            assertIs<Nip98AuthVerifier.Result.Malformed>(verifier.verify(token, "GET", "http://x/", null) { false })
        }

    @Test
    fun theFourArgumentVerifyStillRejectsAReplay(): Unit =
        runBlocking {
            val (_, token) = signedToken("http://x/", "GET")
            assertIs<Nip98AuthVerifier.Result.Verified>(verifier.verify(token, "GET", "http://x/", null))
            assertIs<Nip98AuthVerifier.Result.Malformed>(verifier.verify(token, "GET", "http://x/", null))
        }

    @Test
    fun preverifyChecksTheHeaderWithoutSpendingTheToken(): Unit =
        runBlocking {
            val body = "hello".encodeToByteArray()
            val (pubkey, header) = signedToken("http://x/", "POST", body)
            assertEquals(pubkey, assertIs<Nip98AuthVerifier.Result.Verified>(verifier.preverify(header, "POST", "http://x/")).pubkey)
            assertIs<Nip98AuthVerifier.Result.Verified>(verifier.preverify(header, "POST", "http://x/"))
            // The payload hash and the replay check are the full verify's, once the body is read.
            assertIs<Nip98AuthVerifier.Result.Verified>(verifier.verify(header, "POST", "http://x/", body))
            assertIs<Nip98AuthVerifier.Result.Malformed>(verifier.verify(header, "POST", "http://x/", body))
        }

    @Test
    fun preverifyRefusesWhatVerifyWouldRefuseWithoutABody() {
        val (_, header) = signedToken("http://x/", "POST")
        assertIs<Nip98AuthVerifier.Result.Missing>(verifier.preverify(null, "POST", "http://x/"))
        assertIs<Nip98AuthVerifier.Result.Missing>(verifier.preverify(" ", "POST", "http://x/"))
        assertTrue(assertIs<Nip98AuthVerifier.Result.Malformed>(verifier.preverify("Bearer abc", "POST", "http://x/")).reason.contains("Nostr"))
        assertTrue(assertIs<Nip98AuthVerifier.Result.Malformed>(verifier.preverify("Nostr !!!", "POST", "http://x/")).reason.contains("base64"))
        assertTrue(assertIs<Nip98AuthVerifier.Result.Malformed>(verifier.preverify(header, "POST", "http://y/")).reason.contains("url mismatch"))
        assertTrue(assertIs<Nip98AuthVerifier.Result.Malformed>(verifier.preverify(header, "GET", "http://x/")).reason.contains("method mismatch"))
        val (_, stale) = signedToken("http://x/", "POST", createdAt = 1_000L - 600)
        assertTrue(assertIs<Nip98AuthVerifier.Result.Malformed>(verifier.preverify(stale, "POST", "http://x/")).reason.contains("created_at"))
    }

    @Test
    fun preverifyRefusesAForgedSignature() {
        val signer = NostrSignerSync(KeyPair())
        val signed = signer.sign(HTTPAuthorizationEvent.build(url = "http://x/", method = "POST", createdAt = 1_000L))
        val forged = HTTPAuthorizationEvent(signed.id, NostrSignerSync(KeyPair()).pubKey, signed.createdAt, signed.tags, signed.content, signed.sig)
        val r = verifier.preverify(forged.toAuthToken(), "POST", "http://x/")
        assertTrue(assertIs<Nip98AuthVerifier.Result.Malformed>(r).reason.contains("signature"))
    }
}
