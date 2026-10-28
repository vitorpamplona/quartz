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

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip5dNapplets.tags.ArchetypeTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.ArtifactHashTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.IntentTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.NappletIcon
import com.vitorpamplona.quartz.nip5dNapplets.tags.NappletIconTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.NappletIntent
import com.vitorpamplona.quartz.nip5dNapplets.tags.OptionalNapTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.RequiredNapTag
import com.vitorpamplona.quartz.nip5dNapplets.tags.RequiresTag

/** Legacy `requires` tags. New manifests declare [nappletRequired] and [nappletOptional] instead. */
fun <T : Event> TagArrayBuilder<T>.nappletRequires(napNames: List<String>) = addAll(RequiresTag.assemble(napNames))

fun <T : Event> TagArrayBuilder<T>.nappletRequired(napNames: List<String>) = addAll(RequiredNapTag.assemble(napNames))

fun <T : Event> TagArrayBuilder<T>.nappletOptional(napNames: List<String>) = addAll(OptionalNapTag.assemble(napNames))

fun <T : Event> TagArrayBuilder<T>.nappletArchetypes(archetypes: List<String>) = addAll(ArchetypeTag.assemble(archetypes))

fun <T : Event> TagArrayBuilder<T>.nappletIntents(intents: List<NappletIntent>) = addAll(IntentTag.assemble(intents))

fun <T : Event> TagArrayBuilder<T>.nappletIcon(icon: NappletIcon) = addUnique(NappletIconTag.assemble(icon))

fun <T : Event> TagArrayBuilder<T>.nappletArtifactHash(artifactHash: HexKey) = addUnique(ArtifactHashTag.assemble(artifactHash))
