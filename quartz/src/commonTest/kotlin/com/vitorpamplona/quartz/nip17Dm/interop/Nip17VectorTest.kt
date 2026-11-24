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
package com.vitorpamplona.quartz.nip17Dm.interop

import com.vitorpamplona.quartz.TestResourceLoader
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip09Deletions.DeletionRequestEvent
import com.vitorpamplona.quartz.nip14Subject.subject
import com.vitorpamplona.quartz.nip17Dm.files.ChatMessageEncryptedFileHeaderEvent
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent
import com.vitorpamplona.quartz.nip25Reactions.ReactionEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * NIP-17 / NIP-59 interop with nostr-tools, both ways, from golden files in
 * `commonTest/resources/nip17/` that `quartz/tools/nip17-vector-gen` regenerates.
 *
 *  - `nostr-tools.json` (THEIRS → US): gift wraps nostr-tools made (its nip17.wrapEvent /
 *    wrapManyEvents for kind 14; nip59.wrapEvent for kind 7 and 15, which it has no NIP-17 helper for;
 *    the two wraps of NIP-17's own example; one impersonation attempt), each with the recipient
 *    and `expect`: the rumor nostr-tools put in, read with the vocabulary below. Quartz must open each
 *    with the recipient's key and read the same thing.
 *  - `ours-read-by-nostr-tools.json` (OURS → THEM): wraps Quartz's NIP-17 path made
 *    ([Nip17VectorFixture.ourCases]) and what nostr-tools' unwrapEvent found inside. Our builders
 *    must still write the same rumors, and nostr-tools must have read them as Quartz does.
 */
class Nip17VectorTest {
    private val fx = Nip17VectorFixture

    private fun load(name: String) = Json.parseToJsonElement(TestResourceLoader().loadString("nip17/$name")).jsonObject

    private fun name(case: JsonObject) = case["name"]!!.jsonPrimitive.content

    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))

    /** A rumor read with Quartz's accessors, in the generator's vocabulary. */
    private fun read(rumor: Event): JsonObject {
        val out =
            linkedMapOf<String, JsonElement>(
                "id" to JsonPrimitive(rumor.id),
                "kind" to JsonPrimitive(rumor.kind),
                "pubkey" to JsonPrimitive(rumor.pubKey),
                "content" to JsonPrimitive(rumor.content),
            )
        when (rumor) {
            is ChatMessageEvent -> {
                out["recipients"] = strings(rumor.recipientsPubKey().sorted())
                out["reply_to"] = strings(rumor.replyTo())
                out["subject"] = rumor.tags.subject()?.let(::JsonPrimitive) ?: JsonNull
            }
            is ChatMessageEncryptedFileHeaderEvent -> {
                out["recipients"] = strings(rumor.recipientsPubKey().sorted())
                out["reply_to"] = strings(rumor.replyTo())
                out["file"] =
                    JsonObject(
                        mapOf(
                            "url" to JsonPrimitive(rumor.url()),
                            "type" to JsonPrimitive(rumor.mimeType()),
                            "algorithm" to JsonPrimitive(rumor.algo()),
                            "key" to JsonPrimitive(rumor.key()?.toHexKey()),
                            "nonce" to JsonPrimitive(rumor.nonce()?.toHexKey()),
                            "x" to JsonPrimitive(rumor.hash()),
                        ),
                    )
            }
            is ReactionEvent -> {
                out["recipients"] = strings(rumor.originalAuthor().sorted())
                out["target"] = strings(rumor.originalPost())
            }
            is DeletionRequestEvent -> out["deletes"] = strings(rumor.deleteEventIds())
            else -> out["unexpected_class"] = JsonPrimitive(rumor::class.simpleName)
        }
        return JsonObject(out)
    }

    private fun diff(
        name: String,
        ours: JsonObject,
        theirs: JsonObject,
        out: MutableList<String>,
    ) {
        for ((key, value) in theirs) {
            if (ours[key] != value) out.add("$name.$key: quartz=${ours[key]} nostr-tools=$value")
        }
    }

    private suspend fun open(
        wrap: JsonElement,
        recipient: String,
    ): Event? = (Event.fromJson(wrap.toString()) as GiftWrapEvent).unwrapAndUnsealOrNull(fx.signer(recipient))

    @Test
    fun quartzOpensNostrToolsWrapsAsNostrToolsWroteThem() =
        runTest {
            val cases = load("nostr-tools.json")["cases"]!!.jsonArray.map { it.jsonObject }
            val mismatches = ArrayList<String>()
            for (case in cases) {
                val name = name(case)
                val recipient = case["recipient"]!!.jsonPrimitive.content
                // NIP-17's own example is to a key outside the fixture; the generator records it.
                val signer = case["recipient_sk"]?.jsonPrimitive?.content?.let { NostrSignerInternal(KeyPair(it.hexToByteArray())) } ?: fx.signer(recipient)
                val rumor = (Event.fromJson(case["wrap"]!!.toString()) as GiftWrapEvent).unwrapAndUnsealOrNull(signer)
                val expect = case["expect"]!!.jsonObject
                if (rumor == null) {
                    mismatches.add("$name: Quartz could not open it")
                    continue
                }
                if (name == "impersonation") {
                    // nostr-tools refuses a rumor whose pubkey is not the seal's (NIP-17 "Encrypting":
                    // clients MUST verify it). Quartz keeps the message but rewrites its author to the
                    // seal's signer (Rumor.mergeWith), so the claimed author never shows: the forged
                    // pubkey must not survive.
                    assertNotEquals(expect["claimed_pubkey"]!!.jsonPrimitive.content, rumor.pubKey)
                    assertEquals(expect["seal_pubkey"]!!.jsonPrimitive.content, rumor.pubKey)
                    continue
                }
                diff(name, read(rumor), expect, mismatches)
            }
            assertEquals(emptyList(), mismatches)
            assertTrue(cases.size >= 8, "every case was read: ${cases.map(::name)}")
        }

    @Test
    fun ourBuildersStillWriteTheRumorsNostrToolsRead() =
        runTest {
            val written = fx.ourCases()
            val recorded = load("ours-read-by-nostr-tools.json")["cases"]!!.jsonArray.map { it.jsonObject }
            assertEquals(written.map { it.name }, recorded.map(::name), "regenerate with quartz/tools/nip17-vector-gen/gen.sh")
            for ((mine, case) in written.zip(recorded)) {
                assertEquals(mine.rumor.id, case["rumor"]!!.jsonObject["id"]!!.jsonPrimitive.content, "${mine.name}: our builder changed; regenerate")
            }
        }

    @Test
    fun nostrToolsReadsOurWrapsAsQuartzDoes() =
        runTest {
            val recorded = load("ours-read-by-nostr-tools.json")["cases"]!!.jsonArray.map { it.jsonObject }
            val mismatches = ArrayList<String>()
            for (case in recorded) {
                val name = name(case)
                val theirs = case["nostr_tools"]!!.jsonObject
                if ("error" in theirs) {
                    mismatches.add("$name: nostr-tools could not open it: ${theirs["error"]}")
                    continue
                }
                val rumor = open(case["wrap"]!!, case["recipient"]!!.jsonPrimitive.content)
                if (rumor == null) {
                    mismatches.add("$name: Quartz could not open its own wrap")
                    continue
                }
                diff(name, read(rumor), theirs, mismatches)
            }
            assertEquals(emptyList(), mismatches)
        }
}
