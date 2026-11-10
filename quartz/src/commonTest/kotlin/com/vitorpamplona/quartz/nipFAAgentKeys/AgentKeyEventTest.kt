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
package com.vitorpamplona.quartz.nipFAAgentKeys

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.isReplaceable
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip01Core.signers.SignerExceptions
import com.vitorpamplona.quartz.nip51Lists.encryption.PrivateTagsInContent
import com.vitorpamplona.quartz.nipFAAgentKeys.tags.AgentKeyTag
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentKeyEventTest {
    private val signer = NostrSignerInternal(KeyPair())
    private val stranger = NostrSignerInternal(KeyPair())

    private val openRouter = AgentKeyTag("sk-or-v1-abc", "https://openrouter.ai/api/v1")
    private val ollama = AgentKeyTag("", "http://localhost:11434/v1")
    private val anthropic = AgentKeyTag("sk-ant-xyz", "https://api.anthropic.com/v1")

    private suspend fun roundTrip(template: EventTemplate<AgentKeyEvent>): AgentKeyEvent = assertIs<AgentKeyEvent>(Event.fromJson(signer.sign(template).toJson()))

    @Test
    fun buildParsesBackAsItsClass() =
        runTest {
            val event = roundTrip(AgentKeyEvent.build(listOf(openRouter, ollama), signer))

            assertEquals(12000, event.kind)
            assertTrue(event.kind.isReplaceable())
            assertEquals(AgentKeyEvent.createAddress(signer.pubKey), event.address())
            // NIP-31 alt is unrecommended: no public tags at all.
            assertTrue(event.tags.isEmpty())
            assertEquals(listOf(openRouter, ollama), event.agentKeys(signer))
            assertEquals(openRouter, event.preferredKey(signer))
        }

    @Test
    fun theKeyNeverAppearsInThePublicEvent() =
        runTest {
            val event = roundTrip(AgentKeyEvent.build(listOf(openRouter), signer))
            val json = event.toJson()

            assertFalse(json.contains(openRouter.key))
            assertFalse(json.contains("openrouter.ai"))
            assertFalse(openRouter.toString().contains(openRouter.key))
        }

    @Test
    fun aKeylessLocalRunnerKeepsAnEmptyKey() =
        runTest {
            val event = roundTrip(AgentKeyEvent.build(listOf(ollama), signer))
            val key = event.preferredKey(signer)!!

            assertEquals("", key.key)
            assertFalse(key.hasKey())
            assertEquals("http://localhost:11434/v1", key.url)
        }

    @Test
    fun onlyTheAuthorCanRead() =
        runTest {
            val event = roundTrip(AgentKeyEvent.build(listOf(openRouter), signer))

            assertNull(event.agentKeys(stranger))
            assertNull(event.preferredKey(stranger))
            assertFailsWith<SignerExceptions.UnauthorizedDecryptionException> {
                AgentKeyEvent.replaceKeys(event, listOf(anthropic), stranger)
            }
        }

    @Test
    fun replacingKeysKeepsUnknownPrivateAndPublicTags() =
        runTest {
            val model = arrayOf("model", "anthropic/claude-sonnet-4.5")
            val first = roundTrip(AgentKeyEvent.build(listOf(openRouter), signer) { add(arrayOf("-")) })
            // Another client added a private tag this one doesn't know.
            val withModel =
                roundTrip(
                    EventTemplate(
                        first.createdAt,
                        AgentKeyEvent.KIND,
                        first.tags,
                        signer.nip44Encrypt(PrivateTagsInContent.encode(first.privateTags(signer)!! + arrayOf(model)), signer.pubKey),
                    ),
                )

            val replaced = roundTrip(AgentKeyEvent.replaceKeys(withModel, listOf(anthropic), signer))

            assertEquals(listOf(anthropic), replaced.agentKeys(signer))
            assertTrue(replaced.privateTags(signer)!!.any { it.contentEquals(model) })
            assertTrue(replaced.tags.any { it.contentEquals(arrayOf("-")) })
        }

    @Test
    fun setPreferredMovesOrAddsToTheFront() =
        runTest {
            val event = roundTrip(AgentKeyEvent.build(listOf(openRouter, ollama), signer))

            val moved = roundTrip(AgentKeyEvent.setPreferred(event, ollama, signer))
            assertEquals(listOf(ollama, openRouter), moved.agentKeys(signer))

            val added = roundTrip(AgentKeyEvent.setPreferred(moved, anthropic, signer))
            assertEquals(listOf(anthropic, ollama, openRouter), added.agentKeys(signer))
        }

    @Test
    fun removingTheLastKeyStillMakesAValidEvent() =
        runTest {
            val event = roundTrip(AgentKeyEvent.build(listOf(openRouter), signer))
            val empty = roundTrip(AgentKeyEvent.remove(event, openRouter, signer))

            assertTrue(empty.content.isNotBlank())
            assertEquals(emptyList(), empty.agentKeys(signer))
            assertNull(empty.preferredKey(signer))
        }

    @Test
    fun malformedKeyTagsAreSkipped() {
        val tags =
            arrayOf(
                arrayOf("key", "sk-only-no-url"),
                arrayOf("key", "sk-blank-url", " "),
                arrayOf("other", "x", "https://x"),
                arrayOf("key", "sk-good", "https://api.example.com/v1", "extra"),
            )

        assertEquals(listOf(AgentKeyTag("sk-good", "https://api.example.com/v1")), tags.agentKeys())
        assertEquals(AgentKeyTag("sk-good", "https://api.example.com/v1"), tags.preferredAgentKey())
        assertContentEquals(arrayOf("key", "sk-good", "https://api.example.com/v1"), AgentKeyTag.assemble(tags.preferredAgentKey()!!))
    }

    @Test
    fun aChangeIsADiffOnlyBetweenTheSameAuthor() =
        runTest {
            val older = roundTrip(AgentKeyEvent.build(listOf(openRouter), signer, createdAt = 1000))
            val newer = roundTrip(AgentKeyEvent.replaceKeys(older, listOf(anthropic), signer, createdAt = 2000))
            val strangers = assertIs<AgentKeyEvent>(stranger.sign(AgentKeyEvent.build(listOf(anthropic), stranger)))

            assertFalse(newer.diffFrom(older)!!.removesData())
            assertNull(newer.diffFrom(strangers))
        }
}
