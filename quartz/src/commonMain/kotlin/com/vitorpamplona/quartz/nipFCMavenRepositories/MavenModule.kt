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

import androidx.compose.runtime.Immutable

/**
 * A Maven module, `groupId:artifactId`, the value of a NIP-FC `j` tag. Only the shape is checked
 * (two non-empty parts, no `:`): whether a name is safe as a path is the resolver's call.
 */
@Immutable
data class MavenModule(
    val groupId: String,
    val artifactId: String,
) {
    /** `groupId:artifactId`, as the `j` tag spells it. */
    val key: String get() = "$groupId:$artifactId"

    fun withVersion(version: String) = MavenCoordinate(groupId, artifactId, version)

    override fun toString() = key

    companion object {
        fun parse(key: String): MavenModule? {
            val sep = key.indexOf(':')
            if (sep <= 0 || sep == key.length - 1 || key.indexOf(':', sep + 1) >= 0) return null
            return MavenModule(key.substring(0, sep), key.substring(sep + 1))
        }
    }
}

/**
 * A Maven coordinate, `groupId:artifactId:version`: a release's root module at its version, and
 * a snapshot's `d` tag.
 */
@Immutable
data class MavenCoordinate(
    val groupId: String,
    val artifactId: String,
    val version: String,
) {
    val module: MavenModule get() = MavenModule(groupId, artifactId)

    val isSnapshot: Boolean get() = isSnapshotVersion(version)

    override fun toString() = "$groupId:$artifactId:$version"

    companion object {
        fun parse(value: String): MavenCoordinate? {
            val parts = value.split(':')
            if (parts.size != 3 || parts.any { it.isEmpty() }) return null
            return MavenCoordinate(parts[0], parts[1], parts[2])
        }

        /** Maven's rule: a version ending in `-SNAPSHOT` is mutable, and NIP-FC publishes it as a snapshot. */
        fun isSnapshotVersion(version: String) = version.endsWith("-SNAPSHOT")
    }
}
