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
package com.vitorpamplona.quartz.nip72ModCommunities.approval

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.hints.EventHintBundle
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip72ModCommunities.definition.CommunityDefinitionEvent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CommunityPostApprovalBuildTest {
    private val moderator = NostrSignerInternal(KeyPair())
    private val author = NostrSignerInternal(KeyPair())

    /** NIP-72: the approval SHOULD carry the JSON-stringified post and a `k` tag with its kind. */
    @Test
    fun buildEmbedsTheApprovedPostAndItsKind() =
        runTest {
            val community: CommunityDefinitionEvent = moderator.sign(CommunityDefinitionEvent.build(name = "dev", description = "", moderators = emptyList(), dTag = "dev"))
            val post: TextNoteEvent = author.sign(TextNoteEvent.build("Hi everyone"))

            val approval: CommunityPostApprovalEvent =
                moderator.sign(CommunityPostApprovalEvent.build(EventHintBundle<Event>(post), EventHintBundle(community)))

            assertEquals(post.toJson(), approval.content)
            assertEquals(post.id, approval.containedPost()?.id)
            assertEquals(listOf("1"), approval.tags.filter { it[0] == "k" }.map { it[1] })
        }
}
