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
package com.vitorpamplona.quartz.routstr.node

import com.vitorpamplona.quartz.experimental.nip82SoftwareApps.release.tags.VersionTag
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.fastForEach
import com.vitorpamplona.quartz.nip60Cashu.tags.CashuMintTag
import com.vitorpamplona.quartz.routstr.node.tags.EndpointTag
import com.vitorpamplona.quartz.routstr.node.tags.ModelsTag

fun TagArray.endpointUrls() = mapNotNull(EndpointTag::parse)

fun TagArray.endpoints() = mapNotNull { EndpointTag.parse(it)?.let(RoutstrEndpoint::classify) }

fun TagArray.mints() = mapNotNull(CashuMintTag::parse).filter { it.isNotBlank() }

fun TagArray.version() = firstNotNullOfOrNull(VersionTag::parse)

/** Every id of every `models` tag, in order, without repeats; empty when the node lists none. */
fun TagArray.models(): List<String> {
    val models = LinkedHashSet<String>()
    fastForEach { tag -> ModelsTag.parse(tag)?.let { models.addAll(it) } }
    return if (models.isEmpty()) emptyList() else models.toList()
}
