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
package com.vitorpamplona.quartz.nip5dNapplets.named

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip5dNapplets.NappletManifest
import com.vitorpamplona.quartz.nip5dNapplets.tags.NappletIcon
import com.vitorpamplona.quartz.nip5dNapplets.tags.NappletIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NamedNappletAccessorsTest {
    private val zero = "00".repeat(32)
    private val artifact = "11".repeat(32)
    private val iconHash = "22".repeat(32)

    // Published to nos.lol, primal and ditto in October 2026.
    private val seedHammer =
        """{"id":"99f0b792abdecbe2b7e98cc1e735542fbd5ffdfae54607bd853651fcbdaeeb0e","pubkey":"149e07e619eedd32e47568d3197e36e270f4b5b048c6f652bf56fe90667dbf89","created_at":1793125651,"kind":35129,"tags":[["d","sh-viewer"],["x","b0d735bf559553a436b1ad065c5a44821d8c96e777682cf2ae86d065f6441068"],["z","cad-viewer"],["i","napplet:cad-viewer/open","route","model","version"],["R","inc"],["R","resource"],["O","theme"],["O","link"],["O","outbox"],["O","fs"],["O","storage"],["server","https://bls.unrug.tech"],["server","https://blossom.primal.net"],["title","SeedHammer 3D viewer"],["source","nostr://npub1zj0q0eseamwn9er4drf3jl3kufc0fddsfrr0v54l2mlfqenah7ysxuy9th/relay.ngit.dev/seedhammer-viewer"]],"content":"3D viewer for the Seed I controller and the Hammer and II engraving machines: section cuts, part visibility, exploded views and STEP download.","sig":"f8ab6d65d7e79d5b1bd49832937965a7abd872755b79a448185ebf504776865c2c509859b25de4907a461222f3634f4014d8317e7e50cb1ed7caf43bb1b8a812"}"""

    // Another app reusing kind 35129 for its own plugin format: no x tag, so not a napplet.
    private val foreign =
        """{"id":"e212cb762591ea0cb88424c0aa6fa19b4c3a4df02a83cef273a1fe500efdbaa9","pubkey":"61fa67befbbf3fcf1a0244100ea4a108e94f9738eeb6917706414409cc2bf008","created_at":1792818011,"kind":35129,"tags":[["d","org.omni.plugin.external-tool"],["name","External Tool"],["version","1.0.0"],["description","Packaged nsite (1 files)."],["icon","globe"],["view","main","External Tool",""],["f","index.html","375b9584a57b704df56d73c719df8d68345fcafde352b4abe7e243eddd8b29db","text/html"]],"content":"<html></html>","sig":"a7a6bed7b864b1eccc964b15eb3973cf81fa28b1d13d35f1c94cd5437d392bedb3a2eb813fea622a7f117221e495430baaade6f4829e60fbc5cd335f15284c9d"}"""

    // The retired multi-file form: path tags and an aggregate x.
    private val legacy =
        """{"id":"89d2d038abd77e8881b3328b12e47b3bc58c8f4f19c80270ef7c75b61bd58bfa","pubkey":"0bafb0cb2f602c8579c9bcee4877037cfd03646284e941d548e253ede6dfd852","created_at":1793118170,"kind":35129,"tags":[["d","buildings"],["x","5d7af05007b8ae547a399ddd01ba827256ed0e06db024639d16779215e8c486a","aggregate"],["server","https://blossom.primal.net"],["requires","inc"],["title","Buildings"],["description","Explore an interactive two-floor 3D building model."],["path","/index.html","3a54a9ff7b1b0996142c3b67f793e4768022e5e93a082643fdfdf874199b05c5"]],"content":"","sig":"f2e68a56308435fdaa978f0d4f593092a6f3d76a411676bf6cfc7c656f2847a24640f22f36daf328511393f41d363abe9d3bcc9213e597de5250c1689d341ea8"}"""

    @Test
    fun readsAPublishedNapplet() {
        val event = assertIs<NamedNappletEvent>(Event.fromJson(seedHammer))
        assertTrue(event.isValid())
        assertEquals("sh-viewer", event.identifier())
        assertEquals("b0d735bf559553a436b1ad065c5a44821d8c96e777682cf2ae86d065f6441068", event.artifact())
        assertEquals(NappletManifest.INDEX_PATH, event.artifactFile()?.path)
        assertEquals(event.artifact(), event.artifactFile()?.hash)
        assertEquals(listOf("inc", "resource"), event.required())
        assertEquals(listOf("theme", "link", "outbox", "fs", "storage"), event.optional())
        assertEquals(listOf("cad-viewer"), event.archetypes())
        assertEquals(listOf(NappletIntent("napplet:cad-viewer/open", listOf("route", "model", "version"))), event.intents())
        assertEquals("SeedHammer 3D viewer", event.title())
        assertTrue(event.description().startsWith("3D viewer for the Seed I"))
        assertEquals(listOf("https://bls.unrug.tech", "https://blossom.primal.net"), event.servers())
        assertEquals(listOf("inc"), event.missingRequired(setOf("relay", "resource", "theme")))
        assertTrue(event.missingRequired(setOf("inc", "resource")).isEmpty())
    }

    @Test
    fun anotherAppsKind35129IsNotANapplet() {
        val event = assertIs<NamedNappletEvent>(Event.fromJson(foreign))
        assertFalse(event.isValid())
        assertNull(event.artifact())
        assertNull(event.icon())
    }

    @Test
    fun theRetiredMultiFileFormIsNotANapplet() {
        val event = assertIs<NamedNappletEvent>(Event.fromJson(legacy))
        assertFalse(event.isValid())
        assertNull(event.artifact())
        assertTrue(event.required().isEmpty())
        assertEquals("", event.description())
    }

    @Test
    fun aNamedNappletNeedsItsIdentifier() {
        val event = NamedNappletEvent(zero, zero, 0, arrayOf(arrayOf("x", artifact)), "a napplet", zero)
        assertFalse(event.isValid())
    }

    @Test
    fun buildWritesTheSpecTagSet() {
        val icon = NappletIcon(iconHash, "image/png")
        val template =
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
            )
        val event = NamedNappletEvent(zero, zero, template.createdAt, template.tags, template.content, zero)

        assertEquals(NamedNappletEvent.KIND, template.kind)
        assertTrue(event.isValid())
        assertEquals("A feed viewer", event.content)
        assertEquals("feed", event.identifier())
        assertEquals(artifact, event.artifact())
        assertEquals(listOf("relay"), event.required())
        assertEquals(listOf("theme"), event.optional())
        assertEquals(listOf("feed"), event.archetypes())
        assertEquals(listOf("filters", "relays"), event.intents()[1].params)
        assertEquals(icon, event.icon())
        assertEquals("Feed", event.title())
        assertEquals("https://github.com/example/feed", event.source())
        // Nothing from the retired form (path, description, requires).
        val allowed = setOf("d", "x", "server", "R", "O", "z", "i", "icon", "title", "source", "alt")
        assertEquals(emptySet(), template.tags.map { it[0] }.toSet() - allowed)
    }
}
