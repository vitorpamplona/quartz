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
package com.vitorpamplona.quartz.routstr.node

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.BaseAddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.dTag.dTag
import com.vitorpamplona.quartz.nip01Core.tags.hashtags.hashtags
import com.vitorpamplona.quartz.nip50Search.IndexableFieldVisitor
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A Routstr node announcement (kind 38421, addressable): an OpenAI-compatible AI proxy, paid in
 * sats over Lightning or Cashu, saying where it is. Routstr publishes no NIP; this follows the
 * events live nodes publish (checked 2026-11-13 on relay.routstr.com, relay.damus.io, nos.lol),
 * which differ from the docs. Nodes re-announce every 24 hours.
 *
 * - `d` = the node's id. routstr-core writes the first 12 hex characters of its pubkey; operators
 *   override it with a slug or a host name.
 * - `u` = a base URL, repeatable: https, `http://….onion`, and addresses no client should call.
 *   [endpoints] classifies each one, and [usableEndpoints] is the list to call.
 * - `mint` = a Cashu mint the node accepts, repeatable. `version` = routstr-core's version
 *   (`0.5.1+gd980acc`).
 * - Sometimes `models` (one tag, one id per slot), `t` topics and `payment`.
 * - `content` = `{"name": "...", "about": "..."}`, sometimes empty: read leniently into [name] and
 *   [about].
 *
 * There is no `a`, `e`, `p` or `q`, so no hint or link providers. A node's own `GET /v1/info`
 * carries its npub; a client confirms it matches [pubKey] before it calls the node verified.
 *
 * Searchable by its name and about.
 */
@Immutable
class RoutstrNodeEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: TagArray,
    content: String,
    sig: HexKey,
) : BaseAddressableEvent(id, pubKey, createdAt, KIND, tags, content, sig),
    SearchableEvent {
    /** Every `u`, as written, classified as https, onion or other. */
    fun endpoints(): List<RoutstrEndpoint> = tags.endpoints()

    fun httpsEndpoints(): List<RoutstrEndpoint> = endpoints().filter { it.isHttps() }

    fun onionEndpoints(): List<RoutstrEndpoint> = endpoints().filter { it.isOnion() }

    /**
     * The endpoints a client may call, in the order the node lists them: https always, `.onion`
     * only when [torEnabled]. Never plain http to a non-onion host, an IP literal, a `.fips` mesh
     * name or any other non-public host (see [RoutstrEndpoint]).
     */
    fun usableEndpoints(torEnabled: Boolean): List<RoutstrEndpoint> = endpoints().filter { it.isUsable(torEnabled) }

    /** The Cashu mints the node accepts (`mint`). */
    fun mints(): List<String> = tags.mints()

    /** routstr-core's version (`version`). */
    fun version(): String? = tags.version()

    /** The model ids the node lists in `models`, or empty. `GET /v1/models` is authoritative. */
    fun models(): List<String> = tags.models()

    /** The node's topics (`t`), such as `directory` or `fips`. */
    fun topics(): List<String> = tags.hashtags()

    @kotlinx.serialization.Transient
    @kotlin.jvm.Transient
    private var parsedContent: RoutstrNodeContent? = null

    /** The `content` JSON, parsed once; empty or invalid JSON reads as all nulls. */
    fun nodeContent(): RoutstrNodeContent = parsedContent ?: RoutstrNodeContent.parse(content).also { parsedContent = it }

    fun name(): String? = nodeContent().name

    fun about(): String? = nodeContent().about

    /**
     * Whether the node announced itself within [maxAge] seconds of [now], by default this week
     * ([MAX_AGE]). Nodes re-announce daily, but live ones that still answer have been seen up to
     * a week late, so a node that isn't fresh may still be listed ([isListed]).
     */
    fun isFresh(
        now: Long = TimeUtils.now(),
        maxAge: Long = MAX_AGE,
    ): Boolean = createdAt >= now - maxAge

    /** Whether a client still lists the node: announced within [LISTED_MAX_AGE] (a month) of [now]. */
    fun isListed(now: Long = TimeUtils.now()): Boolean = isFresh(now, LISTED_MAX_AGE)

    override fun indexableContent() = listOfNotNull(name(), about()).joinToString("\n")

    override fun forEachIndexableField(visitor: IndexableFieldVisitor) {
        if (!visitor.visit(name())) return
        visitor.visit(about())
    }

    companion object {
        const val KIND = 38421

        /**
         * "Seen this week": a week, in seconds. A listed node older than this goes behind a
         * "not seen this week" control rather than off the list ([LISTED_MAX_AGE]).
         */
        const val MAX_AGE: Long = TimeUtils.ONE_WEEK.toLong()

        /**
         * Nodes are listed up to a month after their last announcement, in seconds; older ones are
         * not listed at all. Those past [MAX_AGE] sit behind a "not seen this week" control.
         */
        const val LISTED_MAX_AGE: Long = 30L * TimeUtils.ONE_DAY

        /**
         * An announcement in the shape routstr-core publishes. Pass `dTag` as the first 12 hex
         * characters of the signing key to match it; the default is a random id.
         */
        @OptIn(ExperimentalUuidApi::class)
        fun build(
            endpoints: List<String>,
            mints: List<String> = emptyList(),
            version: String? = null,
            name: String? = null,
            about: String? = null,
            models: List<String> = emptyList(),
            topics: List<String> = emptyList(),
            dTag: String = Uuid.random().toString(),
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<RoutstrNodeEvent>.() -> Unit = {},
        ) = eventTemplate<RoutstrNodeEvent>(KIND, RoutstrNodeContent.assemble(name, about), createdAt) {
            dTag(dTag)
            endpoints(endpoints)
            mints(mints)
            version?.let { version(it) }
            if (models.isNotEmpty()) models(models)
            hashtags(topics)
            initializer()
        }
    }
}
