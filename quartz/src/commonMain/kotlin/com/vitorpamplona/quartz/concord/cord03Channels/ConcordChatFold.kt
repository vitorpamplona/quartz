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
package com.vitorpamplona.quartz.concord.cord03Channels

import com.vitorpamplona.quartz.concord.cord02Community.ConcordCommunityState
import com.vitorpamplona.quartz.concord.cord04Roles.AuthorityCitation
import com.vitorpamplona.quartz.concord.cord04Roles.control.tags.VacTag
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip09Deletions.DeletionRequestEvent
import com.vitorpamplona.quartz.nip22Comments.CommentEvent
import com.vitorpamplona.quartz.nip25Reactions.ReactionEvent
import com.vitorpamplona.quartz.nip30CustomEmoji.EmojiUrlTag
import com.vitorpamplona.quartz.nipC7Chats.ChatEvent
import com.vitorpamplona.quartz.utils.concurrent.PlatformLock
import com.vitorpamplona.quartz.utils.concurrent.withLock

/**
 * The rules every Concord reader applies when folding a Channel's Chat Plane into what its timeline
 * shows (CORD-01 Deletions, CORD-02 Appendix B, CORD-03 §3), so two clients that hold the same
 * rumors show the same thing:
 *
 *  - an **edit** (kind 3302) applies only when its author wrote the message it names, and the
 *    newest by CORD-02 §4 send time wins, the rumor id breaking a tie ([latestEdit]);
 *  - a **delete** (kind 5) removes a rumor its own author wrote, and a message or thread reply
 *    (never a reaction) of another member when the Control fold honors it as a moderation hide
 *    ([removes], [ConcordCommunityState.honorsModerationHide]): the hider holds MANAGE_MESSAGES in
 *    the Channel, strictly outranks the author, and their `vac` citation resolves;
 *  - a **reaction** (kind 7) counts once per reactor and display key ([reactionKey]: `+` or empty
 *    is 👍, `-` is 👎), only when a row can show it ([isRenderableReaction]), and stops counting
 *    once its own author deletes it.
 *
 * [ConcordChatFold] applies them to a set of rumors held in memory; an app that keeps rumors in its
 * own store applies them through these functions and holds the deletes in [ConcordModerationHides].
 */
object ConcordChatRules {
    /** Most `:shortcode:` bytes a renderable custom-emoji reaction may name. */
    const val MAX_SHORTCODE = 48

    /** Most characters (code points) a renderable plain reaction may hold. */
    const val MAX_REACTION_CHARS = 12

    private val SHORTCODE = Regex("^:(.+):$")
    private val SHORTCODE_CHARS = Regex("^[A-Za-z0-9_~+-]+$")

    /**
     * The edit overlaying a message by [targetAuthor] among [candidates], or null: only edits its
     * author wrote count, the newest by [ConcordChatEditEvent.orderingMs] wins, then the larger id.
     * [event] reads each candidate's edit (an app's store wraps events in its own type).
     */
    fun <T> latestEdit(
        targetAuthor: HexKey,
        candidates: Iterable<T>,
        event: (T) -> Event?,
    ): T? {
        val author = targetAuthor.lowercase()
        var best: T? = null
        var bestEdit: ConcordChatEditEvent? = null
        for (c in candidates) {
            val e = event(c) as? ConcordChatEditEvent ?: continue
            if (e.pubKey.lowercase() != author) continue
            val b = bestEdit
            if (b == null || e.orderingMs() > b.orderingMs() || (e.orderingMs() == b.orderingMs() && e.id > b.id)) {
                best = c
                bestEdit = e
            }
        }
        return best
    }

    /** [latestEdit] over plain edit events. */
    fun latestEdit(
        targetAuthor: HexKey,
        edits: Iterable<ConcordChatEditEvent>,
    ): ConcordChatEditEvent? = latestEdit(targetAuthor, edits) { it }

