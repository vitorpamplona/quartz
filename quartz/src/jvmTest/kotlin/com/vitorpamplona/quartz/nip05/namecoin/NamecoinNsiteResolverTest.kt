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

import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.NamecoinLookupException
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.NamecoinNameResolver
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.NamecoinNsiteResolveOutcome
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.NamecoinNsiteTarget
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.NamecoinResolutionLimits
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.NamecoinResolveOutcome
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NamecoinNsiteResolverTest {
    @Test
    fun `public API normalizes hostname and exposes detailed and nullable results`() =
        runTest {
            val pointer = NPub.create(TEST_PUBKEY_A)
            val client =
                RecordingElectrumXClient()
                    .register("d/example", """{"nostr":{"nsite":"$pointer"}}""")
            val resolver = resolver(client)

            val detailed = resolver.resolveDomainNsiteDetailed("  ExAmPlE.BiT.  ")
            val success = assertSuccess(detailed)
            assertEquals(NamecoinNsiteTarget.Root(TEST_PUBKEY_A), success.result.target)
            assertEquals("d/example", success.result.namecoinName)
            assertEquals("_", success.result.localPart)

            assertEquals(success.result, resolver.resolveDomainNsite("example.bit"))
            assertEquals(listOf("d/example", "d/example"), client.queriedNames)
        }

    @Test
    fun `import merge retains importer nostr precedence`() =
        runTest {
            val pointer = NPub.create(TEST_PUBKEY_B)
            val client =
                RecordingElectrumXClient()
                    .register("d/example", """{"import":"dd/site","nostr":"$TEST_PUBKEY_A"}""")
                    .register("dd/site", """{"nostr":{"nsite":"$pointer"}}""")

            val outcome = resolver(client).resolveDomainNsiteDetailed("example.bit")

            // Importer-wins semantics apply to the whole nostr object before nSite mapping.
            assertTrue(outcome is NamecoinNsiteResolveOutcome.Success)
            val target = (outcome as NamecoinNsiteResolveOutcome.Success).result.target
            assertEquals(NamecoinNsiteTarget.Root(TEST_PUBKEY_A), target)
            assertEquals(listOf("d/example", "dd/site"), client.queriedNames)
        }

    @Test
    fun `imported direct target is selected when importer has no nostr override`() =
        runTest {
            val pointer = NPub.create(TEST_PUBKEY_B)
            val client =
                RecordingElectrumXClient()
                    .register("d/example", """{"import":"dd/site","ip":"192.0.2.1"}""")
                    .register("dd/site", """{"nostr":{"nsite":"$pointer","pubkey":"$TEST_PUBKEY_A"}}""")

            val success = assertSuccess(resolver(client).resolveDomainNsiteDetailed("example.bit"))

            assertEquals(NamecoinNsiteTarget.Root(TEST_PUBKEY_B), success.result.target)
            assertEquals(listOf("d/example", "dd/site"), client.queriedNames)
        }

    @Test
    fun `map selection returns named shorthand and subdomain context`() =
        runTest {
            val client =
                RecordingElectrumXClient()
                    .register(
                        "d/example",
                        """{"nostr":"$TEST_PUBKEY_C","map":{"www":{"nostr":{"pubkey":"$TEST_PUBKEY_A","nsite":"blog"}}}}""",
                    )

            val success = assertSuccess(resolver(client).resolveDomainNsiteDetailed("www.example.bit"))

            assertEquals(NamecoinNsiteTarget.Named(TEST_PUBKEY_A, "blog"), success.result.target)
            assertEquals("www", success.result.localPart)
            assertEquals("d/example", success.result.namecoinName)
        }

    @Test
    fun `missing selected map child is absent mapping rather than apex root fallback`() =
        runTest {
            val client =
                RecordingElectrumXClient()
                    .register("d/example", """{"nostr":"$TEST_PUBKEY_A","map":{"www":{}}}""")

            val outcome = resolver(client).resolveDomainNsiteDetailed("other.example.bit")

            assertEquals(NamecoinNsiteResolveOutcome.NoNsiteMapping("d/example"), outcome)
        }

    @Test
    fun `delegate exclusively replaces local mapping`() =
        runTest {
            val pointer = NPub.create(TEST_PUBKEY_B)
            val client =
                RecordingElectrumXClient()
                    .register("d/example", """{"delegate":"dd/site","nostr":"$TEST_PUBKEY_A"}""")
                    .register("dd/site", """{"nostr":{"nsite":"$pointer"}}""")

            val success = assertSuccess(resolver(client).resolveDomainNsiteDetailed("example.bit"))

            assertEquals(NamecoinNsiteTarget.Root(TEST_PUBKEY_B), success.result.target)
            assertEquals(listOf("d/example", "dd/site"), client.queriedNames)
        }

    @Test
    fun `unrelated map imports are not traversed`() =
        runTest {
            val pointer = NPub.create(TEST_PUBKEY_A)
            val client =
                RecordingElectrumXClient()
                    .register(
                        "d/example",
                        """{"map":{"www":{"nostr":{"nsite":"$pointer"}},"other":{"import":"dd/unrelated"}}}""",
                    )

            assertSuccess(resolver(client).resolveDomainNsiteDetailed("www.example.bit"))
            assertEquals(listOf("d/example"), client.queriedNames)
        }

    @Test
    fun `existing lookup and malformed record failures pass through unchanged`() =
        runTest {
            val missing = resolver(RecordingElectrumXClient()).resolveDomainNsiteDetailed("example.bit")
            assertNamecoinFailure(missing, NamecoinResolveOutcome.NameNotFound("d/example"))

            val unreachableClient = RecordingElectrumXClient().fail("d/example", NamecoinLookupException.ServersUnreachable(RuntimeException("offline")))
            val unreachable = resolver(unreachableClient).resolveDomainNsiteDetailed("example.bit")
            assertNamecoinFailure(unreachable, NamecoinResolveOutcome.ServersUnreachable("All ElectrumX servers unreachable"))

            val malformedClient = RecordingElectrumXClient().register("d/example", """["not","an","object"]""")
            val malformed = resolver(malformedClient).resolveDomainNsiteDetailed("example.bit")
            val wrapped = assertNamecoinFailureType(malformed)
            assertTrue("expected MalformedRecord, got $wrapped", wrapped is NamecoinResolveOutcome.MalformedRecord)
        }

    @Test
    fun `failed import and delegate retain their typed causes`() =
        runTest {
            val pointer = NPub.create(TEST_PUBKEY_A)
            val importClient =
                RecordingElectrumXClient()
                    .register("d/example", """{"import":"dd/missing","nostr":{"nsite":"$pointer"}}""")
            val importOutcome = resolver(importClient).resolveDomainNsiteDetailed("example.bit")
            assertNamecoinFailure(
                importOutcome,
                NamecoinResolveOutcome.ImportFailed(
                    "d/example",
                    "dd/missing",
                    NamecoinResolveOutcome.NameNotFound("dd/missing"),
                ),
            )

            val delegateClient =
                RecordingElectrumXClient()
                    .register("d/example", """{"delegate":"dd/missing","nostr":{"nsite":"$pointer"}}""")
            val delegateOutcome = resolver(delegateClient).resolveDomainNsiteDetailed("example.bit")
            assertNamecoinFailure(
                delegateOutcome,
                NamecoinResolveOutcome.DelegationFailed(
                    "d/example",
                    "dd/missing",
                    NamecoinResolveOutcome.NameNotFound("dd/missing"),
                ),
            )
        }

    @Test
    fun `cycles lookup budget and depth remain bounded typed failures`() =
        runTest {
            val cyclicClient =
                RecordingElectrumXClient()
                    .register("d/example", """{"import":"d/loop"}""")
                    .register("d/loop", """{"import":"d/example"}""")
            val cyclic = resolver(cyclicClient).resolveDomainNsiteDetailed("example.bit")
            assertTrue(assertNamecoinFailureType(cyclic) is NamecoinResolveOutcome.CyclicReference)
            assertEquals(listOf("d/example", "d/loop"), cyclicClient.queriedNames)

            val budgetClient =
                RecordingElectrumXClient()
                    .register("d/example", """{"import":"dd/site","nostr":"$TEST_PUBKEY_A"}""")
                    .register("dd/site", """{"nostr":"$TEST_PUBKEY_B"}""")
            val budget =
                resolver(budgetClient, NamecoinResolutionLimits(maxLookups = 1, maxDepth = 16))
                    .resolveDomainNsiteDetailed("example.bit")
            assertNamecoinFailure(budget, NamecoinResolveOutcome.LookupBudgetExhausted("dd/site", 1))

            val depthClient =
                RecordingElectrumXClient()
                    .register("d/example", """{"import":"d/one"}""")
                    .register("d/one", """{"import":"d/two"}""")
                    .register("d/two", """{"nostr":"$TEST_PUBKEY_A"}""")
            val depth =
                resolver(depthClient, NamecoinResolutionLimits(maxLookups = 16, maxDepth = 1))
                    .resolveDomainNsiteDetailed("example.bit")
            assertNamecoinFailure(depth, NamecoinResolveOutcome.DepthLimitExceeded("d/two", 1))
        }

    @Test
    fun `timeout and hostname validation retain existing outcome types`() =
        runTest {
            val slowClient = RecordingElectrumXClient().apply { beforeLookup = { delay(1_000L) } }
            val slow = NamecoinNameResolver(slowClient, lookupTimeoutMs = 1L) { emptyList() }
            assertNamecoinFailure(slow.resolveDomainNsiteDetailed("example.bit"), NamecoinResolveOutcome.Timeout)

            val client = RecordingElectrumXClient()
            val invalid = resolver(client).resolveDomainNsiteDetailed("https://example.bit/path")
            assertNamecoinFailure(invalid, NamecoinResolveOutcome.InvalidIdentifier("https://example.bit/path"))
            assertEquals(emptyList<String>(), client.queriedNames)
        }

    private fun resolver(
        client: RecordingElectrumXClient,
        limits: NamecoinResolutionLimits = NamecoinResolutionLimits(),
    ) = NamecoinNameResolver(client, lookupTimeoutMs = 1_000L, resolutionLimits = limits) { emptyList() }

    private fun assertSuccess(outcome: NamecoinNsiteResolveOutcome): NamecoinNsiteResolveOutcome.Success {
        assertTrue("expected Success, got $outcome", outcome is NamecoinNsiteResolveOutcome.Success)
        return outcome as NamecoinNsiteResolveOutcome.Success
    }

    private fun assertNamecoinFailureType(outcome: NamecoinNsiteResolveOutcome): NamecoinResolveOutcome {
        assertTrue("expected NamecoinFailure, got $outcome", outcome is NamecoinNsiteResolveOutcome.NamecoinFailure)
        return (outcome as NamecoinNsiteResolveOutcome.NamecoinFailure).cause
    }

    private fun assertNamecoinFailure(
        outcome: NamecoinNsiteResolveOutcome,
        expected: NamecoinResolveOutcome,
    ) {
        assertEquals(expected, assertNamecoinFailureType(outcome))
    }
}
