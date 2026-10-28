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
package com.vitorpamplona.quartz.nip5dNapplets

import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip5dNapplets.tags.NappletIcon
import com.vitorpamplona.quartz.nip5dNapplets.tags.NappletIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** NIP-5D's current form: one self-contained `/index.html` named by a bare `x` tag. */
class NappletArtifactManifestTest {
    private val zero = "00".repeat(32)
    private val artifact = "11".repeat(32)
    private val iconHash = "22".repeat(32)

    private fun <T : Event> materialize(
        template: EventTemplate<T>,
        factory: (String, String, Long, Array<Array<String>>, String, String) -> T,
    ): T = factory(zero, zero, template.createdAt, template.tags, template.content, zero)

    // Published by napplet tooling to nos.lol, primal and ditto in October 2026.
    private val seedHammer =
        """{"id":"99f0b792abdecbe2b7e98cc1e735542fbd5ffdfae54607bd853651fcbdaeeb0e","pubkey":"149e07e619eedd32e47568d3197e36e270f4b5b048c6f652bf56fe90667dbf89","created_at":1793125651,"kind":35129,"tags":[["d","sh-viewer"],["x","b0d735bf559553a436b1ad065c5a44821d8c96e777682cf2ae86d065f6441068"],["z","cad-viewer"],["i","napplet:cad-viewer/open","route","model","version"],["R","inc"],["R","resource"],["O","theme"],["O","link"],["O","outbox"],["O","fs"],["O","storage"],["server","https://bls.unrug.tech"],["server","https://blossom.primal.net"],["title","SeedHammer 3D viewer"],["source","nostr://npub1zj0q0eseamwn9er4drf3jl3kufc0fddsfrr0v54l2mlfqenah7ysxuy9th/relay.ngit.dev/seedhammer-viewer"]],"content":"3D viewer for the Seed I controller and the Hammer and II engraving machines: section cuts, part visibility, exploded views and STEP download.","sig":"f8ab6d65d7e79d5b1bd49832937965a7abd872755b79a448185ebf504776865c2c509859b25de4907a461222f3634f4014d8317e7e50cb1ed7caf43bb1b8a812"}"""

    private val fotohop =
        """{"id":"72e97779aab2c5d129774fe1bf3f0206109af0d54943edd9f444a33c81db3bba","pubkey":"e771af0b05c8e95fcdf6feb3500544d2fb1ccd384788e9f490bb3ee28e8ed66f","created_at":1793099370,"kind":15129,"tags":[["x","6faaad876de6984e100fc4937f8db88854ef9c31f6b3ad6ed2bdbbac59141e8c"],["R","fs"],["O","keys"],["O","storage"],["O","theme"],["title","Fotohop"],["server","https://blssm.us"],["client","napplet.run"]],"content":"Layered image editor with drawing, text, cropping, adjustments, and PNG export","sig":"7a029d1ddb45bddd2082df96a44c54c4fe6810a49cb0b3f989aaf22afc87fb340ca2e6542ddb36a75de8aaf1c9a3fe7373ff9d19f262eb2fa7d2feaa29c09a60"}"""

    // Another app reusing kind 35129 for its own plugin format: no x tag, so not a napplet.
    private val notANapplet =
        """{"id":"e212cb762591ea0cb88424c0aa6fa19b4c3a4df02a83cef273a1fe500efdbaa9","pubkey":"61fa67befbbf3fcf1a0244100ea4a108e94f9738eeb6917706414409cc2bf008","created_at":1792818011,"kind":35129,"tags":[["d","org.omni.plugin.external-tool"],["name","External Tool"],["version","1.0.0"],["description","Packaged nsite (1 files)."],["icon","globe"],["view","main","External Tool",""],["f","index.html","375b9584a57b704df56d73c719df8d68345fcafde352b4abe7e243eddd8b29db","text/html"]],"content":"<html></html>","sig":"a7a6bed7b864b1eccc964b15eb3973cf81fa28b1d13d35f1c94cd5437d392bedb3a2eb813fea622a7f117221e495430baaade6f4829e60fbc5cd335f15284c9d"}"""

    @Test
    fun readsAPublishedNamedNapplet() {
        val event = assertIs<NamedNappletEvent>(Event.fromJson(seedHammer))

        assertEquals("sh-viewer", event.identifier())
        assertEquals("b0d735bf559553a436b1ad065c5a44821d8c96e777682cf2ae86d065f6441068", event.artifactHash())
        assertEquals(listOf(NappletManifest.INDEX_PATH), event.paths().map { it.path })
        assertEquals(listOf(event.artifactHash()), event.paths().map { it.hash })
        assertTrue(event.isLoadable())
        assertTrue(event.verifyAggregate())
        assertEquals(event.artifactHash(), event.contentHash())
        assertEquals(listOf("inc", "resource"), event.requires())
        assertEquals(listOf("theme", "link", "outbox", "fs", "storage"), event.optional())
        assertEquals(listOf("cad-viewer"), event.archetypes())
        assertEquals(listOf(NappletIntent("napplet:cad-viewer/open", listOf("route", "model", "version"))), event.intents())
        assertEquals("SeedHammer 3D viewer", event.title())
        assertTrue(event.description()!!.startsWith("3D viewer for the Seed I"))
        assertEquals(listOf("https://bls.unrug.tech", "https://blossom.primal.net"), event.servers())
    }

