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
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip35Torrents.tags.TrackerTag
import com.vitorpamplona.quartz.nip51Lists.tags.NameTag
import com.vitorpamplona.quartz.nip94FileMetadata.tags.HashSha256Tag
import com.vitorpamplona.quartz.nip94FileMetadata.tags.MagnetTag
import com.vitorpamplona.quartz.nip94FileMetadata.tags.MimeTypeTag
import com.vitorpamplona.quartz.nip94FileMetadata.tags.UrlTag

fun TagArrayBuilder<ModelTorrentEvent>.magnet(magnet: String) = addUnique(MagnetTag.assemble(magnet))

fun TagArrayBuilder<ModelTorrentEvent>.name(name: String) = addUnique(NameTag.assemble(name))

fun TagArrayBuilder<ModelTorrentEvent>.totalSize(bytes: Long) = addUnique(ByteSizeTag.assemble(bytes))

fun TagArrayBuilder<ModelTorrentEvent>.pieces(count: Long) = addUnique(PiecesTag.assemble(count))

fun TagArrayBuilder<ModelTorrentEvent>.pieceLength(bytes: Long) = addUnique(PieceLengthTag.assemble(bytes))

fun TagArrayBuilder<ModelTorrentEvent>.torrentFileSha256(sha256: String) = addUnique(HashSha256Tag.assemble(sha256))

fun TagArrayBuilder<ModelTorrentEvent>.torrentFileSize(bytes: Long) = addUnique(TorrentFileSizeTag.assemble(bytes))

fun TagArrayBuilder<ModelTorrentEvent>.torrentCreated(iso8601: String) = addUnique(TorrentCreatedTag.assemble(iso8601))

fun TagArrayBuilder<ModelTorrentEvent>.mimeType(mimeType: String) = addUnique(MimeTypeTag.assemble(mimeType))

fun TagArrayBuilder<ModelTorrentEvent>.torrentFileUrls(urls: List<String>) = addAll(urls.map { UrlTag.assemble(it) })

fun TagArrayBuilder<ModelTorrentEvent>.webSeeds(urls: List<String>) = addAll(urls.map { WebSeedTag.assemble(it) })

fun TagArrayBuilder<ModelTorrentEvent>.trackers(urls: List<String>) = addAll(urls.distinct().map { TrackerTag.assemble(it) })

fun TagArrayBuilder<ModelTorrentEvent>.source(source: String) = addUnique(SourceTag.assemble(source))

fun TagArrayBuilder<ModelTorrentEvent>.huggingFace(url: String) = addUnique(HuggingFaceTag.assemble(url))

fun TagArrayBuilder<ModelTorrentEvent>.displayName(name: String) = addUnique(DisplayNameTag.assemble(name))

fun TagArrayBuilder<ModelTorrentEvent>.fileClass(fileClass: String) = addUnique(FileClassTag.assemble(fileClass))

fun TagArrayBuilder<ModelTorrentEvent>.modelKind(kind: String) = addUnique(ModelKindTag.assemble(kind))

fun TagArrayBuilder<ModelTorrentEvent>.quantType(type: String) = addUnique(QuantTypeTag.assemble(type))

fun TagArrayBuilder<ModelTorrentEvent>.quantDev(org: String) = addUnique(QuantDevTag.assemble(org))

fun TagArrayBuilder<ModelTorrentEvent>.quantDetail(token: String) = addUnique(QuantDetailTag.assemble(token))

fun TagArrayBuilder<ModelTorrentEvent>.quantBpw(bitsPerWeight: Double) = addUnique(QuantBpwTag.assemble(bitsPerWeight))

fun TagArrayBuilder<ModelTorrentEvent>.lab(lab: String) = addUnique(LabTag.assemble(lab))

fun TagArrayBuilder<ModelTorrentEvent>.modelName(name: String) = addUnique(ModelNameTag.assemble(name))

fun TagArrayBuilder<ModelTorrentEvent>.repoId(repoId: String) = addUnique(RepoIdTag.assemble(repoId))

fun TagArrayBuilder<ModelTorrentEvent>.subfolder(path: String) = addUnique(SubfolderTag.assemble(path))

fun TagArrayBuilder<ModelTorrentEvent>.torrentName(name: String) = addUnique(TorrentNameTag.assemble(name))

fun TagArrayBuilder<ModelTorrentEvent>.baseModel(repoId: String) = addUnique(BaseModelTag.assemble(repoId))

fun TagArrayBuilder<ModelTorrentEvent>.modelCreatedAt(createdAt: String) = addUnique(ModelCreatedAtTag.assemble(createdAt))

fun TagArrayBuilder<ModelTorrentEvent>.modelRevision(revision: String) = addUnique(ModelRevisionTag.assemble(revision))

fun TagArrayBuilder<ModelTorrentEvent>.commitSha(sha: String) = addUnique(CommitShaTag.assemble(sha))

fun TagArrayBuilder<ModelTorrentEvent>.hfMatch(match: String) = addUnique(HfMatchTag.assemble(match))

fun TagArrayBuilder<ModelTorrentEvent>.cacheOnly() = addUnique(CacheOnlyTag.assemble("1"))

fun TagArrayBuilder<ModelTorrentEvent>.torrentTool(tool: String) = addUnique(TorrentToolTag.assemble(tool))
