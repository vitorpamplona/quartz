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
package com.vitorpamplona.quartz.buzz.workspace

/**
 * The one Buzz role ([BUZZ_ROLES]) a NIP-29 role list maps to, for a kind:9000's `role` tag: Buzz
 * refuses the whole put-user for any role outside owner/admin/member/guest/bot, and has no
 * moderator. The strongest role named wins; anything Buzz doesn't know (`moderator`) is a member.
 */
fun buzzRoleFor(roles: List<String>): String {
    fun has(role: String) = roles.any { it.equals(role, ignoreCase = true) }
    return when {
        has(BUZZ_ROLE_OWNER) -> BUZZ_ROLE_OWNER
        has(BUZZ_ROLE_ADMIN) -> BUZZ_ROLE_ADMIN
        has(BUZZ_ROLE_BOT) -> BUZZ_ROLE_BOT
        has(BUZZ_ROLE_GUEST) -> BUZZ_ROLE_GUEST
        else -> BUZZ_ROLE_MEMBER
    }
}
