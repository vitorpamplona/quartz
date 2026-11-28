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

import com.vitorpamplona.quartz.security.quartz_sec_identity_create_from_pkcs12
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.Security.sec_identity_t
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Test-only identity with a localhost DNS SAN and no IP SAN. Never used by production code. */
@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
internal object AppleTestIdentity {
    const val FINGERPRINT = "7A:1A:DA:64:15:8F:7F:7F:39:67:C5:5F:3F:6B:B5:01:7B:89:60:38:9A:EA:6F:B4:B3:06:40:BC:9B:4F:D7:EC"

    val PEM =
        """
-----BEGIN CERTIFICATE-----
MIIC8zCCAdugAwIBAgIDbJoBMA0GCSqGSIb3DQEBCwUAMBQxEjAQBgNVBAMMCWxv
Y2FsaG9zdDAeFw0yNjExMjcxNzU1MDRaFw0yNzExMjcxNzU1MDRaMBQxEjAQBgNV
BAMMCWxvY2FsaG9zdDCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBALY7
vYynzcDgyVIuEwadGFTU+DD0/DPRh91TSbzNhtncatnY62uW4lmJwuBeF2e+WzZ4
C0o0QiVJvGyoayWlPPqXJKuZORV2pD8yFxHJIhHIVTurK+gzRBRwQCicKcAYU0IX
kLBHEtRAyYhXeajLtfutvBS2hLulYhs9FNcbOLSxDiOWGyhJRiXrksdTFwRyerfv
TkrMZ+NmgKBf1yU4hLWK/0HY2DPyY5dRYn1QApZLMyfYeJxHkuevE9PkPmCvJMjj
B1dEhPVnJQ6n0vwlq8BbtX0Hb0hphRxifVgPCuWyfKfYi98DYIe/MSkuv3xGFBHv
AahxtK6IOe/MLFbIZCkCAwEAAaNOMEwwDwYDVR0TAQH/BAUwAwEB/zAOBgNVHQ8B
Af8EBAMCAqQwEwYDVR0lBAwwCgYIKwYBBQUHAwEwFAYDVR0RBA0wC4IJbG9jYWxo
b3N0MA0GCSqGSIb3DQEBCwUAA4IBAQAqeEnnyYEgoEES3ZTQw/8h+5SnzwTlhb0M
rnEPE/ZsJ3j+1Q8Ylz5xuHwrT/3gtAnvJgjS+f6qBJgjbfKJDJMqjVMQHGwuKIJv
B9Jdjev8yDdqL1dD5MASFdEKVoZNapLnSV9TFqnl+EkaofjxMCD0cow+ql0AbYhX
LOEmywL7zJxR6yaLhfNeTSV/z70aDKewpg98S+anHraSGebNwOCSnsu622qevIvc
1SU4PTfik890aPzX1J88VfTNG0Xo+/iFEQqvJK8afmz+q6Fbod0/adrGEEGgngGs
j3vaL9UtJL2DFNkycduZIbqjWRLS3gLHx1/K6IsdIS/oQUQzyiP9
-----END CERTIFICATE-----
        """.trimIndent()

    fun create(): sec_identity_t {
        val bytes = Base64.decode(PKCS12.filterNot { it.isWhitespace() })
        return bytes.usePinned { pinned ->
            quartz_sec_identity_create_from_pkcs12(
                pinned.addressOf(0).reinterpret(),
                bytes.size.toULong(),
                "quartz-test",
            )
        } ?: error("Could not create Network.framework test identity")
    }

