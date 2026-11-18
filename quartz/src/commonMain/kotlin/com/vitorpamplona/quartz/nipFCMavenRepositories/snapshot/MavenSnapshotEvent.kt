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
package com.vitorpamplona.quartz.nipFCMavenRepositories.snapshot

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip01Core.core.BaseAddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.dTag.dTag
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
 * NIP-FC snapshot: the manifest of a `-SNAPSHOT` version, addressable under
 * `d` = `<groupId>:<artifactId>:<version>` (the root coordinate), so each build replaces the last.
 * It carries a release's tags; the manifest names files in Maven's timestamped form.
 *
 * Not searchable: its tags are coordinates and hashes, which clients look up by the indexed `j`,
 * `v` and `x` tags rather than by text. It references no event, address or key, so it provides no
 * hints.
 */
@Immutable
class MavenSnapshotEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : BaseAddressableEvent(id, pubKey, createdAt, KIND, tags, content, sig),
    MavenPublication {
    override fun moduleKeys() = tags.mavenModuleKeys()

    override fun modules() = tags.mavenModules()

    override fun version() = tags.mavenVersion()

    override fun manifest() = tags.mavenManifest()

    override fun hashes() = tags.mavenHashes()

    override fun servers() = tags.mavenServers()

    /** Also needs a `-SNAPSHOT` version and a `d` tag that is the root coordinate. */
    override fun isWellFormed() =
        super.isWellFormed() &&
            version()?.let { MavenCoordinate.isSnapshotVersion(it) } == true &&
            dTag() == coordinate()?.toString()

    companion object {
        const val KIND = 33071

        fun createAddress(
            pubKey: HexKey,
            coordinate: MavenCoordinate,
        ) = Address(KIND, pubKey, coordinate.toString())

        /** [authors]' snapshots of [module], at [version] or at every version when it is null. */
        fun filter(
            authors: List<HexKey>,
            module: MavenModule,
            version: String? = null,
        ) = MavenPublication.filter(KIND, authors, module, version)

        /**
         * The snapshot of [modules] (root first) at the `-SNAPSHOT` [version], pinning [manifest].
         * The manifest's hash is the first `x`; [artifactHashes] follow it (trim them to keep the
         * event under 16 KB).
         */
        fun build(
            modules: List<MavenModule>,
            version: String,
            manifest: ManifestTag,
            artifactHashes: List<HexKey> = emptyList(),
            servers: List<String> = emptyList(),
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<MavenSnapshotEvent>.() -> Unit = {},
        ): EventTemplate<MavenSnapshotEvent> {
            require(modules.isNotEmpty()) { "A snapshot covers at least one module" }
            require(MavenCoordinate.isSnapshotVersion(version)) { "A snapshot needs a -SNAPSHOT version" }
            val root = modules.first().withVersion(version)
            return eventTemplate(KIND, "", createdAt) {
                dTag(root.toString())
                mavenModules(modules)
                mavenVersion(version)
                mavenManifest(manifest)
                mavenHashes(listOf(manifest.sha256) + artifactHashes)
                mavenServers(servers)
                alt("Snapshot of $root")
                initializer()
            }
        }
    }
}
