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
package com.vitorpamplona.quartz.drss

import com.vitorpamplona.quartz.nip01Core.core.TagArray

/**
 * True when [tags] hold at least one `a` and every `a` names an address of one of [kinds]. drss's
 * folders hold only lists (or provenance records); the other apps on those kinds tag other things
 * with `a`, or nothing.
 */
internal fun TagArray.onlyAddressesOfKinds(kinds: Set<String>): Boolean {
    var found = false
    for (tag in this) {
        if (tag.size < 2 || tag[0] != "a") continue
        if (tag[1].substringBefore(':') !in kinds) return false
        found = true
    }
    return found
}

/** True when [tags] hold at least one [required] tag with a value and every tag's name is in [allowed]. */
internal fun TagArray.onlyTagsNamed(
    allowed: Set<String>,
    required: String,
): Boolean {
    var found = false
    for (tag in this) {
        if (tag.isEmpty()) continue
        if (tag[0] !in allowed) return false
        if (tag[0] == required && tag.size > 1 && tag[1].isNotEmpty()) found = true
    }
    return found
}
