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

import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.fastAny
import com.vitorpamplona.quartz.nip01Core.core.fastFirstNotNullOfOrNull
import com.vitorpamplona.quartz.nip01Core.core.isTagged
import com.vitorpamplona.quartz.nip94FileMetadata.tags.HashSha256Tag
import com.vitorpamplona.quartz.nipB7Blossom.tags.BlossomServerTag
import com.vitorpamplona.quartz.nipFCMavenRepositories.tags.ManifestTag
import com.vitorpamplona.quartz.nipFCMavenRepositories.tags.ModuleTag
import com.vitorpamplona.quartz.nipFCMavenRepositories.tags.VersionTag

/** Every `j` value as written, root first. */
fun TagArray.mavenModuleKeys() = mapNotNull(ModuleTag::parseKey)

/** Every well-formed `j`, root first. */
fun TagArray.mavenModules() = mapNotNull(ModuleTag::parse)

/** The first well-formed `j`: the root module. */
fun TagArray.mavenRootModule() = fastFirstNotNullOfOrNull(ModuleTag::parse)

/** Whether a `j` names [module]; allocates nothing. */
fun TagArray.hasMavenModule(module: MavenModule) = fastAny { it.size > 1 && it[0] == ModuleTag.TAG_NAME && module.matches(it[1]) }

/** Whether the `v` is [version]; allocates nothing. */
fun TagArray.isMavenVersion(version: String) = isTagged(VersionTag.TAG_NAME, version)

fun TagArray.mavenVersion() = fastFirstNotNullOfOrNull(VersionTag::parse)

fun TagArray.mavenManifest() = fastFirstNotNullOfOrNull(ManifestTag::parse)

/** Every `x`: the manifest's and the primary artifacts' sha256. */
fun TagArray.mavenHashes() = mapNotNull(HashSha256Tag::parse)

/** The Blossom servers the publisher uploaded to. */
fun TagArray.mavenServers() = mapNotNull(BlossomServerTag::parse).filter { it.isNotEmpty() }
