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
package com.vitorpamplona.quartz.drss.readWriteLists.tags

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.utils.ensure

/**
 * A list a drss reader designates, `["a", <list>, <relay or "">, <role>]`: [Role.READ] is what the
 * river shows, [Role.WRITE] is where follows and saves go, and no role is both, the way NIP-65
 * marks a relay. drss's own preferences name one list with no role.
 */
@Immutable
data class ListRoleTag(
    val list: ATag,
    val role: Role? = null,
) {
    enum class Role(
        val code: String,
    ) {
        READ("read"),
        WRITE("write"),
    }

    fun isRead() = role != Role.WRITE

    fun isWrite() = role != Role.READ

    fun toTagArray() = assemble(list, role)

    companion object {
        const val TAG_NAME = ATag.TAG_NAME

        fun parse(tag: Array<String>): ListRoleTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            val list = ATag.parse(tag) ?: return null
            val role =
                when (tag.getOrNull(3)) {
                    null, "" -> null
                    Role.READ.code -> Role.READ
                    Role.WRITE.code -> Role.WRITE
                    else -> return null
                }
            return ListRoleTag(list, role)
        }

        fun assemble(
            list: ATag,
            role: Role?,
        ): Array<String> =
            if (role == null) {
                list.toATagArray()
            } else {
                arrayOf(TAG_NAME, list.toTag(), list.relay?.url ?: "", role.code)
            }
    }
}
