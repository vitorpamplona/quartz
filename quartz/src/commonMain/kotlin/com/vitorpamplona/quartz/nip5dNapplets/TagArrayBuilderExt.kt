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
import com.vitorpamplona.quartz.nip5dNapplets.tags.SourceTag

fun <T : NappletManifest> TagArrayBuilder<T>.artifact(artifactHash: HexKey) = addUnique(ArtifactTag.assemble(artifactHash))

fun <T : NappletManifest> TagArrayBuilder<T>.required(napNames: List<String>) = addAll(RequiredTag.assemble(napNames.distinct()))

fun <T : NappletManifest> TagArrayBuilder<T>.optional(napNames: List<String>) = addAll(OptionalTag.assemble(napNames.distinct()))

fun <T : NappletManifest> TagArrayBuilder<T>.archetypes(archetypes: List<String>) = addAll(ArchetypeTag.assemble(archetypes))

fun <T : NappletManifest> TagArrayBuilder<T>.intents(intents: List<NappletIntent>) = addAll(IntentTag.assemble(intents))

fun <T : NappletManifest> TagArrayBuilder<T>.icon(icon: NappletIcon) = addUnique(IconTag.assemble(icon))

fun <T : NappletManifest> TagArrayBuilder<T>.servers(servers: List<String>) = addAll(ServerTag.assemble(servers.distinct()))

fun <T : NappletManifest> TagArrayBuilder<T>.title(title: String) = addUnique(TitleTag.assemble(title))

fun <T : NappletManifest> TagArrayBuilder<T>.source(location: String) = addUnique(SourceTag.assemble(location))
