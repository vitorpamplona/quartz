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
package com.vitorpamplona.quartz.nip57Zaps.validate

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.utils.Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Cached metadata pulled from a recipient's `/.well-known/lnurlp/<user>` endpoint
 * that is relevant to NIP-57 zap receipt validation.
 *
 * `nostrPubkey` is the pubkey the LNURL provider will use to sign zap receipts
 * (kind 9735). NIP-57 Appendix F requires the receipt's `pubkey` to equal this
 * value. May be null if the provider did not advertise one (i.e. doesn't support
 * NIP-57 zaps).
 */
data class LnurlEndpointInfo(
    val nostrPubkey: HexKey?,
    val allowsNostr: Boolean,
) {
    companion object {
        /**
         * Reads an LNURL-pay answer (LUD-06) as NIP-57 sees it. Strict, since the answer is
         * anyone's JSON: `allowsNostr` is true only for the JSON boolean `true` (not the string
         * `"true"`), and `nostrPubkey` is kept only when it is a 64-character hex key, lowercased.
         *
         * Null when [json] is not a JSON object, or is an LNURL error (`"status": "ERROR"`).
         */
        fun parse(json: String): LnurlEndpointInfo? {
            val root =
                try {
                    Json.parseToJsonElement(json) as? JsonObject
                } catch (e: Exception) {
                    null
                } ?: return null
            val status = root["status"] as? JsonPrimitive
            if (status != null && status.isString && status.content.equals("ERROR", ignoreCase = true)) return null

            val allows = root["allowsNostr"] as? JsonPrimitive
            val key = (root["nostrPubkey"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase()
            return LnurlEndpointInfo(
                nostrPubkey = key?.takeIf { it.length == 64 && Hex.isHex64(it) },
                allowsNostr = allows != null && !allows.isString && allows.booleanOrNull == true,
            )
        }
    }
}
