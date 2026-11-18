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
package com.vitorpamplona.quartz.nipFCMavenRepositories.release

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip31Alts.alt
import com.vitorpamplona.quartz.nipFCMavenRepositories.MavenCoordinate
import com.vitorpamplona.quartz.nipFCMavenRepositories.MavenModule
import com.vitorpamplona.quartz.nipFCMavenRepositories.MavenPublication
import com.vitorpamplona.quartz.nipFCMavenRepositories.mavenHashes
import com.vitorpamplona.quartz.nipFCMavenRepositories.mavenManifest
import com.vitorpamplona.quartz.nipFCMavenRepositories.mavenModuleKeys
import com.vitorpamplona.quartz.nipFCMavenRepositories.mavenModules
import com.vitorpamplona.quartz.nipFCMavenRepositories.mavenServers
import com.vitorpamplona.quartz.nipFCMavenRepositories.mavenVersion
import com.vitorpamplona.quartz.nipFCMavenRepositories.tags.ManifestTag
import com.vitorpamplona.quartz.utils.TimeUtils

/**
 * NIP-FC release: a regular, immutable event that pins the manifest of one version of one or more
 * modules sharing a group (a Kotlin Multiplatform library's root and target modules). For a module
 * and version, the canonical release among the group's release key's events is the earliest
 * (`created_at`, then the lowest `id`); a publisher never re-releases a version.
 *
 * Not searchable: its tags are coordinates and hashes, which clients look up by the indexed `j`,
 * `v` and `x` tags rather than by text. It references no event, address or key, so it provides no
 * hints.
 */
@Immutable
class MavenReleaseEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : Event(id, pubKey, createdAt, KIND, tags, content, sig),
    MavenPublication {
    override fun moduleKeys() = tags.mavenModuleKeys()

    override fun modules() = tags.mavenModules()

    override fun version() = tags.mavenVersion()

    override fun manifest() = tags.mavenManifest()

    override fun hashes() = tags.mavenHashes()

    override fun servers() = tags.mavenServers()

    /** Also refuses a `-SNAPSHOT` version, which only a snapshot may carry. */
    override fun isWellFormed() = super.isWellFormed() && version()?.let { !MavenCoordinate.isSnapshotVersion(it) } == true

    companion object {
        const val KIND = 3071

        /** [authors]' releases of [module], at [version] or at every version when it is null. */
        fun filter(
            authors: List<HexKey>,
            module: MavenModule,
            version: String? = null,
        ) = MavenPublication.filter(KIND, authors, module, version)

        /**
         * The release of [modules] (root first) at [version], pinning [manifest]. The manifest's
         * hash is the first `x`; [artifactHashes] (the primary jars, aars, klibs, POMs and Gradle
         * module files) follow it. NIP-FC asks publishers to keep the event under 16 KB, so trim
         * [artifactHashes] from the end before calling this when there are many.
         */
        fun build(
            modules: List<MavenModule>,
            version: String,
            manifest: ManifestTag,
            artifactHashes: List<HexKey> = emptyList(),
            servers: List<String> = emptyList(),
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<MavenReleaseEvent>.() -> Unit = {},
        ): EventTemplate<MavenReleaseEvent> {
            require(modules.isNotEmpty()) { "A release covers at least one module" }
            require(!MavenCoordinate.isSnapshotVersion(version)) { "A -SNAPSHOT version is published as a snapshot" }
            return eventTemplate(KIND, "", createdAt) {
                mavenModules(modules)
                mavenVersion(version)
                mavenManifest(manifest)
                mavenHashes(listOf(manifest.sha256) + artifactHashes)
                mavenServers(servers)
                alt("Release of ${modules.first().withVersion(version)}")
                initializer()
            }
        }
    }
}
