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

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip50Search.IndexableFieldVisitor
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.nip5aStaticWebsites.siteAggregateHash
import com.vitorpamplona.quartz.nip5aStaticWebsites.siteDescription
import com.vitorpamplona.quartz.nip5aStaticWebsites.siteOrigin
import com.vitorpamplona.quartz.nip5aStaticWebsites.siteParent
import com.vitorpamplona.quartz.nip5aStaticWebsites.sitePaths
import com.vitorpamplona.quartz.nip5aStaticWebsites.siteServers
import com.vitorpamplona.quartz.nip5aStaticWebsites.siteSource
import com.vitorpamplona.quartz.nip5aStaticWebsites.siteTitle
import com.vitorpamplona.quartz.nip5aStaticWebsites.tags.PathTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.NappletIcon
import com.vitorpamplona.quartz.nip5dNapplets.tags.NappletIntent
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * NIP-5D napplet **snapshot** (kind 5129) — a regular, immutable event that pins
 * one exact napplet build. Unlike the replaceable root/named manifests, a snapshot
 * is permanent version history. It MAY record lineage with an `a` tag ([parent]) and an
 * `A` tag ([origin]); those never affect verification, which uses its own `x` tag.
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
    SearchableEvent {
    override fun indexableContent() = listOfNotNull(title(), description()).joinToString("\n")

    // The read path: the same fields indexableContent() joins, handed over without
    // building the joined string a scan would throw away.
    override fun forEachIndexableField(visitor: IndexableFieldVisitor) {
        if (!visitor.visit(title())) return
        visitor.visit(description())
    }

    /** The `a` tag: the napplet this version derives from, which may belong to another author. Provenance only. */
    fun parent() = tags.siteParent()

    /** The `A` tag: the root napplet this line of versions originates from. Provenance only. */
    fun origin() = tags.siteOrigin()

    companion object {
        const val KIND = 5129

        /**
         * A manifest for the single self-contained `/index.html` whose sha256 is [artifactHash].
         * [description] becomes the event content and MUST NOT be blank; [required] and [optional]
         * become the `R` and `O` tags.
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
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<NappletSnapshotEvent>.() -> Unit = {},
        ): EventTemplate<NappletSnapshotEvent> {
            require(description.isNotBlank()) { "NIP-5D: a napplet manifest needs a non-empty description" }
            return eventTemplate(KIND, description, createdAt) {
                nappletArtifactHash(artifactHash)
                if (servers.isNotEmpty()) siteServers(servers)
                if (required.isNotEmpty()) nappletRequired(required)
                if (optional.isNotEmpty()) nappletOptional(optional)
                if (archetypes.isNotEmpty()) nappletArchetypes(archetypes)
                if (intents.isNotEmpty()) nappletIntents(intents)
                icon?.let { nappletIcon(it) }
                title?.let { siteTitle(it) }
                source?.let { siteSource(it) }
                parent?.let { siteParent(it) }
                origin?.let { siteOrigin(it) }
                initializer()
            }
        }

        @Deprecated(
            "NIP-5D napplets are now one self-contained /index.html addressed by a bare x tag, with the description " +
                "in content and R/O capability tags. Use the build(…, artifactHash, description, …) overload; publish a " +
                "multi-file app as a NIP-5A nsite.",
        )
        fun build(
            paths: List<PathTag>,
            servers: List<String> = emptyList(),
            requires: List<String> = emptyList(),
            title: String? = null,
            description: String? = null,
            source: String? = null,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<NappletSnapshotEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, "", createdAt) {
            sitePaths(paths)
            siteAggregateHash(paths)
            if (servers.isNotEmpty()) siteServers(servers)
            if (requires.isNotEmpty()) nappletRequires(requires)
            title?.let { siteTitle(it) }
            description?.let { siteDescription(it) }
            source?.let { siteSource(it) }
            initializer()
        }
    }
}
