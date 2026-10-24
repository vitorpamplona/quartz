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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/** The Linux actual follows java.math.BigDecimal where the platforms are allowed to differ. */
class BigDecimalLinuxTest {
    @Test
    fun multiplyAddsScalesLikeTheJvm() {
        assertEquals("0.000010", BigDecimal(10).multiply(BigDecimal("0.000001")).toPlainStringValue())
        assertEquals("1000.00000000", BigDecimal("0.00001").multiply(BigDecimal("100000000.000")).toPlainStringValue())
    }

    @Test
    fun equalsComparesScaleLikeTheJvm() {
        assertNotEquals(BigDecimal("1.0"), BigDecimal("1.00"))
        assertEquals(BigDecimal("1.0"), BigDecimal("1.0"))
    }

    @Test
    fun parsesSignsPointsAndExponents() {
        assertEquals("-0.5", BigDecimal("-.5").toPlainStringValue())
        assertEquals("1500", BigDecimal("1.5E3").toPlainStringValue())
        assertEquals("0.0015", BigDecimal("1.5e-3").toPlainStringValue())
        assertEquals("12", BigDecimal(" +12 ").toPlainStringValue())
    }

    @Test
    fun rejectsTextThatIsNotADecimal() {
        assertFailsWith<NumberFormatException> { BigDecimal("abc") }
        assertFailsWith<NumberFormatException> { BigDecimal("1.2.3") }
        assertFailsWith<NumberFormatException> { BigDecimal("") }
        assertFailsWith<NumberFormatException> { BigDecimal(Double.NaN) }
    }

    @Test
    fun nonTerminatingDivisionThrowsLikeTheJvm() {
        assertFailsWith<ArithmeticException> { BigDecimal(1).divide(BigDecimal(3)) }
        assertFailsWith<ArithmeticException> { BigDecimal(1).divide(BigDecimal(0)) }
    }

    @Test
    fun toLongTruncatesTowardZero() {
        assertEquals(2L, BigDecimal("2.9").toLongValue())
        assertEquals(-2L, BigDecimal("-2.9").toLongValue())
    }
}
