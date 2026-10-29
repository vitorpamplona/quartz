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
package com.vitorpamplona.quartz.drss

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.fastFirstNotNullOfOrNull
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.update
import com.vitorpamplona.quartz.nip51Lists.tags.ImageTag
import com.vitorpamplona.quartz.nip94FileMetadata.tags.ThumbTag
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * A list's picture as drss.io writes it on its folders and NIP-51 sets: `image`, the original,
 * and `thumb`, a small copy (about 192 px on the shorter side) with its SHA-256, which readers load
 * first. A list may have a picture with no thumbnail.
 */
object DrssListPicture {
    fun image(tags: TagArray): String? = tags.fastFirstNotNullOfOrNull(ImageTag::parse)

    fun thumb(tags: TagArray): ThumbTag? = tags.fastFirstNotNullOfOrNull(ThumbTag::parse)

    /** What to draw small: the thumbnail when there is one, else the picture. */
    fun preview(tags: TagArray): String? = thumb(tags)?.imageUrl ?: image(tags)

    /**
     * [list] with [image] as its picture and [thumb] beside it, replacing both; a null [image]
     * takes the picture away. A new picture without a new thumbnail drops the old thumbnail, which
     * would show the old picture.
     */
    fun <T : Event> set(
        list: T,
        image: String?,
        thumb: ThumbTag? = null,
        createdAt: Long = TimeUtils.now(),
    ): EventTemplate<T> =
        list.update(createdAt) {
            remove(ImageTag.TAG_NAME)
            remove(ThumbTag.TAG_NAME)
            if (image != null) {
                add(ImageTag.assemble(image))
                thumb?.let { add(ThumbTag.assemble(it.imageUrl, it.hash)) }
            }
        }
}
