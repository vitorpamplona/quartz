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
package com.vitorpamplona.quartz.nip5aStaticWebsites.observer

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTask
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTaskEvent
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * The morning Observer edition as a NIP-FB scheduled task (`quartz/plans/2026-11-11-observer-site.md`,
 * "The morning schedule, synced"): kind 34817 with the fixed `d` = [D_TAG], so two apps setting it
 * up at once edit one event, and `task` = [TASK]. Whether an occurrence's edition already exists,
 * the check before a scheduled print, is [ObserverSite.alreadyPrinted]. The claim race is
 * NIP-FB's ([com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTaskClaims]), and so is the
 * missed-run warning ([ScheduledTask.missedRun]).
 */
object ObserverSchedule {
    const val D_TAG = "observer"
    const val TASK = "observer"

    fun address(author: HexKey) = ScheduledTaskEvent.createAddress(author, D_TAG)

    /** The author's morning schedule, for a relay read. */
    fun filter(author: HexKey) = ScheduledTaskEvent.filter(author, D_TAG)

    fun isObserverSchedule(event: Event) = event is ScheduledTaskEvent && event.dTag() == D_TAG

    /** A new schedule, paused, at the default time in [zone], set at [now]: what an app writes when none exists. */
    fun new(
        zone: String,
        now: Long = TimeUtils.now(),
    ): ScheduledTask = ScheduledTask.create(D_TAG, TASK, zone = zone, setAt = now, paused = true)

    /**
     * The unsigned schedule event for [task], encrypted to [signer], replacing [previous]. A public
     * `client` tag is dropped (it tells anyone which app the reader schedules with; a private one
     * stays): the one exception to keeping every tag where it was. Sign it with a signer that adds
     * no `client` tag of its own.
     */
    suspend fun build(
        task: ScheduledTask,
        signer: NostrSigner,
        previous: Event?,
        now: Long = TimeUtils.now(),
    ) = ScheduledTaskEvent.rewrite(task, signer, D_TAG, previous, now)

    /** [task] without a public `client` tag; a private one is kept. */
    fun withoutPublicClient(task: ScheduledTask): ScheduledTask = task.withoutPublicClientTag()
}
