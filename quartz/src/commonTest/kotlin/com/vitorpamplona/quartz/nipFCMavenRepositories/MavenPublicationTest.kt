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
import com.vitorpamplona.quartz.nip01Core.crypto.verifyId
import com.vitorpamplona.quartz.nip01Core.crypto.verifySignature
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

    // crypto-kmp 2.1.1, the first release published with Magpie, as wss://vitor.nostr1.com and
    // wss://nos.lol served it on 2026-11-18. Magpie 0.1.1 writes `alt` before the artifact hashes.
    private val cryptoKmpRelease =
        "{\"content\":\"\",\"created_at\":1795012783,\"id\":\"1c993cfd7b8b034a12ce66b20b1910a88f2c1816a39f3c284155fd88" +
            "31acf80d\",\"kind\":3071,\"pubkey\":\"e9867c970848e300d6fca32bbd600c2fd4bd92c760ae5707355058720991955c\",\"s" +
            "ig\":\"5b87ae847a31207b13f15afc3ed23a34f4197b6f5b803285f4c50cb1d0bd0ca0b2ac97ac86ffc8831a6b721e6bb9027" +
            "8c688e9934acb66a9c69ff5709b9c4cb0\",\"tags\":[[\"j\",\"com.vitorpamplona.crypto:crypto\"],[\"j\",\"com.vitorpa" +
            "mplona.crypto:crypto-android\"],[\"j\",\"com.vitorpamplona.crypto:crypto-iosarm64\"],[\"j\",\"com.vitorpampl" +
            "ona.crypto:crypto-iossimulatorarm64\"],[\"j\",\"com.vitorpamplona.crypto:crypto-js\"],[\"j\",\"com.vitorpamp" +
            "lona.crypto:crypto-jvm\"],[\"j\",\"com.vitorpamplona.crypto:crypto-linuxx64\"],[\"j\",\"com.vitorpamplona.cr" +
            "ypto:crypto-macosarm64\"],[\"j\",\"com.vitorpamplona.crypto:crypto-wasm-js\"],[\"v\",\"2.1.1\"],[\"manifest\",\"" +
            "1012859c6c4548599c203c41f2126fcc0ca2138feae1f2acf84f42222f96825e\",\"5784\"],[\"x\",\"1012859c6c4548599c20" +
            "3c41f2126fcc0ca2138feae1f2acf84f42222f96825e\"],[\"server\",\"https://nostr.download\"],[\"alt\",\"Release o" +
            "f com.vitorpamplona.crypto:crypto:2.1.1 (Maven artifacts on Nostr)\"],[\"x\",\"5696e81b2f715a26856d1ecea" +
            "548e85cefe25564259c7064ea7d0b124a5023a6\"],[\"x\",\"bf9e22eb647f7ff0ba950d59809eedab47b9818062fc7d581c45" +
            "5cd16d306aea\"],[\"x\",\"4db9175acae17941dad5633d848f1d05d7f0697818ec48c374528bf2a6d95ab0\"],[\"x\",\"fc191e" +
            "234a5ebd8d27fb5ede3cca00ebc29e77713e13051b56142b44daa3418e\"],[\"x\",\"4d863269c2b8d9c7ca7fa74b50f1fb4be" +
            "45fc84415f84feea9df42c9f5d7f75a\"],[\"x\",\"575c52fb22543a81fdf4ef14ee21f7639e1d52697c2357448d3b055a4270" +
            "9120\"],[\"x\",\"27a5da9f9853553985b46ca5651d7a350ed38e0a597c930946fc0b09fd5fd4af\"],[\"x\",\"64143c84cb134a" +
            "2cfd6990b8d969c89d72a97deb6569d28b0cff086c7e0ea164\"],[\"x\",\"ec6bb6266956b203e573735d0d3f1970365ef0aff" +
            "f473937cffad5376b364751\"],[\"x\",\"70bf27938d6007de6f9b41616faf5339040d23fc0d18aab6db29dc84c4677780\"],[" +
            "\"x\",\"034f9b32984b5bd2a38300af445850cba9c2f382502e3365cf94f8dfc887bfc2\"],[\"x\",\"a23a97ffde75cb5cb1fc55" +
            "8f8c678e7b3734f426fce9b5fca93b2a5d279dd096\"],[\"x\",\"6363cadabfc35efa2ffee360c5282059542b573b923d913f5" +
            "574831c4d9d115b\"],[\"x\",\"722ee282f1524d0d969d5c8e8d2a57f42c3ce17d88d3dfa8b85c20b0642b511b\"],[\"x\",\"918" +
            "a7273c1ecd8747f91e1f9d5943468a16dcd8e14ed272ec316311f38d26f3a\"],[\"x\",\"2e2b05535ddf2f1b351088a8c13c6f" +
            "aecb9b5cd58cae114b43f21aa2b0b56413\"],[\"x\",\"d9699e5f61b02ef85667538bfc86c6fbc73b62d152777fa10df92bf1a" +
            "36ccc9c\"],[\"x\",\"c741d0156bcab5b18fbcabaf5ea6a05ce0581f16c94de499529b2af4bf611962\"],[\"x\",\"12709016931" +
            "a3d2ba0dba6c7e34fba86dfe7e5280df4d5d1d4fcb677ca3d95c7\"],[\"x\",\"1dfa9b07f8e9b2da3faa4f46293ef2ca91a104" +
            "13904cfb7d1b73697695fd6ccd\"],[\"x\",\"a621e69109a576a814d04d5915460c79f5628f544a109b1b94106be3c4dc596b\"" +
            "],[\"x\",\"2b909a2cca207b2e8cfe1fa24ac421891d6604c825a2ce53a4cac0ccc1e30d7b\"],[\"x\",\"c286ac3eadbcd167576" +
            "0821d4397fed6bf80cbaea1b446e134d27c5c994ccab3\"],[\"x\",\"c455877c16a82f9494ed38d48836390a14ec161d15cab4" +
            "969fdfbd092e21822b\"],[\"x\",\"d022136b8e0f26ff5264f3c33ece38e3246e78915f09e7e3a166ba3c3a4c3e67\"],[\"x\",\"" +
            "8b6b4ab31957d13e6cf2f4b3503a68622492fc8896162ac18ab99e99331fe330\"],[\"x\",\"574612aa574d994c7860ea8beb4" +
            "1fb9cc6a90e575c1f120245c38da9fc9811c7\"]]}"

    @Test
    fun parsesCryptoKmpsRelease() {
        val event = Event.fromJson(cryptoKmpRelease)
        assertIs<MavenReleaseEvent>(event)
        assertTrue(event.verifyId())
        assertTrue(event.verifySignature())
        assertEquals(9, event.modules().size)
        assertEquals(root, event.modules().first())
        assertEquals("2.1.1", event.version())
        assertEquals(MavenCoordinate("com.vitorpamplona.crypto", "crypto", "2.1.1"), event.coordinate())
        assertEquals("1012859c6c4548599c203c41f2126fcc0ca2138feae1f2acf84f42222f96825e", event.manifest()?.sha256)
        assertEquals(5784L, event.manifest()?.size)
        assertEquals(28, event.hashes().size)
        assertEquals(event.manifest()?.sha256, event.hashes().first())
        assertEquals(listOf("https://nostr.download"), event.servers())
        assertTrue(event.covers(jvm, "2.1.1"))
        assertFalse(event.covers(jvm, "2.1.0"))
        assertFalse(event.covers(MavenModule("com.vitorpamplona.crypto", "crypto-jv"), "2.1.1"))
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

    @Test
    fun buildRefusesAManifestItCouldNotReadBack() {
        // A hash that isn't 32 bytes of hex, or a negative size, would sign an event whose
        // manifest() is null or that isWellFormed() rejects.
        assertFailsWith<IllegalArgumentException> {
            MavenReleaseEvent.build(listOf(root), "1.0", ManifestTag("abc", 1))
        }
        assertFailsWith<IllegalArgumentException> {
            MavenReleaseEvent.build(listOf(root), "1.0", ManifestTag(manifestHash, -1))
        }
    }

    @Test
    fun buildWritesHashesInLowerCaseOnce() =
        runTest {
            val upper = manifestHash.uppercase()
            val event =
                roundTrip(
                    MavenReleaseEvent.build(listOf(root, root, jvm), "1.0", ManifestTag(upper, 1), artifactHashes = listOf(jarHash.uppercase(), jarHash)),
                ) as MavenReleaseEvent
            // The `manifest` tag and its `x` agree, so a `#x` lookup by the manifest hash finds it.
            assertEquals(manifestHash, event.tags.first { it[0] == "manifest" }[1])
            assertEquals(listOf(manifestHash, jarHash), event.hashes())
            assertEquals(listOf(root.key, jvm.key), event.moduleKeys())
        }

    @Test
    fun aModuleNameHoldsNoColon() {
        assertFailsWith<IllegalArgumentException> { MavenModule("a:b", "c") }
        assertFailsWith<IllegalArgumentException> { MavenModule("a", "") }
    }

    @Test
    fun coversReadsTheRawTags() {
        val event =
            MavenReleaseEvent(
                "1".repeat(64),
                "e".repeat(64),
                1,
                arrayOf(arrayOf("j", "bad"), arrayOf("j", jvm.key), arrayOf("j", root.key), arrayOf("v", "1.0"), arrayOf("manifest", manifestHash, "1")),
                "",
                "0".repeat(128),
            )
        assertTrue(event.covers(root, "1.0"))
        assertTrue(event.covers(jvm, "1.0"))
        assertFalse(event.covers(MavenModule("com.vitorpamplona.crypto", "crypto-j"), "1.0"))
        assertFalse(event.covers(MavenModule("com.vitorpamplona", "crypto"), "1.0"))
        // The root is the first well-formed `j`.
        assertEquals(jvm.withVersion("1.0"), event.coordinate())
        assertTrue(event.isWellFormed())
    }

    @Test
    fun aSecondVersionTagClaimsNothing() {
        // version() reads the first `v`; covers() must agree, or this 1.0 release would be a
        // candidate for 2.0 too.
        val event =
            MavenReleaseEvent(
                "1".repeat(64),
                "e".repeat(64),
                1,
                arrayOf(arrayOf("j", root.key), arrayOf("v", "1.0"), arrayOf("v", "2.0"), arrayOf("manifest", manifestHash, "1")),
                "",
                "0".repeat(128),
            )
        assertEquals("1.0", event.version())
        assertTrue(event.covers(root, "1.0"))
        assertFalse(event.covers(root, "2.0"))
    }
}
