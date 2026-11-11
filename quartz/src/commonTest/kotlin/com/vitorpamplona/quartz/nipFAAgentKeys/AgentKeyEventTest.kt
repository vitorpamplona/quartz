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
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip01Core.signers.SignerExceptions
import com.vitorpamplona.quartz.nip51Lists.encryption.PrivateTagsInContent
import com.vitorpamplona.quartz.nip57Zaps.ZapRequestEvent
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

    private suspend fun withPrivate(
        base: AgentKeyEvent,
        privateTags: Array<Array<String>>,
    ) = roundTrip(EventTemplate(base.createdAt, AgentKeyEvent.KIND, base.tags, signer.nip44Encrypt(PrivateTagsInContent.encode(privateTags), signer.pubKey)))

    @Test
    fun rewritesKeepTheOriginalTagArraysAndUnreadableKeyTags() =
        runTest {
            val withExtra = arrayOf("key", "sk-or-v1-abc", "https://openrouter.ai/api/v1", "budget=5")
            val noUrl = arrayOf("key", "sk-someone-elses")
            val event = withPrivate(roundTrip(AgentKeyEvent.build(emptyList(), signer)), arrayOf(noUrl, withExtra, AgentKeyTag.assemble(anthropic)))

            val moved = roundTrip(AgentKeyEvent.setPreferred(event, anthropic, signer)).privateTags(signer)!!
            assertContentEquals(AgentKeyTag.assemble(anthropic), moved[0])
            assertContentEquals(withExtra, moved[1])
            assertTrue(moved.any { it.contentEquals(noUrl) })

            val removed = roundTrip(AgentKeyEvent.remove(event, anthropic, signer)).privateTags(signer)!!
            assertContentEquals(withExtra, removed[0])
            assertTrue(removed.any { it.contentEquals(noUrl) })
            assertEquals(2, removed.size)

            val replaced = roundTrip(AgentKeyEvent.replaceKeys(event, listOf(openRouter, ollama), signer)).privateTags(signer)!!
            assertContentEquals(withExtra, replaced[0])
            assertContentEquals(AgentKeyTag.assemble(ollama), replaced[1])
            assertTrue(replaced.any { it.contentEquals(noUrl) })
        }

    @Test
    fun rewritesDecryptOnce() =
        runTest {
            val event = roundTrip(AgentKeyEvent.build(listOf(openRouter, ollama), signer))
            var decrypts = 0
            val counting =
                object : NostrSigner(signer.pubKey) {
                    override fun isWriteable() = true

                    override suspend fun <T : Event> sign(
                        createdAt: Long,
                        kind: Int,
                        tags: Array<Array<String>>,
                        content: String,
                    ): T = signer.sign(createdAt, kind, tags, content)

                    override suspend fun nip04Encrypt(
                        plaintext: String,
                        toPublicKey: String,
                    ) = signer.nip04Encrypt(plaintext, toPublicKey)

                    override suspend fun nip04Decrypt(
                        ciphertext: String,
                        fromPublicKey: String,
                    ) = signer.nip04Decrypt(ciphertext, fromPublicKey).also { decrypts++ }

                    override suspend fun nip44Encrypt(
                        plaintext: String,
                        toPublicKey: String,
                    ) = signer.nip44Encrypt(plaintext, toPublicKey)

                    override suspend fun nip44Decrypt(
                        ciphertext: String,
                        fromPublicKey: String,
                    ) = signer.nip44Decrypt(ciphertext, fromPublicKey).also { decrypts++ }

                    override suspend fun decryptZapEvent(event: ZapRequestEvent) = signer.decryptZapEvent(event)

                    override suspend fun signPsbt(psbtHex: String) = signer.signPsbt(psbtHex)

                    override suspend fun deriveKey(nonce: String) = signer.deriveKey(nonce)

                    override fun hasForegroundSupport() = false
                }
            AgentKeyEvent.setPreferred(event, ollama, counting)
            assertEquals(1, decrypts)
            AgentKeyEvent.remove(event, ollama, counting)
            assertEquals(2, decrypts)
        }

    @Test
    fun anUnreadableEventIsNeverOverwritten() =
        runTest {
            val base = roundTrip(AgentKeyEvent.build(listOf(openRouter), signer))
            val garbage = roundTrip(EventTemplate(base.createdAt, AgentKeyEvent.KIND, base.tags, signer.nip44Encrypt("{\"not\":\"tags\"}", signer.pubKey)))

            assertFailsWith<UnreadableAgentKeysException> { AgentKeyEvent.replaceKeys(garbage, listOf(anthropic), signer) }
            assertFailsWith<UnreadableAgentKeysException> { AgentKeyEvent.setPreferred(garbage, anthropic, signer) }
            assertFailsWith<UnreadableAgentKeysException> { AgentKeyEvent.remove(garbage, openRouter, signer) }
        }

    @Test
    fun thePreferredKeyIsTheFirstWithAUrl() {
        val tags = arrayOf(arrayOf("key", "sk-no-url"), AgentKeyTag.assemble(anthropic), AgentKeyTag.assemble(openRouter))
        assertEquals(anthropic, tags.preferredAgentKey())
    }

    @Test
    fun urlRules() {
        assertEquals("https://api.example.com/v1", AgentKeyTag("k", "https://api.example.com/v1/").apiRoot())
        assertEquals("https://api.example.com/v1/", AgentKeyTag("k", "https://api.example.com/v1//").apiRoot())

        assertTrue(AgentKeyTag("k", "https://api.example.com/v1").isUrlAllowed())
        assertTrue(AgentKeyTag("k", "http://localhost:11434/v1").isUrlAllowed())
        assertTrue(AgentKeyTag("k", "http://127.0.0.1:1234/v1").isUrlAllowed())
        assertTrue(AgentKeyTag("k", "http://127.10.2.3/v1").isUrlAllowed())
        assertTrue(AgentKeyTag("k", "http://[::1]:8080/v1").isUrlAllowed())
        assertTrue(AgentKeyTag("", "http://192.168.1.5:11434/v1").isUrlAllowed())

        assertFalse(AgentKeyTag("k", "http://api.example.com/v1").isUrlAllowed())
        assertFalse(AgentKeyTag("k", "http://localhost@evil.example/v1").isUrlAllowed())
        assertFalse(AgentKeyTag("k", "http://localhost.evil.example/v1").isUrlAllowed())
        assertFalse(AgentKeyTag("k", "http://127.0.0.1.evil.example/v1").isUrlAllowed())
        assertFalse(AgentKeyTag("k", "http://128.0.0.1/v1").isUrlAllowed())
        assertFalse(AgentKeyTag("k", "ftp://localhost/v1").isUrlAllowed())
        assertFalse(AgentKeyTag("k", "https://exa mple.com/v1").isUrlAllowed())
        assertFalse(AgentKeyTag("", "javascript://x").isUrlAllowed())
    }

    @Test
    fun aRewriteFromTagsAlreadyReadDecryptsOnceAndMovesTheClockOn() =
        runTest {
            val first = AgentKeyTag("sk-1", "https://a.example/v1")
            val second = AgentKeyTag("", "http://localhost:11434/v1")
            val earlier = roundTrip(AgentKeyEvent.build(listOf(first), signer, createdAt = 1_000) { add(arrayOf("x", "public")) })
            val private = AgentKeyEvent.readForRewrite(earlier, signer) + arrayOf(arrayOf("note", "kept"))
            // The clock behind the event's: still one second after it (NIP-FA, Editing).
            val next = roundTrip(AgentKeyEvent.rewrite(earlier, private, listOf(second, first), signer, AgentKeyEvent.nextCreatedAt(earlier, now = 900)))
            assertEquals(1_001, next.createdAt)
            assertEquals(listOf(second, first), next.agentKeys(signer))
            assertEquals("public", next.tags.first { it[0] == "x" }[1])
            assertEquals("kept", next.privateTags(signer)!!.first { it[0] == "note" }[1])
            assertEquals(2_000, AgentKeyEvent.nextCreatedAt(earlier, now = 2_000))
            assertEquals(5, AgentKeyEvent.nextCreatedAt(null, now = 5))
        }
}