    private const val PKCS12 =
        "MIIJMQIBAzCCCPcGCSqGSIb3DQEHAaCCCOgEggjkMIII4DCCA5cGCSqGSIb3DQEHBqCCA4gwggOEAgEAMIIDfQYJKoZIhvcNAQcBMBwGCiqGSIb3DQEMAQYwDgQIvmsMfn2iDD4CAggAgIIDUHs0JCTRtS3rAvkK0Gpr2qJW6gX6nN/bYrc1sX1TJXX9ffmzQImop11bTYujAndLAN6L+gidJ4JJZ+nLJ96SOEnwQdqk76AYyzty5ms8FXIU2Jr0it5DrJ9ZCy0KLNYKmbRmotgy2BFP0Fgk9f2tEHeTNzsjJYkIUInIa6fX6SEUFmO7Q4SAPK9n/ZT1dqN7+h2DT5eUoqoCW26f+Am2sju07owtM/ilQsMcaXiJNBPVvZlucc58ceK+GCyxSPjWFw+1lIX3ja5p71fxY6IUHuZpEDQwXXeqiUIoXsMfrzZLiQirYDhf2CpaiV+S+OFEPYI1n8H50G1OMzj6+ie9evgM+JFDMFYmcLqEwcgMCrdg0R0ou4vFGXRE16RLHuCKsCdB3YQTQZX6WYvYlm2BIt54IXabXNQEMVDMeMLtsxqZ8iTZYKpUySJ/1+Gh5SwpbMW6F0KNbWZDktOysIjy8lqFw75/gMvFjxFCcj/MfuYKxB5+Rx4ep0xkPVKvYzp4WyErJoG3ENTdiIkFKipBEGGSXZgCQSh69mbiQkbV/euniJrxwzLaCA5PaeJLjqLrXSZ5+VCaZ1VH6e4nMHjdFF8d+ZdH6pt4yHhVNosqLvpjtJ3lVDmDLvVbQom7lMyq4I/JP9BbYg1OiTtvrdj8LX3m/0hfBkGT3zsf63jL51g/jkah7KoI/slr9PppZfBkrekjFYsJd4jcCndvpQDm4sq5nIPH8DyO19l5k5J/qFlFt3AguIrmkzpAAA+VpfzT5/hrJXWkxo2NL1KITG5t1rvzwPmCx0dqI8KmZoC2U+EEKEcNUXTB10+kLN5GpESfBGYGg8Qkf4zdZiDdTWYPkjZmOBwUCgrRIagQFXAVBxtWgV6lAcCa3XcX2EENocA7KrNvIrIVF4yPYCn3IE6v44hLLx/Z1nj5mivQsSly29YE4FZqzwo36FHHjUaApaPLPF02U0IsmxcjEGYd9eC4qJkVZDaZppiYOWg08+TTBBFfS2R35Q99j1QWMpD8zaGf719oPbe8HwZEWB2WV40TKLuvzz8f+VNO8vxj0GfWLKG/73N579iCR9gzfPUExYtWmsMle/xrPumMqPf29sCqAeSsUZoUA1lk52AcJTVOg28/MIIFQQYJKoZIhvcNAQcBoIIFMgSCBS4wggUqMIIFJgYLKoZIhvcNAQwKAQKgggTuMIIE6jAcBgoqhkiG9w0BDAEDMA4ECBRDFhT8bfbAAgIIAASCBMhGph+9l1+l+zCOjh0bc0AZRcqmXckQGtH+QUpJqE4hdMY71LvF4UR3GtakUa53+6y8ocn9s1OgyCxsa1J/Xc+WZDoy9etVzqWa19HVOTGPHwHXI3cJ3Mi/wtp5XT/CWf5f2ETe5mGALXan2txOMyGDFcdvyGEdVCtE3+mnCAeYBxGXAFMd9Sz+MkmabKLKroPLXqcLDDIQpMfcGb7uUZbqI9dFnuK+pfgmclkExrt5AvzbCamKGaycp3BLK5XR/opFXJv5BTDK6Hz6DRD54mjTBiqxbguWSFdu9GYASPVW0SiHuVwfp/c+u5wJnej5uDYd6pa0RpldxMpxWSkqrn5+TsjA3Mq5S6W6XRjtVR6csF2nF85kj/yFxdg7mdQN15ayh3Ef4Z0Rmp/WEM3ra+kd18mfm9H2uo8olLlETfXy4UkUrx7PdCDJZHvaGxc4AquubOxKjRSW0UAdaW5BUtJIenRan21rxjiZxQRssQ+0DOMKGMt8I+JVjh+D58yoKq1E4mgekGER410IrUh81GwP8nEV6xbT0ADrG7dCPSshdNxdCSK7y1nG7Cg88QH6pSIP7NeXalG9YLWPXPLLUJFPVwZXpyBOPK5wzbKPf/2MPJP/V3PlKBu3oE0InK3pWWV1g8hss4imvTYoe7+8KeHwfrTL+h7aIVxcbHfCg73cGARROai0egu8rP7x5wg/Dt6Z5EAYbj/qGw0R81iI0cQGW+BzsPgatg9bJxUXb9LIo/hsxDSGC4b39jhVkCJ7z/mx5FgtkcQfVioRenCyKblsfd4vvnkbtc2Cj8fv2u4UKFxK0ftcmbEJMocZKQi0QCJtEq7sGzr0NMkaByQbZNDE22Mcez8bsGuDdRT9N/UR9Y6OKX6hnfKgK7iRHdXEYM3zPPpfAellhTLNZEMunYFOM7lgynRxsvfW+od/9vLFXUSAdgEHKEoOG2eMtHCKzter0Ps7YzKgG5MMKMjXDgSpK7Lf9k94NbcEUXm7Ctal5gd5GbmR2DMQGRU6UB2AdrQgT9/ob72IiumleB71Rq3S29pkuihFY4zGFZ3WH6/i68rsCZvQo4EYON4YORbEtdmoynqi77qLMxWxKq+YeQy/drmOeZ8dKgb/HDECzIuMVqLdxJ4DphaiEpIWFaXK8BY32i8nfEQ+DTeDc6Q0vWFNict8QK8qp+7242tM+7GCv+QBe781dls3ptrl3dHUQ+qtKeTGACtQl6N66Jj9vQCivqEST8bgDO434vhHN8q9d9ivzskSFNw8sWf51qOeKWjGOL6K/Ug83W5MI+sA2qdfwGlIAv8+jPvDfU6Dhepm7ZlFQxBqokWAKyTY3k1yDPZCNWqQRpiB4AxJJbqiYQz7B82b4P8eCy/LN8rqiQy8dF+ToO1vqxWbMgdjl2ky7TOZLx3NxYPpOVYnUxICDwmqkXie/4aSOMvwHpXdqH3y8HsHBXnqZgOHHbbcULRpin1GEqfJrBS3N4HQc/THu+5a35MFpvdf/CqMyJzf0dU7BbTrdZ5lPSSDvbCP1JZbhcfgJderzFjJhas3bnBlmnuol0k8RBIMCmUrukcLu7QyEI5zTc4fC48kU35Sgnp92mRmNpGIlo7FUDzxDYNJ7k3NQ0/zIycB4y0xJTAjBgkqhkiG9w0BCRUxFgQUgmjxYIfIyNJMaoXLZ3HPpLw4gZswMTAhMAkGBSsOAwIaBQAEFJDMlXoxDlnRU7A3Sa2QJQXOZR3qBAgIkBlcq1BQogICCAA="
}
