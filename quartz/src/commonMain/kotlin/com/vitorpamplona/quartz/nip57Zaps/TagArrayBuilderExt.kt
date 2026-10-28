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
package com.vitorpamplona.quartz.nip57Zaps

import com.vitorpamplona.quartz.experimental.zapPolls.tags.PollOptionTag
import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip01Core.tags.kinds.KindTag
import com.vitorpamplona.quartz.nip29RelayGroups.tags.GroupIdTag
import com.vitorpamplona.quartz.nip57Zaps.tags.AnonTag
import com.vitorpamplona.quartz.nip57Zaps.tags.LnurlTag
import com.vitorpamplona.quartz.nip57Zaps.tags.ZapAmountTag
import com.vitorpamplona.quartz.nip57Zaps.tags.ZapRelaysTag

fun TagArrayBuilder<ZapRequestEvent>.receiptRelays(relays: Collection<NormalizedRelayUrl>) = addUnique(ZapRelaysTag.assemble(relays))

/** The zapped event's kind (`k`). */
fun TagArrayBuilder<ZapRequestEvent>.zappedKind(kind: Int) = addUnique(KindTag.assemble(kind))

/** The zapped addressable event's coordinate (`a`). */
fun TagArrayBuilder<ZapRequestEvent>.zappedAddress(address: Address) = addUnique(ATag.assemble(address, null))

/** The NIP-29 group the zapped content lives in (`h`), so the receipt is scoped to it too. */
fun TagArrayBuilder<ZapRequestEvent>.zappedGroup(groupId: String) = addUnique(GroupIdTag.assemble(groupId))

fun TagArrayBuilder<ZapRequestEvent>.pollVote(index: Int) = addUnique(PollOptionTag.assembleVote(index))

fun TagArrayBuilder<ZapRequestEvent>.amount(millisats: Long) = addUnique(ZapAmountTag.assemble(millisats))

fun TagArrayBuilder<ZapRequestEvent>.lnurl(lnurl: String) = addUnique(LnurlTag.assemble(lnurl))

fun TagArrayBuilder<ZapRequestEvent>.anonymous() = addUnique(AnonTag.assembleAnonymous())

fun TagArrayBuilder<ZapRequestEvent>.privateZap() = addUnique(AnonTag.assemblePrivate())
