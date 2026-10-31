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
package com.vitorpamplona.quartz.drss.listenProgress

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.AddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag

/**
 * One listener's progress through one drss.io episode, in either of the shapes drss has written:
 * [DrssListenProgressAppDataEvent] (kind 30078, what drss writes now) and the older
 * [DrssListenProgressEvent] (kind 30054, which drss moves to 30078 and deletes). The tags are
 * the same in both, so a reader takes either.
 */
@Immutable
interface DrssListenProgress : AddressableEvent {
    val pubKey: HexKey
    val createdAt: Long

    /** The episode article this record tracks (`a`). */
    fun episode(): ATag?

    /** The address of [episode], as `kind:pubkey:d`. */
    fun episodeAddressId(): String?

    /** The playhead in seconds, or null once the episode was played to the end. */
    fun position(): Long?

    fun durationInSeconds(): Long?

    /** When the listener first played the episode through (unix seconds). */
    fun completedAt(): Long?

    fun plays(): Long?
}
