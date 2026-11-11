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
package com.vitorpamplona.quartz.nip5aStaticWebsites.observer

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * One edition listed in an Observer site's `/editions.json`
 * (`quartz/plans/2026-11-11-observer-site.md`). [day], [path] and [sha256] are required; the
 * rest describe the edition for a reader's history and may be absent. Cost and token counts
 * are private and never published, so there is no field for them.
 */
@Immutable
@Serializable
data class ObserverEdition(
    /** The UTC date of the print, `YYYY-MM-DD`. */
    val day: String,
    /** The edition's file in the site, `/YYYY-MM-DD.html`. */
    val path: String,
    /** Lowercase hex sha256 of the edition's HTML: the Blossom blob that serves [path]. */
    val sha256: String,
    val headline: String? = null,
    val code: String? = null,
    /** Unix seconds. */
    val printedAt: Long? = null,
    val posts: Int? = null,
    val quotesVerified: Int? = null,
    val model: String? = null,
)

/** The whole `/editions.json`: newest edition first. */
@Immutable
@Serializable
data class ObserverEditions(
    val v: Int = ObserverSite.FORMAT_VERSION,
    val title: String? = null,
    val editions: List<ObserverEdition> = emptyList(),
)