    /** The `vac` citation a delete carries, or null (the owner's hide and a self-delete cite nothing). */
    fun citationOf(delete: Event): AuthorityCitation? = delete.tags.firstOrNull(VacTag::isTag)?.let(VacTag::parse)

    /**
     * Whether [delete] removes a rumor of [targetKind] written by [targetAuthor] in [channelIdHex]:
     * its author's own, or another member's message when [state] honors it as a moderation hide. A
     * reaction is only ever withdrawn by its own author. Before the Control Plane folds ([state]
     * null) only a self-delete counts.
     */
    fun removes(
        delete: ConcordHeldDelete,
        targetAuthor: HexKey,
        targetKind: Int,
        state: ConcordCommunityState?,
    ): Boolean {
        if (delete.deleter == targetAuthor.lowercase()) return true
        if (targetKind == ReactionEvent.KIND) return false
        return state?.honorsModerationHide(delete.deleter, targetAuthor, delete.channelIdHex, delete.citation) == true
    }

    /**
     * Whether the current fold lets [delete]'s author moderate its Channel at all: it would honor
     * them hiding a message by a member who holds no role. False before the Control Plane folds.
     */
    fun mayModerate(
        delete: ConcordHeldDelete,
        state: ConcordCommunityState?,
    ): Boolean = state?.honorsModerationHide(delete.deleter, NO_ONE, delete.channelIdHex, delete.citation) == true

    // A pubkey no member holds: ranks below everyone, owns nothing, holds no role.
    private const val NO_ONE = ""

    /** The display key a reaction tallies under: `+` or empty is 👍, `-` is 👎, anything else is itself. */
    fun reactionKey(content: String): String =
        when (content) {
            "+", "" -> "👍"
            "-" -> "👎"
            else -> content
        }

    /**
     * Whether a row can show a reaction's [content]: a like or dislike (`+`, `-`, empty), a clean
     * `:shortcode:` of at most [MAX_SHORTCODE] characters, or a single token of at most
     * [MAX_REACTION_CHARS] code points with no whitespace and no URL in it. Anything else is not
     * counted, so a reaction can't smuggle a sentence or a link into the tally.
     */
    fun isRenderableReaction(content: String): Boolean {
        if (content == "" || content == "+" || content == "-") return true
        val code = SHORTCODE.find(content)?.groupValues?.get(1)
        if (code != null && code.length <= MAX_SHORTCODE && SHORTCODE_CHARS.matches(code)) return true
        if (content.contains("://")) return false
        var codePoints = 0
        var i = 0
        while (i < content.length) {
            val c = content[i]
            if (c.isWhitespace()) return false
            i += if (c.isHighSurrogate() && i + 1 < content.length && content[i + 1].isLowSurrogate()) 2 else 1
            codePoints++
        }
        return codePoints <= MAX_REACTION_CHARS
    }

    /** The message a reaction names: its last `e` tag (NIP-25). */
    fun reactionTarget(reaction: ReactionEvent): HexKey? = reaction.originalPost().lastOrNull()

    /** The image of a NIP-30 `:shortcode:` reaction: only the `emoji` tag naming its own shortcode counts. */
    fun customEmojiUrl(reaction: Event): String? {
        val code = SHORTCODE.find(reaction.content)?.groupValues?.get(1) ?: return null
        return reaction.tags.firstNotNullOfOrNull { tag -> EmojiUrlTag.parse(tag)?.takeIf { it.code == code }?.url }
    }

    /** True for the kinds a timeline shows as rows: a message (9) or a thread reply (1111). */
    fun isRow(rumor: Event): Boolean = rumor.kind == ChatEvent.KIND || rumor.kind == CommentEvent.KIND
}

/**
 * A kind-5 delete held for [channelIdHex]: [deleter] wrote [deletionId] naming [targets], citing
 * [citation]. Whether it removes a given target is [ConcordChatRules.removes]'s call, against the
 * fold of the moment, since the target, the hider's Grant or their loss of it can arrive later.
 */
