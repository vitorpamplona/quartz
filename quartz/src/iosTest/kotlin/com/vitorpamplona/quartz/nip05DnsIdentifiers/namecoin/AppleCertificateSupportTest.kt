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

import com.vitorpamplona.quartz.security.quartz_sec_trust_create_with_der
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFRelease
import platform.Security.SecTrustRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class AppleCertificateSupportTest {
    @Test
    fun captureExportsLeafPemAndSha256Fingerprint() {
        val trust = trustFor("localhost")
        try {
            val capture = assertNotNull(AppleCertificateSupport.captureLeaf(trust))
            assertEquals(AppleTestIdentity.PEM, capture.pem)
            assertEquals(AppleTestIdentity.FINGERPRINT, capture.fingerprint)
        } finally {
            CFRelease(trust)
        }
    }

    @Test
    fun selfSignedPinRetainsChainAndHostnameValidation() {
        assertFalse(withTrust("localhost") { AppleCertificateSupport.evaluate(it, emptyList()) })
        assertTrue(withTrust("localhost") { AppleCertificateSupport.evaluate(it, listOf(AppleTestIdentity.PEM)) })
        assertFalse(withTrust("wrong.example") { AppleCertificateSupport.evaluate(it, listOf(AppleTestIdentity.PEM)) })
    }

    @Test
    fun malformedOrUnrelatedPinDoesNotTrustCertificate() {
        assertFalse(withTrust("localhost") { AppleCertificateSupport.evaluate(it, listOf("not a PEM certificate")) })
        assertFalse(withTrust("localhost") { AppleCertificateSupport.evaluate(it, listOf(RELAY_TESTLS_BIT_CERT_PEM)) })
    }

    @Test
    fun legacyChainOnlyModeStillRequiresMatchingPin() {
        assertTrue(
            withTrust("wrong.example") {
                AppleCertificateSupport.evaluate(it, listOf(AppleTestIdentity.PEM), verifyHostname = false)
            },
        )
        assertFalse(
            withTrust("wrong.example") {
                AppleCertificateSupport.evaluate(it, listOf(RELAY_TESTLS_BIT_CERT_PEM), verifyHostname = false)
            },
        )
    }

    private fun withTrust(
        hostname: String,
        block: (SecTrustRef) -> Boolean,
    ): Boolean {
        val trust = trustFor(hostname)
        return try {
            block(trust)
        } finally {
            CFRelease(trust)
        }
    }

    private fun trustFor(hostname: String): SecTrustRef {
        val der = assertNotNull(AppleCertificateSupport.derFromPem(AppleTestIdentity.PEM))
        return der.usePinned { pinned ->
            assertNotNull(
                quartz_sec_trust_create_with_der(
                    pinned.addressOf(0).reinterpret(),
                    der.size.toULong(),
                    hostname,
                ),
            )
        }
    }
}
