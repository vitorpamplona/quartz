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
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip5aStaticWebsites.NappletIconPath
import com.vitorpamplona.quartz.nip5aStaticWebsites.SiteAggregateHash
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
import com.vitorpamplona.quartz.utils.Hex

/**
 * Common surface for the three NIP-5D napplet manifest kinds — snapshot
 * ([NappletSnapshotEvent] / 5129), root ([RootNappletEvent] / 15129), and named
 * ([NamedNappletEvent] / 35129). They differ only in replaceability and the `d`
 * identifier, so all accessors live here and read off the event's [tags].
 *
 * A napplet is one self-contained `/index.html`: the manifest's single `x` tag is that blob's
 * sha256 ([artifactHash]), `content` is its plain-text description, `R`/`O` declare the NAP
 * domains it needs or can use, `z`/`i` its archetypes and intents, and `icon` a content-addressed
 * image.
 *
 * Napplets published before that change carried the NIP-5A tag set instead: one `path` tag per
 * file, the `["x", …, "aggregate"]` hash over them, a `description` tag, `requires` tags and an
 * `icon` URL. The accessors read both, so [paths] is always the list of files to serve: the
 * manifest's own `path` tags, or the single artifact at [INDEX_PATH].
 */
interface NappletManifest {
    val tags: TagArray
    val content: String

    /**
     * The sha256 of the napplet's `/index.html`, from its single `x` tag. Null for a manifest in the
     * older multi-file form, or one that does not carry exactly one bare `x` tag.
     */
    fun artifactHash(): HexKey? = if (tags.sitePaths().isEmpty()) tags.nappletArtifactHash() else null

    /**
     * The files to serve, each mapping a request path to its blob's sha256: the manifest's `path`
     * tags in the older multi-file form, else the single artifact at [INDEX_PATH]. Empty when the
     * manifest names no content, which NIP-5D says to reject.
     */
    fun paths(): List<PathTag> {
        val paths = tags.sitePaths()
        if (paths.isNotEmpty()) return paths
        return tags.nappletArtifactHash()?.let { listOf(PathTag(INDEX_PATH, it)) } ?: emptyList()
    }

    /** Whether the manifest names content a runtime can fetch and verify: false means reject it. */
    fun isLoadable(): Boolean = paths().isNotEmpty()

    /** `server` tags hinting which Blossom servers hold the blobs. */
    fun servers(): List<String> = tags.siteServers()

    /**
     * NAP domains the napplet needs for full functionality: its `R` tags, plus the legacy
     * `requires` tags of older napplets.
     */
    fun requires(): List<String> = (tags.nappletRequired() + tags.nappletRequires()).distinct()

    /** `O` tags: NAP domains the napplet can use when the shell offers them. */
    fun optional(): List<String> = tags.nappletOptional()

    /** `z` tags: what the napplet says it does (`feed`, `profile`). Hints, never grants. */
    fun archetypes(): List<String> = tags.nappletArchetypes()

    /** `i` tags: the intents the napplet accepts, with their advertised parameters. Hints, never grants. */
    fun intents(): List<NappletIntent> = tags.nappletIntents()

    /** The aggregate hash declared in an older manifest's `["x", …, "aggregate"]` tag, or null when absent. */
    fun declaredAggregateHash(): HexKey? = tags.siteAggregateHash()

    fun title(): String? = tags.siteTitle()

    /** The plain-text description: the event `content`, or an older napplet's `description` tag. */
    fun description(): String? = content.ifBlank { null } ?: tags.siteDescription()

    fun source(): String? = tags.siteSource()

    /** The content-addressed `["icon", sha256, mime]` tag, when the manifest carries a valid one. */
    fun nappletIcon(): NappletIcon? = tags.nappletIcon()

    /** An older napplet's `["icon", url]` tag. Null for a content-addressed icon (see [nappletIcon]). */
    fun icon(): String? = tags.siteIcon()?.takeUnless { it.length == 64 && Hex.isHex64(it) }

    /**
     * The blob to show as this napplet's app icon: the declared [nappletIcon], else a conventional
     * `favicon`/`icon` file among [paths] (see [NappletIconPath]). Null when there is neither. The
     * caller fetches it by hash from [servers] and verifies it before drawing it.
     */
    fun iconBlob(): PathTag? = nappletIcon()?.let { PathTag("/icon." + it.extension(), it.hash) } ?: NappletIconPath.choose(paths())

    /** The NIP-5A aggregate hash recomputed from this manifest's [paths]. */
    fun computeAggregateHash(): HexKey = SiteAggregateHash.compute(paths())

    /**
     * The hash that names this exact build, for keying grants and caches: the [artifactHash], or an
     * older manifest's aggregate over its `path` tags.
     */
    fun contentHash(): HexKey = artifactHash() ?: declaredAggregateHash() ?: computeAggregateHash()

    /**
     * Verifies an older manifest's declared `x` aggregate against the one recomputed from its `path`
     * tags. Returns `true` when no aggregate is declared, including every single-artifact manifest:
     * there the `x` tag is the blob's own hash, which the runtime checks when it fetches the blob
     * (see `StaticSiteResolver`), as it must for every blob.
     */
    fun verifyAggregate(): Boolean = SiteAggregateHash.verify(paths(), declaredAggregateHash())

    companion object {
        /** Where a single-artifact napplet's `/index.html` is served. */
        const val INDEX_PATH = "/index.html"
    }
}
