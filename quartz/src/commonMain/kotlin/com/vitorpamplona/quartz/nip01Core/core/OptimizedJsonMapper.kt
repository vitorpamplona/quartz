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
package com.vitorpamplona.quartz.nip01Core.core

import com.vitorpamplona.quartz.nip01Core.kite.KiteReader
import com.vitorpamplona.quartz.nip01Core.kite.KiteWriter
import com.vitorpamplona.quartz.nip01Core.kite.readKnownType
import com.vitorpamplona.quartz.nip01Core.kotlinSerialization.KotlinSerializationMapper
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.Command
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip59Giftwrap.rumors.Rumor
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

interface OptimizedSerializable

/**
 * Quartz's JSON, the same on every target.
 *
 * Kite, Quartz's own Nostr reader and writer ([KiteReader], [KiteWriter]), handles events,
 * relay messages, client commands, filters, tags, rumors, event templates and NIP-46 messages.
 * Everything else that implements [OptimizedSerializable] (NIP-47, CLINK, …) goes through kotlinx.serialization ([KotlinSerializationMapper]).
 *
 * Malformed input throws [IllegalArgumentException] from either path.
 */
object OptimizedJsonMapper {
    fun fromJson(json: String): Event = KiteReader.readEvent(json)

    fun toJson(event: Event): String = KiteWriter.toJson(event)

    fun fromJsonToMessage(json: String): Message = KiteReader.readMessage(json)

    fun fromJsonToCommand(json: String): Command = KiteReader.readCommand(json)

    fun fromJsonToTagArray(json: String): Array<Array<String>> = KiteReader.readTagArray(json)

    fun fromJsonToEventTemplate(json: String): EventTemplate<Event> = KiteReader.readEventTemplate(json)

    fun fromJsonToEventList(json: String): List<Event> = KiteReader.readEventList(json)

    fun fromJsonToRumor(json: String): Rumor = KiteReader.readRumor(json)

    fun toJson(tags: Array<Array<String>>): String = KiteWriter.toJson(tags)

    inline fun <reified T : OptimizedSerializable> fromJsonTo(json: String): T {
        readKnownType(T::class, json)?.let { return it as T }
        return try {
            KotlinSerializationMapper.fromJsonTo<T>(json)
        } catch (e: SerializationException) {
            throw IllegalArgumentException(e.message, e)
        }
    }

    fun toJson(value: OptimizedSerializable): String = KiteWriter.toJsonOrNull(value) ?: KotlinSerializationMapper.toJson(value)
}

object JsonMapper {
    val jsonInstance =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    inline fun <reified T> fromJson(json: String): T = jsonInstance.decodeFromString<T>(json)

    inline fun <reified T : Any> toJson(obj: T): String = jsonInstance.encodeToString(obj)
}
