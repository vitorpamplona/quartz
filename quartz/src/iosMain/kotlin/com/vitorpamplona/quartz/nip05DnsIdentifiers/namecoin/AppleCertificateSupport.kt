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
package com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin

import com.vitorpamplona.crypto.sha2.sha256
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.security.quartz_sec_certificate_array_add_der
import com.vitorpamplona.quartz.security.quartz_sec_certificate_array_create
import com.vitorpamplona.quartz.security.quartz_sec_trust_evaluate_with_pins
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFArrayGetCount
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFRelease
import platform.Security.SecCertificateCopyData
import platform.Security.SecCertificateCreateWithData
import platform.Security.SecCertificateRef
import platform.Security.SecTrustCopyCertificateChain
import platform.Security.SecTrustRef
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal data class AppleCertificateCapture(
    val pem: String,
    val fingerprint: String,
)

@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
internal object AppleCertificateSupport {
    /** Evaluate the connection's own SSL policy, including its target hostname. */
    fun evaluate(
        trust: SecTrustRef,
        pinnedPems: List<String>,
        verifyHostname: Boolean = true,
    ): Boolean {
        val pins = quartz_sec_certificate_array_create() ?: return false
        return try {
            pinnedPems.forEach { pem ->
                val der = derFromPem(pem) ?: return@forEach
                der.usePinned { pinned ->
                    quartz_sec_certificate_array_add_der(
                        pins,
                        pinned.addressOf(0).reinterpret(),
                        der.size.toULong(),
                    )
                }
            }
            // Native code retains normal platform trust as the first choice, then tries explicit
            // pin anchors without relaxing the SSL hostname policy. Chain-only mode is restricted
            // to the existing pinned-IP/onion compatibility path.
            quartz_sec_trust_evaluate_with_pins(trust, pins, verifyHostname)
        } finally {
            CFRelease(pins)
        }
    }

    fun captureLeaf(trust: SecTrustRef): AppleCertificateCapture? {
        val chain = SecTrustCopyCertificateChain(trust) ?: return null
        return try {
            if (CFArrayGetCount(chain) == 0L) return null
            val certificate = CFArrayGetValueAtIndex(chain, 0) as SecCertificateRef
            derToCapture(SecCertificateCopyData(certificate))
        } finally {
            CFRelease(chain)
        }
    }

    fun derToCapture(data: CFDataRef?): AppleCertificateCapture? {
        if (data == null) return null
        val length = CFDataGetLength(data).toInt()
        val bytes = CFDataGetBytePtr(data)?.readBytes(length) ?: return null
        if (bytes.isEmpty()) return null
        val body = Base64.encode(bytes).chunked(PEM_LINE_LENGTH).joinToString("\n")
        val pem = "-----BEGIN CERTIFICATE-----\n" + body + "\n-----END CERTIFICATE-----"
        val fingerprint =
            sha256(bytes)
                .toHexKey()
                .uppercase()
                .chunked(2)
                .joinToString(":")
        return AppleCertificateCapture(pem, fingerprint)
    }

    internal fun certificateFromPem(pem: String): SecCertificateRef? {
        val der = derFromPem(pem) ?: return null
        val data =
            der.usePinned { pinned ->
                CFDataCreate(null, pinned.addressOf(0).reinterpret(), der.size.toLong())
            } ?: return null
        return try {
            SecCertificateCreateWithData(null, data)
        } finally {
            CFRelease(data)
        }
    }

    internal fun derFromPem(pem: String): ByteArray? {
        val body =
            pem
                .substringAfter("-----BEGIN CERTIFICATE-----", "")
                .substringBefore("-----END CERTIFICATE-----", "")
                .filterNot { it.isWhitespace() }
        if (body.isEmpty()) return null
        return try {
            Base64.decode(body).takeIf { it.isNotEmpty() }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private const val PEM_LINE_LENGTH = 64
}
