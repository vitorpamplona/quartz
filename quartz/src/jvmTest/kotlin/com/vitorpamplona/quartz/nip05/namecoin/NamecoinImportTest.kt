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

import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.NamecoinImportResolver
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.NamecoinNameResolver
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.NamecoinResolutionLimits
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.NamecoinResolveOutcome
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.NamecoinTraversalResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hermetic import/delegate traversal tests; no backend sockets are opened. */
class NamecoinImportTest {
    @Test
    fun `import supplies root nostr identity`() =
        runTest {
            val client =
                RecordingElectrumXClient()
                    .register("d/example", """{"import":"dd/shared"}""")
                    .register("dd/shared", """{"nostr":"$TEST_PUBKEY_A"}""")

            val outcome = resolver(client).resolveDomainDetailed("example.bit")

            assertSuccessPubkey(outcome, TEST_PUBKEY_A)
            assertEquals(listOf("d/example", "dd/shared"), client.queriedNames)
        }

    @Test
    fun `first import wins over later imports and local record wins over both`() =
        runTest {
            val client =
                RecordingElectrumXClient()
                    .register("d/example", """{"import":[["dd/a"],["dd/b"]]}""")
                    .register("dd/a", """{"nostr":"$TEST_PUBKEY_A"}""")
                    .register("dd/b", """{"nostr":"$TEST_PUBKEY_B"}""")
            assertSuccessPubkey(resolver(client).resolveDomainDetailed("example.bit"), TEST_PUBKEY_A)

            val allStringClient =
                RecordingElectrumXClient()
                    .register("d/example", """{"import":["dd/a","dd/b"]}""")
                    .register("dd/a", """{"nostr":"$TEST_PUBKEY_A"}""")
                    .register("dd/b", """{"nostr":"$TEST_PUBKEY_B"}""")
            assertSuccessPubkey(resolver(allStringClient).resolveDomainDetailed("example.bit"), TEST_PUBKEY_A)

            val localClient =
                RecordingElectrumXClient()
                    .register(
                        "d/example",
                        """{"import":["dd/a","dd/b"],"nostr":"$TEST_PUBKEY_C"}""",
                    ).register("dd/a", """{"nostr":"$TEST_PUBKEY_A"}""")
                    .register("dd/b", """{"nostr":"$TEST_PUBKEY_B"}""")
            assertSuccessPubkey(resolver(localClient).resolveDomainDetailed("example.bit"), TEST_PUBKEY_C)
        }

    @Test
    fun `pair shorthand and imports before selector use DNS label order`() =
        runTest {
            val client =
                RecordingElectrumXClient()
                    .register("d/example", """{"import":["dd/lib","a.b"]}""")
                    .register("dd/lib", """{"import":"dd/defaults"}""")
                    .register(
                        "dd/defaults",
                        """{"map":{"b":{"map":{"a":{"nostr":"$TEST_PUBKEY_A"}}}}}""",
                    )

            assertSuccessPubkey(resolver(client).resolveDomainDetailed("example.bit"), TEST_PUBKEY_A)
            assertEquals(listOf("d/example", "dd/lib", "dd/defaults"), client.queriedNames)
        }

    @Test
    fun `selector falls back to wildcard but not unrelated default child`() =
        runTest {
            val client =
                RecordingElectrumXClient()
                    .register("d/example", """{"import":[["dd/lib","ghost"]]}""")
                    .register(
                        "dd/lib",
                        """{"map":{"*":{"nostr":"$TEST_PUBKEY_B"},"":{"nostr":"$TEST_PUBKEY_C"}}}""",
                    )

            assertSuccessPubkey(resolver(client).resolveDomainDetailed("example.bit"), TEST_PUBKEY_B)
        }

    @Test
    fun `failed import fails closed even when local nostr exists`() =
        runTest {
            val client =
                RecordingElectrumXClient()
                    .register(
                        "d/example",
                        """{"import":"dd/missing","nostr":"$TEST_PUBKEY_A"}""",
                    )
            val resolver = resolver(client)

            val outcome = resolver.resolveDomainDetailed("example.bit")

            assertTrue("expected ImportFailed, got $outcome", outcome is NamecoinResolveOutcome.ImportFailed)
            outcome as NamecoinResolveOutcome.ImportFailed
            assertEquals("d/example", outcome.name)
            assertEquals("dd/missing", outcome.target)
            assertTrue(outcome.cause is NamecoinResolveOutcome.NameNotFound)
            assertNull(resolver.resolveDomain("example.bit"))
        }

    @Test
    fun `malformed import target retains typed cause`() =
        runTest {
            val client =
                RecordingElectrumXClient()
                    .register("d/example", """{"import":"dd/broken"}""")
                    .register("dd/broken", """{"nostr":""")

            val outcome = resolver(client).resolveDomainDetailed("example.bit")

            assertTrue("expected ImportFailed, got $outcome", outcome is NamecoinResolveOutcome.ImportFailed)
            outcome as NamecoinResolveOutcome.ImportFailed
            assertTrue(outcome.cause is NamecoinResolveOutcome.MalformedRecord)
            assertEquals("dd/broken", (outcome.cause as NamecoinResolveOutcome.MalformedRecord).name)
        }

