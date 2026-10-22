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

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.RuntimeJsonMappingException
import com.vitorpamplona.quartz.experimental.clink.debits.DebitRequest
import com.vitorpamplona.quartz.experimental.clink.debits.DebitResponse
import com.vitorpamplona.quartz.experimental.clink.manage.ManageRequest
import com.vitorpamplona.quartz.experimental.clink.manage.ManageResponse
import com.vitorpamplona.quartz.experimental.clink.offers.OfferReceipt
import com.vitorpamplona.quartz.experimental.clink.offers.OfferRequest
import com.vitorpamplona.quartz.experimental.clink.offers.OfferResponse
import com.vitorpamplona.quartz.nip01Core.jackson.JacksonMapper
import com.vitorpamplona.quartz.nip01Core.kotlinSerialization.KotlinSerializationMapper
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.Command
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip47WalletConnect.rpc.Notification
import com.vitorpamplona.quartz.nip47WalletConnect.rpc.Request
import com.vitorpamplona.quartz.nip47WalletConnect.rpc.Response
import com.vitorpamplona.quartz.nip59Giftwrap.rumors.Rumor
import kotlinx.serialization.SerializationException

actual object OptimizedJsonMapper {
    actual fun fromJson(json: String): Event = if (JsonEngine.isHandWritten) NostrJsonReader.readEvent(json) else jackson { JacksonMapper.fromJson(json) }

    actual fun toJson(event: Event) = if (JsonEngine.isHandWritten) NostrJsonWriter.toJson(event) else JacksonMapper.toJson(event)

    actual fun fromJsonToMessage(json: String): Message = if (JsonEngine.isHandWritten) NostrJsonReader.readMessage(json) else jackson { JacksonMapper.fromJsonToMessage(json) }

    actual fun fromJsonToCommand(json: String): Command = if (JsonEngine.isHandWritten) NostrJsonReader.readCommand(json) else jackson { JacksonMapper.fromJsonToCommand(json) }

    actual fun fromJsonToTagArray(json: String): Array<Array<String>> = if (JsonEngine.isHandWritten) NostrJsonReader.readTagArray(json) else jackson { JacksonMapper.fromJsonToTagArray(json) }

    actual fun fromJsonToRumor(json: String): Rumor = if (JsonEngine.isHandWritten) NostrJsonReader.readRumor(json) else jackson { JacksonMapper.fromJsonToRumor(json) }

    actual fun fromJsonToEventTemplate(json: String): EventTemplate<Event> = if (JsonEngine.isHandWritten) NostrJsonReader.readEventTemplate(json) else jackson { JacksonMapper.fromJsonToEventTemplate(json) }

    actual fun fromJsonToEventList(json: String): List<Event> = if (JsonEngine.isHandWritten) NostrJsonReader.readEventList(json) else jackson { JacksonMapper.fromJsonToEventList(json) }

    actual fun toJson(tags: Array<Array<String>>): String = if (JsonEngine.isHandWritten) NostrJsonWriter.toJson(tags) else JacksonMapper.toJson(tags)

    private inline fun <T> jackson(read: () -> T): T =
        try {
            read()
        } catch (e: JsonParseException) {
            throw IllegalArgumentException(e.message, e)
        }

    /**
     * NIP-47 and CLINK read and write through kotlinx on every target, including this
     * one. Two reasons, in order of importance:
     *
     * 1. These are the only types Jackson bound REFLECTIVELY here — the hand-written
     *    deserializers dispatched to the concrete classes with `treeToValue`. That made
     *    ~106 classes' field names load-bearing under R8 and cost two blanket keep rules.
     *    The kotlinx serializers name every field as a string literal, so nothing has to
     *    be kept.
     * 2. Wallets and CLINK peers are other people's software. The kotlinx path reads a
     *    field of the wrong shape as absent instead of failing the message (see
     *    nip01Core.kotlinSerialization.LenientJson); Jackson raised
     *    MismatchedInputException and lost the whole response over one bad field.
     *
     * Everything else stays on Jackson, which is faster on the event hot path and is
     * already non-reflective there, unless [JsonEngine.HAND_WRITTEN] is on.
     */
    actual inline fun <reified T : OptimizedSerializable> fromJsonTo(json: String): T {
        if (JsonEngine.isHandWritten) {
            readKnownType(T::class, json)?.let { return it as T }
        }
        return when (T::class) {
            Request::class, Response::class, Notification::class,
            OfferRequest::class, OfferResponse::class, OfferReceipt::class,
            DebitRequest::class, DebitResponse::class,
            ManageRequest::class, ManageResponse::class,
            ->
                try {
                    KotlinSerializationMapper.fromJsonTo<T>(json)
                } catch (e: SerializationException) {
                    throw IllegalArgumentException(e.message, e)
                }

            else ->
                try {
                    JacksonMapper.fromJsonTo<T>(json)
                } catch (e: JsonParseException) {
                    throw IllegalArgumentException(e.message, e)
                } catch (e: JsonProcessingException) {
                    throw IllegalArgumentException(e.message, e)
                } catch (e: RuntimeJsonMappingException) {
                    throw IllegalArgumentException(e.message, e)
                }
        }
    }

    actual fun toJson(value: OptimizedSerializable): String =
        (if (JsonEngine.isHandWritten) NostrJsonWriter.toJsonOrNull(value) else null) ?: when (value) {
            is Request, is Response, is Notification,
            is OfferRequest, is OfferResponse, is OfferReceipt,
            is DebitRequest, is DebitResponse,
            is ManageRequest, is ManageResponse,
            -> KotlinSerializationMapper.toJson(value)

            else -> JacksonMapper.toJson(value)
        }
}
