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

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import androidx.core.net.toUri
import com.vitorpamplona.quartz.utils.Log
import com.vitorpamplona.quartz.utils.TimeUtils
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * The NIP-55 large-payloads extension: payloads too big for Binder (1 MB per process, strings
 * marshalled as UTF-16) or a `CursorWindow` (2 MB) travel through single-use pipes the signer
 * hosts at `content://<signer-package>.PAYLOADS`, instead of inline in the Intent data, the
 * `query()` projection or a cursor column.
 *
 * - Requests over [INLINE_THRESHOLD_BYTES] (UTF-8) are [stage]d: the client allocates a handle with
 *   `insert`, writes the payload, closes the stream, and passes the handle in place of the payload
 *   (`payload_uri` extra on Intents, `payload_in_uri=1` plus the handle in projection slot 0 on
 *   `query()`).
 * - Whenever the signer supports the extension, the client says it can take streamed results
 *   (`accept_result_uri`). The signer then replaces an oversized `result`, `event` or batch
 *   `results` with `result_uri`, `event_uri` or `results_uri`, which the client [read]s once.
 *
 * Signers that predate the extension match the operation URI as a whole string, so nothing here
 * adds a query parameter or an extra until [isSupported] says the signer exports the authority.
 *
 * Spec draft: `docs/nip55-large-payloads.md` in Amber (github.com/greenart7c3/Amber).
 */
