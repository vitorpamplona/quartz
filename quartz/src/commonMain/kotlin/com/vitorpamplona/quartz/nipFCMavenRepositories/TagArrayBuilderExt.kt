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

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip94FileMetadata.tags.HashSha256Tag
import com.vitorpamplona.quartz.nipB7Blossom.tags.BlossomServerTag
import com.vitorpamplona.quartz.nipFCMavenRepositories.tags.ManifestTag
import com.vitorpamplona.quartz.nipFCMavenRepositories.tags.ModuleTag
import com.vitorpamplona.quartz.nipFCMavenRepositories.tags.VersionTag

// Shared by releases and snapshots, which carry the same tags.

fun <T : Event> TagArrayBuilder<T>.mavenModules(modules: List<MavenModule>) = addAllUniqueValueIfNew(ModuleTag.assemble(modules))

fun <T : Event> TagArrayBuilder<T>.mavenVersion(version: String) = addUnique(VersionTag.assemble(version))

fun <T : Event> TagArrayBuilder<T>.mavenManifest(manifest: ManifestTag) = addUnique(ManifestTag.assemble(manifest))

fun <T : Event> TagArrayBuilder<T>.mavenHashes(hashes: List<HexKey>) = addAllUniqueValueIfNew(hashes.map { HashSha256Tag.assemble(it.lowercase()) })

fun <T : Event> TagArrayBuilder<T>.mavenServers(servers: List<String>) = addAllUniqueValueIfNew(servers.map(BlossomServerTag::assemble))
