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
import com.vitorpamplona.quartz.nip01Core.hints.PubKeyHintProvider
import com.vitorpamplona.quartz.nip01Core.hints.types.PubKeyHint
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip31Alts.AltTag

/**
 * A drss.io show definition (kind 31341): the settings of a podcast drss generates and publishes
 * from its own key, the show's bot, as kind-30023 episodes with their audio in an `imeta`.
 *
 * - `d` = the show's slug; `p` = the show's bot; `status` = `active` or retired;
 * - `alt` = `Podcast show: <name>`;
 * - `content` = the generator's JSON settings (name, voice, language, schedule, retention, the
 *   sources it draws on). That JSON is drss's private format, so it is kept as is.
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
    PubKeyHintProvider {
    /** The key the show publishes its episodes from. */
    fun showBot(): HexKey? = tags.firstNotNullOfOrNull(PTag::parseKey)

    fun status(): String? = tags.firstNotNullOfOrNull { if (it.size > 1 && it[0] == STATUS) it[1].ifBlank { null } else null }

    fun isActive() = status() == ACTIVE

    fun alt() = tags.firstNotNullOfOrNull(AltTag::parse)

    override fun pubKeyHints(): List<PubKeyHint> = tags.mapNotNull(PTag::parseAsHint)

    override fun linkedPubKeys(): List<HexKey> = listOfNotNull(showBot())

    companion object {
        const val KIND = 31341

        const val STATUS = "status"
        const val ACTIVE = "active"
    }
}
