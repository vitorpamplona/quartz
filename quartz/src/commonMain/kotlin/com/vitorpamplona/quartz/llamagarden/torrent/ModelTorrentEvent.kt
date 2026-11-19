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

import androidx.compose.runtime.Immutable
import com.vitorpamplona.crypto.sha2.sha256
import com.vitorpamplona.quartz.llamagarden.torrent.tags.FileClassTag
import com.vitorpamplona.quartz.llamagarden.torrent.tags.ModelKindTag
import com.vitorpamplona.quartz.nip01Core.core.BaseAddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.core.fastAny
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.dTag.dTag
import com.vitorpamplona.quartz.nip50Search.IndexableFieldVisitor
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.nip51Lists.tags.NameTag
import com.vitorpamplona.quartz.nip94FileMetadata.tags.MagnetTag
import com.vitorpamplona.quartz.nip94FileMetadata.tags.UrlTag
import com.vitorpamplona.quartz.utils.Hex
import com.vitorpamplona.quartz.utils.TimeUtils
import com.vitorpamplona.quartz.utils.UrlEncoder

/**
 * A llama.garden model torrent listing (kind 30099): a BitTorrent v1 torrent of a HuggingFace model,
 * with HuggingFace (and llama.garden's HTTP "pumps") as web seeds, and the `.torrent` file on
 * Blossom. See https://github.com/etemiz/llama.garden.
 *
 * Two shapes are in the wild:
 * - **Catalog listings** from llama.garden's builders (`post-torr-nostr-j.py`, `cache-to-torr-nostr.py`):
 *   `d` is the infohash; `magnet`, `name`, `size`, `pieces`, `piece_length`, `x` (sha256 of the
 *   `.torrent`), `url` mirrors of the `.torrent`, `webseed`s, `tracker`s, and the model metadata
 *   (`display_name`, `file_class`, `model_kind`, `quant_*`, `lab`, `repo_id`, `base_model`, …).
 * - **User submissions** from the viewer's "Add a torrent" form: `d` is `user:<pubkey>:<sha256(url:name)>`,
 *   a `url` to the `.torrent`, a `name`, and optionally `size`, `source`, `hf`, `file_class`, … There
 *   is no magnet or infohash until someone opens the `.torrent`.
 *
 * Both are mined with a NIP-13 `nonce` that names no target difficulty.
 *
 * **Kind 30099 is shared**: nostr-golded publishes its update manifests on it (`code`, `v`, JSON
 * content). [isModelTorrent] tells them apart and EventFactory parses the rest as
 * [UnrecognizedKind30099Event].
 */