    @Test
    fun `malformed import shape is not no nostr field`() =
        runTest {
            val client =
                RecordingElectrumXClient()
                    .register("d/example", """{"import":42,"nostr":"$TEST_PUBKEY_A"}""")

            val outcome = resolver(client).resolveDomainDetailed("example.bit")

            assertTrue("expected MalformedRecord, got $outcome", outcome is NamecoinResolveOutcome.MalformedRecord)
        }

    @Test
    fun `oversized nested import tuple fails closed without fetching target`() =
        runTest {
            val client =
                RecordingElectrumXClient()
                    .register("d/example", """{"import":[["dd/target","","unexpected"]]}""")
                    .register("dd/target", """{"nostr":"$TEST_PUBKEY_A"}""")

            val outcome = resolver(client).resolveDomainDetailed("example.bit")

            assertTrue("expected MalformedRecord, got $outcome", outcome is NamecoinResolveOutcome.MalformedRecord)
            assertEquals(listOf("d/example"), client.queriedNames)
        }

    @Test
    fun `valid imported record without nostr is no nostr field`() =
        runTest {
            val client =
                RecordingElectrumXClient()
                    .register("d/example", """{"import":"dd/empty"}""")
                    .register("dd/empty", """{"info":"valid but no identity"}""")

            val outcome = resolver(client).resolveDomainDetailed("example.bit")

            assertTrue("expected NoNostrField, got $outcome", outcome is NamecoinResolveOutcome.NoNostrField)
        }

    @Test
    fun `delegate exclusively replaces local nostr record`() =
        runTest {
            val client =
                RecordingElectrumXClient()
                    .register(
                        "d/example",
                        """{"delegate":"dd/target","nostr":"$TEST_PUBKEY_A"}""",
                    ).register("dd/target", """{"nostr":"$TEST_PUBKEY_B"}""")

            assertSuccessPubkey(resolver(client).resolveDomainDetailed("example.bit"), TEST_PUBKEY_B)
        }

    @Test
    fun `delegate without target nostr does not resurrect local nostr`() =
        runTest {
            val client =
                RecordingElectrumXClient()
                    .register(
                        "d/example",
                        """{"delegate":"dd/target","nostr":"$TEST_PUBKEY_A"}""",
                    ).register("dd/target", """{"info":"no identity"}""")

            val outcome = resolver(client).resolveDomainDetailed("example.bit")

            assertTrue("expected NoNostrField, got $outcome", outcome is NamecoinResolveOutcome.NoNostrField)
        }

    @Test
    fun `failed delegation fails closed without local fallback`() =
        runTest {
            val client =
                RecordingElectrumXClient()
                    .register(
                        "d/example",
                        """{"delegate":"dd/missing","nostr":"$TEST_PUBKEY_A"}""",
                    )

            val outcome = resolver(client).resolveDomainDetailed("example.bit")

            assertTrue("expected DelegationFailed, got $outcome", outcome is NamecoinResolveOutcome.DelegationFailed)
            outcome as NamecoinResolveOutcome.DelegationFailed
            assertEquals("dd/missing", outcome.target)
            assertTrue(outcome.cause is NamecoinResolveOutcome.NameNotFound)
        }

    @Test
    fun `multiple delegate targets are rejected as ambiguous`() =
        runTest {
            val client =
                RecordingElectrumXClient()
                    .register("d/example", """{"delegate":["dd/a","dd/b"]}""")

            val outcome = resolver(client).resolveDomainDetailed("example.bit")

            assertTrue("expected MalformedRecord, got $outcome", outcome is NamecoinResolveOutcome.MalformedRecord)
            assertEquals(listOf("d/example"), client.queriedNames)
        }

    @Test
    fun `oversized nested delegate tuple fails closed without fetching target`() =
        runTest {
            val client =
                RecordingElectrumXClient()
                    .register("d/example", """{"delegate":[["dd/target","","unexpected"]]}""")
                    .register("dd/target", """{"nostr":"$TEST_PUBKEY_A"}""")

            val outcome = resolver(client).resolveDomainDetailed("example.bit")

            assertTrue("expected MalformedRecord, got $outcome", outcome is NamecoinResolveOutcome.MalformedRecord)
            assertEquals(listOf("d/example"), client.queriedNames)
        }

    @Test
    fun `import cycle has a typed outcome and bounded lookups`() =
        runTest {
            val client =
                RecordingElectrumXClient()
                    .register("d/loop", """{"import":"dd/a"}""")
                    .register("dd/a", """{"import":"d/loop"}""")

            val outcome = resolver(client).resolveDomainDetailed("loop.bit")

            assertTrue("expected CyclicReference, got $outcome", outcome is NamecoinResolveOutcome.CyclicReference)
            assertEquals(2, client.queriedNames.size)
        }

