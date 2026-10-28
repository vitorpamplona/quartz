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
package com.vitorpamplona.quartz.drss.provenance

import com.vitorpamplona.quartz.drss.provenance.tags.SourceTag
import com.vitorpamplona.quartz.drss.provenance.tags.SourceTypeTag
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip32Labeling.tags.LabelNamespaceTag
import com.vitorpamplona.quartz.nip32Labeling.tags.LabelTag
import com.vitorpamplona.quartz.nip51Lists.tags.TitleTag

fun TagArrayBuilder<DrssProvenanceEvent>.provenanceLabel() =
    addUnique(LabelNamespaceTag.assemble(DrssProvenanceEvent.NAMESPACE))
        .addUnique(LabelTag.assemble(DrssProvenanceEvent.LABEL, DrssProvenanceEvent.NAMESPACE))

fun TagArrayBuilder<DrssProvenanceEvent>.feed(feed: PTag) = addUnique(feed.toTagArray())

fun TagArrayBuilder<DrssProvenanceEvent>.foundThrough(article: ATag) = addUnique(article.toATagArray())

fun TagArrayBuilder<DrssProvenanceEvent>.source(source: String) = addUnique(SourceTag.assemble(source))

fun TagArrayBuilder<DrssProvenanceEvent>.sourceType(type: String) = addUnique(SourceTypeTag.assemble(type))

fun TagArrayBuilder<DrssProvenanceFolderEvent>.title(title: String) = addUnique(TitleTag.assemble(title))

fun TagArrayBuilder<DrssProvenanceFolderEvent>.children(children: List<ATag>) = addAll(children.map { it.toATagArray() })
