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

import com.vitorpamplona.quartz.nip5dNapplets.tags.ArchetypeTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.ArtifactTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.IconTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.IntentTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.NappletIcon
import com.vitorpamplona.quartz.nip5dNapplets.tags.NappletIntent
import com.vitorpamplona.quartz.nip5dNapplets.tags.OptionalTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.RequiredTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.SourceTag
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Nip5dNappletTagsTest {
    private val hash = "186ea5fd14e88fd1ac49351759e7ab906fa94892002b60bf7f5a428f28ca1c99"

    @Test
    fun artifactIsABareLowercaseSha256() {
        assertEquals(hash, ArtifactTag.parse(arrayOf("x", hash)))
        assertContentEquals(arrayOf("x", hash), ArtifactTag.assemble(hash))
        assertNull(ArtifactTag.parse(arrayOf("x", hash.uppercase())))
        assertNull(ArtifactTag.parse(arrayOf("x", "../etc/passwd")))
        assertNull(ArtifactTag.parse(arrayOf("x")))
        // NIP-5A's site aggregate hashes path tags, not one artifact.
        assertNull(ArtifactTag.parse(arrayOf("x", hash, "aggregate")))
    }

    @Test
    fun artifactNeedsExactlyOneXTag() {
        assertEquals(hash, arrayOf(arrayOf("x", hash)).artifact())
        assertNull(arrayOf(arrayOf("x", hash), arrayOf("x", hash)).artifact())
        assertNull(arrayOf(arrayOf("x", hash), arrayOf("x", "nope")).artifact())
        assertNull(emptyArray<Array<String>>().artifact())
    }

    @Test
    fun capabilityTagsAreBareDomains() {
        assertEquals("relay", RequiredTag.parse(arrayOf("R", "relay")))
        assertEquals("theme", OptionalTag.parse(arrayOf("O", "theme")))
        assertNull(RequiredTag.parse(arrayOf("R", "")))
        assertNull(OptionalTag.parse(arrayOf("R", "theme")))
        val tags = arrayOf(arrayOf("R", "relay"), arrayOf("R", "relay"), arrayOf("O", "theme"), arrayOf("requires", "identity"))
        // Repeats collapse; the retired `requires` tag is not read.
        assertEquals(listOf("relay"), tags.required())
        assertEquals(listOf("theme"), tags.optional())
    }

    @Test
    fun archetypesAndIntents() {
        assertEquals("feed", ArchetypeTag.parse(arrayOf("z", "feed")))
        assertEquals(NappletIntent("napplet:feed/open"), IntentTag.parse(arrayOf("i", "napplet:feed/open")))
        val edit = NappletIntent("napplet:feed/edit", listOf("filters", "relays"))
        assertEquals(edit, IntentTag.parse(arrayOf("i", "napplet:feed/edit", "filters", "relays")))
        assertContentEquals(arrayOf("i", "napplet:feed/edit", "filters", "relays"), IntentTag.assemble(edit))
    }

    @Test
    fun iconIsAHashAndASupportedImageType() {
        val icon = NappletIcon(hash, "image/png")
        assertEquals(icon, IconTag.parse(arrayOf("icon", hash, "image/png")))
        assertContentEquals(arrayOf("icon", hash, "image/png"), IconTag.assemble(icon))
        assertNull(IconTag.parse(arrayOf("icon", hash, "image/svg+xml")))
        assertNull(IconTag.parse(arrayOf("icon", "https://example.com/icon.png")))
        assertNull(IconTag.parse(arrayOf("icon", hash)))
        // More than one icon tag is not "the" icon.
        assertNull(arrayOf(IconTag.assemble(icon), IconTag.assemble(icon)).icon())
    }

    @Test
    fun serversAreHttpOrigins() {
        val tags =
            arrayOf(
                arrayOf("server", "https://blossom.example.com"),
                arrayOf("server", "http://localhost:3000/"),
                arrayOf("server", "https://blossom.example.com"),
                arrayOf("server", "file:///etc"),
                arrayOf("server", "content://x"),
                arrayOf("server", "https://user:pass@host.example"),
                arrayOf("server", "https://host.example/path"),
                arrayOf("server", "https://host.example?x=1"),
                arrayOf("server", " https://host.example"),
            )
        assertEquals(listOf("https://blossom.example.com", "http://localhost:3000/"), tags.servers())
    }

    @Test
    fun aRepeatedTitleIsNoTitle() {
        assertEquals("T", arrayOf(arrayOf("title", "T")).title())
        assertNull(arrayOf(arrayOf("title", "T"), arrayOf("title", "U")).title())
    }

    @Test
    fun anAggregateXNextToTheArtifactLeavesNoSingleArtifact() {
        assertNull(arrayOf(arrayOf("x", hash), arrayOf("x", hash, "aggregate")).artifact())
    }

    @Test
    fun intentParamsRoundTripWithoutEmptyNames() {
        val written = IntentTag.assemble(NappletIntent("napplet:feed/edit", listOf("", "filters", "")))
        assertContentEquals(arrayOf("i", "napplet:feed/edit", "filters"), written)
        assertEquals(NappletIntent("napplet:feed/edit", listOf("filters")), IntentTag.parse(written))
    }

    @Test
    fun sourceLocations() {
        listOf(
            "https://git.example.com/owner/napplet.git",
            "http://git.example.com/owner/napplet.git",
            "ssh://git@git.example.com/owner/napplet.git",
            "git@git.example.com:owner/napplet.git",
            "git://git.example.com/owner/napplet.git",
        ).forEach { assertEquals(it, SourceTag.parse(arrayOf("source", it)), it) }

        listOf(
            "file:///home/me/napplet",
            "/home/me/napplet",
            "javascript:alert(1)",
            "",
            // Windows paths are local paths too.
            "C:\\Users\\me\\napplet",
            "C:/Users/me/napplet",
            "c:napplet",
            // Other URI schemes, with or without `//`.
            "content://com.evil.provider/x",
            "intent://scan/#Intent;scheme=zxing;end",
            "mailto:a@b.c",
            "tel:+15551234",
            "smb://host/share",
            "blob:https://x/uuid",
            "jar:file:/x!/y",
            "nostr://npub1zj0q0eseamwn9er4drf3jl3kufc0fddsfrr0v54l2mlfqenah7ysxuy9th/relay.ngit.dev/seedhammer-viewer",
            // Option injection into git / ssh.
            "-oProxyCommand=touch_pwned@host.example:path",
            "ssh://-oProxyCommand=touch_pwned/x",
            "ssh://-oProxyCommand@host.example/x",
            "git@host.example:-upload-pack=x",
            // Invisible or bidi characters that would spoof the displayed repo.
            "https://github.com/\u202Eabc",
            "https://github.com/a\u00A0b",
            "https://github.com/a b",
        ).forEach {
            assertNull(SourceTag.parse(arrayOf("source", it)), it)
        }
        // Exactly two elements, and at most one tag.
        assertNull(SourceTag.parse(arrayOf("source", "https://a.example/x", "https://b.example/x")))
        assertNull(arrayOf(arrayOf("source", "https://a.example/x"), arrayOf("source", "https://b.example/x")).source())
    }
}
