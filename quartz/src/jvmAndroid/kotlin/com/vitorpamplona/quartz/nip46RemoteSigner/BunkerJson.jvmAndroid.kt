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

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.RuntimeJsonMappingException
import com.fasterxml.jackson.databind.module.SimpleModule
import com.vitorpamplona.quartz.nip01Core.jackson.JacksonMapper
import com.vitorpamplona.quartz.nip46RemoteSigner.jackson.BunkerMessageDeserializer
import com.vitorpamplona.quartz.nip46RemoteSigner.jackson.BunkerRequestDeserializer
import com.vitorpamplona.quartz.nip46RemoteSigner.jackson.BunkerRequestSerializer
import com.vitorpamplona.quartz.nip46RemoteSigner.jackson.BunkerResponseDeserializer
import com.vitorpamplona.quartz.nip46RemoteSigner.jackson.BunkerResponseSerializer

actual object BunkerJson {
    /**
     * The core mapper plus the bunker (de)serializers. A copy, so the core mapper stays
     * closed to types it has no serializer for (see JacksonMapper.checkRegistered).
     *
     * Reads go through Class objects, never a TypeReference: R8 in full mode erases a
     * TypeReference's type argument and the read then fails on device.
     */
    private val mapper: ObjectMapper =
        JacksonMapper.mapper.copy().registerModule(
            SimpleModule()
                .addDeserializer(BunkerMessage::class.java, BunkerMessageDeserializer())
                .addSerializer(BunkerRequest::class.java, BunkerRequestSerializer())
                .addDeserializer(BunkerRequest::class.java, BunkerRequestDeserializer())
                .addSerializer(BunkerResponse::class.java, BunkerResponseSerializer())
                .addDeserializer(BunkerResponse::class.java, BunkerResponseDeserializer()),
        )

    actual fun toJson(message: BunkerMessage): String =
        when (message) {
            is BunkerRequest, is BunkerResponse -> mapper.writeValueAsString(message)
            else -> throw IllegalArgumentException("No serializer for ${message::class}")
        }

    actual fun fromJsonToMessage(json: String): BunkerMessage = read(json, BunkerMessage::class.java)

    actual fun fromJsonToRequest(json: String): BunkerRequest = read(json, BunkerRequest::class.java)

    actual fun fromJsonToResponse(json: String): BunkerResponse = read(json, BunkerResponse::class.java)

    private fun <T> read(
        json: String,
        type: Class<T>,
    ): T =
        try {
            mapper.readValue(json, type)
        } catch (e: JsonProcessingException) {
            throw IllegalArgumentException(e.message, e)
        } catch (e: RuntimeJsonMappingException) {
            throw IllegalArgumentException(e.message, e)
        }
}