    @Test
    fun readsAPublishedRootNapplet() {
        val event = assertIs<RootNappletEvent>(Event.fromJson(fotohop))
        assertEquals("6faaad876de6984e100fc4937f8db88854ef9c31f6b3ad6ed2bdbbac59141e8c", event.artifactHash())
        assertEquals("Layered image editor with drawing, text, cropping, adjustments, and PNG export", event.description())
        assertEquals(listOf("fs"), event.requires())
        assertTrue(event.isLoadable())
    }

    @Test
    fun rejectsAManifestWithNoArtifact() {
        val event = assertIs<NamedNappletEvent>(Event.fromJson(notANapplet))
        assertNull(event.artifactHash())
        assertTrue(event.paths().isEmpty())
        assertFalse(event.isLoadable())
        // A non-hash icon value is neither a content-addressed icon nor dropped as one.
        assertNull(event.nappletIcon())
    }

    @Test
    fun rejectsTwoArtifactTags() {
        val template = RootNappletEvent.build(artifactHash = artifact, description = "d")
        val tags = template.tags + arrayOf(arrayOf("x", "33".repeat(32)))
        val event = RootNappletEvent(zero, zero, template.createdAt, tags, template.content, zero)
        assertNull(event.artifactHash())
        assertFalse(event.isLoadable())
    }

    @Test
    fun anAggregateXTagIsNotAnArtifact() {
        val event = RootNappletEvent(zero, zero, 0, arrayOf(arrayOf("x", artifact, "aggregate")), "d", zero)
        assertNull(event.artifactHash())
        assertFalse(event.isLoadable())
    }

    @Test
    fun namedNappletRoundTrips() {
        val icon = NappletIcon(iconHash, "image/png")
        val event =
            materialize(
                NamedNappletEvent.build(
                    identifier = "feed",
                    artifactHash = artifact,
                    description = "A feed viewer",
                    servers = listOf("https://blossom.example.com"),
                    required = listOf("relay"),
                    optional = listOf("theme"),
                    archetypes = listOf("feed"),
                    intents = listOf(NappletIntent("napplet:feed/open"), NappletIntent("napplet:feed/edit", listOf("filters", "relays"))),
                    icon = icon,
                    title = "Feed",
                    source = "https://github.com/example/feed",
                ),
                ::NamedNappletEvent,
            )

        assertEquals(35129, event.kind)
        assertEquals("A feed viewer", event.content)
        assertEquals("feed", event.identifier())
        assertEquals(artifact, event.artifactHash())
        assertEquals(listOf("relay"), event.requires())
        assertEquals(listOf("theme"), event.optional())
        assertEquals(listOf("feed"), event.archetypes())
        assertEquals(listOf("filters", "relays"), event.intents()[1].params)
        assertEquals(icon, event.nappletIcon())
        assertNull(event.icon())
        assertEquals(iconHash, event.iconBlob()?.hash)
        assertEquals("Feed", event.title())
        assertEquals("https://github.com/example/feed", event.source())
        // The spec's tag set, nothing from the older form.
        val names = event.tags.map { it[0] }.toSet()
        assertFalse("path" in names || "description" in names || "requires" in names)
    }

    @Test
    fun snapshotCarriesLineage() {
        val root = Address(NamedNappletEvent.KIND, zero, "feed")
        val event =
            materialize(
                NappletSnapshotEvent.build(artifactHash = artifact, description = "v1", parent = root, origin = root),
                ::NappletSnapshotEvent,
            )
        assertEquals(5129, event.kind)
        assertEquals(artifact, event.artifactHash())
        assertEquals(root, event.parent())
        assertEquals(root, event.origin())
    }

    @Test
    fun descriptionIsRequired() {
        assertFailsWith<IllegalArgumentException> { RootNappletEvent.build(artifactHash = artifact, description = " ") }
    }

    @Test
    fun onlySupportedIconTypesParse() {
        val event = RootNappletEvent(zero, zero, 0, arrayOf(arrayOf("x", artifact), arrayOf("icon", iconHash, "image/svg+xml")), "d", zero)
        assertNull(event.nappletIcon())
        assertNull(event.iconBlob())
    }
}
