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
package com.vitorpamplona.quartz.nip90Dvms

import com.vitorpamplona.quartz.nip90Dvms.contentDiscoveryRequest.DvmContentDiscoveryRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.contentDiscoveryResponse.DvmContentDiscoveryResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.contentSearch.DvmContentSearchRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.contentSearch.DvmContentSearchResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.dvmHeartbeat.DvmHeartbeatEvent
import com.vitorpamplona.quartz.nip90Dvms.eventCount.DvmEventCountRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.eventCount.DvmEventCountResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.eventPowDelegation.DvmEventPowDelegationRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.eventPowDelegation.DvmEventPowDelegationResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.eventPublishSchedule.DvmEventPublishScheduleRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.eventPublishSchedule.DvmEventPublishScheduleResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.eventTimestamping.DvmEventTimestampingRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.eventTimestamping.DvmEventTimestampingResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.imageGeneration.DvmImageGenerationRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.imageGeneration.DvmImageGenerationResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.imageToVideo.DvmImageToVideoRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.imageToVideo.DvmImageToVideoResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.malwareScanning.DvmMalwareScanRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.malwareScanning.DvmMalwareScanResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.opReturn.DvmOpReturnRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.opReturn.DvmOpReturnResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.peopleSearch.DvmPeopleSearchRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.peopleSearch.DvmPeopleSearchResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.status.DvmStatusEvent
import com.vitorpamplona.quartz.nip90Dvms.summarization.DvmSummarizationRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.summarization.DvmSummarizationResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.textExtraction.DvmTextExtractionRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.textExtraction.DvmTextExtractionResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.textGeneration.DvmTextGenerationRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.textGeneration.DvmTextGenerationResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.textToSpeech.DvmTextToSpeechRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.textToSpeech.DvmTextToSpeechResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.translation.DvmTranslationRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.translation.DvmTranslationResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.userDiscoveryRequest.DvmUserDiscoveryRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.userDiscoveryResponse.DvmUserDiscoveryResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.videoConversion.DvmVideoConversionRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.videoConversion.DvmVideoConversionResponseEvent
import com.vitorpamplona.quartz.nip90Dvms.videoTranslation.DvmVideoTranslationRequestEvent
import com.vitorpamplona.quartz.nip90Dvms.videoTranslation.DvmVideoTranslationResponseEvent
import com.vitorpamplona.quartz.utils.EventKindProvider
import com.vitorpamplona.quartz.utils.EventKindRegistry

/** Registers the event classes of the `quartz-dvms` module with [com.vitorpamplona.quartz.utils.EventFactory]. */
object DvmEventKinds : EventKindProvider {
    override fun registerKinds(registry: EventKindRegistry) {
        registry.register(DvmStatusEvent.KIND, ::DvmStatusEvent)
        registry.register(DvmHeartbeatEvent.KIND, ::DvmHeartbeatEvent)
        registry.register(DvmTextExtractionRequestEvent.KIND, ::DvmTextExtractionRequestEvent)
        registry.register(DvmTextExtractionResponseEvent.KIND, ::DvmTextExtractionResponseEvent)
        registry.register(DvmSummarizationRequestEvent.KIND, ::DvmSummarizationRequestEvent)
        registry.register(DvmSummarizationResponseEvent.KIND, ::DvmSummarizationResponseEvent)
        registry.register(DvmTranslationRequestEvent.KIND, ::DvmTranslationRequestEvent)
        registry.register(DvmTranslationResponseEvent.KIND, ::DvmTranslationResponseEvent)
        registry.register(DvmTextGenerationRequestEvent.KIND, ::DvmTextGenerationRequestEvent)
        registry.register(DvmTextGenerationResponseEvent.KIND, ::DvmTextGenerationResponseEvent)
        registry.register(DvmImageGenerationRequestEvent.KIND, ::DvmImageGenerationRequestEvent)
        registry.register(DvmImageGenerationResponseEvent.KIND, ::DvmImageGenerationResponseEvent)
        registry.register(DvmVideoConversionRequestEvent.KIND, ::DvmVideoConversionRequestEvent)
        registry.register(DvmVideoConversionResponseEvent.KIND, ::DvmVideoConversionResponseEvent)
        registry.register(DvmVideoTranslationRequestEvent.KIND, ::DvmVideoTranslationRequestEvent)
        registry.register(DvmVideoTranslationResponseEvent.KIND, ::DvmVideoTranslationResponseEvent)
        registry.register(DvmImageToVideoRequestEvent.KIND, ::DvmImageToVideoRequestEvent)
        registry.register(DvmImageToVideoResponseEvent.KIND, ::DvmImageToVideoResponseEvent)
        registry.register(DvmTextToSpeechRequestEvent.KIND, ::DvmTextToSpeechRequestEvent)
        registry.register(DvmTextToSpeechResponseEvent.KIND, ::DvmTextToSpeechResponseEvent)
        registry.register(DvmContentDiscoveryRequestEvent.KIND, ::DvmContentDiscoveryRequestEvent)
        registry.register(DvmContentDiscoveryResponseEvent.KIND, ::DvmContentDiscoveryResponseEvent)
        registry.register(DvmUserDiscoveryRequestEvent.KIND, ::DvmUserDiscoveryRequestEvent)
        registry.register(DvmUserDiscoveryResponseEvent.KIND, ::DvmUserDiscoveryResponseEvent)
        registry.register(DvmContentSearchRequestEvent.KIND, ::DvmContentSearchRequestEvent)
        registry.register(DvmContentSearchResponseEvent.KIND, ::DvmContentSearchResponseEvent)
        registry.register(DvmPeopleSearchRequestEvent.KIND, ::DvmPeopleSearchRequestEvent)
        registry.register(DvmPeopleSearchResponseEvent.KIND, ::DvmPeopleSearchResponseEvent)
        registry.register(DvmEventCountRequestEvent.KIND, ::DvmEventCountRequestEvent)
        registry.register(DvmEventCountResponseEvent.KIND, ::DvmEventCountResponseEvent)
        registry.register(DvmMalwareScanRequestEvent.KIND, ::DvmMalwareScanRequestEvent)
        registry.register(DvmMalwareScanResponseEvent.KIND, ::DvmMalwareScanResponseEvent)
        registry.register(DvmEventTimestampingRequestEvent.KIND, ::DvmEventTimestampingRequestEvent)
        registry.register(DvmEventTimestampingResponseEvent.KIND, ::DvmEventTimestampingResponseEvent)
        registry.register(DvmOpReturnRequestEvent.KIND, ::DvmOpReturnRequestEvent)
        registry.register(DvmOpReturnResponseEvent.KIND, ::DvmOpReturnResponseEvent)
        registry.register(DvmEventPublishScheduleRequestEvent.KIND, ::DvmEventPublishScheduleRequestEvent)
        registry.register(DvmEventPublishScheduleResponseEvent.KIND, ::DvmEventPublishScheduleResponseEvent)
        registry.register(DvmEventPowDelegationRequestEvent.KIND, ::DvmEventPowDelegationRequestEvent)
        registry.register(DvmEventPowDelegationResponseEvent.KIND, ::DvmEventPowDelegationResponseEvent)
    }
}
