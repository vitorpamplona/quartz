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
package com.vitorpamplona.quartz.nip55AndroidSigner

import com.vitorpamplona.quartz.nip55AndroidSigner.api.foreground.intents.results.IntentResult
import com.vitorpamplona.quartz.nip55AndroidSigner.api.permission.Permission
import com.vitorpamplona.quartz.nip55AndroidSigner.kite.Nip55Kite

/**
 * NIP-55's JSON: the permission list a client sends a signer app, and the results a signer
 * returns for a batch of requests, read and written by Kite ([Nip55Kite]).
 */
object JsonMapperNip55 {
    fun fromJsonToPermission(json: String): Permission = Nip55Kite.fromJsonToPermission(json)

    fun fromJsonToPermissionArray(json: String): Array<Permission> = Nip55Kite.fromJsonToPermissionArray(json)

    fun fromJsonToIntentResult(json: String): IntentResult = Nip55Kite.fromJsonToIntentResult(json)

    fun fromJsonToIntentResultList(json: String): List<IntentResult> = Nip55Kite.fromJsonToIntentResultList(json)

    fun toJson(permission: Permission): String = Nip55Kite.toJson(permission)

    fun toJson(permissions: List<Permission>): String = Nip55Kite.toJson(permissions)

    fun toJson(result: IntentResult): String = Nip55Kite.toJson(result)
}
