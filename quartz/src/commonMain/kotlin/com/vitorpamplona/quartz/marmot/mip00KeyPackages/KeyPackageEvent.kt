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
package com.vitorpamplona.quartz.marmot.mip00KeyPackages

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.tags.AppComponentsTag
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.tags.MlsProposalsTag
import com.vitorpamplona.quartz.nip01Core.core.BaseAddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.dTag.dTag
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * Marmot KeyPackage Event (MIP-00) — kind 30443.
 *
 * Addressable event that publishes an MLS KeyPackage for asynchronous group invitations.
 * Each KeyPackage is identified by its (kind, pubkey, d-tag) tuple, enabling native
 * rotation without NIP-09 deletion events.
 *
 * Content: base64-encoded TLS-serialized KeyPackageBundle from MLS library.
 *
 * Required tags: d, mls_protocol_version, mls_ciphersuite, mls_extensions,
 *                mls_proposals, encoding, i, relays
 * Optional tags: client
 *
 * The tag accessors are shared with the legacy kind 443 form and live on
 * [PublishedKeyPackage].
 */
@Immutable
class KeyPackageEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : BaseAddressableEvent(id, pubKey, createdAt, KIND, tags, content, sig),
    PublishedKeyPackage {
    companion object {
        const val KIND = 30443

        /** `app_data_dictionary` — the current profile's only required MLS extension. */
        const val CURRENT_PROFILE_EXTENSION = "0x0006"

        /** `app_data_update` — required alongside `self_remove`. */
        const val APP_DATA_UPDATE_PROPOSAL = "0x0008"

        /**
         * Build a CURRENT-PROFILE kind:30443 event.
         *
         * Differences from [build], all of them wire-visible:
         *  - `mls_extensions` names `app_data_dictionary` (`0x0006`), not
         *    MIP-01's `marmot_group_data`; last resort is a KeyPackage
         *    component now, not extension `0x000a`;
         *  - `mls_proposals` adds `app_data_update` (`0x0008`);
         *  - an `app_components` tag is required and must include `0x8009`;
         *  - NO `encoding` tag — the current profile forbids it;
         *  - NO `relays` tag — discovery uses the author's NIP-65 write set,
         *    and the spec removed the dedicated KeyPackage relay list.
         *
         * [dTagSlot] is a stable random 32-byte publication slot id. Replacing
         * the KeyPackage in that logical slot MUST reuse the same value; a
         * fresh one creates a second concurrently discoverable slot.
         */
        fun buildCurrentProfile(
            keyPackageBase64: String,
            dTagSlot: String,
            keyPackageRef: HexKey,
            appComponentIds: List<String>,
            ciphersuite: String = "0x0001",
            mlsExtensionIds: List<String> = listOf(CURRENT_PROFILE_EXTENSION),
            mlsProposalIds: List<String> = listOf(APP_DATA_UPDATE_PROPOSAL, MlsProposalsTag.SELF_REMOVE),
            clientName: String? = null,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<KeyPackageEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, keyPackageBase64, createdAt) {
            dTag(dTagSlot)
            mlsProtocolVersion()
            mlsCiphersuite(ciphersuite)
            mlsExtensions(mlsExtensionIds.distinct().sorted())
            mlsProposals(mlsProposalIds.distinct().sorted())
            appComponents(
                (appComponentIds + AppComponentsTag.ACCOUNT_IDENTITY_PROOF_V2).distinct().sorted(),
            )
            keyPackageRef(keyPackageRef)
            clientName?.let { client(it) }
            initializer()
        }

        /** `0x`-prefixed lowercase hex of a 16-bit id, zero-padded to four digits. */
        fun idHex(id: Int): String {
            val hex = id.toString(16)
            return "0x" + "0".repeat(4 - hex.length) + hex
        }

        /** MIP-era builder, kept for legacy groups already on disk. */
        fun build(
            keyPackageBase64: String,
            dTagSlot: String,
            keyPackageRef: HexKey,
            relays: List<NormalizedRelayUrl>,
            ciphersuite: String = "0x0001",
            clientName: String? = null,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<KeyPackageEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, keyPackageBase64, createdAt) {
            dTag(dTagSlot)
            mlsProtocolVersion()
            mlsCiphersuite(ciphersuite)
            mlsExtensions(listOf("0xf2ee", "0x000a"))
            mlsProposals(listOf("0x000a"))
            encoding()
            keyPackageRef(keyPackageRef)
            keyPackageRelays(relays)
            clientName?.let { client(it) }
            initializer()
        }
    }
}
