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
package com.vitorpamplona.quartz.nip05.namecoin

import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.ElectrumxServer
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.IElectrumXClient
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.NameShowResult
import com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin.NamecoinLookupException

internal const val TEST_PUBKEY_A = "aaaa000000000000000000000000000000000000000000000000000000000001"
internal const val TEST_PUBKEY_B = "bbbb000000000000000000000000000000000000000000000000000000000002"
internal const val TEST_PUBKEY_C = "cccc000000000000000000000000000000000000000000000000000000000003"
internal const val TEST_PUBKEY_D = "dddd000000000000000000000000000000000000000000000000000000000004"

internal class RecordingElectrumXClient : IElectrumXClient {
    private val records = mutableMapOf<String, String>()
    private val failures = mutableMapOf<String, NamecoinLookupException>()
    val queriedNames = mutableListOf<String>()
    var beforeLookup: (suspend (String) -> Unit)? = null

    fun register(
        name: String,
        value: String,
    ) = apply {
        records[name] = value
    }

    fun fail(
        name: String,
        failure: NamecoinLookupException,
    ) = apply {
        failures[name] = failure
    }

    override suspend fun nameShowWithFallback(
        identifier: String,
        servers: List<ElectrumxServer>,
    ): NameShowResult? {
        queriedNames += identifier
        beforeLookup?.invoke(identifier)
        failures[identifier]?.let { throw it }
        val value = records[identifier] ?: return null
        return NameShowResult(name = identifier, value = value)
    }
}
