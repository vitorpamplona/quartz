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

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.IEvent
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip50Search.IndexableFieldVisitor
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.nip5aStaticWebsites.tags.PathTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.NappletIcon
import com.vitorpamplona.quartz.nip5dNapplets.tags.NappletIntent

/**
 * What the three NIP-5D napplet manifest kinds share: snapshot
 * ([com.vitorpamplona.quartz.nip5dNapplets.snapshot.NappletSnapshotEvent], 5129), root
 * ([com.vitorpamplona.quartz.nip5dNapplets.root.RootNappletEvent], 15129) and named
 * ([com.vitorpamplona.quartz.nip5dNapplets.named.NamedNappletEvent], 35129).
 *
 * A napplet is one self-contained `/index.html`. The manifest names it by its sha256 in a single
 * `x` tag ([artifact]), describes it in plain-text `content` ([description]), and declares the NAP
 * domains it needs (`R`, [required]) or can use (`O`, [optional]), what it does (`z`, [archetypes]),
 * the intents it accepts (`i`, [intents]), a content-addressed [icon], Blossom [servers], a [title]
 * and a [source] location. Declarations are routing hints from untrusted content, never grants.
 */
interface NappletManifest :
    IEvent,
    SearchableEvent {
    val tags: TagArray
    val content: String

    /** The sha256 of the napplet's `/index.html`, or null when the manifest lacks exactly one valid `x` tag. */
    fun artifact(): HexKey? = tags.artifact()

    /** The artifact as the file a static-site resolver serves: `/index.html` mapped to [artifact]. */
    fun artifactFile(): PathTag? = artifact()?.let { PathTag(INDEX_PATH, it) }

    /**
     * Whether the manifest meets NIP-5D's MUSTs: exactly one `x` tag and a non-empty description
     * (each kind adds its own `d` and lineage rules). A runtime MUST reject one that doesn't; it
     * MUST still verify the signature and the blob's hash.
     */
    fun isValid(): Boolean = artifact() != null && content.isNotBlank()

    /** The plain-text description. Clients MUST treat it as plain text, not HTML or Markdown. */
    fun description(): String = content

    fun title(): String? = tags.title()

    fun servers(): List<String> = tags.servers()

    fun source(): String? = tags.source()

    fun icon(): NappletIcon? = tags.icon()

    /** `R`: NAP domains the napplet needs for full functionality. */
    fun required(): List<String> = tags.required()

    /** `O`: NAP domains the napplet integrates with when offered. */
    fun optional(): List<String> = tags.optional()

    /** `z`: what the napplet says it does (`feed`, `profile`). */
    fun archetypes(): List<String> = tags.archetypes()

    /** `i`: the intents the napplet accepts, with their advertised parameter names. */
    fun intents(): List<NappletIntent> = tags.intents()

    /**
     * The [required] domains missing from [available]. Compatibility is `required ⊆ available`: a
     * shell SHOULD reject the napplet, or warn, when this is not empty. Optional domains never count.
     */
    fun missingRequired(available: Set<String>): List<String> = required().filter { it !in available }

    // NIP-50: what the manifest says about itself, its title and description.
    override fun indexableContent() = listOfNotNull(title(), description()).joinToString("\n")

    // The read path: the same fields indexableContent() joins, handed over without building the
    // joined string a scan would throw away.
    override fun forEachIndexableField(visitor: IndexableFieldVisitor) {
        if (!visitor.visit(title())) return
        visitor.visit(description())
    }

    companion object {
        /** Where the artifact is served. */
        const val INDEX_PATH = "/index.html"
    }
}
