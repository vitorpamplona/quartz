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

actual object OptimizedJsonMapper {
    actual fun fromJson(json: String): Event = if (JsonEngine.isKite) KiteReader.readEvent(json) else kotlinx { KotlinSerializationMapper.fromJson(json) }

    actual fun toJson(event: Event): String = if (JsonEngine.isKite) KiteWriter.toJson(event) else KotlinSerializationMapper.toJson(event)

    actual fun fromJsonToMessage(json: String): Message = if (JsonEngine.isKite) KiteReader.readMessage(json) else kotlinx { KotlinSerializationMapper.fromJsonToMessage(json) }

    actual fun fromJsonToCommand(json: String): Command = if (JsonEngine.isKite) KiteReader.readCommand(json) else kotlinx { KotlinSerializationMapper.fromJsonToCommand(json) }

    actual fun fromJsonToTagArray(json: String): Array<Array<String>> = if (JsonEngine.isKite) KiteReader.readTagArray(json) else kotlinx { KotlinSerializationMapper.fromJsonToTagArray(json) }

    actual fun fromJsonToEventTemplate(json: String): EventTemplate<Event> = if (JsonEngine.isKite) KiteReader.readEventTemplate(json) else kotlinx { KotlinSerializationMapper.fromJsonToEventTemplate(json) }

    actual fun fromJsonToEventList(json: String): List<Event> = if (JsonEngine.isKite) KiteReader.readEventList(json) else kotlinx { KotlinSerializationMapper.fromJsonToEventList(json) }

    actual fun fromJsonToRumor(json: String): Rumor = if (JsonEngine.isKite) KiteReader.readRumor(json) else kotlinx { KotlinSerializationMapper.fromJsonToRumor(json) }

    actual fun toJson(tags: Array<Array<String>>): String = if (JsonEngine.isKite) KiteWriter.toJson(tags) else KotlinSerializationMapper.toJson(tags)

    private inline fun <T> kotlinx(read: () -> T): T =
        try {
            read()
        } catch (e: SerializationException) {
            throw IllegalArgumentException(e.message, e)
        }

    actual inline fun <reified T : OptimizedSerializable> fromJsonTo(json: String): T {
        if (JsonEngine.isKite) {
            readKnownType(T::class, json)?.let { return it as T }
        }
        return try {
            KotlinSerializationMapper.fromJsonTo<T>(json)
        } catch (e: SerializationException) {
            throw IllegalArgumentException(e.message, e)
        }
    }

    actual fun toJson(value: OptimizedSerializable): String = (if (JsonEngine.isKite) KiteWriter.toJsonOrNull(value) else null) ?: KotlinSerializationMapper.toJson(value)
}
