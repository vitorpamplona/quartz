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
package com.vitorpamplona.quartz.experimental.clink

import com.vitorpamplona.quartz.experimental.clink.debits.DebitRequest
import com.vitorpamplona.quartz.experimental.clink.debits.DebitResponse
import com.vitorpamplona.quartz.experimental.clink.kotlinSerialization.DebitRequestKSerializer
import com.vitorpamplona.quartz.experimental.clink.kotlinSerialization.DebitResponseKSerializer
import com.vitorpamplona.quartz.experimental.clink.kotlinSerialization.ManageRequestKSerializer
import com.vitorpamplona.quartz.experimental.clink.kotlinSerialization.ManageResponseKSerializer
import com.vitorpamplona.quartz.experimental.clink.kotlinSerialization.OfferReceiptKSerializer
import com.vitorpamplona.quartz.experimental.clink.kotlinSerialization.OfferRequestKSerializer
import com.vitorpamplona.quartz.experimental.clink.kotlinSerialization.OfferResponseKSerializer
import com.vitorpamplona.quartz.experimental.clink.manage.ManageRequest
import com.vitorpamplona.quartz.experimental.clink.manage.ManageResponse
import com.vitorpamplona.quartz.experimental.clink.offers.OfferReceipt
import com.vitorpamplona.quartz.experimental.clink.offers.OfferRequest
import com.vitorpamplona.quartz.experimental.clink.offers.OfferResponse
import com.vitorpamplona.quartz.nip01Core.kotlinSerialization.KotlinSerializationMapper
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

/**
 * JSON for CLINK's offer, debit and manage payloads, on every target. kotlinx for the same
 * reasons as NIP-47: peers are other people's software, and nothing has to survive R8.
 *
 * Parse failures surface as [IllegalArgumentException], like the core
 * [com.vitorpamplona.quartz.nip01Core.core.OptimizedJsonMapper].
 */
object ClinkJson {
    fun toJson(value: OfferRequest): String = encode(OfferRequestKSerializer, value)

    fun toJson(value: OfferResponse): String = encode(OfferResponseKSerializer, value)

    fun toJson(value: OfferReceipt): String = encode(OfferReceiptKSerializer, value)

    fun toJson(value: DebitRequest): String = encode(DebitRequestKSerializer, value)

    fun toJson(value: DebitResponse): String = encode(DebitResponseKSerializer, value)

    fun toJson(value: ManageRequest): String = encode(ManageRequestKSerializer, value)

    fun toJson(value: ManageResponse): String = encode(ManageResponseKSerializer, value)

    fun fromJsonToOfferRequest(json: String): OfferRequest = decode(OfferRequestKSerializer, json)

    fun fromJsonToOfferResponse(json: String): OfferResponse = decode(OfferResponseKSerializer, json)

    fun fromJsonToOfferReceipt(json: String): OfferReceipt = decode(OfferReceiptKSerializer, json)

    fun fromJsonToDebitRequest(json: String): DebitRequest = decode(DebitRequestKSerializer, json)

    fun fromJsonToDebitResponse(json: String): DebitResponse = decode(DebitResponseKSerializer, json)

    fun fromJsonToManageRequest(json: String): ManageRequest = decode(ManageRequestKSerializer, json)

    fun fromJsonToManageResponse(json: String): ManageResponse = decode(ManageResponseKSerializer, json)

    /** Reads [json] as the CLINK payload type [T]. */
    inline fun <reified T : Any> fromJsonTo(json: String): T =
        when (T::class) {
            OfferRequest::class -> fromJsonToOfferRequest(json)
            OfferResponse::class -> fromJsonToOfferResponse(json)
            OfferReceipt::class -> fromJsonToOfferReceipt(json)
            DebitRequest::class -> fromJsonToDebitRequest(json)
            DebitResponse::class -> fromJsonToDebitResponse(json)
            ManageRequest::class -> fromJsonToManageRequest(json)
            ManageResponse::class -> fromJsonToManageResponse(json)
            else -> throw IllegalArgumentException("Not a CLINK payload: ${T::class}")
        } as T

    private fun <T> encode(
        serializer: KSerializer<T>,
        value: T,
    ): String = KotlinSerializationMapper.json.encodeToString(serializer, value)

    private fun <T> decode(
        serializer: KSerializer<T>,
        json: String,
    ): T =
        try {
            KotlinSerializationMapper.json.decodeFromString(serializer, json)
        } catch (e: SerializationException) {
            throw IllegalArgumentException(e.message, e)
        }
}