@Immutable
class ModelTorrentEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: TagArray,
    content: String,
    sig: HexKey,
) : BaseAddressableEvent(id, pubKey, createdAt, KIND, tags, content, sig),
    SearchableEvent {
    /**
     * The BitTorrent v1 infohash (lower-case hex): the `d` of a catalog listing, else the magnet's
     * `btih`. Null for a user submission, whose `d` is not a hash.
     */
    fun infoHash(): String? = dTag().takeIf(::isInfoHash)?.lowercase() ?: magnet()?.let(::infoHashFromMagnet)

    /** True for a catalog listing, whose `d` is the infohash; false for a user submission. */
    fun isCatalogListing() = isInfoHash(dTag())

    fun magnet() = tags.magnet()

    /** The torrent's stem name (`Qwen_Qwen3.6-27B---Q6_K---bartowski-…`) or the name a user typed. */
    fun name() = tags.name()

    /** Total size of the torrent's content, in bytes. */
    fun totalSize() = tags.totalSize()

    fun pieces() = tags.pieces()

    fun pieceLength() = tags.pieceLength()

    /** SHA-256 (hex) of the `.torrent` file, to check a download from [torrentFileUrls] against. */
    fun torrentFileSha256() = tags.torrentFileSha256()

    fun torrentFileSize() = tags.torrentFileSize()

    fun torrentCreated() = tags.torrentCreated()

    fun mimeType() = tags.mimeType()

    /** Where to download the `.torrent` file (Blossom mirrors, or the URL a user submitted). */
    fun torrentFileUrls() = tags.torrentFileUrls()

    fun webSeeds() = tags.webSeeds()

    fun trackers() = tags.trackers()

    fun source() = tags.source()

    fun displayName() = tags.displayName()

    /** The title llama.garden's viewer shows: [displayName], else [name], else the infohash's first 12 characters. */
    fun title(): String? = displayName() ?: name() ?: infoHash()?.take(12)

    /** `base`, `fine tune`, `quant` or `n/a`; see [FileClassTag]. */
    fun fileClass() = tags.fileClass()

    fun isQuant() = fileClass() == FileClassTag.QUANT

    /** `base`, `fine tune` or `n/a`; see [ModelKindTag]. */
    fun modelKind() = tags.modelKind()

    /**
     * What the model is, ignoring quantization, the way llama.garden's Type filter reads it: [modelKind],
     * else `base` or `fine tune` from [fileClass], `base` for a quant that does not say, and null for a
     * listing with neither tag.
     */
    fun effectiveModelKind(): String? =
        modelKind() ?: when (fileClass()) {
            FileClassTag.BASE, FileClassTag.QUANT -> ModelKindTag.BASE
            FileClassTag.FINE_TUNE -> ModelKindTag.FINE_TUNE
            else -> null
        }

    fun quantType() = tags.quantType()

    fun quantDev() = tags.quantDev()

    fun quantDetail() = tags.quantDetail()

    fun quantBpw() = tags.quantBpw()

    fun lab() = tags.lab()

    fun modelName() = tags.modelName()

    /** The HuggingFace repository id: the `repo_id` tag, else parsed out of a `huggingface.co/<org>/<repo>` [source] or `hf` URL. */
    fun repoId(): String? = tags.repoId() ?: source()?.let(::repoIdFromUrl) ?: tags.huggingFace()?.let(::repoIdFromUrl)

    /** A link to the model on HuggingFace: the `hf` URL a user gave, else one built from [repoId]. */
    fun huggingFaceUrl(): String? = tags.huggingFace() ?: repoId()?.let { "https://huggingface.co/$it" }

    fun subfolder() = tags.subfolder()

    fun torrentName() = tags.torrentName()

    /** The repository id (`Qwen/Qwen3.6-27B`) of the model this quant or fine tune derives from. */
    fun baseModel() = tags.baseModel()

    fun modelCreatedAt() = tags.modelCreatedAt()

    fun modelRevision() = tags.modelRevision()

    fun commitSha() = tags.commitSha()

    /** `yes`, `no` or `unknown`: whether the files match the HuggingFace repository. */
    fun hfMatch() = tags.hfMatch()

    fun isCacheOnly() = tags.cacheOnly() == "1"

    fun torrentTool() = tags.torrentTool()

    /** The publishing client (`waifu-magnet-26`), when it says. */
    fun clientName() = tags.clientName()

    /**
     * A magnet link: the event's own `magnet`, else one built from [infoHash] with the name, size,
     * trackers and web seeds. Null when there is no infohash (a user submission before its `.torrent`
     * is read). Never carries `xs=`: add a verified `.torrent` URL with [magnetWithExactSource].
     */
    fun magnetLink(): String? {
        val hash = infoHash() ?: return null
        // The signed `d` decides what is downloaded: a `magnet` for another hash (or not a magnet at
        // all) is ignored in favour of one built from the listing's own fields.
        magnet()?.let { if (it.startsWith(MAGNET_PREFIX, ignoreCase = true) && infoHashFromMagnet(it) == hash) return it }
        return buildString {
            append("magnet:?xt=urn:btih:")
            append(hash)
            title()?.let { append("&dn=").append(UrlEncoder.encode(it)) }
            totalSize()?.let { append("&xl=").append(it) }
            trackers().forEach { append("&tr=").append(UrlEncoder.encode(it)) }
            webSeeds().forEach { append("&ws=").append(UrlEncoder.encode(it)) }
        }
    }

    /**
     * [magnetLink] with a BEP 9 exact source (`xs=`) pointing at [verifiedTorrentUrl]. Pass only a
     * URL whose bytes hashed to [torrentFileSha256]: llama.garden's viewer appends `xs=` only after
     * that check, so a tampered mirror can't reach the BitTorrent client.
     */
    fun magnetWithExactSource(verifiedTorrentUrl: String): String? = magnetLink()?.let { "$it&xs=${UrlEncoder.encode(verifiedTorrentUrl)}" }

    override fun indexableContent(): String {
        val out = StringBuilder()
        forEachIndexableField { field ->
            if (field != null) {
                if (out.isNotEmpty()) out.append('\n')
                out.append(field)
            }
            true
        }
        return out.toString()
    }

    // The read path: each field once, in order, skipping repeats (a quant's lab is often its
    // quantizer), without the lists a joined string would build.
    override fun forEachIndexableField(visitor: IndexableFieldVisitor) {
        val display = displayName()
        val name = name()
        val model = modelName()
        val lab = lab()
        val repo = repoId()
        val base = baseModel()
        val detail = quantDetail()
        val dev = quantDev()
        val title = display ?: name ?: infoHash()?.take(12)
        if (!visitor.visit(title)) return
        if (name != title && !visitor.visit(name)) return
        if (model != title && model != name && !visitor.visit(model)) return
        if (lab != null && lab != model && !visitor.visit(lab)) return
        if (repo != null && !visitor.visit(repo)) return
        if (base != null && base != repo && !visitor.visit(base)) return
        if (detail != null && !visitor.visit(detail)) return
        if (dev != null && dev != lab && !visitor.visit(dev)) return
    }

    companion object {
        const val KIND = 30099
        const val TORRENT_MIME_TYPE = "application/x-bittorrent"

        /** The prefix of a user submission's `d`: `user:<pubkey>:<sha256(url:name)>`. */
        const val USER_SUBMISSION_PREFIX = "user:"

        /** True for llama.garden's shapes: a `name` and either a `magnet` or a `.torrent` `url`. */
        fun isModelTorrent(tags: TagArray): Boolean =
            tags.fastAny { NameTag.parse(it) != null } &&
                tags.fastAny { MagnetTag.parse(it) != null || UrlTag.isTag(it) }

        private const val MAGNET_PREFIX = "magnet:?"

        /**
         * A BitTorrent v1 infohash (40 hex), what llama.garden's builders write. A v2 hash would need a
         * `btmh` magnet, which nothing here writes.
         */
        fun isInfoHash(value: String): Boolean = value.length == 40 && Hex.isHex(value)

        /** The `btih` of a `magnet:?xt=urn:btih:<hash>` link, lower-cased, when it is hex. */
        fun infoHashFromMagnet(magnet: String): String? {
            val start = magnet.indexOf("urn:btih:", ignoreCase = true)
            if (start < 0) return null
            val from = start + "urn:btih:".length
            val end = magnet.indexOf('&', from).let { if (it < 0) magnet.length else it }
            return magnet.substring(from, end).takeIf(::isInfoHash)?.lowercase()
        }

        /**
         * `org/repo` out of `huggingface.co/org/repo`, `https://huggingface.co/org/repo/tree/main` or the `www.` host;
         * null for another host (`evilhuggingface.co`) or a dataset or space (`/datasets/…`, `/spaces/…`).
         */
        fun repoIdFromUrl(url: String): String? {
            val rest = url.trim().substringAfter("://").removePrefix("www.")
            if (!rest.startsWith(HUGGING_FACE_HOST, ignoreCase = true)) return null
            val path = rest.substring(HUGGING_FACE_HOST.length).substringBefore('?').substringBefore('#')
            val org = path.substringBefore('/')
            val repo = path.substringAfter('/', "").substringBefore('/')
            if (org.isEmpty() || repo.isEmpty() || org == "datasets" || org == "spaces") return null
            return "$org/$repo"
        }

        private const val HUGGING_FACE_HOST = "huggingface.co/"

        /**
         * The `d` llama.garden's viewer gives a user submission: `user:<submitter>:<sha256 hex of "<url>:<name>">`,
         * over the trimmed [torrentUrl] and [name] as the form reads them. Stable, so resubmitting the
         * same `.torrent` under the same name replaces the earlier listing.
         */
        fun userSubmissionDTag(
            submitter: HexKey,
            torrentUrl: String,
            name: String,
        ): String {
            val hash = sha256("${torrentUrl.trim()}:${name.trim()}".encodeToByteArray()).toHexKey()
            return "$USER_SUBMISSION_PREFIX$submitter:$hash"
        }

        /**
         * A catalog listing in the shape llama.garden's builders publish, keyed by [infoHash]. The
         * metadata tags (`display_name`, `file_class`, `quant_*`, `lab`, `repo_id`, …) go in [initializer]
         * through this kind's writers.
         */
        fun build(
            infoHash: String,
            name: String,
            magnet: String,
            totalSize: Long,
            torrentFileSha256: String? = null,
            torrentFileUrls: List<String> = emptyList(),
            webSeeds: List<String> = emptyList(),
            trackers: List<String> = emptyList(),
            source: String? = null,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<ModelTorrentEvent>.() -> Unit = {},
        ) = eventTemplate<ModelTorrentEvent>(KIND, "", createdAt) {
            dTag(infoHash.lowercase())
            magnet(magnet)
            name(name)
            totalSize(totalSize)
            torrentFileSha256?.let { torrentFileSha256(it) }
            mimeType(TORRENT_MIME_TYPE)
            torrentFileUrls(torrentFileUrls)
            webSeeds(webSeeds)
            trackers(trackers)
            source?.let { source(it) }
            initializer()
        }

        /**
         * A user submission in the shape of llama.garden's "Add a torrent" form, by [submitter] (the key
         * that will sign it): a [torrentUrl] to an already uploaded `.torrent`, a [name], and what the
         * user knows about the model. The `d` is [userSubmissionDTag]. [approximateSize] is bytes (the
         * form rounds GB to bytes). Mine it before publishing, as the viewer does.
         */
        fun buildSubmission(
            submitter: HexKey,
            torrentUrl: String,
            name: String,
            approximateSize: Long? = null,
            huggingFaceUrl: String? = null,
            fileClass: String = FileClassTag.NOT_SAID,
            modelKind: String = ModelKindTag.NOT_SAID,
            lab: String? = null,
            quantType: String? = null,
            torrentTool: String? = null,
            hfMatch: String? = null,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<ModelTorrentEvent>.() -> Unit = {},
        ) = eventTemplate<ModelTorrentEvent>(KIND, "", createdAt) {
            dTag(userSubmissionDTag(submitter, torrentUrl, name))
            torrentFileUrls(listOf(torrentUrl.trim()))
            name(name.trim())
            approximateSize?.let { totalSize(it) }
            huggingFaceUrl?.trim()?.ifEmpty { null }?.let {
                hostOf(it)?.let { host -> source(host) }
                huggingFace(it)
            }
            fileClass(fileClass)
            modelKind(modelKind)
            lab?.trim()?.ifEmpty { null }?.let { lab(it) }
            quantType?.trim()?.ifEmpty { null }?.let { quantType(it) }
            torrentTool?.trim()?.ifEmpty { null }?.let { torrentTool(it) }
            hfMatch?.let { hfMatch(it) }
            initializer()
        }

        /** The host, as the viewer's `new URL(...).hostname` reads it: no scheme, user info, port or `www.`. */
        private fun hostOf(url: String): String? =
            url
                .substringAfter("://")
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
                .substringAfterLast('@')
                .substringBefore(':')
                .removePrefix("www.")
                .lowercase()
                .ifBlank { null }
    }
}
