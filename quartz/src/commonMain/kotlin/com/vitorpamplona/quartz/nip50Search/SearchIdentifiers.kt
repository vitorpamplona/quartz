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
package com.vitorpamplona.quartz.nip50Search

/**
 * The canonical search forms of an identifier people write in many formats, or an empty list
 * when [value] is not one. Recognized:
 *
 * - **ISBNs**, validated by their check digit, which turns away most other 10- and 13-digit
 *   numbers (timestamps, amounts): one in eleven passes for ten digits, and one in ten of those
 *   that start with `978` or `979` for thirteen, and a false match only adds an extra token. Spaces, hyphens and an `ISBN`, `ISBN-10`, `ISBN-13` or `isbn:`
 *   prefix are ignored. Forms: the ISBN-13 digits, then, for a `978` ISBN, its ISBN-10 (with an
 *   upper-case `X` check digit), so a book stored either way is found by either.
 * - **Phone numbers**, recognized only by an explicit sign, since bare digits cannot be told from
 *   any other number: a `tel:` URI, a leading `+` (8–15 digits), or 10–15 digits in at least two
 *   groups separated by spaces, hyphens, dots or parentheses (`(555) 123-4567`, `020 7946 0958`).
 *   Dotted pairs and quads (decimals, IPv4 addresses) are not phones. Forms: the digits alone;
 *   for an international number also the digits without a 1-, 2- or 3-digit country code (the
 *   code's length is not knowable without numbering tables), and for an 11-digit number
 *   written `1-…` without a `+`, the 10 digits after the `1`.
 *
 * Index BOTH sides with this. A store adds the forms of each stored value beside the value
 * itself, and a query term that has forms matches when the term OR any of its forms does: the
 * tokenizer splits `978-0-306-40615-7` into five numbers, and only the canonical forms meet a
 * value that was written another way.
 */
fun searchIdentifierForms(value: String): List<String> {
    var start = 0
    var end = value.length
    while (start < end && value[start].isWhitespace()) start++
    while (end > start && value[end - 1].isWhitespace()) end--
    if (end - start < 7) return emptyList()
    // Cheap reject first: the read path calls this once per tag value per keystroke, and almost
    // no value starts the way an ISBN or a phone number can.
    when (value[start]) {
        in '0'..'9', '+', '(', 'i', 'I', 't', 'T' -> {}
        else -> return emptyList()
    }
    val isbn = isbnForms(value, start, end)
    val phone = phoneForms(value, start, end)
    // Both can hold: `555-123-4567` passes the ISBN-10 check one time in eleven.
    return when {
        isbn == null -> phone ?: emptyList()
        phone == null -> isbn
        else -> (isbn + phone).distinct()
    }
}

private fun isbnForms(
    v: String,
    start: Int,
    end: Int,
): List<String>? {
    var i = start
    if (v.startsWith("isbn", i, ignoreCase = true)) {
        i += 4
        if (v.startsWith("-13", i) || v.startsWith("-10", i)) i += 3
        while (i < end && (v[i] == ':' || v[i] == ' ')) i++
    }
    val compact = StringBuilder(13)
    while (i < end) {
        val c = v[i++]
        when {
            c in '0'..'9' -> compact.append(c)
            c == 'X' || c == 'x' -> compact.append('X')
            c == '-' || c == ' ' -> {}
            else -> return null
        }
        if (compact.length > 13) return null
    }
    val isbn = compact.toString()
    return when (isbn.length) {
        13 -> {
            if (!isbn13Valid(isbn)) return null
            if (isbn.startsWith("978")) listOf(isbn, isbn10Of(isbn)) else listOf(isbn)
        }

        10 -> {
            if (!isbn10Valid(isbn)) null else listOf(isbn13Of(isbn), isbn)
        }

        else -> {
            null
        }
    }
}

private fun isbn13Valid(isbn: String): Boolean {
    if (!isbn.startsWith("978") && !isbn.startsWith("979")) return false
    var sum = 0
    for (i in 0 until 13) {
        val c = isbn[i]
        if (c !in '0'..'9') return false
        sum += (c - '0') * (if (i % 2 == 0) 1 else 3)
    }
    return sum % 10 == 0
}

private fun isbn10Valid(isbn: String): Boolean {
    var sum = 0
    for (i in 0 until 10) {
        val c = isbn[i]
        val digit =
            when {
                c in '0'..'9' -> c - '0'
                c == 'X' && i == 9 -> 10
                else -> return false
            }
        sum += digit * (10 - i)
    }
    return sum % 11 == 0
}

/** The ISBN-13 of a valid ISBN-10: `978`, its first nine digits, a new check digit. */
private fun isbn13Of(isbn10: String): String {
    val body = "978" + isbn10.substring(0, 9)
    var sum = 0
    for (i in 0 until 12) sum += (body[i] - '0') * (if (i % 2 == 0) 1 else 3)
    return body + ((10 - sum % 10) % 10)
}

/** The ISBN-10 of a valid `978` ISBN-13: its nine digits after `978`, a new check digit. */
private fun isbn10Of(isbn13: String): String {
    val body = isbn13.substring(3, 12)
    var sum = 0
    for (i in 0 until 9) sum += (body[i] - '0') * (10 - i)
    val check = (11 - sum % 11) % 11
    return body + (if (check == 10) "X" else check.toString())
}

private fun phoneForms(
    v: String,
    start: Int,
    valueEnd: Int,
): List<String>? {
    var i = start
    var end = valueEnd
    val explicit = v.startsWith("tel:", i, ignoreCase = true)
    if (explicit) {
        i += 4
        // A tel: URI may carry `;ext=…` or other parameters after the number.
        val params = v.indexOf(';', i)
        if (params in i until end) end = params
    }
    val international = i < end && v[i] == '+'
    if (international) i++

    val digits = StringBuilder(15)
    var groups = 0
    var inGroup = false
    var onlyDots = true
    var separators = 0
    while (i < end) {
        val c = v[i++]
        if (c in '0'..'9') {
            if (!inGroup) groups++
            inGroup = true
            digits.append(c)
            if (digits.length > 15) return null
        } else if (c == ' ' || c == '-' || c == '.' || c == '(' || c == ')') {
            inGroup = false
            separators++
            if (c != '.') onlyDots = false
        } else {
            return null
        }
    }

    val n = digits.length
    val isPhone =
        when {
            explicit -> n >= 7
            international -> n >= 8
            else -> n >= 10 && groups >= 2 && !(onlyDots && separators > 0 && (groups == 2 || groups == 4))
        }
    if (!isPhone) return null

    val all = digits.toString()
    val forms = ArrayList<String>(4)
    forms.add(all)
    if (international) {
        for (code in 1..3) if (n - code >= 7) forms.add(all.substring(code))
    } else if (!explicit && n == 11 && all[0] == '1') {
        forms.add(all.substring(1))
    }
    return forms
}
