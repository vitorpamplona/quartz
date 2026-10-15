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
package com.vitorpamplona.quartz.utils

import androidx.collection.MutableIntObjectMap
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import kotlin.concurrent.Volatile

/**
 * Builds the typed event class for one kind. Every Quartz event class's primary constructor
 * fits, so `::TextNoteEvent` is a builder.
 */
fun interface EventBuilder {
    fun build(
        id: HexKey,
        pubKey: HexKey,
        createdAt: Long,
        tags: Array<Array<String>>,
        content: String,
        sig: HexKey,
    ): Event
}

/**
 * One Quartz module's event classes. Each module ships one (`SocialEventKinds`,
 * `PaymentEventKinds`, ...) and [EventFactory] loads every one it finds on the classpath
 * on JVM/Android. On native targets, register them yourself with [EventFactory.register].
 */
fun interface EventKindProvider {
    fun registerKinds(registry: EventKindRegistry)
}

/**
 * Where [EventKindProvider]s declare their classes. A kind normally has one class, given to
 * [register]. Some numbers are reused by unrelated apps; those get a default class plus
 * [variant]s, each claiming the events whose tags it recognizes.
 */
class EventKindRegistry internal constructor() {
    internal class Variant(
        val priority: Int,
        val matches: (TagArray) -> Boolean,
        val builder: EventBuilder,
    )

    internal class Entry(
        val default: EventBuilder?,
        val variants: Array<Variant>,
        val probe: EventBuilder?,
    ) {
        fun build(
            id: HexKey,
            pubKey: HexKey,
            createdAt: Long,
            tags: TagArray,
            content: String,
            sig: HexKey,
        ): Event? {
            for (i in variants.indices) {
                val variant = variants[i]
                if (variant.matches(tags)) return variant.builder.build(id, pubKey, createdAt, tags, content, sig)
            }
            return default?.build(id, pubKey, createdAt, tags, content, sig)
        }

        fun withDefault(builder: EventBuilder) = Entry(builder, variants, probe)

        fun withVariant(variant: Variant) = Entry(default, (variants + variant).sortedBy { it.priority }.toTypedArray(), probe)

        fun withProbe(builder: EventBuilder) = Entry(default, variants, builder)

        companion object {
            val EMPTY = Entry(null, emptyArray(), null)
        }
    }

    // Copy-on-write: registration is rare and happens before parsing starts, reads are on every
    // parsed event. Readers see either the old map or the new one, never a half-built one.
    @Volatile
    internal var entries = MutableIntObjectMap<Entry>()
        private set

    private fun update(
        kind: Int,
        change: (Entry) -> Entry,
    ) {
        val next = MutableIntObjectMap<Entry>(entries.size + 1)
        next.putAll(entries)
        next[kind] = change(entries[kind] ?: Entry.EMPTY)
        entries = next
    }

    /**
     * [builder] makes every event of [kind] that no [variant] claims. The first module to
     * register a kind keeps it.
     */
    fun register(
        kind: Int,
        builder: EventBuilder,
    ) = update(kind) { if (it.default == null) it.withDefault(builder) else it }

    /**
     * [builder] makes the events of [kind] whose tags [matches]. Variants are tried in
     * [priority] order, lowest first, before the kind's default class.
     */
    fun variant(
        kind: Int,
        priority: Int = 0,
        matches: (TagArray) -> Boolean,
        builder: EventBuilder,
    ) = update(kind) { it.withVariant(Variant(priority, matches, builder)) }

    /**
     * What [EventFactory.probe] answers for [kind]. Only for kinds whose tagless event lands on
     * a fallback class (see [EventFactory.probe]).
     */
    fun probeAs(
        kind: Int,
        builder: EventBuilder,
    ) = update(kind) { it.withProbe(builder) }
}

class EventFactory {
    companion object {
        /**
         * Builders registered by the app at runtime. Consulted for kinds no Quartz module
         * registers.
         */
        val factories: MutableMap<Int, EventBuilder> = mutableMapOf()

        private val registry: EventKindRegistry by lazy {
            EventKindRegistry().also { registry ->
                platformEventKindProviders().forEach { it.registerKinds(registry) }
            }
        }

        /**
         * Adds a module's classes. Needed on native targets, which cannot discover providers;
         * on JVM/Android every Quartz module on the classpath is already registered.
         */
        fun register(provider: EventKindProvider) {
            provider.registerKinds(registry)
        }

        fun <T : Event> create(
            id: HexKey,
            pubKey: HexKey,
            createdAt: Long,
            kind: Int,
            tags: Array<Array<String>>,
            content: String,
            sig: HexKey,
        ): T =
            (
                registry.entries[kind]?.build(id, pubKey, createdAt, tags, content, sig)
                    ?: factories[kind]?.build(id, pubKey, createdAt, tags, content, sig)
                    ?: Event(id, pubKey, createdAt, kind, tags, content, sig)
            ) as T

        /**
         * True when [kind] maps to a typed Quartz event class: a registered module class (even
         * one that only claims some of the kind's events by their tags) or a [factories]
         * builder. Unknown kinds are parsed as a bare [Event].
         *
         * Used to decide whether a repost's inner (boosted) kind is something the app can parse
         * and render at all, once per repost in a feed, so it is a map lookup, not a probe.
         */
        fun isKnownKind(kind: Int): Boolean {
            if (kind in factories) return true
            val entry = registry.entries[kind] ?: return false
            return entry.default != null || entry.probe != null || entry.variants.isNotEmpty()
        }

        /**
         * A tagless, contentless instance of [kind], for questions asked of a kind rather than of
         * an event: does it have a typed class ([isKnownKind]), is it searchable (the stores'
         * reindex pre-filters, `SearchableKinds`)?
         *
         * For most kinds that is just [create] with no tags. It is not for kinds whose class is
         * chosen by tags, where a tagless event can land on a different class than the kind's
         * real events. Kind 38000 is the case that matters: with no tags it is an
         * `UnrecognizedKind38000Event` (junk, unsearchable), yet every typed shape of it — mint
         * recommendation, ballot, prediction market — is a searchable, renderable class, so it
         * answers as its primary class. Those kinds name it with [EventKindRegistry.probeAs].
         *
         * The id is non-blank so kinds that lazily hash a missing id (NIP-17 chat) skip that work
         * — only the runtime type matters here.
         */
        fun probe(kind: Int): Event =
            registry.entries[kind]?.probe?.build(PROBE_ID, PROBE_ID, 0L, emptyArray(), "", "")
                ?: create(PROBE_ID, PROBE_ID, 0L, kind, emptyArray(), "", "")

        /** Every kind a Quartz module registered, for sweeps like `SearchableKinds`. */
        fun registeredKinds(): IntArray {
            val kinds = IntArray(registry.entries.size)
            var i = 0
            registry.entries.forEachKey { kinds[i++] = it }
            kinds.sort()
            return kinds
        }

        private const val PROBE_ID = "0"
    }
}

/**
 * The providers this platform can find on its own: on JVM/Android, every Quartz module's
 * provider that is on the classpath. Empty on native, where modules must be registered with
 * [EventFactory.register].
 */
internal expect fun platformEventKindProviders(): List<EventKindProvider>
