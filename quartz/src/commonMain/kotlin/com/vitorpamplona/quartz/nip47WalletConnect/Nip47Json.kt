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
package com.vitorpamplona.quartz.nip47WalletConnect

import com.vitorpamplona.quartz.nip01Core.kotlinSerialization.KotlinSerializationMapper
import com.vitorpamplona.quartz.nip47WalletConnect.kotlinSerialization.Nip47NotificationKSerializer
import com.vitorpamplona.quartz.nip47WalletConnect.kotlinSerialization.Nip47RequestKSerializer
import com.vitorpamplona.quartz.nip47WalletConnect.kotlinSerialization.Nip47ResponseKSerializer
import com.vitorpamplona.quartz.nip47WalletConnect.rpc.Notification
import com.vitorpamplona.quartz.nip47WalletConnect.rpc.Request
import com.vitorpamplona.quartz.nip47WalletConnect.rpc.Response
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

/**
 * JSON for NIP-47's request, response and notification payloads, on every target.
 *
 * kotlinx, not Jackson, on JVM/Android too: wallets are other people's software, and the
 * hand-written serializers read a field of the wrong shape as absent instead of failing the
 * whole message. They also name every field as a string literal, so R8 has nothing to keep.
 *
 * Parse failures surface as [IllegalArgumentException], like the core
 * [com.vitorpamplona.quartz.nip01Core.core.OptimizedJsonMapper].
 */
object Nip47Json {
    fun toJson(request: Request): String = encode(Nip47RequestKSerializer, request)

    fun toJson(response: Response): String = encode(Nip47ResponseKSerializer, response)

    fun toJson(notification: Notification): String = encode(Nip47NotificationKSerializer, notification)

    fun fromJsonToRequest(json: String): Request = decode(Nip47RequestKSerializer, json)

    fun fromJsonToResponse(json: String): Response = decode(Nip47ResponseKSerializer, json)

    fun fromJsonToNotification(json: String): Notification = decode(Nip47NotificationKSerializer, json)

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
