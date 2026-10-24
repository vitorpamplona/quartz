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
package com.vitorpamplona.quartz.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Money math must be exact on every platform: JVM, Apple and Linux must agree to the unit. */
class BigDecimalArithmeticTest {
    private val btcToSats = BigDecimal(100_000_000)

    private fun sats(
        amount: Int,
        multiplier: String,
    ) = BigDecimal(amount).multiply(BigDecimal(multiplier)).multiply(btcToSats)

    @Test
    fun lnbc10uIsAThousandSats() {
        assertEquals(1000L, sats(10, "0.000001").toLongValue())
    }

    @Test
    fun invoiceAmountsConvertExactly() {
        for (amount in 1..3000) {
            assertEquals(amount * 100_000L, sats(amount, "0.001").toLongValue(), "${amount}m")
            assertEquals(amount * 100L, sats(amount, "0.000001").toLongValue(), "${amount}u")
            assertEquals(amount / 10L, sats(amount, "0.000000001").toLongValue(), "${amount}n")
        }
    }

    @Test
    fun matchingZapAmountsSubtractToExactlyZero() {
        val invoiceMillisats = sats(10, "0.000001").multiply(BigDecimal(1000))
        assertEquals(0, invoiceMillisats.subtract(BigDecimal(1_000_000)).signum())
    }

    @Test
    fun plainStringIsNeverScientific() {
        val tiny = BigDecimal(10).multiply(BigDecimal("0.000001"))
        val text = tiny.toPlainStringValue()
        assertFalse(text.contains('E') || text.contains('e'), text)
        assertEquals(0, BigDecimal(text).compareToValue(BigDecimal("0.00001")))
    }

    @Test
    fun addsAndSubtractsAcrossScales() {
        assertEquals(0, BigDecimal("0.1").add(BigDecimal("0.2")).compareToValue(BigDecimal("0.3")))
        assertEquals(0, BigDecimal("1").subtract(BigDecimal("0.001")).compareToValue(BigDecimal("0.999")))
        assertEquals(-1, BigDecimal("0.001").subtract(BigDecimal("1")).signum())
        assertEquals(0, BigDecimal("-2.5").negate().compareToValue(BigDecimal("2.5")))
    }

    @Test
    fun dividesExactQuotients() {
        assertEquals(0, BigDecimal(1).divide(BigDecimal(4)).compareToValue(BigDecimal("0.25")))
        assertEquals(0, BigDecimal(100).divide(BigDecimal("0.5")).compareToValue(BigDecimal(200)))
        assertEquals(0, BigDecimal("-7.5").divide(BigDecimal("2.5")).compareToValue(BigDecimal(-3)))
    }
}
