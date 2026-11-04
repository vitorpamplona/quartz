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
package com.vitorpamplona.quartz.nip34Git.review

import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip22Comments.CommentEvent
import com.vitorpamplona.quartz.nip32Labeling.LabelEvent
import com.vitorpamplona.quartz.nip34Git.patch.GitPatchEvent
import com.vitorpamplona.quartz.nip34Git.pr.GitPullRequestEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitItemMetadataTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val id = "1".repeat(64)
    private val sig = "00".repeat(64)

    @Test
    fun inlineCommentLocation() {
        val comment =
            CommentEvent(
                id,
                alice,
                1,
                arrayOf(arrayOf("E", id), arrayOf("K", "1618"), arrayOf("f", "src/main.rs"), arrayOf("c", "abc1234"), arrayOf("line", "42-48")),
                "this is wrong",
                sig,
            )
        assertEquals(GitCodeLocation("src/main.rs", "abc1234", 42, 48, false), comment.gitCodeLocation())

        val removed = CommentEvent(id, alice, 1, arrayOf(arrayOf("f", "a.kt"), arrayOf("line", "3", "del")), "", sig)
        assertEquals(GitCodeLocation("a.kt", null, 3, 3, true), removed.gitCodeLocation())

        val plain = CommentEvent(id, alice, 1, arrayOf(arrayOf("E", id)), "lgtm", sig)
        assertNull(plain.gitCodeLocation())

        val resolution = CommentEvent(id, alice, 1, arrayOf(arrayOf("e", id), arrayOf("l", "resolved")), "marked as resolved", sig)
        assertTrue(resolution.isGitThreadResolution())
        assertFalse(plain.isGitThreadResolution())
    }

    @Test
    fun subjectEditLabel() {
        val rename = LabelEvent(id, alice, 1, arrayOf(arrayOf("e", id, "", "root"), arrayOf("L", "#subject"), arrayOf("l", "New title", "#subject")), "", sig)
        assertEquals("New title", rename.subjectEdit())

        val labels = LabelEvent(id, alice, 1, arrayOf(arrayOf("e", id), arrayOf("L", "#t"), arrayOf("l", "bug", "#t")), "", sig)
        assertNull(labels.subjectEdit())
        assertEquals(listOf("bug"), labels.hashtagAssociations())
    }

    @Test
    fun pullRequestTargetBranchAndCoordinates() {
        val pr =
            GitPullRequestEvent(
                id,
                alice,
                1,
                arrayOf(
                    arrayOf("a", "30617:$alice:din", "wss://relay"),
                    arrayOf("a", "30617:$bob:din"),
                    arrayOf("a", "30617:$bob:other", "", "mention"),
                    arrayOf("a", "30023:$bob:article"),
                    arrayOf("b", "release"),
                ),
                "",
                sig,
            )
        assertEquals("release", pr.targetBranch())
        assertEquals(listOf(Address(30617, alice, "din"), Address(30617, bob, "din")), pr.repositoryAddresses())

        val toDefault = GitPullRequestEvent(id, alice, 1, arrayOf(arrayOf("b", "")), "", sig)
        assertNull(toDefault.targetBranch())
    }

    @Test
    fun patchTitlePrefersTheDescriptionTag() {
        val content = "From abc Mon Sep 17 00:00:00 2001\nFrom: A <a@b>\nSubject: [PATCH 1/2] From the headers\n\nbody\n---\n"
        val withDescription = GitPatchEvent(id, alice, 1, arrayOf(arrayOf("description", "[PATCH v2 1/2] From the tag\n\nMore text")), content, sig)
        assertEquals("From the tag", withDescription.title())

        val headersOnly = GitPatchEvent(id, alice, 1, emptyArray(), content, sig)
        assertEquals("From the headers", headersOnly.title())
        assertFalse(headersOnly.isCoverLetter())

        val cover = GitPatchEvent(id, alice, 1, arrayOf(arrayOf("description", "[PATCH 0/3] Series")), "", sig)
        assertTrue(cover.isCoverLetter())
    }
}
