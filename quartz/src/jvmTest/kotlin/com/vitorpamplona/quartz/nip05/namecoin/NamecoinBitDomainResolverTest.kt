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
package com.vitorpamplona.quartz.nip05.namecoin

import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.NamecoinNameResolver
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.NamecoinResolveOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NamecoinBitDomainResolverTest {
    @Test
    fun `apex resolves with case and trailing dot normalization`() =
        runTest {
            val client =
                RecordingElectrumXClient()
                    .register("d/example", """{"nostr":"$TEST_PUBKEY_A"}""")

            val outcome = resolver(client).resolveDomainDetailed("  ExAmPlE.BiT.  ")

            assertSuccessPubkey(outcome, TEST_PUBKEY_A)
            outcome as NamecoinResolveOutcome.Success
            assertEquals("d/example", outcome.result.namecoinName)
            assertEquals("_", outcome.result.localPart)
            assertEquals(listOf("d/example"), client.queriedNames)
        }

    @Test
    fun `multiple subdomain labels query apex and descend in DNS order`() =
        runTest {
            val client =
                RecordingElectrumXClient()
                    .register(
                        "d/example",
                        """{"map":{"b":{"map":{"a":{"nostr":"$TEST_PUBKEY_A"}}}}}""",
                    ).register("d/a.b.example", """{"nostr":"$TEST_PUBKEY_D"}""")

            val outcome = resolver(client).resolveDomainDetailed("a.b.example.bit")

            assertSuccessPubkey(outcome, TEST_PUBKEY_A)
            outcome as NamecoinResolveOutcome.Success
            assertEquals("a.b", outcome.result.localPart)
            assertEquals(listOf("d/example"), client.queriedNames)
        }

    @Test
    fun `exact map child wins over wildcard and ancestor default`() =
        runTest {
            val client =
                RecordingElectrumXClient()
                    .register(
                        "d/example",
                        """
                        {"nostr":"$TEST_PUBKEY_D","map":{
                            "":{"nostr":"$TEST_PUBKEY_C"},
                            "*":{"nostr":"$TEST_PUBKEY_B"},
                            "www":{"nostr":"$TEST_PUBKEY_A"}
                        }}
                        """.trimIndent(),
                    )
            val resolver = resolver(client)

            assertSuccessPubkey(resolver.resolveDomainDetailed("www.example.bit"), TEST_PUBKEY_A)
            assertSuccessPubkey(resolver.resolveDomainDetailed("other.example.bit"), TEST_PUBKEY_B)
            assertSuccessPubkey(resolver.resolveDomainDetailed("example.bit"), TEST_PUBKEY_D)
        }

    @Test
    fun `default map supplies ancestor nostr but is not a subdomain wildcard`() =
        runTest {
            val defaultOnly =
                RecordingElectrumXClient()
                    .register("d/example", """{"map":{"":{"nostr":"$TEST_PUBKEY_C"}}}""")
            assertSuccessPubkey(resolver(defaultOnly).resolveDomainDetailed("example.bit"), TEST_PUBKEY_C)

            val outcome = resolver(defaultOnly).resolveDomainDetailed("unknown.example.bit")
            assertTrue("expected NoNostrField, got $outcome", outcome is NamecoinResolveOutcome.NoNostrField)
        }

    @Test
    fun `unrelated map branch causes zero lookups`() =
        runTest {
            val client =
                RecordingElectrumXClient()
                    .register(
                        "d/example",
                        """
                        {"map":{
                            "www":{"nostr":"$TEST_PUBKEY_A"},
                            "other":{"import":"dd/unrelated"},
                            "wildcard-source":{"delegate":"dd/also-unrelated"}
                        }}
                        """.trimIndent(),
                    )

            assertSuccessPubkey(resolver(client).resolveDomainDetailed("www.example.bit"), TEST_PUBKEY_A)
            assertEquals(listOf("d/example"), client.queriedNames)
        }

    @Test
    fun `malformed exact child fails closed instead of using wildcard`() =
        runTest {
            val client =
                RecordingElectrumXClient()
                    .register(
                        "d/example",
                        """{"map":{"www":42,"*":{"nostr":"$TEST_PUBKEY_B"}}}""",
                    )

            val outcome = resolver(client).resolveDomainDetailed("www.example.bit")

            assertTrue("expected MalformedRecord, got $outcome", outcome is NamecoinResolveOutcome.MalformedRecord)
        }

    @Test
    fun `browser resolution never chooses arbitrary first names entry`() =
        runTest {
            val client =
                RecordingElectrumXClient()
                    .register(
                        "d/example",
                        """{"nostr":{"names":{"alice":"$TEST_PUBKEY_A"}}}""",
                    )
            val resolver = resolver(client)

            val browser = resolver.resolveDomainDetailed("example.bit")
            assertTrue("expected NoNostrField, got $browser", browser is NamecoinResolveOutcome.NoNostrField)

            // Direct d/ NIP-05 lookups retain the legacy root fallback.
            val legacyDirectName = resolver.resolveDetailed("d/example")
            assertSuccessPubkey(legacyDirectName, TEST_PUBKEY_A)

            val legacyBareDomain = resolver.resolveDetailed("example.bit")
            assertSuccessPubkey(legacyBareDomain, TEST_PUBKEY_A)
        }

    @Test
    fun `delegate can replace a mapped subdomain`() =
        runTest {
            val client =
                RecordingElectrumXClient()
                    .register(
                        "d/example",
                        """{"map":{"www":{"delegate":"dd/target","nostr":"$TEST_PUBKEY_A"}}}""",
                    ).register("dd/target", """{"nostr":"$TEST_PUBKEY_B"}""")

            assertSuccessPubkey(resolver(client).resolveDomainDetailed("www.example.bit"), TEST_PUBKEY_B)
            assertEquals(listOf("d/example", "dd/target"), client.queriedNames)
        }

    @Test
    fun `24 label self import shape is bounded with one backend lookup`() =
        runTest {
            var selected = """{"nostr":"$TEST_PUBKEY_A"}"""
            for (index in 0 until 23) {
                selected = """{"import":"d/loop","map":{"l$index":$selected}}"""
            }
            val root = """{"map":{"l23":$selected}}"""
            val client = RecordingElectrumXClient().register("d/loop", root)
            val hostname = (0 until 24).joinToString(".") { "l$it" } + ".loop.bit"

            val outcome = resolver(client).resolveDomainDetailed(hostname)

            assertTrue("expected DepthLimitExceeded, got $outcome", outcome is NamecoinResolveOutcome.DepthLimitExceeded)
            assertEquals("only the memoized apex may be queried", listOf("d/loop"), client.queriedNames)
        }

    @Test
    fun `invalid hostname inputs are rejected without backend access`() =
        runTest {
            val client =
                RecordingElectrumXClient()
                    .register("d/example", """{"nostr":"$TEST_PUBKEY_A"}""")
            val resolver = resolver(client)
            val invalid =
                listOf(
                    "https://example.bit/path",
                    "example.bit:443",
                    "example.bit/path",
                    "user@example.bit",
                    "example.onion",
                    "123.bit",
                    "-bad.bit",
                    "bad..example.bit",
                    "",
                    "d/example",
                )

            for (input in invalid) {
                val outcome = resolver.resolveDomainDetailed(input)
                assertTrue("expected InvalidIdentifier for $input, got $outcome", outcome is NamecoinResolveOutcome.InvalidIdentifier)
            }
            assertEquals(emptyList<String>(), client.queriedNames)
        }

    @Test
    fun `overall timeout remains a typed bound`() =
        runTest {
            val client =
                RecordingElectrumXClient().apply {
                    beforeLookup = { delay(1_000L) }
                }
            val resolver = NamecoinNameResolver(client, lookupTimeoutMs = 1L) { emptyList() }

            val outcome = resolver.resolveDomainDetailed("example.bit")

            assertTrue("expected Timeout, got $outcome", outcome is NamecoinResolveOutcome.Timeout)
        }

    @Test
    fun `caller cancellation propagates through backend lookup`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val client =
                RecordingElectrumXClient().apply {
                    beforeLookup = {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                }
            val resolver = NamecoinNameResolver(client, lookupTimeoutMs = 60_000L) { emptyList() }

            val job = launch { resolver.resolveDomainDetailed("example.bit") }
            started.await()
            job.cancelAndJoin()

            assertTrue(job.isCancelled)
            assertEquals(listOf("d/example"), client.queriedNames)
        }

    private fun resolver(client: RecordingElectrumXClient) = NamecoinNameResolver(client, lookupTimeoutMs = 1_000L) { emptyList() }

    private fun assertSuccessPubkey(
        outcome: NamecoinResolveOutcome,
        expected: String,
    ) {
        assertTrue("expected Success, got $outcome", outcome is NamecoinResolveOutcome.Success)
        assertEquals(expected, (outcome as NamecoinResolveOutcome.Success).result.pubkey)
    }
}
