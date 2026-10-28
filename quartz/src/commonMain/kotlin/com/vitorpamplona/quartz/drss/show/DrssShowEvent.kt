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
package com.vitorpamplona.quartz.drss.show

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.BaseAddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.hints.PubKeyHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.types.PubKeyHint
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.dTag.dTag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip31Alts.alt
import com.vitorpamplona.quartz.nip50Search.IndexableFieldVisitor
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * A drss.io show definition (kind 31341): the settings of a podcast drss generates and publishes
 * from its own key, the show's bot, as kind-30023 episodes with their audio in an `imeta`.
 *
 * - `d` = the show's slug; `p` = the show's bot; `status` = [ACTIVE] or retired;
 * - `alt` = `Podcast show: <name>`;
 * - `content` = the generator's JSON settings (name, voice, language, schedule, retention, the
 *   sources it draws on). That JSON is drss's private format, so it is kept as is.
 *
 * Searchable by the show's name, read from `alt`.
 */
@Immutable
class DrssShowEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: TagArray,
    content: String,
    sig: HexKey,
) : BaseAddressableEvent(id, pubKey, createdAt, KIND, tags, content, sig),
    PubKeyHintProvider,
    SearchableEvent {
    /** The key the show publishes its episodes from (`p`). */
    fun showBot(): PTag? = tags.showBot()

    /** The key of [showBot] (`p`). */
    fun showBotKey(): HexKey? = tags.showBotKey()

    fun status(): String? = tags.status()

    fun isActive() = status() == ACTIVE

    fun alt() = tags.alt()

    /** The show's name, from `alt` (`Podcast show: <name>`). */
    fun showName(): String? = alt()?.removePrefix(ALT_PREFIX)?.ifBlank { null }

    /** The generator's settings, drss's private JSON. */
    fun settings(): String = content

    override fun indexableContent() = showName() ?: ""

    override fun forEachIndexableField(visitor: IndexableFieldVisitor) {
        visitor.visit(showName())
    }

    override fun pubKeyHints(): List<PubKeyHint> = tags.mapNotNull(PTag::parseAsHint)

    /** `MEMBER`: the show's bot (`p`). */
    override fun linkedPubKeys(): List<HexKey> = listOfNotNull(showBotKey())

    companion object {
        const val KIND = 31341

        const val ACTIVE = "active"
        const val ALT_PREFIX = "Podcast show: "

        /** The definition of the show [slug], published by [showBot], with drss's [settings] JSON. */
        fun build(
            slug: String,
            name: String,
            showBot: PTag,
            settings: String,
            status: String = ACTIVE,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<DrssShowEvent>.() -> Unit = {},
        ) = eventTemplate<DrssShowEvent>(KIND, settings, createdAt) {
            dTag(slug)
            showBot(showBot)
            status(status)
            alt(ALT_PREFIX + name)
            initializer()
        }
    }
}
