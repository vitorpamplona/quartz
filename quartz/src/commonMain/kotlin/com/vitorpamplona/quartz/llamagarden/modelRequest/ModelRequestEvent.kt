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
package com.vitorpamplona.quartz.llamagarden.modelRequest

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.llamagarden.modelRequest.tags.RequestTypeTag
import com.vitorpamplona.quartz.nip01Core.core.BaseAddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.core.fastAny
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.dTag.dTag
import com.vitorpamplona.quartz.nip50Search.IndexableFieldVisitor
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.nip51Lists.tags.NameTag
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * A llama.garden model request (kind 30103): "please add this model to the catalog." It names the
 * model, links to it (`url`, HuggingFace preferred), says whether the requester wants the base model
 * or a quant (`type`), how much VRAM they have (`vram_gb`), and carries a mined NIP-13 `nonce`.
 *
 * The viewer sets `d` to the requester's pubkey, so each user holds one open request: a new one
 * replaces the last. [build] keeps that unless the caller passes another `d`.
 *
 * **Kind 30103 is shared** with an app's usage counters (`arb`, `m`, JSON content). [isModelRequest]
 * tells them apart and EventFactory parses the rest as [UnrecognizedKind30103Event].
 */
@Immutable
class ModelRequestEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: TagArray,
    content: String,
    sig: HexKey,
) : BaseAddressableEvent(id, pubKey, createdAt, KIND, tags, content, sig),
    SearchableEvent {
    /** The model asked for (`Qwen3-Next-80B-A3B`, or a repository id). */
    fun name() = tags.name()

    /** Links to the model, in tag order. */
    fun links() = tags.links()

    /** `all`, `base` or `quant`; see [RequestTypeTag]. */
    fun requestType() = tags.requestType()

    /** VRAM in GB the requester has to run the model. */
    fun vramGb() = tags.vramGb()

    fun clientName() = tags.clientName()

    override fun indexableContent() = (listOfNotNull(name()) + links()).joinToString("\n")

    override fun forEachIndexableField(visitor: IndexableFieldVisitor) {
        if (!visitor.visit(name())) return
        for (link in links()) if (!visitor.visit(link)) return
    }

    companion object {
        const val KIND = 30103

        /** True for llama.garden's shape: a `name` and a request `type`. */
        fun isModelRequest(tags: TagArray): Boolean = tags.fastAny { NameTag.parse(it) != null } && tags.fastAny { RequestTypeTag.parse(it) != null }

        /**
         * A request by [requester] (the key that will sign it) for the model [name]. [dTag] defaults to
         * [requester], as llama.garden's viewer does, so it replaces the requester's previous request.
         */
        fun build(
            requester: HexKey,
            name: String,
            links: List<String> = emptyList(),
            type: String = RequestTypeTag.ALL,
            vramGb: Int? = null,
            dTag: String = requester,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<ModelRequestEvent>.() -> Unit = {},
        ) = eventTemplate<ModelRequestEvent>(KIND, "", createdAt) {
            dTag(dTag)
            name(name)
            links(links.map { it.trim() }.filter { it.isNotEmpty() })
            requestType(type)
            vramGb?.let { vramGb(it) }
            initializer()
        }
    }
}
