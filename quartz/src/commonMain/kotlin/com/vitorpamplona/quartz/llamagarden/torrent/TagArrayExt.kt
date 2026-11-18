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
package com.vitorpamplona.quartz.llamagarden.torrent

import com.vitorpamplona.quartz.llamagarden.tags.ByteSizeTag
import com.vitorpamplona.quartz.llamagarden.torrent.tags.BaseModelTag
import com.vitorpamplona.quartz.llamagarden.torrent.tags.CacheOnlyTag
import com.vitorpamplona.quartz.llamagarden.torrent.tags.CommitShaTag
import com.vitorpamplona.quartz.llamagarden.torrent.tags.DisplayNameTag
import com.vitorpamplona.quartz.llamagarden.torrent.tags.FileClassTag
import com.vitorpamplona.quartz.llamagarden.torrent.tags.HfMatchTag
import com.vitorpamplona.quartz.llamagarden.torrent.tags.HuggingFaceTag
import com.vitorpamplona.quartz.llamagarden.torrent.tags.LabTag
import com.vitorpamplona.quartz.llamagarden.torrent.tags.ModelCreatedAtTag
import com.vitorpamplona.quartz.llamagarden.torrent.tags.ModelKindTag
import com.vitorpamplona.quartz.llamagarden.torrent.tags.ModelNameTag
import com.vitorpamplona.quartz.llamagarden.torrent.tags.ModelRevisionTag
import com.vitorpamplona.quartz.llamagarden.torrent.tags.PieceLengthTag
import com.vitorpamplona.quartz.llamagarden.torrent.tags.PiecesTag
import com.vitorpamplona.quartz.llamagarden.torrent.tags.QuantBpwTag
import com.vitorpamplona.quartz.llamagarden.torrent.tags.QuantDetailTag
import com.vitorpamplona.quartz.llamagarden.torrent.tags.QuantDevTag
import com.vitorpamplona.quartz.llamagarden.torrent.tags.QuantTypeTag
import com.vitorpamplona.quartz.llamagarden.torrent.tags.RepoIdTag
import com.vitorpamplona.quartz.llamagarden.torrent.tags.SourceTag
import com.vitorpamplona.quartz.llamagarden.torrent.tags.SubfolderTag
import com.vitorpamplona.quartz.llamagarden.torrent.tags.TorrentCreatedTag
import com.vitorpamplona.quartz.llamagarden.torrent.tags.TorrentFileSizeTag
import com.vitorpamplona.quartz.llamagarden.torrent.tags.TorrentNameTag
import com.vitorpamplona.quartz.llamagarden.torrent.tags.TorrentToolTag
import com.vitorpamplona.quartz.llamagarden.torrent.tags.WebSeedTag
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.fastFirstNotNullOfOrNull
import com.vitorpamplona.quartz.nip35Torrents.tags.TrackerTag
import com.vitorpamplona.quartz.nip51Lists.tags.NameTag
import com.vitorpamplona.quartz.nip89AppHandlers.clientTag.ClientTag
import com.vitorpamplona.quartz.nip94FileMetadata.tags.HashSha256Tag
import com.vitorpamplona.quartz.nip94FileMetadata.tags.MagnetTag
import com.vitorpamplona.quartz.nip94FileMetadata.tags.MimeTypeTag
import com.vitorpamplona.quartz.nip94FileMetadata.tags.UrlTag

fun TagArray.magnet() = fastFirstNotNullOfOrNull(MagnetTag::parse)

fun TagArray.name() = fastFirstNotNullOfOrNull(NameTag::parse)

fun TagArray.totalSize() = fastFirstNotNullOfOrNull(ByteSizeTag::parse)

fun TagArray.pieces() = fastFirstNotNullOfOrNull(PiecesTag::parse)

fun TagArray.pieceLength() = fastFirstNotNullOfOrNull(PieceLengthTag::parse)

fun TagArray.torrentFileSha256() = fastFirstNotNullOfOrNull(HashSha256Tag::parse)

fun TagArray.torrentFileSize() = fastFirstNotNullOfOrNull(TorrentFileSizeTag::parse)

fun TagArray.torrentCreated() = fastFirstNotNullOfOrNull(TorrentCreatedTag::parse)

fun TagArray.mimeType() = fastFirstNotNullOfOrNull(MimeTypeTag::parse)

fun TagArray.torrentFileUrls() = mapNotNull(UrlTag::parse)

fun TagArray.webSeeds() = mapNotNull(WebSeedTag::parse)

fun TagArray.trackers() = mapNotNull(TrackerTag::parse)

fun TagArray.source() = fastFirstNotNullOfOrNull(SourceTag::parse)

fun TagArray.huggingFace() = fastFirstNotNullOfOrNull(HuggingFaceTag::parse)

fun TagArray.displayName() = fastFirstNotNullOfOrNull(DisplayNameTag::parse)

fun TagArray.fileClass() = fastFirstNotNullOfOrNull(FileClassTag::parse)

fun TagArray.modelKind() = fastFirstNotNullOfOrNull(ModelKindTag::parse)

fun TagArray.quantType() = fastFirstNotNullOfOrNull(QuantTypeTag::parse)

fun TagArray.quantDev() = fastFirstNotNullOfOrNull(QuantDevTag::parse)

fun TagArray.quantDetail() = fastFirstNotNullOfOrNull(QuantDetailTag::parse)

fun TagArray.quantBpw() = fastFirstNotNullOfOrNull(QuantBpwTag::parse)

fun TagArray.lab() = fastFirstNotNullOfOrNull(LabTag::parse)

fun TagArray.modelName() = fastFirstNotNullOfOrNull(ModelNameTag::parse)

fun TagArray.repoId() = fastFirstNotNullOfOrNull(RepoIdTag::parse)

fun TagArray.subfolder() = fastFirstNotNullOfOrNull(SubfolderTag::parse)

fun TagArray.torrentName() = fastFirstNotNullOfOrNull(TorrentNameTag::parse)

fun TagArray.baseModel() = fastFirstNotNullOfOrNull(BaseModelTag::parse)

fun TagArray.modelCreatedAt() = fastFirstNotNullOfOrNull(ModelCreatedAtTag::parse)

fun TagArray.modelRevision() = fastFirstNotNullOfOrNull(ModelRevisionTag::parse)

fun TagArray.commitSha() = fastFirstNotNullOfOrNull(CommitShaTag::parse)

fun TagArray.hfMatch() = fastFirstNotNullOfOrNull(HfMatchTag::parse)

fun TagArray.cacheOnly() = fastFirstNotNullOfOrNull(CacheOnlyTag::parse)

fun TagArray.torrentTool() = fastFirstNotNullOfOrNull(TorrentToolTag::parse)

fun TagArray.clientName() = fastFirstNotNullOfOrNull(ClientTag::parse)?.name