class ConcordHeldDelete(
    channelIdHex: HexKey,
    deletionId: HexKey,
    deleter: HexKey,
    val citation: AuthorityCitation?,
    targets: Set<HexKey>,
) {
    val channelIdHex: HexKey = channelIdHex.lowercase()
    val deletionId: HexKey = deletionId.lowercase()
    val deleter: HexKey = deleter.lowercase()
    val targets: Set<HexKey> = targets.mapTo(HashSet()) { it.lowercase() }

    companion object {
        /** The [delete] rumor of [channelIdHex] as a held delete, or null when it names nothing. */
        fun of(
            channelIdHex: HexKey,
            delete: DeletionRequestEvent,
        ): ConcordHeldDelete? {
            val targets = delete.deleteEventIds().toSet()
            if (targets.isEmpty()) return null
            return ConcordHeldDelete(channelIdHex, delete.id, delete.pubKey, ConcordChatRules.citationOf(delete), targets)
        }
    }
}

/**
 * The deletes a reader holds for each Channel, so a moderation hide (CORD-03 §3) applies to a target
 * that arrives after it and keeps applying while the fold honors it. Thread-safe.
 *
 * Any member can post deletes, so the store is bounded at [maxPerChannel] per Channel, and the ones
 * whose author could not moderate the Channel when they were held (a member's own deletes, mostly)
 * are evicted first, oldest first, re-judged against the fold at eviction: a flood of self-deletes
 * can never push a moderator's hide out. Only moderators' own hides push each other out.
 *
 * A delete held with no fold to judge it (before the Control Plane folds) is unjudged. Those go
 * after the judged-unprivileged ones, are judged once an eviction has a fold, and while none is
 * there are evicted from whoever holds the most of them, oldest first: a member flooding deletes
 * before the fold pushes out their own, not a moderator's few hides.
 */
