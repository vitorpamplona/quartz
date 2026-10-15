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
package com.vitorpamplona.quartz.nip34Git

import com.vitorpamplona.quartz.nip34Git.ci.coordinatorAdvertisement.CiCoordinatorAdvertisementEvent
import com.vitorpamplona.quartz.nip34Git.ci.jobResult.CiJobResultEvent
import com.vitorpamplona.quartz.nip34Git.ci.manualTrigger.CiManualTriggerEvent
import com.vitorpamplona.quartz.nip34Git.ci.repositoryStatus.CiRepositoryStatusEvent
import com.vitorpamplona.quartz.nip34Git.ci.requestReadiness.CiRequestReadinessListEvent
import com.vitorpamplona.quartz.nip34Git.ci.secretUpdate.CiSecretUpdateEvent
import com.vitorpamplona.quartz.nip34Git.ci.serviceRequest.CiServiceRequestEvent
import com.vitorpamplona.quartz.nip34Git.ci.serviceStop.CiServiceStopEvent
import com.vitorpamplona.quartz.nip34Git.ci.workflowProgress.CiWorkflowProgressEvent
import com.vitorpamplona.quartz.nip34Git.ci.workflowResult.CiWorkflowResultEvent
import com.vitorpamplona.quartz.nip34Git.coverNote.GitCoverNoteEvent
import com.vitorpamplona.quartz.nip34Git.grasp.UserGraspListEvent
import com.vitorpamplona.quartz.nip34Git.issue.GitIssueEvent
import com.vitorpamplona.quartz.nip34Git.patch.GitPatchEvent
import com.vitorpamplona.quartz.nip34Git.pr.GitPullRequestEvent
import com.vitorpamplona.quartz.nip34Git.pr.GitPullRequestUpdateEvent
import com.vitorpamplona.quartz.nip34Git.reply.GitReplyEvent
import com.vitorpamplona.quartz.nip34Git.repository.GitRepositoryEvent
import com.vitorpamplona.quartz.nip34Git.state.GitRepositoryStateEvent
import com.vitorpamplona.quartz.nip34Git.status.GitStatusAppliedEvent
import com.vitorpamplona.quartz.nip34Git.status.GitStatusClosedEvent
import com.vitorpamplona.quartz.nip34Git.status.GitStatusDraftEvent
import com.vitorpamplona.quartz.nip34Git.status.GitStatusOpenEvent
import com.vitorpamplona.quartz.utils.EventKindProvider
import com.vitorpamplona.quartz.utils.EventKindRegistry

/** Registers the event classes of the `quartz-git` module with [com.vitorpamplona.quartz.utils.EventFactory]. */
object GitEventKinds : EventKindProvider {
    override fun registerKinds(registry: EventKindRegistry) {
        registry.register(GitIssueEvent.KIND, ::GitIssueEvent)
        registry.register(GitReplyEvent.KIND, ::GitReplyEvent)
        registry.register(GitPatchEvent.KIND, ::GitPatchEvent)
        registry.register(GitPullRequestEvent.KIND, ::GitPullRequestEvent)
        registry.register(GitPullRequestUpdateEvent.KIND, ::GitPullRequestUpdateEvent)
        registry.register(GitRepositoryEvent.KIND, ::GitRepositoryEvent)
        registry.register(GitRepositoryStateEvent.KIND, ::GitRepositoryStateEvent)
        registry.register(GitStatusOpenEvent.KIND, ::GitStatusOpenEvent)
        registry.register(GitStatusAppliedEvent.KIND, ::GitStatusAppliedEvent)
        registry.register(GitStatusClosedEvent.KIND, ::GitStatusClosedEvent)
        registry.register(GitStatusDraftEvent.KIND, ::GitStatusDraftEvent)
        registry.register(GitCoverNoteEvent.KIND, ::GitCoverNoteEvent)
        registry.register(CiManualTriggerEvent.KIND, ::CiManualTriggerEvent)
        registry.register(CiJobResultEvent.KIND, ::CiJobResultEvent)
        registry.register(CiWorkflowResultEvent.KIND, ::CiWorkflowResultEvent)
        registry.register(CiServiceRequestEvent.KIND, ::CiServiceRequestEvent)
        registry.register(CiServiceStopEvent.KIND, ::CiServiceStopEvent)
        registry.register(CiWorkflowProgressEvent.KIND, ::CiWorkflowProgressEvent)
        registry.register(CiCoordinatorAdvertisementEvent.KIND, ::CiCoordinatorAdvertisementEvent)
        registry.register(CiRequestReadinessListEvent.KIND, ::CiRequestReadinessListEvent)
        registry.register(CiRepositoryStatusEvent.KIND, ::CiRepositoryStatusEvent)
        registry.register(CiSecretUpdateEvent.KIND, ::CiSecretUpdateEvent)
        registry.register(UserGraspListEvent.KIND, ::UserGraspListEvent)
    }
}
