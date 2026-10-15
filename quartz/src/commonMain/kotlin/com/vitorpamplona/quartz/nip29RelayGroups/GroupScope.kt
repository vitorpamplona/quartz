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
package com.vitorpamplona.quartz.nip29RelayGroups

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip22Comments.CommentEvent
import com.vitorpamplona.quartz.nip7DThreads.ThreadEvent
import com.vitorpamplona.quartz.nip88Polls.poll.PollEvent
import com.vitorpamplona.quartz.nipC7Chats.ChatEvent

/**
 * True when this group-scoped event is actual room *content* — a message someone
 * posted — rather than metadata *about* content (a reaction, deletion, label,
 * moderation action, …), which carries the same `h` tag but isn't a "message".
 *
 * Only content represents a room's latest message in list views: a kind-7 reaction
 * to my group message is group-scoped too, so without this distinction it would be
 * picked as the group's "last message" and render as a bogus, unopenable row on the
 * Messages tab. NIP-29 content kinds: 9 ([ChatEvent]), 1068 ([PollEvent]), 11
 * ([ThreadEvent]), 1111 ([CommentEvent]).
 *
 * Plus any dialect's own chat-timeline rows ([GroupChatTimelineContent]; Buzz's stream
 * messages, system lines, diffs, agent-job and huddle lifecycle): they are `h`-scoped, attach
 * to the same channel as a kind-9 and render as chat rows, so they must count as a room's
 * newest message too — otherwise a Buzz channel's Messages-list preview and unread dot never
 * reflect its real activity. (A caller previewing these must summarize their `content`, which
 * is JSON/diff for the non-plain kinds; see the Buzz preview-summary helpers in the UI.)
 */
fun Event.isGroupChatContent(): Boolean = this is ChatEvent || this is PollEvent || this is ThreadEvent || this is CommentEvent || this is GroupChatTimelineContent

/**
 * Marks an event class that a NIP-29 dialect renders as a row of a group's chat timeline,
 * beside the standard content kinds [isGroupChatContent] lists. Buzz's stream messages,
 * system lines, diffs, agent jobs and huddle lifecycle events implement it.
 */
interface GroupChatTimelineContent
