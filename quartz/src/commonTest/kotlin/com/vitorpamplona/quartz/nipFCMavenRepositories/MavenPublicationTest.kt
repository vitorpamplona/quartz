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
package com.vitorpamplona.quartz.nipFCMavenRepositories

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip31Alts.alt
import com.vitorpamplona.quartz.nipFCMavenRepositories.release.MavenReleaseEvent
import com.vitorpamplona.quartz.nipFCMavenRepositories.snapshot.MavenSnapshotEvent
import com.vitorpamplona.quartz.nipFCMavenRepositories.tags.ManifestTag
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MavenPublicationTest {
    private val signer = NostrSignerInternal(KeyPair())

    private val manifestHash = "a".repeat(64)
    private val jarHash = "b".repeat(64)
    private val pomHash = "c".repeat(64)
    private val root = MavenModule("com.vitorpamplona.crypto", "crypto")
    private val jvm = MavenModule("com.vitorpamplona.crypto", "crypto-jvm")

    private suspend fun <T : Event> roundTrip(template: EventTemplate<T>): Event = Event.fromJson(signer.sign(template).toJson())

    // The shape Magpie 0.1.x publishes: root module first, the manifest hash as the first `x`.
    private val magpieRelease =
        """
        {"id":"${"1".repeat(64)}","pubkey":"${"e".repeat(64)}","created_at":1763000000,"kind":3071,
        "tags":[["j","com.vitorpamplona.crypto:crypto"],["j","com.vitorpamplona.crypto:crypto-jvm"],
        ["v","2.1.1"],["manifest","${"A".repeat(64)}","2048"],["x","$manifestHash"],["x","$jarHash"],
        ["server","https://blossom.primal.net"],["server","https://nostr.download"],
        ["alt","Release of com.vitorpamplona.crypto:crypto:2.1.1 (Maven artifacts on Nostr)"]],
        "content":"","sig":"${"0".repeat(128)}"}
        """.trimIndent()

    @Test
    fun parsesMagpiesRelease() {
        val event = Event.fromJson(magpieRelease)
        assertIs<MavenReleaseEvent>(event)
        assertEquals(listOf(root, jvm), event.modules())
        assertEquals(listOf(root.key, jvm.key), event.moduleKeys())
        assertEquals("2.1.1", event.version())
        assertEquals(MavenCoordinate("com.vitorpamplona.crypto", "crypto", "2.1.1"), event.coordinate())
        // The manifest hash is read in lower case.
        assertEquals(manifestHash, event.manifest()?.sha256)
        assertEquals(2048L, event.manifest()?.size)
        assertEquals(listOf(manifestHash, jarHash), event.hashes())
        assertEquals(listOf("https://blossom.primal.net", "https://nostr.download"), event.servers())
        assertTrue(event.covers(jvm, "2.1.1"))
        assertFalse(event.covers(jvm, "2.1.0"))
        assertFalse(event.covers(MavenModule("com.vitorpamplona.crypto", "crypto-js"), "2.1.1"))
        assertTrue(event.isWellFormed())
    }

    @Test
    fun releaseBuildRoundTrips() =
        runTest {
            val template =
                MavenReleaseEvent.build(
                    modules = listOf(root, jvm),
                    version = "2.1.1",
                    manifest = ManifestTag(manifestHash, 2048),
                    artifactHashes = listOf(pomHash, jarHash, manifestHash),
                    servers = listOf("https://blossom.primal.net", "https://blossom.primal.net"),
                )
            val event = roundTrip(template)
            assertIs<MavenReleaseEvent>(event)
            assertEquals(listOf(root, jvm), event.modules())
            assertEquals("2.1.1", event.version())
            assertEquals(2048L, event.manifest()?.size)
            // The manifest first, each hash once.
            assertEquals(listOf(manifestHash, pomHash, jarHash), event.hashes())
            assertEquals(listOf("https://blossom.primal.net"), event.servers())
            assertEquals("Release of com.vitorpamplona.crypto:crypto:2.1.1", event.tags.first { it[0] == "alt" }[1])
            assertTrue(event.isWellFormed())
        }

    @Test
    fun snapshotBuildRoundTrips() =
        runTest {
            val template =
                MavenSnapshotEvent.build(
                    modules = listOf(root),
                    version = "2.2.0-SNAPSHOT",
                    manifest = ManifestTag(manifestHash, 100),
                    servers = listOf("https://blossom.primal.net"),
                )
            val event = roundTrip(template)
            assertIs<MavenSnapshotEvent>(event)
            assertEquals("com.vitorpamplona.crypto:crypto:2.2.0-SNAPSHOT", event.dTag())
            assertEquals(MavenSnapshotEvent.createAddress(event.pubKey, root.withVersion("2.2.0-SNAPSHOT")), event.address())
            assertEquals(listOf(manifestHash), event.hashes())
            assertTrue(event.isWellFormed())
        }

    @Test
    fun theVersionDecidesReleaseOrSnapshot() {
        assertFailsWith<IllegalArgumentException> {
            MavenReleaseEvent.build(listOf(root), "1.0-SNAPSHOT", ManifestTag(manifestHash, 1))
        }
        assertFailsWith<IllegalArgumentException> {
            MavenSnapshotEvent.build(listOf(root), "1.0", ManifestTag(manifestHash, 1))
        }
        assertFailsWith<IllegalArgumentException> {
            MavenReleaseEvent.build(emptyList(), "1.0", ManifestTag(manifestHash, 1))
        }
    }

    @Test
    fun malformedEventsAreNotWellFormed() {
        fun release(vararg tags: Array<String>) = MavenReleaseEvent("1".repeat(64), "e".repeat(64), 1, arrayOf(*tags), "", "0".repeat(128))

        val module = arrayOf("j", root.key)
        val version = arrayOf("v", "1.0")
        val manifest = arrayOf("manifest", manifestHash, "10")
        assertTrue(release(module, version, manifest).isWellFormed())
        assertFalse(release(version, manifest).isWellFormed())
        assertFalse(release(module, manifest).isWellFormed())
        assertFalse(release(module, version).isWellFormed())
        // A size is required, a short hash is no hash, and a snapshot version is not a release.
        assertFalse(release(module, version, arrayOf("manifest", manifestHash)).isWellFormed())
        assertNull(release(module, version, arrayOf("manifest", "abc", "10")).manifest())
        assertFalse(release(module, arrayOf("v", "1.0-SNAPSHOT"), manifest).isWellFormed())
        // `j` must be group:artifact; the raw value is still listed.
        val bad = release(arrayOf("j", "no-colon"), version, manifest)
        assertEquals(listOf("no-colon"), bad.moduleKeys())
        assertEquals(emptyList(), bad.modules())
        assertFalse(bad.isWellFormed())
    }

    @Test
    fun aSnapshotsDTagIsItsRootCoordinate() =
        runTest {
            val event =
                roundTrip(
                    MavenSnapshotEvent.build(listOf(root), "1.0-SNAPSHOT", ManifestTag(manifestHash, 1)) {
                        alt("x")
                    },
                ) as MavenSnapshotEvent
            assertTrue(event.isWellFormed())
            val moved =
                MavenSnapshotEvent(event.id, event.pubKey, event.createdAt, event.tags.map { if (it[0] == "d") arrayOf("d", "other:thing:1.0-SNAPSHOT") else it }.toTypedArray(), "", event.sig)
            assertFalse(moved.isWellFormed())
        }

    @Test
    fun coordinatesParse() {
        assertEquals(root, MavenModule.parse("com.vitorpamplona.crypto:crypto"))
        assertNull(MavenModule.parse("crypto"))
        assertNull(MavenModule.parse(":crypto"))
        assertNull(MavenModule.parse("a:b:c"))
        assertEquals(root.withVersion("1.0"), MavenCoordinate.parse("com.vitorpamplona.crypto:crypto:1.0"))
        assertNull(MavenCoordinate.parse("a:b"))
        assertTrue(MavenCoordinate("a", "b", "1-SNAPSHOT").isSnapshot)
    }

    @Test
    fun filtersAskForTheIndexedTags() {
        val author = "e".repeat(64)
        val release = MavenReleaseEvent.filter(listOf(author), jvm, "2.1.1")
        assertEquals(listOf(3071), release.kinds)
        assertEquals(mapOf("j" to listOf(jvm.key), "v" to listOf("2.1.1")), release.tags)
        val versions = MavenPublication.versionsFilter(listOf(author), jvm)
        assertEquals(listOf(3071, 33071), versions.kinds)
        assertEquals(mapOf("j" to listOf(jvm.key)), versions.tags)
    }
}
