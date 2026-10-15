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
@file:OptIn(ExperimentalStdlibApi::class)
@file:Suppress("DEPRECATION")

package com.vitorpamplona.quartz.utils

import com.vitorpamplona.quartz.buzz.BuzzEventKinds
import com.vitorpamplona.quartz.concord.ConcordEventKinds
import com.vitorpamplona.quartz.contextvm.ContextVmEventKinds
import com.vitorpamplona.quartz.cyberspace.CyberspaceEventKinds
import com.vitorpamplona.quartz.experimental.ExperimentalEventKinds
import com.vitorpamplona.quartz.marketplace.MarketplaceEventKinds
import com.vitorpamplona.quartz.marmot.MarmotEventKinds
import com.vitorpamplona.quartz.mediaServers.MediaServerEventKinds
import com.vitorpamplona.quartz.nip01Core.CoreEventKinds
import com.vitorpamplona.quartz.nip03Timestamp.OtsEventKinds
import com.vitorpamplona.quartz.nip17Dm.DmEventKinds
import com.vitorpamplona.quartz.nip29RelayGroups.RelayGroupEventKinds
import com.vitorpamplona.quartz.nip34Git.GitEventKinds
import com.vitorpamplona.quartz.nip46RemoteSigner.RemoteSignerEventKinds
import com.vitorpamplona.quartz.nip5aStaticWebsites.NsiteEventKinds
import com.vitorpamplona.quartz.nip64Chess.ChessEventKinds
import com.vitorpamplona.quartz.nip66RelayMonitor.RelayMonitorEventKinds
import com.vitorpamplona.quartz.nip85TrustedAssertions.TrustedAssertionEventKinds
import com.vitorpamplona.quartz.nip86RelayManagement.RelayManagementEventKinds
import com.vitorpamplona.quartz.nip90Dvms.DvmEventKinds
import com.vitorpamplona.quartz.nipACWebRtcCalls.WebRtcCallEventKinds
import com.vitorpamplona.quartz.nipXXPushNotifications.PushNotificationEventKinds
import com.vitorpamplona.quartz.payments.PaymentEventKinds
import com.vitorpamplona.quartz.podcasts.PodcastEventKinds
import com.vitorpamplona.quartz.social.SocialEventKinds
import kotlin.native.EagerInitialization

/**
 * Native has no class loader to find providers with, so they register when the binary loads.
 */
@EagerInitialization
private val registerKinds =
    run {
        EventFactory.register(CoreEventKinds)
        EventFactory.register(SocialEventKinds)
        EventFactory.register(DmEventKinds)
        EventFactory.register(PaymentEventKinds)
        EventFactory.register(RelayGroupEventKinds)
        EventFactory.register(RelayManagementEventKinds)
        EventFactory.register(TrustedAssertionEventKinds)
        EventFactory.register(RemoteSignerEventKinds)
        EventFactory.register(GitEventKinds)
        EventFactory.register(ChessEventKinds)
        EventFactory.register(OtsEventKinds)
        EventFactory.register(DvmEventKinds)
        EventFactory.register(PodcastEventKinds)
        EventFactory.register(MarketplaceEventKinds)
        EventFactory.register(MediaServerEventKinds)
        EventFactory.register(NsiteEventKinds)
        EventFactory.register(WebRtcCallEventKinds)
        EventFactory.register(PushNotificationEventKinds)
        EventFactory.register(RelayMonitorEventKinds)
        EventFactory.register(MarmotEventKinds)
        EventFactory.register(ContextVmEventKinds)
        EventFactory.register(ConcordEventKinds)
        EventFactory.register(BuzzEventKinds)
        EventFactory.register(CyberspaceEventKinds)
        EventFactory.register(ExperimentalEventKinds)
    }