class ConcordModerationHides(
    val maxPerChannel: Int = DEFAULT_MAX_PER_CHANNEL,
) {
    private val lock = PlatformLock()

    // Channel id -> held deletes by deletion id, oldest first.
    private val byChannel = HashMap<HexKey, LinkedHashMap<HexKey, ConcordHeldDelete>>()

    // Channel id -> target rumor id -> the held deletes naming it.
    private val byTarget = HashMap<HexKey, HashMap<HexKey, MutableList<ConcordHeldDelete>>>()

    // Channel id -> deletion ids whose author could not moderate when held: evicted first.
    private val unprivileged = HashMap<HexKey, LinkedHashSet<HexKey>>()

    // Channel id -> deletion ids held with no fold to judge them: evicted after the unprivileged.
    private val unjudged = HashMap<HexKey, LinkedHashSet<HexKey>>()

    /**
     * Holds [delete], judging whether its author may moderate against [state]. False when it was
     * already held.
     */
    fun hold(
        delete: ConcordHeldDelete,
        state: ConcordCommunityState?,
    ): Boolean =
        lock.withLock {
            val held = byChannel.getOrPut(delete.channelIdHex) { LinkedHashMap() }
            if (held.containsKey(delete.deletionId)) return@withLock false
            held[delete.deletionId] = delete
            val targets = byTarget.getOrPut(delete.channelIdHex) { HashMap() }
            delete.targets.forEach { targets.getOrPut(it) { ArrayList(1) }.add(delete) }
            val weak = unprivileged.getOrPut(delete.channelIdHex) { LinkedHashSet() }
            val pending = unjudged.getOrPut(delete.channelIdHex) { LinkedHashSet() }
            if (state == null) {
                pending.add(delete.deletionId)
            } else if (!ConcordChatRules.mayModerate(delete, state)) {
                weak.add(delete.deletionId)
            }
            while (held.size > maxPerChannel) {
                val victim = nextEvictionLocked(held, weak, pending, state) ?: break
                held.remove(victim.deletionId)
                weak.remove(victim.deletionId)
                pending.remove(victim.deletionId)
                victim.targets.forEach { target ->
                    val list = targets[target] ?: return@forEach
                    list.remove(victim)
                    if (list.isEmpty()) targets.remove(target)
                }
            }
            true
        }

    /** Holds the kind-5 [delete] seen on [channelIdHex]; false when it names nothing or was already held. */
    fun hold(
        channelIdHex: HexKey,
        delete: DeletionRequestEvent,
        state: ConcordCommunityState?,
    ): Boolean = ConcordHeldDelete.of(channelIdHex, delete)?.let { hold(it, state) } ?: false

    /** Holds every one of [deletes] (a replaced session's); the number newly held. */
    fun holdAll(
        deletes: Collection<ConcordHeldDelete>,
        state: ConcordCommunityState?,
    ): Int = deletes.count { hold(it, state) }

    private fun nextEvictionLocked(
        held: LinkedHashMap<HexKey, ConcordHeldDelete>,
        weak: LinkedHashSet<HexKey>,
        pending: LinkedHashSet<HexKey>,
        state: ConcordCommunityState?,
    ): ConcordHeldDelete? {
        while (weak.isNotEmpty()) {
            val id = weak.first()
            weak.remove(id)
            val delete = held[id] ?: continue
            // One granted the bit since it was held is promoted out of the weak set here.
            if (!ConcordChatRules.mayModerate(delete, state)) return delete
        }
        if (state != null) {
            // A fold is here at last: judge the unjudged, oldest first, promoting the moderators'.
            while (pending.isNotEmpty()) {
                val id = pending.first()
                pending.remove(id)
                val delete = held[id] ?: continue
                if (!ConcordChatRules.mayModerate(delete, state)) return delete
            }
        } else if (pending.isNotEmpty()) {
            return busiestPendingLocked(held, pending) ?: held.values.firstOrNull()
        }
        return held.values.firstOrNull()
    }

    // The oldest unjudged delete of the deleter holding the most of them (the earliest such
    // deleter on a tie), so nobody's flood can evict anyone else's deletes before its own.
    private fun busiestPendingLocked(
        held: LinkedHashMap<HexKey, ConcordHeldDelete>,
        pending: LinkedHashSet<HexKey>,
    ): ConcordHeldDelete? {
        val counts = LinkedHashMap<HexKey, Int>()
        for (id in pending) {
            val deleter = held[id]?.deleter ?: continue
            counts[deleter] = (counts[deleter] ?: 0) + 1
        }
        var busiest: HexKey? = null
        var most = 0
        for ((deleter, count) in counts) {
            if (count > most) {
                busiest = deleter
                most = count
            }
        }
        for (id in pending) {
            val delete = held[id] ?: continue
            if (delete.deleter == busiest) return delete
        }
        return null
    }

    /** The held deletes on [channelIdHex] naming [rumorId]. */
    fun deletesNaming(
        channelIdHex: HexKey,
        rumorId: HexKey,
    ): List<ConcordHeldDelete> = lock.withLock { byTarget[channelIdHex.lowercase()]?.get(rumorId.lowercase())?.toList() } ?: emptyList()

    /**
     * Whether a held delete on [channelIdHex] removes [rumorId], of [kind], written by [author],
     * under [state] ([ConcordChatRules.removes]): a self-delete, or a moderation hide the fold honors.
     */
    fun removes(
        channelIdHex: HexKey,
        rumorId: HexKey,
        author: HexKey,
        kind: Int,
        state: ConcordCommunityState?,
    ): Boolean = deletesNaming(channelIdHex, rumorId).any { ConcordChatRules.removes(it, author, kind, state) }

    /**
     * Whether a moderation hide (a delete by someone other than [author]) held on [channelIdHex]
     * covers [rumorId], of [kind], under [state]. A self-delete is not a hide, a reaction is never
     * hidden, and nothing is before the fold.
     */
    fun isHidden(
        channelIdHex: HexKey,
        rumorId: HexKey,
        author: HexKey,
        state: ConcordCommunityState?,
        kind: Int = ChatEvent.KIND,
    ): Boolean {
        val a = author.lowercase()
        return deletesNaming(channelIdHex, rumorId).any { it.deleter != a && ConcordChatRules.removes(it, a, kind, state) }
    }

    /** Channel id -> every rumor id a held delete names there (honored or not). */
    fun targets(): Map<HexKey, Set<HexKey>> = lock.withLock { byTarget.mapValues { it.value.keys.toSet() } }

    /** Every held delete, per Channel oldest first. */
    fun all(): List<ConcordHeldDelete> = lock.withLock { byChannel.values.flatMap { it.values } }

    /** How many deletes [channelIdHex] holds. */
    fun size(channelIdHex: HexKey): Int = lock.withLock { byChannel[channelIdHex.lowercase()]?.size ?: 0 }

    companion object {
        /** Held deletes per Channel before the oldest unprivileged one is evicted. */
        const val DEFAULT_MAX_PER_CHANNEL = 2000
    }
}

