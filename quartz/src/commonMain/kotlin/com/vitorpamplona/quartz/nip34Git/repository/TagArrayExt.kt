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
package com.vitorpamplona.quartz.nip34Git.repository

import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.fastAny
import com.vitorpamplona.quartz.nip01Core.core.fastFirstNotNullOfOrNull
import com.vitorpamplona.quartz.nip01Core.tags.aTag.ATag
import com.vitorpamplona.quartz.nip34Git.repository.tags.CommitReferenceTag
import com.vitorpamplona.quartz.nip34Git.repository.tags.MaintainersTag
import com.vitorpamplona.quartz.nip34Git.repository.tags.RepositoryRole
import com.vitorpamplona.quartz.nip34Git.repository.tags.RepositoryRoleRecord
import com.vitorpamplona.quartz.nip34Git.repository.tags.RepositoryRoleTag

/** The repository's earliest unique commit, as patches and pull requests carry it: the first non-empty `r`. */
fun TagArray.earliestUniqueCommit() = fastFirstNotNullOfOrNull(CommitReferenceTag::parse)

/**
 * Every repository announcement coordinate in the `a` tags, in tag order. Items carry one `a` per
 * maintainer; an `a` marked `mention` (older clients' way to cite a repository) is not one of them.
 */
fun TagArray.repositoryAddresses(): List<Address> =
    mapNotNull { tag ->
        if (tag.getOrNull(3) == MENTION_MARKER) return@mapNotNull null
        ATag.parseAddress(tag)?.takeIf { it.kind == GitRepositoryEvent.KIND }
    }.distinct()

private const val MENTION_MARKER = "mention"

/** Every well-formed `M`/`m`/`o` role record, in tag order. */
fun TagArray.roleRecords(): List<RepositoryRoleRecord> = mapNotNull(RepositoryRoleTag::parse)

/** The `role:subject` of every role tag with a valid pubkey, malformed boundaries included, repeats kept. */
fun TagArray.roleTagKeys(): List<String> = mapNotNull(RepositoryRoleTag::parseKey)

/** True when any `M`/`m`/`o` tag is present, well-formed or not. */
fun TagArray.hasRoleTags(): Boolean = fastAny(RepositoryRoleTag::isTagged)

/** True when any `M` or `m` tag is present, well-formed or not. */
fun TagArray.hasMaintainerRoleTags(): Boolean = fastAny(RepositoryRoleTag::isMaintainerTagged)

/** True when a role tag of any of [roles] names [subject], well-formed or not. */
fun TagArray.hasRoleTagFor(
    subject: HexKey,
    roles: Collection<RepositoryRole> = RepositoryRole.entries,
): Boolean = fastAny { RepositoryRoleTag.isTaggedFor(it, subject, roles) }

/** True when the deprecated `maintainers` tag is present. */
fun TagArray.hasMaintainersTag(): Boolean = fastAny(MaintainersTag::isTag)

/** The pubkeys in every `maintainers` tag, in order, without repeats. */
fun TagArray.maintainers(): List<HexKey> = flatMap { MaintainersTag.parse(it) ?: emptyList() }.distinct()
