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
package com.vitorpamplona.quartz.nip17Dm

import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip04Dm.messages.EncryptedDmEvent
import com.vitorpamplona.quartz.nip17Dm.files.ChatMessageEncryptedFileHeaderEvent
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent
import com.vitorpamplona.quartz.nip17Dm.settings.DmRelayListEvent
import com.vitorpamplona.quartz.nipXXPrivateNoteStorage.PnsEvent
import com.vitorpamplona.quartz.utils.EventKindProvider
import com.vitorpamplona.quartz.utils.EventKindRegistry

/** Registers the event classes of the `quartz-dms` module with [com.vitorpamplona.quartz.utils.EventFactory]. */
object DmEventKinds : EventKindProvider {
    override fun registerKinds(registry: EventKindRegistry) {
        registry.register(DmRelayListEvent.KIND, ::DmRelayListEvent)
        registry.register(PnsEvent.KIND, ::PnsEvent)
        registry.register(EncryptedDmEvent.KIND, ::EncryptedDmEvent)

        // Rumors arrive unsigned and may come without an id: hash one so the event is addressable.
        registry.register(ChatMessageEncryptedFileHeaderEvent.KIND) { id, pubKey, createdAt, tags, content, sig ->
            ChatMessageEncryptedFileHeaderEvent(id.ifBlank { EventHasher.hashId(pubKey, createdAt, ChatMessageEncryptedFileHeaderEvent.KIND, tags, content) }, pubKey, createdAt, tags, content, sig)
        }
        registry.register(ChatMessageEvent.KIND) { id, pubKey, createdAt, tags, content, sig ->
            ChatMessageEvent(id.ifBlank { EventHasher.hashId(pubKey, createdAt, ChatMessageEvent.KIND, tags, content) }, pubKey, createdAt, tags, content, sig)
        }
    }
}
