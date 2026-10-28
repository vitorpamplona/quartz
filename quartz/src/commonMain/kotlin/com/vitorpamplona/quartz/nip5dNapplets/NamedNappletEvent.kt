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
import com.vitorpamplona.quartz.nip01Core.core.BaseAddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.dTag.dTag
import com.vitorpamplona.quartz.nip50Search.IndexableFieldVisitor
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.nip5aStaticWebsites.siteAggregateHash
import com.vitorpamplona.quartz.nip5aStaticWebsites.siteDescription
import com.vitorpamplona.quartz.nip5aStaticWebsites.siteIcon
import com.vitorpamplona.quartz.nip5aStaticWebsites.sitePaths
import com.vitorpamplona.quartz.nip5aStaticWebsites.siteServers
import com.vitorpamplona.quartz.nip5aStaticWebsites.siteSource
import com.vitorpamplona.quartz.nip5aStaticWebsites.siteTitle
import com.vitorpamplona.quartz.nip5aStaticWebsites.tags.PathTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.NappletIcon
import com.vitorpamplona.quartz.nip5dNapplets.tags.NappletIntent
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * NIP-5D **named napplet** (kind 35129) — an addressable manifest identified by a
 * `d` tag, so one pubkey can publish many named napplets. See [NappletManifest] for
 * the tags it carries.
 */
@Immutable
class NamedNappletEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : BaseAddressableEvent(id, pubKey, createdAt, KIND, tags, content, sig),
    NappletManifest,
    SearchableEvent {
    override fun indexableContent() = listOfNotNull(title(), description()).joinToString("\n")

    // The read path: the same fields indexableContent() joins, handed over without
    // building the joined string a scan would throw away.
    override fun forEachIndexableField(visitor: IndexableFieldVisitor) {
        if (!visitor.visit(title())) return
        visitor.visit(description())
    }

    fun identifier() = dTag()

    companion object {
        const val KIND = 35129

        /**
         * A manifest for the single self-contained `/index.html` whose sha256 is [artifactHash].
         * [description] becomes the event content and MUST NOT be blank; [required] and [optional]
         * become the `R` and `O` tags.
         */
        fun build(
            identifier: String,
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
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<NamedNappletEvent>.() -> Unit = {},
        ): EventTemplate<NamedNappletEvent> {
            require(description.isNotBlank()) { "NIP-5D: a napplet manifest needs a non-empty description" }
            return eventTemplate(KIND, description, createdAt) {
                dTag(identifier)
                nappletArtifactHash(artifactHash)
                if (servers.isNotEmpty()) siteServers(servers)
                if (required.isNotEmpty()) nappletRequired(required)
                if (optional.isNotEmpty()) nappletOptional(optional)
                if (archetypes.isNotEmpty()) nappletArchetypes(archetypes)
                if (intents.isNotEmpty()) nappletIntents(intents)
                icon?.let { nappletIcon(it) }
                title?.let { siteTitle(it) }
                source?.let { siteSource(it) }
                initializer()
            }
        }

        @Deprecated(
            "NIP-5D napplets are now one self-contained /index.html addressed by a bare x tag, with the description " +
                "in content and R/O capability tags. Use the build(…, artifactHash, description, …) overload; publish a " +
                "multi-file app as a NIP-5A nsite.",
        )
        fun build(
            identifier: String,
            paths: List<PathTag>,
            servers: List<String> = emptyList(),
            requires: List<String> = emptyList(),
            title: String? = null,
            description: String? = null,
            source: String? = null,
            icon: String? = null,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<NamedNappletEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, "", createdAt) {
            dTag(identifier)
            sitePaths(paths)
            siteAggregateHash(paths)
            if (servers.isNotEmpty()) siteServers(servers)
            if (requires.isNotEmpty()) nappletRequires(requires)
            title?.let { siteTitle(it) }
            description?.let { siteDescription(it) }
            source?.let { siteSource(it) }
            icon?.let { siteIcon(it) }
            initializer()
        }
    }
}
