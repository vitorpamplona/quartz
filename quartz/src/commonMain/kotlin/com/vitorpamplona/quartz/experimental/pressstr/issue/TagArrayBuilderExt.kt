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
import com.vitorpamplona.quartz.experimental.pressstr.tags.BlossomTag
import com.vitorpamplona.quartz.experimental.pressstr.tags.SizeTag
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip23LongContent.tags.ImageTag
import com.vitorpamplona.quartz.nip23LongContent.tags.PublishedAtTag
import com.vitorpamplona.quartz.nip23LongContent.tags.SummaryTag
import com.vitorpamplona.quartz.nip23LongContent.tags.TitleTag
import com.vitorpamplona.quartz.nip94FileMetadata.tags.HashSha256Tag
import com.vitorpamplona.quartz.nip94FileMetadata.tags.MimeTypeTag
import com.vitorpamplona.quartz.nip94FileMetadata.tags.UrlTag
import com.vitorpamplona.quartz.nipB7Blossom.BlossomUri

fun TagArrayBuilder<MagazineIssueEvent>.title(title: String) = addUnique(TitleTag.assemble(title))

fun TagArrayBuilder<MagazineIssueEvent>.issue(issue: String) = addUnique(IssueTag.assemble(issue))

fun TagArrayBuilder<MagazineIssueEvent>.magazine(magazine: ATag) = addUnique(magazine.toATagArray())

fun TagArrayBuilder<MagazineIssueEvent>.image(url: String) = addUnique(ImageTag.assemble(url))

/** The first call is the primary copy; later calls add mirrors of the same file. */
fun TagArrayBuilder<MagazineIssueEvent>.url(url: String) = add(UrlTag.assemble(url))

/** The primary copy first, then its mirrors. */
fun TagArrayBuilder<MagazineIssueEvent>.urls(urls: List<String>) = addAll(UrlTag.assemble(urls))

fun TagArrayBuilder<MagazineIssueEvent>.mimeType(mimeType: String) = addUnique(MimeTypeTag.assemble(mimeType))

fun TagArrayBuilder<MagazineIssueEvent>.summary(summary: String) = addUnique(SummaryTag.assemble(summary))

fun TagArrayBuilder<MagazineIssueEvent>.publishedAt(timestamp: Long) = addUnique(PublishedAtTag.assemble(timestamp))

fun TagArrayBuilder<MagazineIssueEvent>.size(sizeInBytes: Long) = addUnique(SizeTag.assemble(sizeInBytes))

fun TagArrayBuilder<MagazineIssueEvent>.hash(sha256: String) = addUnique(HashSha256Tag.assemble(sha256))

fun TagArrayBuilder<MagazineIssueEvent>.blossom(uri: BlossomUri) = addUnique(BlossomTag.assemble(uri))
