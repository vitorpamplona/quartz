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
package com.vitorpamplona.quartz.nip5dNapplets.snapshot

import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip5dNapplets.named.NamedNappletEvent
import com.vitorpamplona.quartz.nip5dNapplets.root.RootNappletEvent
import com.vitorpamplona.quartz.utils.EventFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NappletSnapshotAccessorsTest {
    private val zero = "00".repeat(32)
    private val alice = "460c25e682fda7832b52d1f22d3d22b3176d972f60dcdc3212ed8c92ef85065c"
    private val bob = "99bb5591c9116600f845107d31f9b59e2f7c7e09a1ff802e84f1d43da557ca64"
    private val artifact = "11".repeat(32)
    private val relay = RelayUrlNormalizer.normalizeOrNull("wss://relay.example.com")!!

    @Test
    fun lineageRoundTrips() {
        val origin = Address(NamedNappletEvent.KIND, alice, "feed")
        val parent = Address(NamedNappletEvent.KIND, bob, "feed")
        val template = NappletSnapshotEvent.build(artifactHash = artifact, description = "v1", parent = parent, origin = origin, lineageRelay = relay)
        val event = NappletSnapshotEvent(zero, bob, template.createdAt, template.tags, template.content, zero)

        assertEquals(NappletSnapshotEvent.KIND, template.kind)
        assertTrue(event.isValid())
        assertEquals(parent, event.parent())
        assertEquals(origin, event.origin())
        assertEquals(listOf(parent.toValue(), origin.toValue()), event.linkedAddressIds())
        assertEquals(listOf(parent.toValue(), origin.toValue()), event.addressHints().map { it.addressId })
        assertEquals(listOf(relay, relay), event.addressHints().map { it.relay })
    }

    @Test
    fun lineageIsOptional() {
        val template = NappletSnapshotEvent.build(artifactHash = artifact, description = "v1")
        val event = NappletSnapshotEvent(zero, zero, template.createdAt, template.tags, template.content, zero)
        assertTrue(event.isValid())
        assertNull(event.parent())
        assertNull(event.origin())
        assertTrue(event.addressHints().isEmpty())
    }

    @Test
    fun eventFactoryRoutesAllThreeKinds() {
        fun route(kind: Int) = EventFactory.create<Event>(zero, zero, 0, kind, arrayOf(arrayOf("x", artifact)), "d", zero)
        assertIs<NappletSnapshotEvent>(route(NappletSnapshotEvent.KIND))
        assertIs<RootNappletEvent>(route(RootNappletEvent.KIND))
        assertIs<NamedNappletEvent>(route(NamedNappletEvent.KIND))
    }
}
