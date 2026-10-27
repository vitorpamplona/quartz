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
package com.vitorpamplona.quartz.nipBCOnchainZaps.chain

import com.vitorpamplona.quartz.utils.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync

/**
 * [OnchainBackend] implementation that talks to an Esplora-compatible HTTP
 * API (mempool.space, blockstream.info, self-hosted).
 *
 * The API surface used:
 * - `GET /tx/{txid}`            — transaction JSON
 * - `GET /address/{addr}/utxo`  — UTXO list for a derived address
 * - `POST /tx`                  — broadcast (body = raw tx hex)
 * - `GET /blocks/tip/height`    — chain tip
 * - `GET /v1/fees/recommended`  — fee tiers (mempool.space); falls back to
 *   `GET /fee-estimates` (the standard Esplora target→rate map) on 404
 *
 * The `baseUrl` is supplied as a function so callers can hot-swap endpoints
 * from `AccountSettings` without rebuilding the backend.
 *
 * @param baseUrl Function returning the base URL with trailing slash stripped
 *                (e.g. "https://mempool.space/api").
 * @param client Shared OkHttp client.
 */
class EsploraBackend(
    private val baseUrl: () -> String,
    private val client: OkHttpClient,
) : OnchainBackend {
    private val logTag = "EsploraBackend"

    override suspend fun getTx(txid: String): BitcoinTx? {
        val url = "${baseUrl()}/tx/$txid"
        val request =
            Request
                .Builder()
                .header("Accept", "application/json")
                .url(url)
                .get()
                .build()
        return client.newCall(request).executeAsync().use { response ->
            when {
                response.code == 404 -> null

                response.isSuccessful -> parseTx(response.body.string())

                else -> throw OnchainBackendException(
                    "GET $url failed: ${response.code} ${response.message}",
                )
            }
        }
    }

    override suspend fun getUtxosForAddress(address: String): List<Utxo> {
        val url = "${baseUrl()}/address/$address/utxo"
        val request =
            Request
                .Builder()
                .header("Accept", "application/json")
                .url(url)
                .get()
                .build()
        return client.newCall(request).executeAsync().use { response ->
            if (!response.isSuccessful) {
                throw OnchainBackendException(
                    "GET $url failed: ${response.code} ${response.message}",
                )
            }
            parseUtxoList(response.body.string())
        }
    }

    override suspend fun getTxsForAddress(
        address: String,
        afterTxid: String?,
    ): List<BitcoinAddressTx> {
        // Esplora returns mempool + most-recent confirmed for the first call,
        // and successive pages of confirmed via /chain/:last_seen_txid.
        val url =
            if (afterTxid == null) {
                "${baseUrl()}/address/$address/txs"
            } else {
                "${baseUrl()}/address/$address/txs/chain/$afterTxid"
            }
        val request =
            Request
                .Builder()
                .header("Accept", "application/json")
                .url(url)
                .get()
                .build()
        return client.newCall(request).executeAsync().use { response ->
            if (!response.isSuccessful) {
                throw OnchainBackendException(
                    "GET $url failed: ${response.code} ${response.message}",
                )
            }
            parseAddressTxList(response.body.string(), address)
        }
    }

    override suspend fun broadcast(rawTxHex: String): String {
        val url = "${baseUrl()}/tx"
        val request =
            Request
                .Builder()
                .url(url)
                .post(rawTxHex.toRequestBody("text/plain".toMediaType()))
                .build()
        return client.newCall(request).executeAsync().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw OnchainBackendException(
                    "POST $url failed: ${response.code} ${response.message} body=$body",
                )
            }
            body.trim()
        }
    }

    override suspend fun tipHeight(): Long {
        val url = "${baseUrl()}/blocks/tip/height"
        val request =
            Request
                .Builder()
                .url(url)
                .get()
                .build()
        return client.newCall(request).executeAsync().use { response ->
            if (!response.isSuccessful) {
                throw OnchainBackendException(
                    "GET $url failed: ${response.code} ${response.message}",
                )
            }
            response.body
                .string()
                .trim()
                .toLong()
        }
    }

    override suspend fun feeEstimates(): FeeEstimates {
        // mempool.space-style recommended-fees endpoint.
        val recommendedUrl = "${baseUrl()}/v1/fees/recommended"
        val recommended =
            Request
                .Builder()
                .header("Accept", "application/json")
                .url(recommendedUrl)
                .get()
                .build()
        client.newCall(recommended).executeAsync().use { response ->
            when {
                response.isSuccessful -> {
                    return parseRecommendedFees(response.body.string())
                }

                // Standard Esplora servers (blockstream.info, self-hosted) don't
                // expose /v1/fees/recommended — fall back to /fee-estimates.
                response.code == 404 -> {
                    Unit
                }

                else -> {
                    throw OnchainBackendException(
                        "GET $recommendedUrl failed: ${response.code} ${response.message}",
                    )
                }
            }
        }

        val estimatesUrl = "${baseUrl()}/fee-estimates"
        val estimates =
            Request
                .Builder()
                .header("Accept", "application/json")
                .url(estimatesUrl)
                .get()
                .build()
        return client.newCall(estimates).executeAsync().use { response ->
            if (!response.isSuccessful) {
                throw OnchainBackendException(
                    "GET $estimatesUrl failed: ${response.code} ${response.message}",
                )
            }
            parseFeeEstimates(response.body.string())
        }
    }

    internal fun parseTx(json: String): BitcoinTx {
        val node = Json.parseToJsonElement(json).obj()
        val txid = node["txid"].text() ?: throw IllegalArgumentException("tx without a txid")
        val status = node["status"].objOrNull()
        val confirmed = status?.get("confirmed").bool()
        val blockHeight = status?.get("block_height").long()
        val blockHash = status?.get("block_hash").text()

        val outputs =
            node["vout"].array().mapIndexed { idx, element ->
                val vout = element.obj()
                BitcoinTxOutput(
                    index = idx,
                    valueSats = vout["value"].long() ?: 0L,
                    scriptPubKeyHex = (vout["scriptpubkey"].text() ?: "").lowercase(),
                )
            }

        val confirmations =
            if (confirmed && blockHeight != null) {
                // Esplora returns confirmations only via /tx/{txid}/status sometimes;
                // we conservatively report 1 here and let callers re-query tip if they
                // need a precise count.
                1
            } else {
                0
            }

        return BitcoinTx(
            txid = txid,
            outputs = outputs,
            confirmations = confirmations,
            blockHashHex = blockHash,
            blockHeight = blockHeight,
        )
    }

    /**
     * Parse an Esplora `/address/:addr/txs[...] `response — an array of full
     * tx objects with vin/vout/status — into address-perspective rows.
     */
    internal fun parseAddressTxList(
        json: String,
        address: String,
    ): List<BitcoinAddressTx> =
        Json.parseToJsonElement(json).array().map { element ->
            val tx = element.obj()
            val txid = tx["txid"].text() ?: throw IllegalArgumentException("tx without a txid")
            val status = tx["status"].objOrNull()
            val confirmed = status?.get("confirmed").bool()
            val blockHeight = status?.get("block_height").long()
            val blockTime = status?.get("block_time").long()

            var spentByMe = 0L
            val incomingCounterparties = LinkedHashSet<String>()
            (tx["vin"] as? JsonArray)?.forEach { vin ->
                val prevout = vin.objOrNull()?.get("prevout").objOrNull()
                val prevAddress = prevout?.get("scriptpubkey_address").text()
                val value = prevout?.get("value").long() ?: 0L
                if (prevAddress == address) {
                    spentByMe += value
                } else if (prevAddress != null) {
                    incomingCounterparties.add(prevAddress)
                }
            }

            var receivedByMe = 0L
            val outgoingCounterparties = LinkedHashSet<String>()
            (tx["vout"] as? JsonArray)?.forEach { element ->
                val vout = element.objOrNull()
                val outAddress = vout?.get("scriptpubkey_address").text()
                val value = vout?.get("value").long() ?: 0L
                if (outAddress == address) {
                    receivedByMe += value
                } else if (outAddress != null) {
                    outgoingCounterparties.add(outAddress)
                }
            }

            val net = receivedByMe - spentByMe
            val counterparties =
                if (net >= 0L) incomingCounterparties.toList() else outgoingCounterparties.toList()

            BitcoinAddressTx(
                txid = txid,
                netValueSats = net,
                confirmations = if (confirmed) 1 else 0,
                blockHeight = blockHeight,
                blockTime = blockTime,
                counterpartyAddresses = counterparties,
            )
        }

    internal fun parseUtxoList(json: String): List<Utxo> =
        Json.parseToJsonElement(json).array().map { element ->
            val utxo = element.obj()
            val confirmed = utxo["status"].objOrNull()?.get("confirmed").bool()
            Utxo(
                txid = utxo["txid"].text() ?: throw IllegalArgumentException("utxo without a txid"),
                vout = utxo["vout"].long()?.toInt() ?: 0,
                valueSats = utxo["value"].long() ?: 0L,
                confirmations = if (confirmed) 1 else 0,
            )
        }

    /** mempool.space `/v1/fees/recommended`: `{ fastestFee, halfHourFee, hourFee, minimumFee }`. */
    internal fun parseRecommendedFees(json: String): FeeEstimates {
        val node = Json.parseToJsonElement(json).obj()
        val fast = node["fastestFee"].double()
        val normal = node["halfHourFee"].double() ?: fast
        val slow = node["hourFee"].double() ?: node["minimumFee"].double() ?: normal

        if (fast == null) {
            Log.w(logTag) { "fee response missing 'fastestFee': $json" }
            return FeeEstimates(20.0, 10.0, 5.0)
        }
        return FeeEstimates(
            fastSatPerVbyte = fast,
            normalSatPerVbyte = normal ?: fast,
            slowSatPerVbyte = slow ?: fast,
        )
    }

    /**
     * Standard Esplora `/fee-estimates`: a JSON object mapping a confirmation
     * target (in blocks, as a string key) to the estimated sat/vB. We map the
     * 2-block / 6-block / 144-block targets to the fast / normal / slow tiers.
     */
    internal fun parseFeeEstimates(json: String): FeeEstimates {
        val node = Json.parseToJsonElement(json).obj()

        fun rate(vararg targets: String): Double? = targets.firstNotNullOfOrNull { node[it].double() }

        val fast = rate("1", "2")
        val normal = rate("4", "6", "3")
        val slow = rate("144", "1008", "10")

        if (fast == null) {
            Log.w(logTag) { "fee-estimates response missing block targets: $json" }
            return FeeEstimates(20.0, 10.0, 5.0)
        }
        return FeeEstimates(
            fastSatPerVbyte = fast,
            normalSatPerVbyte = normal ?: fast,
            slowSatPerVbyte = slow ?: normal ?: fast,
        )
    }

    private fun JsonElement.obj(): JsonObject = this as? JsonObject ?: throw IllegalArgumentException("expected a JSON object")

    private fun JsonElement?.objOrNull(): JsonObject? = this as? JsonObject

    private fun JsonElement?.array(): JsonArray = this as? JsonArray ?: throw IllegalArgumentException("expected a JSON array")

    private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

    private fun JsonElement?.double(): Double? = (this as? JsonPrimitive)?.doubleOrNull

    private fun JsonElement?.bool(): Boolean = (this as? JsonPrimitive)?.booleanOrNull == true

    companion object {
        const val MEMPOOL_API_URL = "https://mempool.space/api"
        const val BLOCKSTREAM_API_URL = "https://blockstream.info/api"
    }
}
