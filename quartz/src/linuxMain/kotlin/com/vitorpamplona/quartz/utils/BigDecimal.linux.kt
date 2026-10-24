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

/**
 * Linux actual: an exact decimal, `unscaled × 10^-scale`, like `java.math.BigDecimal` and Apple's
 * `NSDecimalNumber`. Kotlin/Native has no big-integer type, so the magnitude is an arbitrary-length
 * array of base-10 digits; the amounts Quartz handles (lightning invoices, zap totals) are a few
 * dozen digits at most.
 *
 * It used to do every operation in `Double`, which made Linux disagree with the other platforms on
 * plain money: `10 × 0.000001 × 100000000` came out as 999.9999999999999, so `lnbc10u` read as 999
 * sats, and a zap whose invoice and request matched was rejected as mismatched.
 *
 * As on the JVM: [multiply] adds the scales, [add] and [subtract] keep the larger one, [divide]
 * returns the exact quotient or throws [ArithmeticException] when it doesn't terminate, and a
 * string that isn't a decimal throws [NumberFormatException]. `equals` compares value and scale.
 */
actual class BigDecimal private constructor(
    /** -1, 0 or 1. */
    private val sign: Int,
    private val magnitude: Digits,
    /** Digits after the decimal point; never negative. */
    private val scale: Int,
) : Number() {
    actual constructor(strVal: String) : this(parse(strVal))

    actual constructor(doubleVal: Double) : this(parse(doubleVal.toPlainDecimalString()))

    actual constructor(intVal: Int) : this(parse(intVal.toString()))

    actual constructor(longVal: Long) : this(parse(longVal.toString()))

    private constructor(other: BigDecimal) : this(other.sign, other.magnitude, other.scale)

    actual fun add(augend: BigDecimal): BigDecimal {
        val scale = maxOf(scale, augend.scale)
        val a = magnitude.shiftLeft(scale - this.scale)
        val b = augend.magnitude.shiftLeft(scale - augend.scale)
        return when {
            sign == 0 -> of(augend.sign, b, scale)
            augend.sign == 0 -> of(sign, a, scale)
            sign == augend.sign -> of(sign, a + b, scale)
            else -> {
                val cmp = a.compareTo(b)
                when {
                    cmp == 0 -> of(0, Digits.ZERO, scale)
                    cmp > 0 -> of(sign, a - b, scale)
                    else -> of(augend.sign, b - a, scale)
                }
            }
        }
    }

    actual fun subtract(subtrahend: BigDecimal): BigDecimal = add(subtrahend.negate())

    actual fun multiply(multiplicand: BigDecimal): BigDecimal = of(sign * multiplicand.sign, magnitude * multiplicand.magnitude, scale + multiplicand.scale)

    actual fun divide(divisor: BigDecimal): BigDecimal {
        if (divisor.sign == 0) throw ArithmeticException("Division by zero")
        // For a result scale r, the quotient is magnitude × 10^(r + divisor.scale - scale) / divisor.magnitude.
        // Start at the preferred scale (scale - divisor.scale, floored at 0) and add digits until it is exact.
        val preferred = maxOf(0, scale - divisor.scale)
        for (resultScale in preferred..preferred + MAX_EXTRA_DIVISION_DIGITS) {
            val numerator = magnitude.shiftLeft(resultScale + divisor.scale - scale)
            val (quotient, remainder) = numerator.divRem(divisor.magnitude)
            if (remainder.isZero()) return of(sign * divisor.sign, quotient, resultScale)
        }
        throw ArithmeticException("Non-terminating decimal expansion; no exact representable decimal result.")
    }

    actual fun negate(): BigDecimal = of(-sign, magnitude, scale)

    actual fun signum(): Int = sign

    /** Numeric order, ignoring scale: 1.0 and 1.00 compare equal. */
    internal fun compareValue(other: BigDecimal): Int {
        if (sign != other.sign) return sign.compareTo(other.sign)
        val scale = maxOf(scale, other.scale)
        val cmp = magnitude.shiftLeft(scale - this.scale).compareTo(other.magnitude.shiftLeft(scale - other.scale))
        return if (sign < 0) -cmp else cmp
    }

    /** Plain notation, never scientific: "-0.000010", "1500". */
    internal fun toPlainString(): String {
        val digits = magnitude.toString()
        val body =
            if (scale == 0) {
                digits
            } else {
                val padded = digits.padStart(scale + 1, '0')
                padded.substring(0, padded.length - scale) + "." + padded.substring(padded.length - scale)
            }
        return if (sign < 0) "-$body" else body
    }

    /** Truncates toward zero, keeping the low-order 64 bits like `java.math.BigDecimal.toLong()`. */
    override fun toLong(): Long {
        val integer = magnitude.toString().dropLast(scale).ifEmpty { "0" }
        var result = 0L
        for (c in integer) result = result * 10 + (c - '0')
        return if (sign < 0) -result else result
    }

    override fun toInt(): Int = toLong().toInt()

    override fun toShort(): Short = toLong().toInt().toShort()

    override fun toByte(): Byte = toLong().toInt().toByte()

    override fun toDouble(): Double = toPlainString().toDouble()

    override fun toFloat(): Float = toPlainString().toFloat()

    override fun equals(other: Any?): Boolean = this === other || (other is BigDecimal && sign == other.sign && scale == other.scale && magnitude == other.magnitude)

    override fun hashCode(): Int = (magnitude.hashCode() * 31 + scale) * 31 + sign

    override fun toString(): String = toPlainString()

    private companion object {
        /** Past this many digits beyond the preferred scale, a quotient is treated as non-terminating. */
        const val MAX_EXTRA_DIVISION_DIGITS = 64

        fun of(
            sign: Int,
            magnitude: Digits,
            scale: Int,
        ): BigDecimal = if (magnitude.isZero()) BigDecimal(0, Digits.ZERO, scale) else BigDecimal(sign, magnitude, scale)

        fun parse(text: String): BigDecimal {
            val s = text.trim()
            var i = 0
            var negative = false
            if (i < s.length && (s[i] == '+' || s[i] == '-')) {
                negative = s[i] == '-'
                i++
            }
            val digits = StringBuilder()
            var scale = 0
            var seenPoint = false
            var seenDigit = false
            while (i < s.length && (s[i].isAsciiDigit() || s[i] == '.')) {
                if (s[i] == '.') {
                    if (seenPoint) throw NumberFormatException("Not a decimal: $text")
                    seenPoint = true
                } else {
                    digits.append(s[i])
                    seenDigit = true
                    if (seenPoint) scale++
                }
                i++
            }
            if (!seenDigit) throw NumberFormatException("Not a decimal: $text")
            if (i < s.length) {
                if (s[i] != 'e' && s[i] != 'E') throw NumberFormatException("Not a decimal: $text")
                val exponent = s.substring(i + 1).toIntOrNull() ?: throw NumberFormatException("Not a decimal: $text")
                scale -= exponent
            }
            var magnitude = Digits.parse(digits.toString())
            if (scale < 0) {
                magnitude = magnitude.shiftLeft(-scale)
                scale = 0
            }
            return of(if (negative) -1 else 1, magnitude, scale)
        }

        fun Char.isAsciiDigit() = this in '0'..'9'

        /** Like `java.math.BigDecimal.valueOf(double)`: the double's shortest decimal form. */
        fun Double.toPlainDecimalString(): String {
            if (isNaN() || isInfinite()) throw NumberFormatException("Infinite or NaN")
            return toString()
        }
    }
}

