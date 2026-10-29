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
package com.vitorpamplona.quartz.nipB7Blossom

import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nipB7Blossom.tags.BlossomHashTag
import com.vitorpamplona.quartz.nipB7Blossom.tags.BlossomServerTag
import com.vitorpamplona.quartz.nipB7Blossom.tags.BlossomSizeTag
import com.vitorpamplona.quartz.nipB7Blossom.tags.BlossomVerbTag

/** BUD-03 `server` tags, in preference order. */
fun TagArrayBuilder<BlossomServersEvent>.blossomServers(servers: List<String>) = addAll(servers.map { BlossomServerTag.assemble(it) })

/** BUD-11 `t`: what the token allows (`upload`, `delete`, `list`, `get`, `media`). */
fun TagArrayBuilder<BlossomAuthorizationEvent>.verb(verb: String) = addUnique(BlossomVerbTag.assemble(verb))

fun TagArrayBuilder<BlossomAuthorizationEvent>.blobHash(hash: String) = add(BlossomHashTag.assemble(hash))

fun TagArrayBuilder<BlossomAuthorizationEvent>.blobSize(size: Long) = addUnique(BlossomSizeTag.assemble(size))

/** BUD-11 `server` tags: the bare domains the token is scoped to. */
fun TagArrayBuilder<BlossomAuthorizationEvent>.serverDomains(domains: List<String>) = addAll(domains.map { BlossomServerTag.assemble(it) })
