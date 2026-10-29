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
package com.vitorpamplona.quartz.experimental.ps1saves

import com.vitorpamplona.quartz.experimental.ps1saves.tags.BlockStateTag
import com.vitorpamplona.quartz.experimental.ps1saves.tags.BlockTag
import com.vitorpamplona.quartz.experimental.ps1saves.tags.CardTag
import com.vitorpamplona.quartz.experimental.ps1saves.tags.FilenameTag
import com.vitorpamplona.quartz.experimental.ps1saves.tags.RegionTag
import com.vitorpamplona.quartz.experimental.ps1saves.tags.TitleTag
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip94FileMetadata.tags.HashSha256Tag

fun TagArrayBuilder<Ps1SaveEvent>.memoryCard(cardId: String) = addUnique(CardTag.assemble(cardId))

fun TagArrayBuilder<Ps1SaveEvent>.blockNumber(block: Int) = addUnique(BlockTag.assemble(block))

fun TagArrayBuilder<Ps1SaveEvent>.blockHash(sha256: HexKey) = addUnique(HashSha256Tag.assemble(sha256))

fun TagArrayBuilder<Ps1SaveEvent>.blockState(state: String) = addUnique(BlockStateTag.assemble(state))

fun TagArrayBuilder<Ps1SaveEvent>.saveFilename(filename: String) = addUnique(FilenameTag.assemble(filename))

fun TagArrayBuilder<Ps1SaveEvent>.saveRegion(region: String) = addUnique(RegionTag.assemble(region))

fun TagArrayBuilder<Ps1SaveEvent>.saveTitle(title: String) = addUnique(TitleTag.assemble(title))
