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
package com.vitorpamplona.quartz.nip5aStaticWebsites.observer

import com.vitorpamplona.quartz.nipFAAgentKeys.tags.AgentKeyTag
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTask
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObserverParamsTest {
    private val openRouter = "https://openrouter.ai/api/v1"
    private val first = AgentKeyTag("sk-or-v1-0000000000001111", openRouter)
    private val second = AgentKeyTag("sk-or-v1-000000000000a3f9", openRouter)
    private val ppq = AgentKeyTag("sk-ppq-00000000000000bb", "https://api.ppq.ai")
    private val keys = listOf(ppq, first, second)

    private val schedule = ObserverSchedule.new("America/New_York", now = 0)

    @Test
    fun theModelIsTheFirstValueAndMissingMeansTheAppsDefault() {
        assertNull(ObserverParams.model(schedule))
        assertEquals("anthropic/claude-sonnet-4.5", ObserverParams.model(ObserverParams.withModel(schedule, "anthropic/claude-sonnet-4.5")))
        assertNull(ObserverParams.model(schedule.withParam("model", " ")))
        assertNull(ObserverParams.model(schedule.withParam("model", emptyList())))
        // Clearing removes the param.
        assertTrue(ObserverParams.withModel(ObserverParams.withModel(schedule, "m"), null).params.isEmpty())
    }

    @Test
    fun theKeyMatchesByUrlAndSuffix() {
        val chosen = ObserverParams.withKey(schedule, second)
        assertContentEquals(arrayOf("param", "key", openRouter, "a3f9"), chosen.privateTags.last())
        // Never the key itself.
        assertFalse(chosen.privateTags.any { tag -> tag.any { it == second.key } })
        assertEquals(ObserverParams.KeyChoice(openRouter, "a3f9"), ObserverParams.keyChoice(chosen))
        assertEquals(second, ObserverParams.key(chosen, keys))
        // A trailing slash on either side is the same endpoint.
        assertEquals(second, ObserverParams.key(schedule.withParam("key", "$openRouter/", "a3f9"), keys))
    }

    @Test
    fun theKeyFallsBackToTheFirstAtThatUrlThenTheDefault() {
        // The suffix matches none: the first key at the URL.
        assertEquals(first, ObserverParams.key(schedule.withParam("key", openRouter, "zzzz"), keys))
        assertEquals(first, ObserverParams.key(schedule.withParam("key", openRouter), keys))
        // No key at the URL: NIP-FA's default, the first usable one.
        assertEquals(ppq, ObserverParams.key(schedule.withParam("key", "https://elsewhere.example/v1", "a3f9"), keys))
        // No param: the default.
        assertEquals(ppq, ObserverParams.key(schedule, keys))
        assertNull(ObserverParams.key(schedule, emptyList()))
        // From a NIP-FA list's tags: a key over plain http to another machine isn't usable, even when named.
        val insecure = AgentKeyTag("sk-insecure-a3f9", "http://example.com/v1")
        val tags = arrayOf(insecure.toTagArray(), ppq.toTagArray(), second.toTagArray())
        assertEquals(ppq, ObserverParams.key(schedule.withParam("key", "http://example.com/v1", "a3f9"), tags))
        assertEquals(second, ObserverParams.key(ObserverParams.withKey(schedule, second), tags))
    }

    @Test
    fun aKeylessEndpointIsNamedByItsUrlOnly() {
        val local = AgentKeyTag("", "http://localhost:11434/v1")
        val chosen = ObserverParams.withKey(schedule, local)
        assertContentEquals(arrayOf("param", "key", "http://localhost:11434/v1"), chosen.privateTags.last())
        assertEquals(local, ObserverParams.key(chosen, listOf(ppq, local)))
        assertTrue(ObserverParams.withKey(chosen, null).params.isEmpty())
    }

    @Test
    fun effortIsKnownAndStandardIsItsAbsence() {
        val task = ScheduledTask.create("observer", "observer", "0 6 * * *", "America/New_York", setAt = 0)
        assertFalse(ObserverParams.isDeep(task))
        val deep = ObserverParams.withDeep(task, true)
        assertTrue(ObserverParams.isDeep(deep))
        assertTrue(ObserverParams.understands(deep))
        assertEquals(emptyList(), ObserverParams.unknownParams(deep))
        val standard = ObserverParams.withDeep(deep, false)
        assertFalse(ObserverParams.isDeep(standard))
        assertNull(standard.param(ObserverParams.EFFORT))
    }

    @Test
    fun settingsKeepTheParamsTheyDontKnow() {
        val other = ScheduledTask(schedule.publicTags, schedule.privateTags + arrayOf(arrayOf("param", "size", "big")))
        val edited = ObserverParams.withKey(ObserverParams.withModel(other, "m"), first)
        assertEquals(listOf("size", "model", "key"), edited.params.map { it.name })
        assertEquals(listOf("size"), ObserverParams.unknownParams(edited))
        assertFalse(ObserverParams.understands(edited))
        assertTrue(ObserverParams.understands(edited.withoutParam("size")))
        assertTrue(ObserverParams.understands(schedule))
        // Changing the model again replaces it in place.
        assertEquals(listOf("size", "model", "key"), ObserverParams.withModel(edited, "m2").params.map { it.name })
        assertEquals("m2", ObserverParams.model(ObserverParams.withModel(edited, "m2")))
    }
}
