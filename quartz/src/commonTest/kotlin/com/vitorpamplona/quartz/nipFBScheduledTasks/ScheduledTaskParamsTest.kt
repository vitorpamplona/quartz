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
package com.vitorpamplona.quartz.nipFBScheduledTasks

import com.vitorpamplona.quartz.nip01Core.core.tagArray
import com.vitorpamplona.quartz.nipFBScheduledTasks.tags.ParamTag
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScheduledTaskParamsTest {
    @Test
    fun theParamTagCarriesANameThenItsValues() {
        assertEquals(ParamTag("model", listOf("anthropic/claude-sonnet-4.5")), ParamTag.parse(arrayOf("param", "model", "anthropic/claude-sonnet-4.5")))
        assertEquals(ParamTag("key", listOf("https://openrouter.ai/api/v1", "a3f9")), ParamTag.parse(arrayOf("param", "key", "https://openrouter.ai/api/v1", "a3f9")))
        // A name alone is a param with no values.
        assertEquals(ParamTag("flag"), ParamTag.parse(arrayOf("param", "flag")))
        assertNull(ParamTag.parse(arrayOf("param")))
        assertNull(ParamTag.parse(arrayOf("param", " ")))
        assertNull(ParamTag.parse(arrayOf("cron", "model")))
        assertContentEquals(arrayOf("param", "key", "u", "a3f9"), ParamTag("key", listOf("u", "a3f9")).toTagArray())
    }

    private val task =
        ScheduledTask(
            arrayOf(arrayOf("d", "observer"), arrayOf("param", "size", "big"), arrayOf("param", "topic", "public")),
            arrayOf(
                arrayOf("task", "observer"),
                arrayOf("param", "topic", "nostr"),
                arrayOf("cron", "0 6 * * *", "UTC"),
                arrayOf("param", "model", "m1"),
                arrayOf("param", "topic", "bitcoin"),
            ),
        )

    @Test
    fun paramsAreOrderedAndRepeatablePrivateFirst() {
        assertEquals(listOf("topic", "model", "topic", "size", "topic"), task.params.map { it.name })
        assertEquals(listOf("nostr", "bitcoin", "public"), task.params("topic").map { it.value() })
        assertEquals(ParamTag("model", listOf("m1")), task.param("model"))
        assertEquals(listOf("big"), task.paramValues("size"))
        assertNull(task.param("missing"))
    }

    @Test
    fun settingANameReplacesEveryTagOfItAtTheFirstOnesPlace() {
        val set = task.withParam("topic", "art")
        assertEquals(listOf(listOf("art")), set.params("topic").map { it.values })
        // The private first one took the new value in place; the rest of that name went, on both sides.
        assertContentEquals(arrayOf("param", "topic", "art"), set.privateTags[1])
        assertEquals(listOf("task", "param", "cron", "param"), set.privateTags.map { it[0] })
        assertEquals(listOf("d", "param"), set.publicTags.map { it[0] })
        // Params it doesn't touch stay on their side, unknown names included.
        assertEquals("big", set.publicTags[1][2])
        assertEquals("m1", set.param("model")!!.value())
    }

    @Test
    fun aPublicOnlyParamIsSetOnThePublicSideAndANewOneGoesPrivate() {
        val size = task.withParam("size", listOf("small"))
        assertContentEquals(arrayOf("param", "size", "small"), size.publicTags[1])
        assertEquals(task.privateTags.size, size.privateTags.size)
        val added = task.withParam("length", "short")
        assertContentEquals(arrayOf("param", "length", "short"), added.privateTags.last())
        assertEquals(task.publicTags.size, added.publicTags.size)
    }

    @Test
    fun addingKeepsTheOthersOfThatNameInOrder() {
        val added = task.withAddedParam("topic", listOf("art"))
        // After the last one readers see: the public `topic`.
        assertEquals(listOf("nostr", "bitcoin", "public", "art"), added.params("topic").map { it.value() })
        assertContentEquals(arrayOf("param", "topic", "art"), added.publicTags[3])
        val model = task.withAddedParam("model", listOf("m2"))
        assertEquals(listOf("m1", "m2"), model.params("model").map { it.value() })
        assertContentEquals(arrayOf("param", "model", "m2"), model.privateTags[4])
        assertContentEquals(arrayOf("param", "new", "x"), task.withAddedParam("new", listOf("x")).privateTags.last())
    }

    @Test
    fun removingANameKeepsEveryOtherParam() {
        val removed = task.withoutParam("topic")
        assertEquals(listOf("model", "size"), removed.params.map { it.name })
        assertEquals(listOf("d", "param"), removed.publicTags.map { it[0] })
        assertEquals(task, task.withoutParam("missing"))
    }

    @Test
    fun theBuilderAddsParamsInOrder() {
        val tags =
            tagArray<ScheduledTaskEvent> {
                param("topic", "nostr")
                param(ParamTag("topic", listOf("bitcoin")))
            }
        assertEquals(listOf("nostr", "bitcoin"), tags.params("topic").map { it.value() })
    }
}
