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

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.hints.AddressHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.types.AddressHint
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip50Search.IndexableFieldVisitor
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.nip5dNapplets.NappletManifest
import com.vitorpamplona.quartz.nip5dNapplets.archetypes
import com.vitorpamplona.quartz.nip5dNapplets.artifact
import com.vitorpamplona.quartz.nip5dNapplets.icon
import com.vitorpamplona.quartz.nip5dNapplets.identifierCount
import com.vitorpamplona.quartz.nip5dNapplets.intents
import com.vitorpamplona.quartz.nip5dNapplets.optional
import com.vitorpamplona.quartz.nip5dNapplets.required
import com.vitorpamplona.quartz.nip5dNapplets.servers
import com.vitorpamplona.quartz.nip5dNapplets.source
import com.vitorpamplona.quartz.nip5dNapplets.tags.NappletIcon
import com.vitorpamplona.quartz.nip5dNapplets.tags.NappletIntent
import com.vitorpamplona.quartz.nip5dNapplets.title
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * NIP-5D napplet **snapshot** (kind 5129): a regular, immutable event pinning one exact napplet
 * build. It MAY record lineage: [origin] (`A`), the root napplet the line starts from, and
 * [parent] (`a`), the napplet this version derives from, which may belong to another author.
 * Lineage is provenance only. A snapshot is verified against its own `x` tag, and a runtime MUST
 * NOT resolve or trust a referenced napplet on its behalf. See [NappletManifest] for the rest.
 */
@Immutable
class NappletSnapshotEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : Event(id, pubKey, createdAt, KIND, tags, content, sig),
    NappletManifest,
    AddressHintProvider,
    SearchableEvent {
    override fun indexableContent() = listOfNotNull(title(), description()).joinToString("\n")

    // The read path: the same fields indexableContent() joins, handed over without
    // building the joined string a scan would throw away.
    override fun forEachIndexableField(visitor: IndexableFieldVisitor) {
        if (!visitor.visit(title())) return
        visitor.visit(description())
    }

    override fun addressHints(): List<AddressHint> = tags.lineageHints()

    override fun linkedAddressIds(): List<String> = listOfNotNull(parent(), origin()).map { it.toValue() }

    /** The `a` tag: the napplet this version derives from. */
    fun parent(): Address? = tags.parent()

    /** The `A` tag: the root napplet this line of versions originates from. */
    fun origin(): Address? = tags.origin()

    /** Only a named napplet carries a `d` tag. */
    override fun isValid() = super<NappletManifest>.isValid() && tags.identifierCount() == 0

    companion object {
        const val KIND = 5129

        /**
         * A manifest for the self-contained `/index.html` whose sha256 is [artifactHash]. NIP-5D
         * requires a non-empty plain-text [description], which becomes the event content.
         */
        fun build(
            artifactHash: HexKey,
            description: String,
            servers: List<String> = emptyList(),
            required: List<String> = emptyList(),
            optional: List<String> = emptyList(),
            archetypes: List<String> = emptyList(),
            intents: List<NappletIntent> = emptyList(),
            icon: NappletIcon? = null,
            title: String? = null,
            source: String? = null,
            parent: Address? = null,
            origin: Address? = null,
            lineageRelay: NormalizedRelayUrl? = null,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<NappletSnapshotEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, description, createdAt) {
            artifact(artifactHash)
            if (servers.isNotEmpty()) servers(servers)
            if (required.isNotEmpty()) required(required)
            if (optional.isNotEmpty()) optional(optional)
            if (archetypes.isNotEmpty()) archetypes(archetypes)
            if (intents.isNotEmpty()) intents(intents)
            icon?.let { icon(it) }
            title?.let { title(it) }
            source?.let { source(it) }
            parent?.let { parent(it, lineageRelay) }
            origin?.let { origin(it, lineageRelay) }
            initializer()
        }
    }
}
