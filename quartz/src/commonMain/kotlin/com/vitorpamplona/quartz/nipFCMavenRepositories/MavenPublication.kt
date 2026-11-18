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
package com.vitorpamplona.quartz.nipFCMavenRepositories

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nipFCMavenRepositories.release.MavenReleaseEvent
import com.vitorpamplona.quartz.nipFCMavenRepositories.snapshot.MavenSnapshotEvent
import com.vitorpamplona.quartz.nipFCMavenRepositories.tags.ManifestTag
import com.vitorpamplona.quartz.nipFCMavenRepositories.tags.ModuleTag
import com.vitorpamplona.quartz.nipFCMavenRepositories.tags.VersionTag
import com.vitorpamplona.quartz.utils.Hex

/**
 * NIP-FC: what a release ([MavenReleaseEvent]) and a snapshot ([MavenSnapshotEvent]) share, an
 * event that pins the manifest of one version of one or more modules. Nothing here says whether
 * to trust it: that takes the group's release key and the earliest-release rule.
 */
interface MavenPublication {
    /** Every `j` value as written, root module first. */
    fun moduleKeys(): List<String>

    /** Every well-formed `j`, root module first. */
    fun modules(): List<MavenModule>

    /** The first well-formed `j`: the root module. */
    fun rootModule(): MavenModule?

    fun version(): String?

    /** The manifest blob the event pins. */
    fun manifest(): ManifestTag?

    /** Every `x`: the manifest's and the primary artifacts' sha256, so a `#x` lookup finds who signed a file. */
    fun hashes(): List<HexKey>

    /** The Blossom servers the publisher uploaded to. */
    fun servers(): List<String>

    /** The root coordinate: the first module at [version]. */
    fun coordinate(): MavenCoordinate? {
        val version = version() ?: return null
        return rootModule()?.withVersion(version)
    }

    /**
     * Whether this event is a candidate for [module] at [version]: its [version] (the first `v`)
     * is [version] and a `j` names [module]. Resolvers call it for every event, so it allocates
     * nothing.
     */
    fun covers(
        module: MavenModule,
        version: String,
    ): Boolean

    /** Has modules, a version, and a manifest with a size. */
    fun isWellFormed(): Boolean {
        val size = manifest()?.size ?: return false
        return size >= 0 && version() != null && rootModule() != null
    }

    companion object {
        /** What [ManifestTag.parse] reads back: 32 bytes of hex and a size. */
        internal fun checkManifest(manifest: ManifestTag) {
            require(Hex.isHexKey(manifest.sha256)) { "The manifest hash must be 32 bytes of hex" }
            require(manifest.size >= 0) { "The manifest size can't be negative" }
        }

        val KINDS = listOf(MavenReleaseEvent.KIND, MavenSnapshotEvent.KIND)

        /** Every release and snapshot of [module] by [authors]: what a client lists for `maven-metadata.xml`. */
        fun versionsFilter(
            authors: List<HexKey>,
            module: MavenModule,
        ) = Filter(kinds = KINDS, authors = authors, tags = mapOf(ModuleTag.TAG_NAME to listOf(module.key)))

        internal fun filter(
            kind: Int,
            authors: List<HexKey>,
            module: MavenModule,
            version: String?,
        ): Filter {
            val tags =
                if (version == null) {
                    mapOf(ModuleTag.TAG_NAME to listOf(module.key))
                } else {
                    mapOf(ModuleTag.TAG_NAME to listOf(module.key), VersionTag.TAG_NAME to listOf(version))
                }
            return Filter(kinds = listOf(kind), authors = authors, tags = tags)
        }
    }
}