/** A non-negative integer as base-10 digits, least significant first, without leading zeros. */
internal class Digits private constructor(
    private val d: IntArray,
) {
    fun isZero() = d.isEmpty()

    operator fun compareTo(other: Digits): Int {
        if (d.size != other.d.size) return d.size.compareTo(other.d.size)
        for (i in d.indices.reversed()) {
            if (d[i] != other.d[i]) return d[i].compareTo(other.d[i])
        }
        return 0
    }

    operator fun plus(other: Digits): Digits {
        val out = IntArray(maxOf(d.size, other.d.size) + 1)
        var carry = 0
        for (i in out.indices) {
            val sum = (d.getOrElse(i) { 0 }) + (other.d.getOrElse(i) { 0 }) + carry
            out[i] = sum % 10
            carry = sum / 10
        }
        return trimmed(out)
    }

    /** Requires this >= [other]. */
    operator fun minus(other: Digits): Digits {
        val out = IntArray(d.size)
        var borrow = 0
        for (i in d.indices) {
            var diff = d[i] - (other.d.getOrElse(i) { 0 }) - borrow
            borrow = if (diff < 0) 1 else 0
            if (diff < 0) diff += 10
            out[i] = diff
        }
        return trimmed(out)
    }

    operator fun times(other: Digits): Digits {
        if (isZero() || other.isZero()) return ZERO
        val out = IntArray(d.size + other.d.size)
        for (i in d.indices) {
            var carry = 0
            for (j in other.d.indices) {
                val cur = out[i + j] + d[i] * other.d[j] + carry
                out[i + j] = cur % 10
                carry = cur / 10
            }
            var k = i + other.d.size
            while (carry > 0) {
                val cur = out[k] + carry
                out[k] = cur % 10
                carry = cur / 10
                k++
            }
        }
        return trimmed(out)
    }

    /** This × 10^[places]. */
    fun shiftLeft(places: Int): Digits = if (places == 0 || isZero()) this else Digits(IntArray(places) + d)

    /** Schoolbook long division; [divisor] must not be zero. */
    fun divRem(divisor: Digits): Pair<Digits, Digits> {
        val quotient = IntArray(d.size)
        var remainder = ZERO
        for (i in d.indices.reversed()) {
            remainder = remainder.shiftLeft(1) + of(d[i])
            var q = 0
            while (remainder >= divisor) {
                remainder -= divisor
                q++
            }
            quotient[i] = q
        }
        return trimmed(quotient) to remainder
    }

    override fun equals(other: Any?) = other is Digits && d.contentEquals(other.d)

    override fun hashCode() = d.contentHashCode()

    override fun toString(): String = if (isZero()) "0" else buildString { for (i in d.indices.reversed()) append(('0' + d[i])) }

    companion object {
        val ZERO = Digits(IntArray(0))

        fun of(digit: Int): Digits = if (digit == 0) ZERO else Digits(intArrayOf(digit))

        fun parse(text: String): Digits = trimmed(IntArray(text.length) { text[text.length - 1 - it] - '0' })

        private fun trimmed(digits: IntArray): Digits {
            var size = digits.size
            while (size > 0 && digits[size - 1] == 0) size--
            return Digits(if (size == digits.size) digits else digits.copyOf(size))
        }
    }
}
