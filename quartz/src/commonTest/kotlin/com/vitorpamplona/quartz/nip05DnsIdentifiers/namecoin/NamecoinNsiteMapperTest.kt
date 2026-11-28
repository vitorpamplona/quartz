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
package com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin

import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip19Bech32.bech32.Bech32
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.vitorpamplona.quartz.nip19Bech32.entities.NNote
import com.vitorpamplona.quartz.nip19Bech32.entities.NProfile
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import com.vitorpamplona.quartz.nip19Bech32.tlv.TlvBuilder
import com.vitorpamplona.quartz.nip5aStaticWebsites.NamedSiteEvent
import com.vitorpamplona.quartz.nip5aStaticWebsites.RootSiteEvent
import com.vitorpamplona.quartz.nip5aStaticWebsites.SiteSnapshotEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NamecoinNsiteMapperTest {
    @Test
    fun `npub and nprofile select normalized current roots with hints`() {
        val npubTarget = successTarget(mapPointer(NPub.create(AUTHOR_A)))
        assertEquals(NamecoinNsiteTarget.Root(AUTHOR_A), npubTarget)
        assertEquals(RootSiteEvent.KIND, (npubTarget as NamecoinNsiteTarget.Root).kind)

        val relay = NormalizedRelayUrl("wss://relay.example.com/")
        val profileTarget = successTarget(mapPointer(NProfile.create(AUTHOR_A, listOf(relay))))
        assertEquals(NamecoinNsiteTarget.Root(AUTHOR_A, listOf(relay)), profileTarget)
    }

    @Test
    fun `root and named naddr retain exact coordinates and relay hints`() {
        val relay = NormalizedRelayUrl("wss://relay.example.com/")
        val root = successTarget(mapPointer(NAddress.create(RootSiteEvent.KIND, AUTHOR_A, "", listOf(relay))))
        assertEquals(NamecoinNsiteTarget.Root(AUTHOR_A, listOf(relay)), root)

        val named = successTarget(mapPointer(NAddress.create(NamedSiteEvent.KIND, AUTHOR_A, "blog", listOf(relay))))
        assertEquals(NamecoinNsiteTarget.Named(AUTHOR_A, "blog", listOf(relay)), named)
        assertEquals(NamedSiteEvent.KIND, (named as NamecoinNsiteTarget.Named).kind)

        // A direct naddr carries an exact NIP-01 identifier, not only a canonical URL slug.
        val nonCanonical = successTarget(mapPointer(NAddress.create(NamedSiteEvent.KIND, AUTHOR_A, "a much longer identifier", emptyList())))
        assertEquals(NamecoinNsiteTarget.Named(AUTHOR_A, "a much longer identifier"), nonCanonical)
    }

    @Test
    fun `nevent supports every exact manifest kind and preserves all hints`() {
        val relay = NormalizedRelayUrl("wss://relay.example.com/")
        for (kind in NamecoinNsiteTarget.ExactEvent.ALLOWED_KINDS) {
            val pointer = NEvent.create(EVENT_ID, AUTHOR_A, kind, listOf(relay))
            val target = assertIs<NamecoinNsiteTarget.ExactEvent>(successTarget(mapPointer(pointer)))
            assertEquals(EVENT_ID, target.eventId)
            assertEquals(AUTHOR_A, target.author)
            assertEquals(kind, target.kind)
            assertEquals(listOf(relay), target.relays)
            assertEquals(setOf(RootSiteEvent.KIND, NamedSiteEvent.KIND, SiteSnapshotEvent.KIND), target.allowedKinds)
        }
    }

    @Test
    fun `note and hint-free nevent retain the allowed kind constraint`() {
        val note = assertIs<NamecoinNsiteTarget.ExactEvent>(successTarget(mapPointer(NNote.create(EVENT_ID))))
        assertEquals(EVENT_ID, note.eventId)
        assertNull(note.author)
        assertNull(note.kind)
        assertTrue(note.relays.isEmpty())
        assertEquals(NamecoinNsiteTarget.ExactEvent.ALLOWED_KINDS, note.allowedKinds)

        val event = assertIs<NamecoinNsiteTarget.ExactEvent>(successTarget(mapPointer(NEvent.create(EVENT_ID, null, null, emptyList()))))
        assertNull(event.author)
        assertNull(event.kind)
        assertEquals(NamecoinNsiteTarget.ExactEvent.ALLOWED_KINDS, event.allowedKinds)
    }

    @Test
    fun `wrong address shapes and exact event kinds fail without legacy fallback`() {
        val invalid =
            listOf(
                NAddress.create(RootSiteEvent.KIND, AUTHOR_A, "not-empty", emptyList()),
                NAddress.create(NamedSiteEvent.KIND, AUTHOR_A, "", emptyList()),
                NAddress.create(30023, AUTHOR_A, "blog", emptyList()),
                NEvent.create(EVENT_ID, AUTHOR_A, 1, emptyList()),
            )
        for (pointer in invalid) assertMappingFailure(mapPointer(pointer))
    }

    @Test
    fun `nested direct pointer has exclusive precedence over alias and identity`() {
        val outcome =
            mapJson(
                """
                {
                  "nsite": ${JsonPrimitive(NPub.create(AUTHOR_B))},
                  "nostr": {
                    "nsite": ${JsonPrimitive(NPub.create(AUTHOR_A))},
                    "pubkey": "${AUTHOR_C}",
                    "names": {"_": "${AUTHOR_C}"}
                  }
                }
                """.trimIndent(),
            )
        assertEquals(NamecoinNsiteTarget.Root(AUTHOR_A), successTarget(outcome))
    }

    @Test
    fun `malformed nested pointer cannot use valid alias or legacy root`() {
        val malformedValues = listOf(JsonPrimitive("definitely-not-a-pointer"), JsonNull, JsonPrimitive(42))
        for (malformed in malformedValues) {
            val value =
                JsonObject(
                    mapOf(
                        "nsite" to JsonPrimitive(NPub.create(AUTHOR_A)),
                        "nostr" to
                            JsonObject(
                                mapOf(
                                    "nsite" to malformed,
                                    "pubkey" to JsonPrimitive(AUTHOR_C),
                                ),
                            ),
                    ),
                )
            assertMappingFailure(mapValue(value))
        }
    }

    @Test
    fun `top level alias accepts pointers and bare shorthand only when nested is absent`() {
        val pointerAlias =
            JsonObject(
                mapOf(
                    "nsite" to JsonPrimitive(NPub.create(AUTHOR_A)),
                    "nostr" to JsonObject(mapOf("pubkey" to JsonPrimitive(AUTHOR_B))),
                ),
            )
        assertEquals(NamecoinNsiteTarget.Root(AUTHOR_A), successTarget(mapValue(pointerAlias)))

        val bareAlias =
            JsonObject(
                mapOf(
                    "nsite" to JsonPrimitive("blog"),
                    "nostr" to JsonObject(mapOf("pubkey" to JsonPrimitive(AUTHOR_B))),
                ),
            )
        assertEquals(NamecoinNsiteTarget.Named(AUTHOR_B, "blog"), successTarget(mapValue(bareAlias)))
    }

    @Test
    fun `bare shorthand uses selected legacy author and relay hints`() {
        val outcome =
            mapJson(
                """
                {
                  "nostr": {
                    "nsite": "blog-1",
                    "pubkey": "${AUTHOR_C}",
                    "names": {"alice": "${AUTHOR_A.uppercase()}"},
                    "relays": {"${AUTHOR_A}": ["wss://relay.example.com/"]}
                  }
                }
                """.trimIndent(),
            )
        val relay = NormalizedRelayUrl("wss://relay.example.com/")
        assertEquals(NamecoinNsiteTarget.Named(AUTHOR_A, "blog-1", listOf(relay)), successTarget(outcome))
    }

    @Test
    fun `bare shorthand grammar and required author fail closed`() {
        val invalid = listOf("", "a".repeat(14), "blog-", "Blog", "blog_name", "two.words", "-bad-")
        for (value in invalid) assertMappingFailure(mapPointer(value))

        val noAuthor = JsonObject(mapOf("nostr" to JsonObject(mapOf("nsite" to JsonPrimitive("blog")))))
        assertMappingFailure(mapValue(noAuthor))
        assertEquals(NamecoinNsiteTarget.Named(AUTHOR_B, "-ok"), successTarget(mapPointer("-ok")))
    }

    @Test
    fun `legacy simple and single identity forms select normalized roots`() {
        assertEquals(
            NamecoinNsiteTarget.Root(AUTHOR_A),
            successTarget(mapJson("""{"nostr":"${AUTHOR_A.uppercase()}"}""")),
        )

        val relay = NormalizedRelayUrl("wss://relay.example.com/")
        assertEquals(
            NamecoinNsiteTarget.Root(AUTHOR_A, listOf(relay)),
            successTarget(
                mapJson(
                    """{"nostr":{"pubkey":"${AUTHOR_A.uppercase()}","relays":["wss://relay.example.com/"]}}""",
                ),
            ),
        )
    }

    @Test
    fun `names is exclusive and never falls back to pubkey`() {
        assertEquals(
            NamecoinNsiteTarget.Root(AUTHOR_A),
            successTarget(
                mapJson("""{"nostr":{"pubkey":"${AUTHOR_B}","names":{"_":"${AUTHOR_A}","alice":"${AUTHOR_C}"}}}"""),
            ),
        )
        assertEquals(
            NamecoinNsiteTarget.Root(AUTHOR_A),
            successTarget(mapJson("""{"nostr":{"pubkey":"${AUTHOR_B}","names":{"alice":"${AUTHOR_A}"}}}""")),
        )

        val invalid =
            listOf(
                """{"nostr":{"pubkey":"${AUTHOR_B}","names":{}}}""",
                """{"nostr":{"pubkey":"${AUTHOR_B}","names":{"alice":"${AUTHOR_A}","bob":"${AUTHOR_C}"}}}""",
                """{"nostr":{"pubkey":"${AUTHOR_B}","names":{"_":"bad","alice":"${AUTHOR_A}"}}}""",
                """{"nostr":{"pubkey":"${AUTHOR_B}","names":null}}""",
            )
        for (record in invalid) assertMappingFailure(mapJson(record))
    }

    @Test
    fun `direct pointer ignores malformed legacy identity and absence is distinct from malformed mappings`() {
        assertEquals(
            NamecoinNsiteTarget.Root(AUTHOR_A),
            successTarget(mapJson("""{"nostr":{"nsite":${JsonPrimitive(NPub.create(AUTHOR_A))},"names":{"_":"bad"}}}""")),
        )
        assertEquals(NamecoinNsiteResolveOutcome.NoNsiteMapping("d/example"), mapJson("""{"ip":"192.0.2.1"}"""))
        assertEquals(NamecoinNsiteResolveOutcome.NoNsiteMapping("d/example"), mapJson("""{"nostr":{}}"""))
        assertMappingFailure(mapJson("""{"nostr":null}"""))
        assertMappingFailure(mapJson("""{"nostr":{"nsite":"definitely-not-a-pointer"}}"""))
        assertMappingFailure(mapJson("""{"nostr":{"pubkey":"bad"}}"""))
    }

    @Test
    fun `only one canonical lowercase bare NIP-19 string is accepted`() {
        val pointer = NPub.create(AUTHOR_A)
        val invalid =
            listOf(
                "nostr:$pointer",
                " $pointer",
                "$pointer ",
                pointer.uppercase(),
                "$pointer/path",
                "$pointer?query=yes",
                "$pointer#fragment",
                pointer + NPub.create(AUTHOR_B),
                pointer.dropLast(1) + if (pointer.last() == 'q') 'p' else 'q',
            )
        for (value in invalid) assertMappingFailure(mapPointer(value))
    }

    @Test
    fun `bech32m and wrong fixed payload sizes are rejected`() {
        val bytes = AUTHOR_A.hexToByteArray()
        val bech32m = Bech32.encodeBytes("npub", bytes, Bech32.Encoding.Bech32m)
        assertMappingFailure(mapPointer(bech32m))
        assertMappingFailure(mapPointer(Bech32.encodeBytes("npub", bytes.copyOf(31), Bech32.Encoding.Bech32)))
        assertMappingFailure(mapPointer(Bech32.encodeBytes("note", bytes.copyOf(31), Bech32.Encoding.Bech32)))
    }

    @Test
    fun `truncated TLV and duplicate singleton fields are rejected`() {
        val profileBytes = Bech32.decodeBytesOrNull(NProfile.create(AUTHOR_A, emptyList()), "nprofile")!!
        val truncated = encode("nprofile", profileBytes.copyOf(profileBytes.size - 1))
        assertMappingFailure(mapPointer(truncated))

        val invalid =
            listOf(
                tlv("nprofile") {
                    addHex(0, AUTHOR_A)
                    addHex(0, AUTHOR_B)
                },
                tlv("nevent") {
                    addHex(0, EVENT_ID)
                    addHex(0, EVENT_ID_B)
                },
                tlv("nevent") {
                    addHex(0, EVENT_ID)
                    addHex(2, AUTHOR_A)
                    addHex(2, AUTHOR_B)
                },
                tlv("nevent") {
                    addHex(0, EVENT_ID)
                    addInt(3, RootSiteEvent.KIND)
                    addInt(3, RootSiteEvent.KIND)
                },
                tlv("naddr") {
                    addString(0, "blog")
                    addString(0, "other")
                    addHex(2, AUTHOR_A)
                    addInt(3, NamedSiteEvent.KIND)
                },
                tlv("naddr") {
                    addString(0, "blog")
                    addHex(2, AUTHOR_A)
                    addHex(2, AUTHOR_B)
                    addInt(3, NamedSiteEvent.KIND)
                },
                tlv("naddr") {
                    addString(0, "blog")
                    addHex(2, AUTHOR_A)
                    addInt(3, NamedSiteEvent.KIND)
                    addInt(3, NamedSiteEvent.KIND)
                },
            )
        for (pointer in invalid) assertMappingFailure(mapPointer(pointer))
    }

    @Test
    fun `missing required special author and kind fields are rejected`() {
        val invalid =
            listOf(
                tlv("nprofile") { addString(1, "wss://relay.example.com/") },
                tlv("nevent") { addHex(2, AUTHOR_A) },
                tlv("naddr") {
                    addHex(2, AUTHOR_A)
                    addInt(3, NamedSiteEvent.KIND)
                },
                tlv("naddr") {
                    addString(0, "blog")
                    addInt(3, NamedSiteEvent.KIND)
                },
                tlv("naddr") {
                    addString(0, "blog")
                    addHex(2, AUTHOR_A)
                },
            )
        for (pointer in invalid) assertMappingFailure(mapPointer(pointer))
    }

    @Test
    fun `malformed singleton lengths and kind values are rejected`() {
        val invalid =
            listOf(
                tlv("nprofile") { addHex(0, "abcd") },
                tlv("nevent") {
                    addHex(0, EVENT_ID)
                    addHex(2, "abcd")
                },
                tlv("nevent") {
                    addHex(0, EVENT_ID)
                    addHex(3, "000000")
                },
                tlv("nevent") {
                    addHex(0, EVENT_ID)
                    addInt(3, -1)
                },
                tlv("naddr") {
                    addString(0, "blog")
                    addHex(2, "abcd")
                    addInt(3, NamedSiteEvent.KIND)
                },
                tlv("naddr") {
                    addString(0, "blog")
                    addHex(2, AUTHOR_A)
                    addHex(3, "000000")
                },
            )
        for (pointer in invalid) assertMappingFailure(mapPointer(pointer))
    }

    @Test
    fun `relay TLVs must be valid ASCII websocket URLs`() {
        val invalidRelays = listOf("https://relay.example.com/", "wss://relay.example.com/a b", "wss://rél ay.example.com", "")
        for (relay in invalidRelays) {
            val pointer =
                tlv("nprofile") {
                    addHex(0, AUTHOR_A)
                    addString(1, relay)
                }
            assertMappingFailure(mapPointer(pointer))
        }
    }

    @Test
    fun `naddr identifier must be valid UTF-8`() {
        val bytes =
            record(0, byteArrayOf(0xc3.toByte(), 0x28)) +
                record(2, AUTHOR_A.hexToByteArray()) +
                record(3, int32(NamedSiteEvent.KIND))
        assertMappingFailure(mapPointer(encode("naddr", bytes)))
    }

    @Test
    fun `unknown TLVs are ignored and every valid relay is preserved`() {
        val relays = listOf("wss://one.example.com/", "wss://two.example.com/")
        val pointer =
            tlv("nprofile") {
                addHex(0, AUTHOR_A)
                addString(1, relays[0])
                addString(42, "ignored")
                addString(1, relays[1])
            }
        assertEquals(
            NamecoinNsiteTarget.Root(AUTHOR_A, relays.map(::NormalizedRelayUrl)),
            successTarget(mapPointer(pointer)),
        )
    }

    private fun mapPointer(pointer: String): NamecoinNsiteResolveOutcome =
        mapValue(
            JsonObject(
                mapOf(
                    "nostr" to
                        JsonObject(
                            mapOf(
                                "nsite" to JsonPrimitive(pointer),
                                "pubkey" to JsonPrimitive(AUTHOR_B),
                            ),
                        ),
                ),
            ),
        )

    private fun mapJson(raw: String): NamecoinNsiteResolveOutcome = mapValue(Json.parseToJsonElement(raw) as JsonObject)

    private fun mapValue(value: JsonObject): NamecoinNsiteResolveOutcome = NamecoinNsiteMapper.map(value, "d/example", "_")

    private fun successTarget(outcome: NamecoinNsiteResolveOutcome): NamecoinNsiteTarget {
        val success = assertIs<NamecoinNsiteResolveOutcome.Success>(outcome)
        assertEquals("d/example", success.result.namecoinName)
        assertEquals("_", success.result.localPart)
        return success.result.target
    }

    private fun assertMappingFailure(outcome: NamecoinNsiteResolveOutcome) {
        val failure = assertIs<NamecoinNsiteResolveOutcome.MappingFailure>(outcome)
        assertEquals("d/example", failure.name)
        assertTrue(failure.error.isNotEmpty())
    }

    private fun tlv(
        hrp: String,
        build: TlvBuilder.() -> Unit,
    ): String = encode(hrp, TlvBuilder().apply(build).build())

    private fun encode(
        hrp: String,
        bytes: ByteArray,
    ): String = Bech32.encodeBytes(hrp, bytes, Bech32.Encoding.Bech32)

    private fun record(
        type: Int,
        value: ByteArray,
    ): ByteArray = byteArrayOf(type.toByte(), value.size.toByte()) + value

    private fun int32(value: Int): ByteArray =
        byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )

    companion object {
        private val AUTHOR_A = "aa".repeat(32)
        private val AUTHOR_B = "bb".repeat(32)
        private val AUTHOR_C = "cc".repeat(32)
        private val EVENT_ID = "11".repeat(32)
        private val EVENT_ID_B = "22".repeat(32)
    }
}
