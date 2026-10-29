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
package com.vitorpamplona.quartz.experimental.predictionMarkets

import com.vitorpamplona.quartz.experimental.predictionMarkets.tags.MarketCategoryTag
import com.vitorpamplona.quartz.experimental.predictionMarkets.tags.MarketDataTag
import com.vitorpamplona.quartz.experimental.predictionMarkets.tags.MarketEndTag
import com.vitorpamplona.quartz.experimental.predictionMarkets.tags.MarketNetworkTag
import com.vitorpamplona.quartz.experimental.predictionMarkets.tags.MarketStatusTag
import com.vitorpamplona.quartz.experimental.predictionMarkets.tags.MarketTag
import com.vitorpamplona.quartz.experimental.predictionMarkets.tags.OutcomeTag
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.fastFirstNotNullOfOrNull
import com.vitorpamplona.quartz.nip01Core.core.fastForEach

/** The first non-blank value of a tag named [name], or null. */
private fun TagArray.firstNonBlankValue(name: String): String? = fastFirstNotNullOfOrNull { if (it.size > 1 && it[0] == name && it[1].isNotBlank()) it[1] else null }

fun TagArray.marketId() = fastFirstNotNullOfOrNull(MarketTag::parse)

fun TagArray.marketTitle() = firstNonBlankValue("title")

/** The JSON blob of the `data` tag BAO Markets attaches to its current event shape. */
fun TagArray.marketData() = fastFirstNotNullOfOrNull(MarketDataTag::parse)

fun TagArray.marketType() = firstNonBlankValue("type")

/**
 * The declared lifecycle state, tried by tag name in priority order: `status` first, then the
 * older `state` and the one-letter `s`. BAO Markets writes all three but lets them drift apart
 * (an "active" status next to an "ended" state), and `status` is the one it keeps current.
 */
fun TagArray.marketStatusValues(): List<String> = listOfNotNull(fastFirstNotNullOfOrNull(MarketStatusTag::parse), firstNonBlankValue("state"), firstNonBlankValue("s"))

/** When betting closes, unix seconds. */
fun TagArray.marketEnd() = fastFirstNotNullOfOrNull(MarketEndTag::parse)?.trim()?.toLongOrNull()

fun TagArray.marketNetwork() = fastFirstNotNullOfOrNull(MarketNetworkTag::parse) ?: firstNonBlankValue("n")

fun TagArray.marketCategory() = fastFirstNotNullOfOrNull(MarketCategoryTag::parse) ?: firstNonBlankValue("c")

/**
 * Every `outcome` tag, in order, once per id and at most [PredictionMarketOutcome.MAX]. The BAO
 * Fund shape writes `["outcome", id, label]`; the current shape a bare word, its own label.
 */
fun TagArray.marketOutcomes(): List<PredictionMarketOutcome> {
    val result = LinkedHashMap<String, PredictionMarketOutcome>(4)
    fastForEach {
        if (result.size < PredictionMarketOutcome.MAX) {
            val outcome = OutcomeTag.parse(it)
            if (outcome != null && outcome.id !in result) result[outcome.id] = outcome
        }
    }
    return result.values.toList()
}

/** The winning outcome, once the market is resolved. */
fun TagArray.marketResolution() = firstNonBlankValue("resolution")

fun TagArray.marketResolutionSource() = firstNonBlankValue("resolution_source")

fun TagArray.marketMinBet() = firstNonBlankValue("min_bet")?.trim()?.toLongOrNull()

fun TagArray.marketMaxBet() = firstNonBlankValue("max_bet")?.trim()?.toLongOrNull()

fun TagArray.marketFeePercent() = firstNonBlankValue("fee_percent")?.trim()?.toDoubleOrNull()

fun TagArray.marketOracle() = firstNonBlankValue("oracle")

fun TagArray.marketCancelReason() = firstNonBlankValue("cancel_reason")

fun TagArray.marketPoolModel() = firstNonBlankValue("pool_model")

fun TagArray.marketClient() = firstNonBlankValue("client")
