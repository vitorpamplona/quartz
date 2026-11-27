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
package com.vitorpamplona.quartz.nip55AndroidSigner.api.payloads

/**
 * The handles signers retained after a background query (`retain_payload`), waiting for the
 * Intent that passes them instead of staging the payload again. Each entry holds its payload in
 * memory, so entries leave once handed out, after [ttlSeconds], or when a signer has more than
 * [maxPerSigner] newer ones.
 *
 * @param H the handle's type (a `Uri` in production).
 */
internal class RetainedPayloads<H>(
    private val ttlSeconds: Long,
    private val maxPerSigner: Int,
) {
    private class Entry<H>(
        val signer: String,
        val payload: String,
        val handle: H,
        val keptAt: Long,
    )

    private val entries = ArrayList<Entry<H>>()

    /**
     * Keeps [handle] for [payload] and returns the older handles of [signer] it evicted, which
     * the caller releases on the signer: no Intent will pass them.
     */
    fun keep(
        signer: String,
        payload: String,
        handle: H,
        now: Long,
    ): List<H> =
        synchronized(entries) {
            forgetExpired(now)
            entries.add(Entry(signer, payload, handle, now))
            val ofThisSigner = entries.filter { it.signer == signer }
            if (ofThisSigner.size <= maxPerSigner) return emptyList()
            val evicted = ofThisSigner.take(ofThisSigner.size - maxPerSigner)
            entries.removeAll(evicted.toSet())
            evicted.map { it.handle }
        }

    /** The fresh handle [signer] retained for [payload], removed so it is handed out once. */
    fun take(
        signer: String,
        payload: String,
        now: Long,
    ): H? =
        synchronized(entries) {
            forgetExpired(now)
            val index = entries.indexOfFirst { it.signer == signer && it.payload.length == payload.length && it.payload == payload }
            if (index < 0) null else entries.removeAt(index).handle
        }

    fun forgetExpired(now: Long) {
        synchronized(entries) {
            entries.removeAll { now - it.keptAt >= ttlSeconds }
        }
    }

    fun size(): Int = synchronized(entries) { entries.size }
}
