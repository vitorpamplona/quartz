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
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip5aStaticWebsites.tags.ServerTag
import com.vitorpamplona.quartz.nip5aStaticWebsites.tags.TitleTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.ArchetypeTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.ArtifactTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.IconTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.IntentTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.NappletIcon
import com.vitorpamplona.quartz.nip5dNapplets.tags.NappletIntent
import com.vitorpamplona.quartz.nip5dNapplets.tags.OptionalTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.RequiredTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.ServerOrigin
import com.vitorpamplona.quartz.nip5dNapplets.tags.SourceTag

// Each writer keeps only what the matching reader in TagArrayExt accepts back, so a built manifest
// never carries a tag that silently disappears on read: hashes are lowercased, blank values and
// values the reader refuses (a non-origin server, a refused source, an unsupported icon) are dropped.

/** The artifact hash, lowercased: NIP-5D wants lowercase hex, and the reader refuses anything else. */
fun <T : NappletManifest> TagArrayBuilder<T>.artifact(artifactHash: HexKey) = addUnique(ArtifactTag.assemble(artifactHash.lowercase()))

fun <T : NappletManifest> TagArrayBuilder<T>.required(napNames: List<String>) = addAll(RequiredTag.assemble(napNames.filter { it.isNotEmpty() }.distinct()))

fun <T : NappletManifest> TagArrayBuilder<T>.optional(napNames: List<String>) = addAll(OptionalTag.assemble(napNames.filter { it.isNotEmpty() }.distinct()))

fun <T : NappletManifest> TagArrayBuilder<T>.archetypes(archetypes: List<String>) = addAll(ArchetypeTag.assemble(archetypes.filter { it.isNotEmpty() }))

fun <T : NappletManifest> TagArrayBuilder<T>.intents(intents: List<NappletIntent>) = addAll(IntentTag.assemble(intents.filter { it.intent.isNotEmpty() }))

fun <T : NappletManifest> TagArrayBuilder<T>.icon(icon: NappletIcon): TagArrayBuilder<T> {
    val tag = IconTag.assemble(NappletIcon(icon.hash.lowercase(), icon.mimeType.lowercase()))
    return if (IconTag.parse(tag) != null) addUnique(tag) else this
}

fun <T : NappletManifest> TagArrayBuilder<T>.servers(servers: List<String>) = addAll(ServerTag.assemble(servers.filter(ServerOrigin::isValid).distinct()))

fun <T : NappletManifest> TagArrayBuilder<T>.title(title: String) = if (title.isNotBlank()) addUnique(TitleTag.assemble(title)) else this

fun <T : NappletManifest> TagArrayBuilder<T>.source(location: String) = if (SourceTag.isLocation(location)) addUnique(SourceTag.assemble(location)) else this

/** Writes the tags all three manifest kinds share; each kind's `build()` adds its own (`d`, lineage). */
fun <T : NappletManifest> TagArrayBuilder<T>.manifest(
    artifactHash: HexKey,
    servers: List<String>,
    required: List<String>,
    optional: List<String>,
    archetypes: List<String>,
    intents: List<NappletIntent>,
    icon: NappletIcon?,
    title: String?,
    source: String?,
) {
    artifact(artifactHash)
    if (servers.isNotEmpty()) servers(servers)
    if (required.isNotEmpty()) required(required)
    if (optional.isNotEmpty()) optional(optional)
    if (archetypes.isNotEmpty()) archetypes(archetypes)
    if (intents.isNotEmpty()) intents(intents)
    icon?.let { icon(it) }
    title?.let { title(it) }
    source?.let { source(it) }
}
