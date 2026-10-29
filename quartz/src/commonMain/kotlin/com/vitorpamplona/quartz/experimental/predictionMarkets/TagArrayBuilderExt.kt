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
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder

fun TagArrayBuilder<PredictionMarketEvent>.marketId(marketId: String) = addUnique(MarketTag.assemble(marketId))

fun TagArrayBuilder<PredictionMarketEvent>.marketStatus(status: String) = addUnique(MarketStatusTag.assemble(status))

fun TagArrayBuilder<PredictionMarketEvent>.marketEnd(endsAt: Long) = addUnique(MarketEndTag.assemble(endsAt))

fun TagArrayBuilder<PredictionMarketEvent>.marketNetwork(network: String) = addUnique(MarketNetworkTag.assemble(network))

fun TagArrayBuilder<PredictionMarketEvent>.marketCategory(category: String) = addUnique(MarketCategoryTag.assemble(category))

fun TagArrayBuilder<PredictionMarketEvent>.outcomes(outcomes: List<PredictionMarketOutcome>) = addAll(outcomes.map { OutcomeTag.assemble(it) })

fun TagArrayBuilder<PredictionMarketEvent>.marketData(json: String) = addUnique(MarketDataTag.assemble(json))