class Nip55PayloadStreams(
    val packageName: String,
    val contentResolver: ContentResolver,
) {
    val authority = "$packageName.$PAYLOADS_SUFFIX"

    /**
     * True when the signer exports the `PAYLOADS` authority. The answer is cached per signer
     * package: a yes for the life of the process, a no for [NEGATIVE_PROBE_TTL_SECONDS], so a
     * signer updated while the app runs is picked up. The probe is a Binder call that may start
     * the signer's process: call it off the main thread.
     */
    fun isSupported(): Boolean {
        if (supported.contains(packageName)) return true
        val lastNo = unsupportedAt[packageName]
        val now = TimeUtils.now()
        if (lastNo != null && now - lastNo < NEGATIVE_PROBE_TTL_SECONDS) return false

        val found =
            try {
                contentResolver.acquireUnstableContentProviderClient(authority)?.let {
                    it.close()
                    true
                } ?: false
            } catch (e: SecurityException) {
                Log.w(TAG, "Signer $packageName refused the payload stream probe", e)
                false
            }

        if (found) {
            supported.add(packageName)
            unsupportedAt.remove(packageName)
        } else {
            unsupportedAt[packageName] = now
        }
        return found
    }

    /**
     * Allocates a single-use upload handle on the signer and writes [payload] to it, closing the
     * stream before returning, as the extension requires. Throws [IOException] when the signer
     * doesn't allocate a handle (its quotas are full) or the write fails; the handle is released
     * in that case.
     */
    fun stage(payload: String): Uri = stage(payload.encodeToByteArray())

    fun stage(payload: ByteArray): Uri {
        val handle =
            contentResolver.insert("content://$authority".toUri(), ContentValues())
                ?: throw IOException("Signer $packageName did not allocate a payload handle")

        if (!isSignerPayloadUri(handle.toString(), packageName)) {
            release(handle)
            throw IOException("Signer $packageName returned an invalid payload handle")
        }

        try {
            val stream =
                contentResolver.openOutputStream(handle, "w")
                    ?: throw IOException("Signer $packageName did not open the payload handle for writing")
            stream.use { it.write(payload) }
            return handle
        } catch (e: Exception) {
            release(handle)
            throw e
        }
    }

    /** Best-effort release of a handle the signer may not have consumed. Never throws. */
    fun release(handle: Uri) {
        try {
            contentResolver.delete(handle, null, null)
        } catch (e: Exception) {
            Log.d(TAG, "Could not release payload handle", e)
        }
    }

    /**
     * Reads a response handle the signer returned (`result_uri`, `event_uri`, `results_uri`).
     * Refuses anything but a handle on this signer's `PAYLOADS` authority, so a signer can't
     * make the app open its own files or another app's provider. Blocking: call it off the main
     * thread.
     */
    fun read(uriString: String): String {
        if (!isSignerPayloadUri(uriString, packageName)) {
            throw IOException("Refusing to read $uriString: not a payload handle of $packageName")
        }
        val stream =
            contentResolver.openInputStream(uriString.toUri())
                ?: throw IOException("Signer $packageName did not open the response handle")
        return stream.use { it.readBytes().decodeToString() }
    }

    /**
     * The value of column [inline], or the streamed contents of column [streamed] when the
     * signer moved it to a handle. Blank values count as absent, as [Cursor] callers expect.
     */
    fun readColumn(
        cursor: Cursor,
        inline: String,
        streamed: String,
    ): String? {
        val uriIndex = cursor.getColumnIndex(streamed)
        if (uriIndex >= 0) {
            val uri = cursor.getString(uriIndex)
            if (!uri.isNullOrBlank()) return read(uri).ifBlank { null }
        }
        val index = cursor.getColumnIndex(inline)
        if (index < 0) return null
        return cursor.getString(index)?.ifBlank { null }
    }

    /**
     * Turns a `content://<signer>.<OPERATION>` query URI into its extended form: the staged
     * payload flag when [staged], the result-handle opt-in, and `return_type=signature` when
     * [signatureOnly].
     */
    fun extendQueryUri(
        operation: Uri,
        staged: Boolean,
        signatureOnly: Boolean = false,
    ): Uri = extendQueryUri(operation.toString(), staged, signatureOnly).toUri()

    /**
     * Moves [intent]'s payload to [staged] when there is one, and opts into result handles.
     * [signatureOnly] asks a `sign_event` signer to return just the signature, which keeps the
     * reply small however big the event is.
     */
    fun extendIntent(
        intent: Intent,
        staged: Uri?,
        signatureOnly: Boolean = false,
    ): Intent {
        if (staged != null) {
            intent.data = "$NOSTRSIGNER_SCHEME:".toUri()
            intent.putExtra(EXTRA_PAYLOAD_URI, staged.toString())
        }
        intent.putExtra(EXTRA_ACCEPT_RESULT_URI, true)
        if (signatureOnly) intent.putExtra(EXTRA_RETURN_TYPE, RETURN_TYPE_SIGNATURE)
        return intent
    }

    companion object {
        private const val TAG = "Nip55PayloadStreams"

        const val PAYLOADS_SUFFIX = "PAYLOADS"

        /** Payloads above this many UTF-8 bytes go through a handle instead of inline. */
        const val INLINE_THRESHOLD_BYTES = 128 * 1024

        const val NEGATIVE_PROBE_TTL_SECONDS = 60L

        const val NOSTRSIGNER_SCHEME = "nostrsigner"

        const val EXTRA_PAYLOAD_URI = "payload_uri"
        const val EXTRA_ACCEPT_RESULT_URI = "accept_result_uri"
        const val EXTRA_RETURN_TYPE = "returnType"
        const val EXTRA_RESULT_URI = "result_uri"
        const val EXTRA_EVENT_URI = "event_uri"
        const val EXTRA_RESULTS_URI = "results_uri"

        const val COLUMN_RESULT_URI = EXTRA_RESULT_URI
        const val COLUMN_EVENT_URI = EXTRA_EVENT_URI

        const val QUERY_PARAM_PAYLOAD_IN_URI = "payload_in_uri"
        const val QUERY_PARAM_ACCEPT_RESULT_URI = "accept_result_uri"
        const val QUERY_PARAM_RETURN_TYPE = "return_type"

        const val RETURN_TYPE_SIGNATURE = "signature"

        private val supported: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val unsupportedAt = ConcurrentHashMap<String, Long>()

        private val TOKEN = Regex("[0-9a-f]{32}")

        /** True when [payload] is too big to send inline. */
        fun needsStaging(payload: String): Boolean = utf8Size(payload) > INLINE_THRESHOLD_BYTES

        /**
         * UTF-8 size of [text], counted without encoding it, stopping once it passes the
         * threshold.
         */
        internal fun utf8Size(text: String): Int {
            var size = 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                size +=
                    when {
                        c.code < 0x80 -> 1
                        c.code < 0x800 -> 2
                        c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                            i++
                            4
                        }
                        else -> 3
                    }
                if (size > INLINE_THRESHOLD_BYTES) return size
                i++
            }
            return size
        }

        /**
         * True only for `content://<packageName>.PAYLOADS/<32 lowercase hex>`, the one handle
         * shape the extension allows: no other authority, scheme, path segment, query or
         * fragment.
         */
        fun isSignerPayloadUri(
            uriString: String,
            packageName: String,
        ): Boolean {
            val prefix = "content://$packageName.$PAYLOADS_SUFFIX/"
            if (!uriString.startsWith(prefix)) return false
            return TOKEN.matches(uriString.substring(prefix.length))
        }

        /** String form of [extendQueryUri], for callers and tests without [Uri]. */
        fun extendQueryUri(
            operation: String,
            staged: Boolean,
            signatureOnly: Boolean = false,
        ): String {
            val params = mutableListOf<String>()
            if (staged) params.add("$QUERY_PARAM_PAYLOAD_IN_URI=1")
            params.add("$QUERY_PARAM_ACCEPT_RESULT_URI=1")
            if (signatureOnly) params.add("$QUERY_PARAM_RETURN_TYPE=$RETURN_TYPE_SIGNATURE")
            val separator = if (operation.contains('?')) "&" else "?"
            return operation + separator + params.joinToString("&")
        }
    }
}
