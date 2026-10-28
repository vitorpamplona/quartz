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
package com.vitorpamplona.quartz.nip05DnsIdentifiers

/**
 * Why [Nip05Fetcher.fetch] got no document. The cases call for different handling: a [Status]
 * is the domain answering about this address, while [Unreachable] says nothing about the
 * address at all, so a caller that remembers verdicts should retry it rather than record it.
 *
 * All of them are [IllegalStateException]s, which is what a failed fetch threw before, so
 * existing `catch` blocks still apply. [Nip05Client] wraps them; the original is the `cause`.
 */
sealed class Nip05FetchException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause) {
    /**
     * The server answered with [code] instead of a document. 404 and 410 say the address isn't
     * there. A redirect lands here too: NIP-05 fetchers must not follow one. A 403 is often a
     * firewall turning crawlers away, and a 5xx a server fault, neither about the address.
     */
    class Status(
        val code: Int,
    ) : Nip05FetchException("HTTP $code")

    /** The document is longer than [limitBytes]; the rest was not read. */
    class TooLarge(
        val limitBytes: Long,
    ) : Nip05FetchException("NIP-05 document over $limitBytes bytes")

    /** Never asked: the client refused the URL (for example, a host that isn't public). */
    class Refused(
        reason: String,
        cause: Throwable? = null,
    ) : Nip05FetchException(reason, cause)

    /** No answer: DNS, connection, TLS or timeout. Says nothing about the address. */
    class Unreachable(
        cause: Throwable,
    ) : Nip05FetchException(cause.message ?: cause::class.simpleName ?: "unreachable", cause)
}
