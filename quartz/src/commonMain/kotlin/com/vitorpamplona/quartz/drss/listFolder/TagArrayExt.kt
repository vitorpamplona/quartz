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
package com.vitorpamplona.quartz.drss.listFolder

import com.vitorpamplona.quartz.drss.listFolder.tags.ImportDateTag
import com.vitorpamplona.quartz.drss.listFolder.tags.ImportSourceTag
import com.vitorpamplona.quartz.drss.listFolder.tags.LanguageTag
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip51Lists.tags.TitleTag

fun TagArray.title() = firstNotNullOfOrNull(TitleTag::parse)

fun TagArray.language() = firstNotNullOfOrNull(LanguageTag::parse)

fun TagArray.importSource() = firstNotNullOfOrNull(ImportSourceTag::parse)

fun TagArray.importDate() = firstNotNullOfOrNull(ImportDateTag::parse)

fun TagArray.children() = mapNotNull(ATag::parse)

fun TagArray.childAddressIds() = mapNotNull(ATag::parseValidAddress)
