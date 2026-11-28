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
package com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin

import com.vitorpamplona.crypto.sha2.sha256
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Transport-independent Electrum JSON-RPC and Namecoin script handling. */
internal object ElectrumXProtocol {
    const val NAME_EXPIRE_DEPTH = 36_000
    const val MAX_LINE_BYTES = 256 * 1024
    const val PROTOCOL_VERSION = "1.4"

    private const val OP_NAME_FIRSTUPDATE: Byte = 0x52
    private const val OP_NAME_UPDATE: Byte = 0x53
    private const val OP_2DROP: Byte = 0x6d
    private const val OP_DROP: Byte = 0x75
    private const val OP_RETURN: Byte = 0x6a
    private const val OP_PUSHDATA1: Byte = 0x4c
    private const val OP_PUSHDATA2: Byte = 0x4d

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    fun request(
        id: Int,
        method: String,
        params: List<JsonElement> = emptyList(),
    ): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", JsonArray(params))
        }.toString()

    fun response(
        raw: String,
        expectedId: Int,
    ): JsonElement {
        val envelope =
            try {
                json.parseToJsonElement(raw).jsonObject
            } catch (e: Exception) {
                throw ElectrumXProtocolException("Malformed ElectrumX JSON-RPC response", e)
            }
        val id = envelope["id"]?.jsonPrimitive?.intOrNull
        if (id != expectedId) throw ElectrumXProtocolException("ElectrumX response id does not match request")
        val error = envelope["error"]
        if (error != null && error !is JsonNull) {
            val message = (error as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: error.toString()
            throw ElectrumXProtocolException("ElectrumX server error: $message")
        }
        return envelope["result"] ?: throw ElectrumXProtocolException("ElectrumX response has no result")
    }

    fun nameIndexScript(identifier: String): ByteArray {
        val name = asciiBytes(identifier)
        return byteArrayOf(OP_NAME_UPDATE) + pushData(name) + pushData(byteArrayOf()) +
            byteArrayOf(OP_2DROP, OP_DROP, OP_RETURN)
    }

    fun scriptHash(identifier: String): String = sha256(nameIndexScript(identifier)).reversedArray().toHexKey()

    fun history(result: JsonElement): List<Pair<String, Int>> {
        val entries = result as? JsonArray ?: throw ElectrumXProtocolException("ElectrumX history is not an array")
        return entries.map { entry ->
            val obj = entry as? JsonObject ?: throw ElectrumXProtocolException("Malformed ElectrumX history entry")
            val txHash = obj["tx_hash"]?.jsonPrimitive?.contentOrNull
            val height = obj["height"]?.jsonPrimitive?.intOrNull
            if (txHash == null || height == null) throw ElectrumXProtocolException("Malformed ElectrumX history entry")
            txHash to height
        }
    }

    fun blockHeight(rawResult: JsonElement): Int? = (rawResult as? JsonObject)?.get("height")?.jsonPrimitive?.intOrNull

    fun nameFromTransaction(
        identifier: String,
        txHash: String,
        height: Int,
        result: JsonElement,
        currentHeight: Int?,
    ): NameShowResult? {
        val vouts = (result as? JsonObject)?.get("vout") as? JsonArray ?: return null
        for (vout in vouts) {
            val scriptHex =
                ((vout as? JsonObject)?.get("scriptPubKey") as? JsonObject)
                    ?.get("hex")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?: continue
            val parsed = parseNameScript(scriptHex) ?: continue
            if (parsed.first == identifier) {
                return NameShowResult(
                    name = parsed.first,
                    value = parsed.second,
                    txid = txHash,
                    height = height,
                    expiresIn = if (currentHeight != null && height > 0) NAME_EXPIRE_DEPTH - (currentHeight - height) else null,
                )
            }
        }
        return null
    }

    fun checkExpiry(
        identifier: String,
        height: Int,
        currentHeight: Int?,
    ) {
        if (currentHeight != null && height > 0 && currentHeight - height >= NAME_EXPIRE_DEPTH) {
            throw NamecoinLookupException.NameExpired(identifier)
        }
    }

    private fun parseNameScript(scriptHex: String): Pair<String, String>? {
        if (scriptHex.length % 2 != 0) return null
        val script =
            try {
                scriptHex.hexToByteArray()
            } catch (_: Exception) {
                return null
            }
        if (script.isEmpty()) return null
        val op = script[0]
        if (op != OP_NAME_UPDATE && op != OP_NAME_FIRSTUPDATE) return null
        var pos = 1
        val (nameBytes, afterName) = readPushData(script, pos) ?: return null
        pos = afterName
        if (op == OP_NAME_FIRSTUPDATE) {
            val (_, afterRand) = readPushData(script, pos) ?: return null
            pos = afterRand
        }
        val (valueBytes, _) = readPushData(script, pos) ?: return null
        return nameBytes.decodeToString() to valueBytes.decodeToString()
    }

    private fun pushData(data: ByteArray): ByteArray =
        when {
            data.size < 0x4c -> byteArrayOf(data.size.toByte()) + data
            data.size <= 0xff -> byteArrayOf(OP_PUSHDATA1, data.size.toByte()) + data
            data.size <= 0xffff ->
                byteArrayOf(OP_PUSHDATA2, (data.size and 0xff).toByte(), ((data.size shr 8) and 0xff).toByte()) + data
            else -> throw IllegalArgumentException("Namecoin push is too large")
        }

    private fun readPushData(
        script: ByteArray,
        pos: Int,
    ): Pair<ByteArray, Int>? {
        if (pos < 0 || pos >= script.size) return null
        val opcode = script[pos].toInt() and 0xff
        val header: Int
        val len: Int
        when {
            opcode == 0 -> return byteArrayOf() to pos + 1
            opcode < 0x4c -> {
                header = 1
                len = opcode
            }
            opcode == 0x4c -> {
                if (pos + 2 > script.size) return null
                header = 2
                len = script[pos + 1].toInt() and 0xff
            }
            opcode == 0x4d -> {
                if (pos + 3 > script.size) return null
                header = 3
                len = (script[pos + 1].toInt() and 0xff) or ((script[pos + 2].toInt() and 0xff) shl 8)
            }
            else -> return null
        }
        val start = pos + header
        val end = start + len
        if (end < start || end > script.size) return null
        return script.copyOfRange(start, end) to end
    }

    private fun asciiBytes(value: String) =
        CharArray(value.length) { index -> if (value[index].code <= 0x7f) value[index] else '?' }
            .concatToString()
            .encodeToByteArray()
}

