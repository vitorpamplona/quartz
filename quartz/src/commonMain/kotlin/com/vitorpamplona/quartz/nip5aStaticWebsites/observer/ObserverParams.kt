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

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nipFAAgentKeys.tags.AgentKeyTag
import com.vitorpamplona.quartz.nipFAAgentKeys.usableAgentKeys
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTask

/**
 * The Observer's settings, as NIP-FB `param` tags on its schedule ([ObserverSchedule]), so every
 * app, and a runner printing for the reader, prints with the same choices
 * (`quartz/plans/2026-11-11-observer-site.md`, "Params"):
 *
 * | param   | values | when missing |
 * |---------|--------|--------------|
 * | [KEY]   | the NIP-FA key's endpoint URL, then the key's last 4 characters | NIP-FA's default key |
 * | [MODEL] | the model id, as the endpoint takes it | the app's default for that endpoint |
 * | [EFFORT] | [EFFORT_DEEP]: ask the provider for its high reasoning effort | Standard: nothing extra |
 *
 * `key` never holds the key itself. `size` is listed in the plan as not defined yet, so it isn't
 * one of [KNOWN]: a schedule carrying it is one this code doesn't understand, which NIP-FB says
 * not to run ([understands]).
 */
object ObserverParams {
    const val MODEL = "model"
    const val KEY = "key"
    const val EFFORT = "effort"

    /** The one [EFFORT] value today. Standard is the param's absence, so apps that predate it still run the schedule. */
    const val EFFORT_DEEP = "deep"

    /** How many of the key's last characters `key` holds. */
    const val KEY_SUFFIX_LENGTH = 4

    /** The params this code understands. */
    val KNOWN: Set<String> = setOf(MODEL, KEY, EFFORT)

    /** Whether [task] asks for Deep writing; any other value, or none, is Standard. */
    fun isDeep(task: ScheduledTask): Boolean =
        task
            .param(EFFORT)
            ?.value()
            ?.trim()
            ?.lowercase() == EFFORT_DEEP

    /** [task] printing Deep, or Standard (which removes the param). */
    fun withDeep(
        task: ScheduledTask,
        deep: Boolean,
    ): ScheduledTask = if (deep) task.withParam(EFFORT, EFFORT_DEEP) else task.withoutParam(EFFORT)

    /** The `key` param: which of the reader's NIP-FA keys to print with. */
    @Immutable
    data class KeyChoice(
        /** The key's endpoint, as the NIP-FA `key` tag writes it. */
        val url: String,
        /** The key's last [KEY_SUFFIX_LENGTH] characters, or null when the param has none (a key-less endpoint). */
        val suffix: String?,
    ) {
        /** Whether [key] is at this endpoint (compared as API roots: trimmed, no trailing `/`). */
        fun isAt(key: AgentKeyTag): Boolean = AgentKeyTag.apiRoot(key.url) == AgentKeyTag.apiRoot(url)

        /** Whether [key] is at this endpoint and ends with [suffix]. */
        fun matches(key: AgentKeyTag): Boolean = isAt(key) && suffix != null && key.hasKey() && key.key.endsWith(suffix)
    }

    /** The model to print with, or null when the schedule names none: the app picks its default for the endpoint. */
    fun model(task: ScheduledTask): String? =
        task
            .param(MODEL)
            ?.value()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** The schedule's `key` choice, or null when it has none (or one without a URL). */
    fun keyChoice(task: ScheduledTask): KeyChoice? {
        val values = task.paramValues(KEY) ?: return null
        val url = values.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return KeyChoice(url, values.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() })
    }

    /**
     * The key to print with, out of [usableKeys] (the reader's NIP-FA keys a client may use, in
     * their order: [usableAgentKeys]): the one at the `key` param's URL ending with its suffix,
     * else the first at that URL, else NIP-FA's default (the first usable key). Null only when
     * there are no usable keys.
     */
    fun key(
        task: ScheduledTask,
        usableKeys: List<AgentKeyTag>,
    ): AgentKeyTag? {
        val choice = keyChoice(task) ?: return usableKeys.firstOrNull()
        return usableKeys.firstOrNull(choice::matches)
            ?: usableKeys.firstOrNull(choice::isAt)
            ?: usableKeys.firstOrNull()
    }

    /** [key] over a NIP-FA list's private tags: only its usable keys ([usableAgentKeys]) count. */
    fun key(
        task: ScheduledTask,
        agentKeyTags: TagArray,
    ): AgentKeyTag? = key(task, agentKeyTags.usableAgentKeys())

    /** The `key` param for [key]: its URL, then its last [KEY_SUFFIX_LENGTH] characters (none for a key-less endpoint). */
    fun keyValues(key: AgentKeyTag): List<String> = if (key.hasKey()) listOf(key.url, key.key.takeLast(KEY_SUFFIX_LENGTH)) else listOf(key.url)

    /** [task] printing with [model]; null removes the param, so the app's default applies. */
    fun withModel(
        task: ScheduledTask,
        model: String?,
    ): ScheduledTask {
        val trimmed = model?.trim()
        return if (trimmed.isNullOrEmpty()) task.withoutParam(MODEL) else task.withParam(MODEL, trimmed)
    }

    /** [task] printing with [key] (its URL and last characters, never the key); null removes the param, so NIP-FA's default applies. */
    fun withKey(
        task: ScheduledTask,
        key: AgentKeyTag?,
    ): ScheduledTask = if (key == null) task.withoutParam(KEY) else task.withParam(KEY, keyValues(key))

    /** The names of [task]'s params this code doesn't understand, in order, each once. */
    fun unknownParams(task: ScheduledTask): List<String> =
        task.params
            .map { it.name }
            .filter { it !in KNOWN }
            .distinct()

    /** Whether this code understands every param on [task]; NIP-FB says not to run a task with one it doesn't. */
    fun understands(task: ScheduledTask): Boolean = task.params.all { it.name in KNOWN }
}
