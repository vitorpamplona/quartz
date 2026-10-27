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
package com.vitorpamplona.quartz.experimental.pressstr.ebook

import com.vitorpamplona.quartz.experimental.pressstr.ebook.tags.BlossomTag
import com.vitorpamplona.quartz.experimental.pressstr.ebook.tags.IsbnTag
import com.vitorpamplona.quartz.experimental.pressstr.ebook.tags.LanguageTag
import com.vitorpamplona.quartz.experimental.pressstr.ebook.tags.SizeTag
import com.vitorpamplona.quartz.experimental.publications.tags.AuthorTag
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip23LongContent.tags.ImageTag
import com.vitorpamplona.quartz.nip23LongContent.tags.PublishedAtTag
import com.vitorpamplona.quartz.nip23LongContent.tags.SummaryTag
import com.vitorpamplona.quartz.nip23LongContent.tags.TitleTag
import com.vitorpamplona.quartz.nip94FileMetadata.tags.HashSha256Tag
import com.vitorpamplona.quartz.nip94FileMetadata.tags.MimeTypeTag
import com.vitorpamplona.quartz.nip94FileMetadata.tags.UrlTag
import com.vitorpamplona.quartz.nipB7Blossom.BlossomUri

fun TagArrayBuilder<EbookEvent>.title(title: String) = addUnique(TitleTag.assemble(title))

fun TagArrayBuilder<EbookEvent>.image(url: String) = addUnique(ImageTag.assemble(url))

/** The first call is the primary copy; later calls add mirrors of the same file. */
fun TagArrayBuilder<EbookEvent>.url(url: String) = add(UrlTag.assemble(url))

/** The primary copy first, then its mirrors. */
fun TagArrayBuilder<EbookEvent>.urls(urls: List<String>) = addAll(UrlTag.assemble(urls))

fun TagArrayBuilder<EbookEvent>.mimeType(mimeType: String) = addUnique(MimeTypeTag.assemble(mimeType))

fun TagArrayBuilder<EbookEvent>.author(name: String) = add(AuthorTag.assemble(name))

fun TagArrayBuilder<EbookEvent>.authors(names: List<String>) = addAll(AuthorTag.assemble(names))

fun TagArrayBuilder<EbookEvent>.summary(summary: String) = addUnique(SummaryTag.assemble(summary))

fun TagArrayBuilder<EbookEvent>.publishedAt(timestamp: Long) = addUnique(PublishedAtTag.assemble(timestamp))

fun TagArrayBuilder<EbookEvent>.size(sizeInBytes: Long) = addUnique(SizeTag.assemble(sizeInBytes))

fun TagArrayBuilder<EbookEvent>.hash(sha256: String) = addUnique(HashSha256Tag.assemble(sha256))

fun TagArrayBuilder<EbookEvent>.language(iso639: String) = addUnique(LanguageTag.assemble(iso639))

fun TagArrayBuilder<EbookEvent>.isbn(isbn: String) = addUnique(IsbnTag.assemble(isbn))

fun TagArrayBuilder<EbookEvent>.blossom(uri: BlossomUri) = addUnique(BlossomTag.assemble(uri))