/** One reaction display key on a message: [reactors] maps each reactor to their reaction's rumor id. */
class ConcordReactionTally(
    val key: String,
    val url: String?,
    val reactors: Map<HexKey, HexKey>,
) {
    val count: Int get() = reactors.size
}

/** A row a Channel timeline shows: [rumor], its [content] after [edit], and its [reactions]. */
class ConcordFoldedMessage(
    val rumor: Event,
    val content: String,
    val edit: ConcordChatEditEvent?,
    val reactions: List<ConcordReactionTally>,
) {
    val edited: Boolean get() = edit != null
}

/**
 * One Channel's Chat Plane folded into what its timeline shows ([ConcordChatRules]): edits overlaid,
 * deleted and hidden rows gone, reactions tallied. Feed it the rumors the Chat ingest gate accepted
 * ([ChannelChat.acceptOpened]) in any order, then read it against the Control fold of the moment, so
 * a hide comes undone when its hider loses the bit. A banned author's rows and reactions are left
 * out (CORD-04 §4). Holds every rumor it is given; not thread-safe.
 */
class ConcordChatFold(
    channelIdHex: HexKey,
    maxHeldDeletes: Int = ConcordModerationHides.DEFAULT_MAX_PER_CHANNEL,
) {
    val channelIdHex: HexKey = channelIdHex.lowercase()

    private val byId = LinkedHashMap<HexKey, Event>()
    private val editsByTarget = HashMap<HexKey, MutableList<ConcordChatEditEvent>>()
    private val reactionsByTarget = HashMap<HexKey, MutableList<ReactionEvent>>()

    // Target id -> the authors who deleted it: an author's own delete always stands, so it is kept
    // apart from the bounded hide store.
    private val selfDeleters = HashMap<HexKey, MutableSet<HexKey>>()

    /** The deletes this fold holds as possible moderation hides. */
    val hides = ConcordModerationHides(maxHeldDeletes)

    /** Adds an accepted rumor of this Channel; false when it was already in. [state] judges a delete's author for the hide store's eviction. */
    fun add(
        rumor: Event,
        state: ConcordCommunityState? = null,
    ): Boolean {
        val id = rumor.id.lowercase()
        if (byId.containsKey(id)) return false
        byId[id] = rumor
        when (rumor) {
            is ConcordChatEditEvent -> rumor.editedMessageId()?.let { editsByTarget.getOrPut(it.lowercase()) { ArrayList(1) }.add(rumor) }
            is ReactionEvent -> ConcordChatRules.reactionTarget(rumor)?.let { reactionsByTarget.getOrPut(it.lowercase()) { ArrayList(1) }.add(rumor) }
            is DeletionRequestEvent -> {
                val held = ConcordHeldDelete.of(channelIdHex, rumor) ?: return true
                held.targets.forEach { selfDeleters.getOrPut(it) { HashSet(1) }.add(held.deleter) }
                hides.hold(held, state)
            }
        }
        return true
    }

    /** Adds every one of [rumors]; how many were new. */
    fun addAll(
        rumors: Iterable<Event>,
        state: ConcordCommunityState? = null,
    ): Int = rumors.count { add(it, state) }

    /** The rumor with [id], if this fold holds it. */
    fun rumor(id: HexKey): Event? = byId[id.lowercase()]

    private fun isBanned(
        author: HexKey,
        state: ConcordCommunityState?,
    ) = state?.authority?.isBanned(author) == true

    /** Whether [rumor] is gone: its author deleted it, or a moderation hide [state] honors covers it. */
    fun isRemoved(
        rumor: Event,
        state: ConcordCommunityState?,
    ): Boolean {
        val id = rumor.id.lowercase()
        if (selfDeleters[id]?.contains(rumor.pubKey.lowercase()) == true) return true
        return hides.isHidden(channelIdHex, id, rumor.pubKey, state, rumor.kind)
    }

    /** Whether the rumor [id] is a row the timeline shows: a message or thread reply, not removed, by an author not banned. */
    fun isVisible(
        id: HexKey,
        state: ConcordCommunityState?,
    ): Boolean {
        val rumor = rumor(id) ?: return false
        return ConcordChatRules.isRow(rumor) && !isBanned(rumor.pubKey, state) && !isRemoved(rumor, state)
    }

    /** The edit overlaying the message [targetId] ([ConcordChatRules.latestEdit]); edits their author deleted don't count. */
    fun latestEdit(targetId: HexKey): ConcordChatEditEvent? {
        val target = rumor(targetId) ?: return null
        val edits = editsByTarget[target.id.lowercase()] ?: return null
        return ConcordChatRules.latestEdit(target.pubKey, edits.filter { selfDeleters[it.id.lowercase()]?.contains(it.pubKey.lowercase()) != true })
    }

    /** The reaction tallies on [targetId], by first appearance of each key: one per reactor and key, withdrawn ones out. */
    fun reactions(
        targetId: HexKey,
        state: ConcordCommunityState?,
    ): List<ConcordReactionTally> {
        val reactions = reactionsByTarget[targetId.lowercase()] ?: return emptyList()
        val keys = LinkedHashMap<String, LinkedHashMap<HexKey, ReactionEvent>>()
        for (r in reactions.sortedWith(compareBy({ ChannelChat.orderingMs(it) ?: (it.createdAt * 1000) }, { it.id }))) {
            if (!ConcordChatRules.isRenderableReaction(r.content)) continue
            if (isBanned(r.pubKey, state) || isRemoved(r, state)) continue
            keys.getOrPut(ConcordChatRules.reactionKey(r.content)) { LinkedHashMap() }[r.pubKey.lowercase()] = r
        }
        return keys.map { (key, reactors) ->
            ConcordReactionTally(key, reactors.values.firstNotNullOfOrNull(ConcordChatRules::customEmojiUrl), reactors.mapValues { it.value.id })
        }
    }

    /** The timeline: every visible row in CORD-02 §4 send order (then id), edits applied, reactions tallied. */
    fun timeline(state: ConcordCommunityState?): List<ConcordFoldedMessage> =
        byId.values
            .filter { isVisible(it.id, state) }
            .sortedWith(compareBy({ ChannelChat.orderingMs(it) ?: (it.createdAt * 1000) }, { it.id }))
            .map { row ->
                val edit = latestEdit(row.id)
                ConcordFoldedMessage(row, edit?.content ?: row.content, edit, reactions(row.id, state))
            }
}
