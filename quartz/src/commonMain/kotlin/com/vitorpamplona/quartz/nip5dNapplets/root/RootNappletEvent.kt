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
package com.vitorpamplona.quartz.nip5dNapplets.root

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.BaseReplaceableEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip5dNapplets.NappletManifest
import com.vitorpamplona.quartz.nip5dNapplets.identifierCount
import com.vitorpamplona.quartz.nip5dNapplets.manifest
import com.vitorpamplona.quartz.nip5dNapplets.originCount
import com.vitorpamplona.quartz.nip5dNapplets.parentCount
import com.vitorpamplona.quartz.nip5dNapplets.tags.NappletIcon
import com.vitorpamplona.quartz.nip5dNapplets.tags.NappletIntent
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * NIP-5D **root napplet** (kind 15129): the replaceable manifest for a pubkey's default napplet,
 * one per author, with no `d` tag. See [NappletManifest] for what it carries.
 */
@Immutable
class RootNappletEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : BaseReplaceableEvent(id, pubKey, createdAt, KIND, tags, content, sig),
    NappletManifest {
    /** Only a named napplet carries a `d` tag, and only a snapshot records lineage (`a` / `A`). */
    override fun isValid() = super<NappletManifest>.isValid() && tags.identifierCount() == 0 && tags.parentCount() == 0 && tags.originCount() == 0

    companion object {
        const val KIND = 15129

        /**
         * A manifest for the self-contained `/index.html` whose sha256 is [artifactHash]. NIP-5D
         * requires a non-empty plain-text [description], which becomes the event content.
         */
        fun build(
            artifactHash: HexKey,
            description: String,
            servers: List<String> = emptyList(),
            required: List<String> = emptyList(),
            optional: List<String> = emptyList(),
            archetypes: List<String> = emptyList(),
            intents: List<NappletIntent> = emptyList(),
            icon: NappletIcon? = null,
            title: String? = null,
            source: String? = null,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<RootNappletEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, description, createdAt) {
            manifest(artifactHash, servers, required, optional, archetypes, intents, icon, title, source)
            initializer()
        }
    }
}
