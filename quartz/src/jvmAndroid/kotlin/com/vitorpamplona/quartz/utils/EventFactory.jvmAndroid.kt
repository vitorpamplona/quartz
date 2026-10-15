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
package com.vitorpamplona.quartz.utils

/**
 * Every Quartz module's [EventKindProvider], by class name. A module that is not on the
 * classpath is skipped, so an app gets exactly the typed classes of the artifacts it
 * depends on. Each module's consumer R8 rules keep its provider under this name.
 */
private val KNOWN_PROVIDERS =
    listOf(
        "com.vitorpamplona.quartz.nip01Core.CoreEventKinds",
        "com.vitorpamplona.quartz.social.SocialEventKinds",
        "com.vitorpamplona.quartz.nip17Dm.DmEventKinds",
        "com.vitorpamplona.quartz.payments.PaymentEventKinds",
        "com.vitorpamplona.quartz.nip29RelayGroups.RelayGroupEventKinds",
        "com.vitorpamplona.quartz.nip86RelayManagement.RelayManagementEventKinds",
        "com.vitorpamplona.quartz.nip85TrustedAssertions.TrustedAssertionEventKinds",
        "com.vitorpamplona.quartz.nip46RemoteSigner.RemoteSignerEventKinds",
        "com.vitorpamplona.quartz.nip34Git.GitEventKinds",
        "com.vitorpamplona.quartz.nip64Chess.ChessEventKinds",
        "com.vitorpamplona.quartz.nip03Timestamp.OtsEventKinds",
        "com.vitorpamplona.quartz.nip90Dvms.DvmEventKinds",
        "com.vitorpamplona.quartz.podcasts.PodcastEventKinds",
        "com.vitorpamplona.quartz.marketplace.MarketplaceEventKinds",
        "com.vitorpamplona.quartz.mediaServers.MediaServerEventKinds",
        "com.vitorpamplona.quartz.nip5aStaticWebsites.NsiteEventKinds",
        "com.vitorpamplona.quartz.nipACWebRtcCalls.WebRtcCallEventKinds",
        "com.vitorpamplona.quartz.nipXXPushNotifications.PushNotificationEventKinds",
        "com.vitorpamplona.quartz.nip66RelayMonitor.RelayMonitorEventKinds",
        "com.vitorpamplona.quartz.marmot.MarmotEventKinds",
        "com.vitorpamplona.quartz.contextvm.ContextVmEventKinds",
        "com.vitorpamplona.quartz.concord.ConcordEventKinds",
        "com.vitorpamplona.quartz.buzz.BuzzEventKinds",
        "com.vitorpamplona.quartz.cyberspace.CyberspaceEventKinds",
        "com.vitorpamplona.quartz.experimental.ExperimentalEventKinds",
    )

internal actual fun platformEventKindProviders(): List<EventKindProvider> =
    KNOWN_PROVIDERS.mapNotNull { className ->
        try {
            Class.forName(className).getField("INSTANCE").get(null) as EventKindProvider
        } catch (_: ClassNotFoundException) {
            null
        }
    }