internal open class ElectrumXProtocolException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal interface ElectrumXExchange {
    suspend fun call(
        requestId: Int,
        method: String,
        params: List<JsonElement> = emptyList(),
    ): JsonElement
}

internal suspend fun queryNamecoinName(
    identifier: String,
    exchange: ElectrumXExchange,
): NameShowResult? {
    exchange.call(1, "server.version", listOf(JsonPrimitive("AmethystNMC/0.1"), JsonPrimitive(ElectrumXProtocol.PROTOCOL_VERSION)))
    val history =
        ElectrumXProtocol.history(
            exchange.call(
                2,
                "blockchain.scripthash.get_history",
                listOf(JsonPrimitive(ElectrumXProtocol.scriptHash(identifier))),
            ),
        )
    if (history.isEmpty()) throw NamecoinLookupException.NameNotFound(identifier)
    val (txHash, height) = history.last()
    val transaction =
        exchange.call(
            3,
            "blockchain.transaction.get",
            listOf(JsonPrimitive(txHash), JsonPrimitive(true)),
        )
    val currentHeight = ElectrumXProtocol.blockHeight(exchange.call(4, "blockchain.headers.subscribe"))
    ElectrumXProtocol.checkExpiry(identifier, height, currentHeight)
    return ElectrumXProtocol.nameFromTransaction(identifier, txHash, height, transaction, currentHeight)
}

/** Incremental LF-delimited decoder with a hard bound on an unfinished line. */
internal class ElectrumXLineDecoder(
    private val maxLineBytes: Int = ElectrumXProtocol.MAX_LINE_BYTES,
) {
    private var pending = byteArrayOf()
    private val lines = ArrayDeque<String>()

    fun append(bytes: ByteArray) {
        var lineStart = 0
        for (index in bytes.indices) {
            if (bytes[index] != 0x0a.toByte()) continue
            val line = pending + bytes.copyOfRange(lineStart, index)
            if (line.size > maxLineBytes) throw ElectrumXProtocolException("ElectrumX response line exceeds $maxLineBytes bytes")
            pending = byteArrayOf()
            val withoutCr = if (line.lastOrNull() == 0x0d.toByte()) line.copyOf(line.size - 1) else line
            lines.addLast(withoutCr.decodeToString())
            lineStart = index + 1
        }
        if (lineStart < bytes.size) pending += bytes.copyOfRange(lineStart, bytes.size)
        if (pending.size > maxLineBytes) throw ElectrumXProtocolException("ElectrumX response line exceeds $maxLineBytes bytes")
    }

    fun nextLine(): String? = lines.removeFirstOrNull()

    fun finish() {
        if (pending.isNotEmpty()) throw ElectrumXProtocolException("ElectrumX connection ended inside a response")
        if (lines.isNotEmpty()) return
        throw ElectrumXProtocolException("ElectrumX connection closed without a response")
    }
}
