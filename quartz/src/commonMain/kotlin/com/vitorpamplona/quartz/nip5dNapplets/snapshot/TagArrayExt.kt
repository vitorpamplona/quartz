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
package com.vitorpamplona.quartz.nip5dNapplets.snapshot

import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip01Core.core.AddressSerializer
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.fastForEach
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.nip01Core.hints.types.AddressHint
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip5aStaticWebsites.tags.OriginSiteTag
import com.vitorpamplona.quartz.nip5dNapplets.named.NamedNappletEvent
import com.vitorpamplona.quartz.nip5dNapplets.root.RootNappletEvent

// NIP-5D lineage: at most one `a` (the antecedent) and one `A` (the root of the line), each
// addressing a napplet. A repeated tag, or one addressing any other kind, counts as absent, and the
// hints come from the same single tag the address does, so they never point anywhere else.

private val NAPPLET_ADDRESS_KINDS = setOf(RootNappletEvent.KIND, NamedNappletEvent.KIND)

/** The `a` tag: the napplet a snapshot derives from. */
fun TagArray.parent(): Address? = singleNamed(ATag.TAG_NAME)?.let(ATag::parseAddress)?.takeIf { it.kind in NAPPLET_ADDRESS_KINDS }

/** The `A` tag: the root napplet a snapshot's lineage starts from. */
fun TagArray.origin(): Address? = singleNamed(OriginSiteTag.TAG_NAME)?.let(OriginSiteTag::parseAddress)?.takeIf { it.kind in NAPPLET_ADDRESS_KINDS }

fun TagArray.parentHint(): AddressHint? = singleNamed(ATag.TAG_NAME)?.let(ATag::parseAsHint)?.takeIf { it.isNappletAddress() }

fun TagArray.originHint(): AddressHint? = singleNamed(OriginSiteTag.TAG_NAME)?.let(OriginSiteTag::parseAsHint)?.takeIf { it.isNappletAddress() }

private fun AddressHint.isNappletAddress() = AddressSerializer.parse(addressId)?.kind in NAPPLET_ADDRESS_KINDS

private fun TagArray.singleNamed(name: String): Array<String>? {
    var found: Array<String>? = null
    fastForEach {
        if (it.has(0) && it[0] == name) {
            if (found != null) return null
            found = it
        }
    }
    return found
}
