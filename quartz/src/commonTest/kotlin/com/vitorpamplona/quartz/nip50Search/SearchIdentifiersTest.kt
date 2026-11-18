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

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchIdentifiersTest {
    @Test
    fun isbnsAreFoundByBothFormsHoweverTheyAreWritten() {
        val both = listOf("9780306406157", "0306406152")
        listOf(
            "9780306406157",
            "978-0-306-40615-7",
            "978 0 306 40615 7",
            "ISBN 978-0-306-40615-7",
            "ISBN-13: 978-0-306-40615-7",
            "isbn:9780306406157",
            "0306406152",
            "0-306-40615-2",
            "ISBN-10 0-306-40615-2",
        ).forEach { assertEquals(both, searchIdentifierForms(it).take(2).sortedDescending(), it) }
    }

    @Test
    fun anIsbn10WithAnXCheckDigitKeepsItUpperCase() {
        assertEquals(listOf("9780575076815", "057507681X"), searchIdentifierForms("0-575-07681-x"))
        assertEquals(listOf("9780226360935", "0226360938"), searchIdentifierForms("9780226360935"))
    }

    @Test
    fun a979IsbnHasNoIsbn10() {
        assertEquals(listOf("9791090636071"), searchIdentifierForms("979-10-90636-07-1"))
    }

    @Test
    fun aWrongCheckDigitIsNotAnIsbn() {
        assertEquals(emptyList(), searchIdentifierForms("9781534445145"))
        assertEquals(emptyList(), searchIdentifierForms("9780306406158"))
        // Grouped, the same digits still read as a phone number's: no ISBN-10 is derived.
        assertEquals(listOf("9780306406158"), searchIdentifierForms("978-0-306-40615-8"))
    }

    @Test
    fun internationalNumbersDropTheirCountryCodeToo() {
        val us = listOf("15551234567", "5551234567", "551234567", "51234567")
        listOf("+15551234567", "+1 555 123 4567", "+1-555-123-4567", "+1 (555) 123-4567", "tel:+1-555-123-4567").forEach {
            assertEquals(us, searchIdentifierForms(it), it)
        }
        assertEquals(listOf("442079460958", "42079460958", "2079460958", "079460958"), searchIdentifierForms("+44 20 7946 0958"))
    }

    @Test
    fun nationalNumbersNeedTheirGroupsAndTenDigits() {
        assertEquals(listOf("5551234567"), searchIdentifierForms("(555) 123-4567"))
        assertEquals(listOf("5551234567"), searchIdentifierForms("555.123.4567"))
        assertEquals(listOf("02079460958"), searchIdentifierForms("020 7946 0958"))
        assertEquals(listOf("15551234567", "5551234567"), searchIdentifierForms("1-555-123-4567"))
        assertEquals(listOf("5551234"), searchIdentifierForms("tel:555-1234;ext=12"))
    }

    @Test
    fun otherNumbersAreNotPhones() {
        listOf(
            "1788453464", // a timestamp: no groups
            "5551234567", // bare digits: no sign it is a phone
            "555-1234", // seven digits without a sign
            "2026-06-01", // a date
            "01.06.2026",
            "1914-1918", // a range of years
            "192.168.100.200", // an IPv4 address
            "40.71277600", // a decimal
            "1,234,567",
            "12:30:45",
            "+1234567", // seven digits after a +
            "vcavallo",
            "OL1141875W",
            "",
        ).forEach { assertEquals(emptyList(), searchIdentifierForms(it), it) }
    }
}