    @Test
    fun `four import levels are supported by default`() =
        runTest {
            val client =
                RecordingElectrumXClient()
                    .register("d/example", """{"import":"dd/a"}""")
                    .register("dd/a", """{"import":"dd/b"}""")
                    .register("dd/b", """{"import":"dd/c"}""")
                    .register("dd/c", """{"import":"dd/d"}""")
                    .register("dd/d", """{"nostr":"$TEST_PUBKEY_D"}""")

            assertSuccessPubkey(resolver(client).resolveDomainDetailed("example.bit"), TEST_PUBKEY_D)
        }

    @Test
    fun `depth exhaustion is typed and does not truncate into local success`() =
        runTest {
            val client =
                RecordingElectrumXClient()
                    .register("d/example", """{"import":"dd/a","nostr":"$TEST_PUBKEY_A"}""")
                    .register("dd/a", """{"import":"dd/b"}""")
                    .register("dd/b", """{"import":"dd/c"}""")
                    .register("dd/c", """{"nostr":"$TEST_PUBKEY_C"}""")
            val resolver =
                resolver(
                    client,
                    NamecoinResolutionLimits(maxLookups = 16, maxDepth = 2),
                )

            val outcome = resolver.resolveDomainDetailed("example.bit")

            assertTrue("expected DepthLimitExceeded, got $outcome", outcome is NamecoinResolveOutcome.DepthLimitExceeded)
            outcome as NamecoinResolveOutcome.DepthLimitExceeded
            assertEquals(2, outcome.limit)
            assertEquals(listOf("d/example", "dd/a", "dd/b"), client.queriedNames)
        }

    @Test
    fun `lookup budget is global across imports`() =
        runTest {
            val client =
                RecordingElectrumXClient()
                    .register("d/example", """{"import":[["dd/a"],["dd/b"],["dd/c"]]}""")
                    .register("dd/a", """{}""")
                    .register("dd/b", """{}""")
                    .register("dd/c", """{"nostr":"$TEST_PUBKEY_C"}""")
            val resolver =
                resolver(
                    client,
                    NamecoinResolutionLimits(maxLookups = 2, maxDepth = 16),
                )

            val outcome = resolver.resolveDomainDetailed("example.bit")

            assertTrue("expected LookupBudgetExhausted, got $outcome", outcome is NamecoinResolveOutcome.LookupBudgetExhausted)
            outcome as NamecoinResolveOutcome.LookupBudgetExhausted
            assertEquals("dd/b", outcome.name)
            assertEquals(listOf("d/example", "dd/a"), client.queriedNames)
        }

    @Test
    fun `diamond imports memoize record and expanded resolution`() =
        runTest {
            val client =
                RecordingElectrumXClient()
                    .register("d/example", """{"import":[["dd/a"],["dd/b"]]}""")
                    .register("dd/a", """{"import":"dd/shared"}""")
                    .register("dd/b", """{"import":"dd/shared"}""")
                    .register("dd/shared", """{"nostr":"$TEST_PUBKEY_A"}""")

            assertSuccessPubkey(resolver(client).resolveDomainDetailed("example.bit"), TEST_PUBKEY_A)
            assertEquals(listOf("d/example", "dd/a", "dd/shared", "dd/b"), client.queriedNames)
            assertEquals(1, client.queriedNames.count { it == "dd/shared" })
        }

    @Test
    fun `non object lookup failure is memoized for repeated references`() =
        runTest {
            val client = RecordingElectrumXClient().register("dd/broken", "[]")
            val traversal =
                NamecoinImportResolver(NamecoinResolutionLimits()) { name ->
                    client.nameShowWithFallback(name, emptyList())?.value
                }

            // Exercise the shared lookup memo directly. A public fail-closed
            // resolution correctly stops at the first malformed reference.
            val first = traversal.resolve("dd/broken")
            val second = traversal.resolve("dd/broken")

            assertTrue(
                "expected MalformedRecord, got $first",
                (first as? NamecoinTraversalResult.Failure)?.outcome is NamecoinResolveOutcome.MalformedRecord,
            )
            assertTrue(
                "expected MalformedRecord, got $second",
                (second as? NamecoinTraversalResult.Failure)?.outcome is NamecoinResolveOutcome.MalformedRecord,
            )
            assertEquals(listOf("dd/broken"), client.queriedNames)
        }

    @Test
    fun `record without import performs only apex lookup`() =
        runTest {
            val client =
                RecordingElectrumXClient()
                    .register("d/example", """{"nostr":"$TEST_PUBKEY_A"}""")

            assertSuccessPubkey(resolver(client).resolveDomainDetailed("example.bit"), TEST_PUBKEY_A)
            assertEquals(listOf("d/example"), client.queriedNames)
        }

    private fun resolver(
        client: RecordingElectrumXClient,
        limits: NamecoinResolutionLimits = NamecoinResolutionLimits(),
    ) = NamecoinNameResolver(client, lookupTimeoutMs = 1_000L, resolutionLimits = limits) { emptyList() }

    private fun assertSuccessPubkey(
        outcome: NamecoinResolveOutcome,
        expected: String,
    ) {
        assertTrue("expected Success, got $outcome", outcome is NamecoinResolveOutcome.Success)
        assertEquals(expected, (outcome as NamecoinResolveOutcome.Success).result.pubkey)
    }
}
