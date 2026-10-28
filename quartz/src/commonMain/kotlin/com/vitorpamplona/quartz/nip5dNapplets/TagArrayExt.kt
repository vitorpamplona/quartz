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
import com.vitorpamplona.quartz.nip01Core.core.fastForEach
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.nip5aStaticWebsites.tags.ServerTag
import com.vitorpamplona.quartz.nip5aStaticWebsites.tags.TitleTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.ArchetypeTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.ArtifactTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.IconTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.IntentTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.OptionalTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.RequiredTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.SourceTag

/**
 * The artifact sha256 from the manifest's `x` tag. NIP-5D: a manifest that does not carry exactly
 * one `x` tag MUST be rejected, so a second `x` tag, or one that isn't a bare lowercase sha256,
 * gives null.
 */
fun TagArray.artifact(): HexKey? {
    var found: Array<String>? = null
    fastForEach {
        if (ArtifactTag.isTag(it)) {
            if (found != null) return null
            found = it
        }
    }
    return found?.let(ArtifactTag::parse)
}

/** How many `d` tags the manifest carries: NIP-5D wants exactly one on a named napplet and none otherwise. */
fun TagArray.identifierCount() = count { it.has(0) && it[0] == "d" }

fun TagArray.required() = mapNotNull(RequiredTag::parse).distinct()

fun TagArray.optional() = mapNotNull(OptionalTag::parse).distinct()

fun TagArray.archetypes() = mapNotNull(ArchetypeTag::parse)

fun TagArray.intents() = mapNotNull(IntentTag::parse)

/** The `icon` tag, when there is exactly one and it is well formed; anything else falls back to generic artwork. */
fun TagArray.icon() = singleOrNullMatching(IconTag::isTag)?.let(IconTag::parse)

fun TagArray.servers() = mapNotNull(ServerTag::parse).distinct()

fun TagArray.title() = singleOrNullMatching { it.has(0) && it[0] == TitleTag.TAG_NAME }?.let(TitleTag::parse)

/** The `source` tag; null when absent, repeated, or not an accepted location. */
fun TagArray.source() = singleOrNullMatching(SourceTag::isTag)?.let(SourceTag::parse)

private inline fun TagArray.singleOrNullMatching(isTag: (Array<String>) -> Boolean): Array<String>? {
    var found: Array<String>? = null
    fastForEach {
        if (isTag(it)) {
            if (found != null) return null
            found = it
        }
    }
    return found
}
