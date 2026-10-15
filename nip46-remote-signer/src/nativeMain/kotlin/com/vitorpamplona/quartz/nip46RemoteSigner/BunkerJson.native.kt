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
package com.vitorpamplona.quartz.nip46RemoteSigner

import com.vitorpamplona.quartz.nip01Core.kotlinSerialization.KotlinSerializationMapper
import com.vitorpamplona.quartz.nip46RemoteSigner.kotlinSerialization.BunkerMessageKSerializer
import com.vitorpamplona.quartz.nip46RemoteSigner.kotlinSerialization.BunkerRequestKSerializer
import com.vitorpamplona.quartz.nip46RemoteSigner.kotlinSerialization.BunkerResponseKSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

actual object BunkerJson {
    actual fun toJson(message: BunkerMessage): String =
        when (message) {
            is BunkerRequest -> KotlinSerializationMapper.json.encodeToString(BunkerRequestKSerializer, message)
            is BunkerResponse -> KotlinSerializationMapper.json.encodeToString(BunkerResponseKSerializer, message)
            else -> KotlinSerializationMapper.json.encodeToString(BunkerMessageKSerializer, message)
        }

    actual fun fromJsonToMessage(json: String): BunkerMessage = read(BunkerMessageKSerializer, json)

    actual fun fromJsonToRequest(json: String): BunkerRequest = read(BunkerRequestKSerializer, json)

    actual fun fromJsonToResponse(json: String): BunkerResponse = read(BunkerResponseKSerializer, json)

    private fun <T> read(
        serializer: KSerializer<T>,
        json: String,
    ): T =
        try {
            KotlinSerializationMapper.json.decodeFromString(serializer, json)
        } catch (e: SerializationException) {
            throw IllegalArgumentException(e.message, e)
        }
}
