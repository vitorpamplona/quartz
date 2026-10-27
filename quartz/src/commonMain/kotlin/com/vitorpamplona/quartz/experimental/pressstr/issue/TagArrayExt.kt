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
package com.vitorpamplona.quartz.experimental.pressstr.issue

import com.vitorpamplona.quartz.experimental.pressstr.issue.tags.IssueTag
import com.vitorpamplona.quartz.experimental.pressstr.magazine.MagazineEvent
import com.vitorpamplona.quartz.experimental.pressstr.tags.BlossomTag
import com.vitorpamplona.quartz.experimental.pressstr.tags.SizeTag
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip23LongContent.tags.ImageTag
import com.vitorpamplona.quartz.nip23LongContent.tags.PublishedAtTag
import com.vitorpamplona.quartz.nip23LongContent.tags.SummaryTag
import com.vitorpamplona.quartz.nip23LongContent.tags.TitleTag
import com.vitorpamplona.quartz.nip94FileMetadata.tags.HashSha256Tag
import com.vitorpamplona.quartz.nip94FileMetadata.tags.MimeTypeTag
import com.vitorpamplona.quartz.nip94FileMetadata.tags.UrlTag

private val MAGAZINE_KIND = MagazineEvent.KIND.toString()

fun TagArray.title() = firstNotNullOfOrNull(TitleTag::parse)

fun TagArray.issue() = firstNotNullOfOrNull(IssueTag::parse)

/** The magazine this issue belongs to: the first `a` tag that points at a kind 34609. */
fun TagArray.magazine() = firstNotNullOfOrNull { ATag.parseIfOfKind(it, MAGAZINE_KIND) }

fun TagArray.image() = firstNotNullOfOrNull(ImageTag::parse)

/** The primary copy of the file. */
fun TagArray.url() = firstNotNullOfOrNull(UrlTag::parse)

/** Every copy of the file, primary first, then the mirrors. */
fun TagArray.urls() = mapNotNull(UrlTag::parse)

fun TagArray.mimeType() = firstNotNullOfOrNull(MimeTypeTag::parse)

fun TagArray.summary() = firstNotNullOfOrNull(SummaryTag::parse)

fun TagArray.publishedAt() = firstNotNullOfOrNull(PublishedAtTag::parse)

fun TagArray.size() = firstNotNullOfOrNull(SizeTag::parse)

fun TagArray.hash() = firstNotNullOfOrNull(HashSha256Tag::parse)

fun TagArray.blossom() = firstNotNullOfOrNull(BlossomTag::parse)
