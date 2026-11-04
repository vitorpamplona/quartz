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
package com.vitorpamplona.quartz.nip01Core.crypto

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.kite.KiteWriter
import com.vitorpamplona.quartz.utils.Hex

/**
 * Checks an event's id and signature with buffers reused across events: the id's SHA-256 is
 * written into [hash] and, once it matches the id, signed over as is (it is the id), and the sig
 * and pubkey are decoded into [sig] and [pubKey]. Each thread keeps one ([threadEventVerifier]):
 * a check never suspends, so it runs start to finish on one thread.
 */
internal class EventVerifier {
    private val hash = ByteArray(32)
    private val sig = ByteArray(64)
    private val pubKey = ByteArray(32)
    private var inUse = false

    /** The caller checked the lengths: [id] and [pubKeyHex] 64 chars, [sigHex] 128. */
    private fun check(
        id: HexKey,
        pubKeyHex: HexKey,
        createdAt: Long,
        kind: Int,
        tags: TagArray,
        content: String,
        sigHex: HexKey,
    ): Boolean {
        inUse = true
        try {
            KiteWriter.idHashInto(pubKeyHex, createdAt, kind, tags, content, hash)
            if (!Hex.isEqual(id, hash)) return false
            Hex.decodeInto(sigHex, sig)
            Hex.decodeInto(pubKeyHex, pubKey)
            return Nip01Crypto.verify(sig, hash, pubKey)
        } finally {
            inUse = false
        }
    }

    companion object {
        /**
         * True when [id] is the hash of the other fields and [sigHex] is [pubKeyHex]'s BIP-340
         * signature over it: what `verifyId() && verifySignature()` answers, without allocating.
         */
        fun verify(
            id: HexKey,
            pubKeyHex: HexKey,
            createdAt: Long,
            kind: Int,
            tags: TagArray,
            content: String,
            sigHex: HexKey,
        ): Boolean {
            if (id.length != 64 || pubKeyHex.length != 64 || sigHex.length != 128) return false
            val local = threadEventVerifier()
            val verifier = if (local == null || local.inUse) EventVerifier() else local
            return verifier.check(id, pubKeyHex, createdAt, kind, tags, content, sigHex)
        }
    }
}

/** This thread's reusable verifier, or null where there is none. */
internal expect fun threadEventVerifier(): EventVerifier?
