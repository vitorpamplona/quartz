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
import com.vitorpamplona.quartz.nip5dNapplets.tags.ArchetypeTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.ArtifactHashTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.IntentTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.NappletIconTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.OptionalNapTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.RequiredNapTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.RequiresTag

/** Legacy `requires` tags, from napplets published before `R`/`O` replaced them. */
fun TagArray.nappletRequires() = mapNotNull(RequiresTag::parse)

/** `R` tags: NAP domains the napplet needs for full functionality. */
fun TagArray.nappletRequired() = mapNotNull(RequiredNapTag::parse)

/** `O` tags: NAP domains the napplet can use when offered. */
fun TagArray.nappletOptional() = mapNotNull(OptionalNapTag::parse)

fun TagArray.nappletArchetypes() = mapNotNull(ArchetypeTag::parse)

fun TagArray.nappletIntents() = mapNotNull(IntentTag::parse)

fun TagArray.nappletIcon() = firstNotNullOfOrNull(NappletIconTag::parse)

/**
 * The artifact sha256 from the manifest's `x` tag. NIP-5D: a manifest that does not carry exactly
 * one `x` tag MUST be rejected, so two `x` tags (or one that isn't a bare sha256) give null.
 */
fun TagArray.nappletArtifactHash(): HexKey? {
    var found: Array<String>? = null
    fastForEach {
        if (ArtifactHashTag.isTag(it)) {
            if (found != null) return null
            found = it
        }
    }
    return found?.let(ArtifactHashTag::parse)
}
