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
package com.vitorpamplona.quartz.experimental.decentralizedLists

import com.vitorpamplona.quartz.experimental.decentralizedLists.header.names
import com.vitorpamplona.quartz.experimental.decentralizedLists.header.tags.ConceptGraphTag
import com.vitorpamplona.quartz.experimental.decentralizedLists.header.tags.DisplayHintTag
import com.vitorpamplona.quartz.experimental.decentralizedLists.header.tags.FieldTypeTag
import com.vitorpamplona.quartz.experimental.decentralizedLists.header.tags.ItemKindTag
import com.vitorpamplona.quartz.experimental.decentralizedLists.header.tags.NamesTag
import com.vitorpamplona.quartz.experimental.decentralizedLists.header.tags.SingularPlural
import com.vitorpamplona.quartz.experimental.decentralizedLists.header.tags.SlugsTag
import com.vitorpamplona.quartz.experimental.decentralizedLists.header.tags.TagRuleType
import com.vitorpamplona.quartz.experimental.decentralizedLists.header.tags.TitlesTag
import com.vitorpamplona.quartz.experimental.decentralizedLists.header.titles
import com.vitorpamplona.quartz.experimental.decentralizedLists.item.comments
import com.vitorpamplona.quartz.experimental.decentralizedLists.item.name
import com.vitorpamplona.quartz.experimental.decentralizedLists.item.tags.CommentsTag
import com.vitorpamplona.quartz.experimental.decentralizedLists.item.tags.NameTag
import com.vitorpamplona.quartz.experimental.decentralizedLists.item.tags.SlugTag
import com.vitorpamplona.quartz.experimental.decentralizedLists.item.tags.TitleTag
import com.vitorpamplona.quartz.experimental.decentralizedLists.item.title
import com.vitorpamplona.quartz.experimental.decentralizedLists.taggings.tags.CurationMethodTag
import com.vitorpamplona.quartz.experimental.decentralizedLists.taggings.tags.PolarityTag
import com.vitorpamplona.quartz.experimental.decentralizedLists.tags.DescriptionTag
import com.vitorpamplona.quartz.experimental.decentralizedLists.tags.JsonTag
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.fastForEach
import com.vitorpamplona.quartz.nip01Core.tags.hashtags.HashtagTag
import com.vitorpamplona.quartz.nip31Alts.AltTag
import com.vitorpamplona.quartz.nip50Search.IndexableFieldVisitor
import com.vitorpamplona.quartz.nip50Search.isMachineValue
import com.vitorpamplona.quartz.nip50Search.isNaturalLanguageValue
import com.vitorpamplona.quartz.nip50Search.isNostrReference
import com.vitorpamplona.quartz.nip50Search.isUrlValue
import com.vitorpamplona.quartz.nip89AppHandlers.clientTag.ClientTag
import com.vitorpamplona.quartz.nip92IMeta.IMetaTag

/**
 * The human-authored text of any kind in the family: first, in a fixed order, the header's `names`
 * and `titles` (singular, then plural), the item's `name` and `title`, `description` and
 * `comments`; then, in tag order, each `t` value that is not a machine value and every value of
 * every tag the author named that is not a Nostr reference ([forEachOtherValue]). Headers and items share
 * one walk because the spec's nonstandard method lets an item carry header tags; each kind simply
 * has fewer of them set.
 *
 * `content` is not part of the spec and ids/pubkeys/coordinates are served by tag filters,
 * so neither is indexed.
 *
 * @return false when the visitor stopped the walk.
 */
fun TagArray.forEachSearchableListField(visitor: IndexableFieldVisitor): Boolean {
    // The read path runs once per event per keystroke, so this is two allocation-free passes
    // instead of one full scan (and one parsed object) per field. The first pass only remembers
    // the first well-formed tag of each named field, so their fixed order can be applied before
    // the second pass visits everything else.
    var names: Array<String>? = null
    var titles: Array<String>? = null
    var name: String? = null
    var title: String? = null
    var description: String? = null
    var comments: String? = null

    fastForEach { tag ->
        if (tag.size < 2 || tag[1].isEmpty()) return@fastForEach
        when (tag[0]) {
            NamesTag.TAG_NAME -> if (names == null && SingularPlural.isTag(tag, NamesTag.TAG_NAME)) names = tag
            TitlesTag.TAG_NAME -> if (titles == null && SingularPlural.isTag(tag, TitlesTag.TAG_NAME)) titles = tag
            NameTag.TAG_NAME -> if (name == null) name = tag[1]
            TitleTag.TAG_NAME -> if (title == null) title = tag[1]
            DescriptionTag.TAG_NAME -> if (description == null) description = tag[1]
            CommentsTag.TAG_NAME -> if (comments == null) comments = tag[1]
        }
    }

    names?.let {
        if (!visitor.visit(it[1])) return false
        if (!visitor.visit(it[2])) return false
    }
    titles?.let {
        if (!visitor.visit(it[1])) return false
        if (!visitor.visit(it[2])) return false
    }
    name?.let { if (!visitor.visit(it)) return false }
    title?.let { if (!visitor.visit(it)) return false }
    description?.let { if (!visitor.visit(it)) return false }
    comments?.let { if (!visitor.visit(it)) return false }
    return forEachOtherValue(withHashtags = true) { value, _ -> visitor.visit(value) }
}

/**
 * The tags whose values are never the author's own data, though their names are longer than one
 * letter: this NIP's own tags (the fixed-order fields the first pass of
 * [forEachSearchableListField] owns, and the schema, concept and curation tags that describe the
 * list rather than the item), and the NIP-wide metadata any event may carry. Single-letter tags
 * never reach this check: they are NIP-01's indexable tags, already reachable by `#x` filters.
 */
private val NOT_AUTHORED_TAG_NAMES =
    hashSetOf(
        // the fixed-order fields
        NamesTag.TAG_NAME,
        TitlesTag.TAG_NAME,
        NameTag.TAG_NAME,
        TitleTag.TAG_NAME,
        DescriptionTag.TAG_NAME,
        CommentsTag.TAG_NAME,
        // the rest of this NIP's own vocabulary
        SlugsTag.TAG_NAME,
        SlugTag.TAG_NAME,
        TagRuleType.REQUIRED.code,
        TagRuleType.ALLOWED.code,
        TagRuleType.RECOMMENDED.code,
        TagRuleType.DISALLOWED.code,
        TagRuleType.OPTIONAL.code,
        FieldTypeTag.TAG_NAME,
        DisplayHintTag.TAG_NAME,
        ItemKindTag.TAG_NAME,
        ConceptGraphTag.TAG_NAME,
        JsonTag.TAG_NAME,
        CurationMethodTag.TAG_NAME,
        PolarityTag.TAG_NAME,
        // NIP-wide metadata
        AltTag.TAG_NAME, // NIP-31: restates title and artist behind a fixed "Song: … by …" prefix
        ClientTag.TAG_NAME, // NIP-89
        IMetaTag.TAG_NAME, // NIP-92: `key value` pairs of URLs and hashes
        "expiration", // NIP-40
        "emoji", // NIP-30
        "nonce", // NIP-13
        "proxy", // NIP-48
        "published_at", // NIP-23
        "content-warning", // NIP-36
        "relay", // NIP-42
        "relays", // NIP-57
        "zap", // NIP-57 splits
        "image", // NIP-23, NIP-52, NIP-58: media URLs, often content hashes
        "thumb",
        "picture",
        "banner",
        "icon",
    )

/** Whether [value], ignoring leading whitespace, starts with `{`: a JSON object, not text to search. */
private fun isJsonObject(value: String): Boolean {
    for (i in value.indices) {
        val c = value[i]
        if (!c.isWhitespace()) return c == '{'
    }
    return false
}

/** Whether [name] is a tag whose values are the author's own data rather than NIP structure. */
private fun isAuthoredTagName(name: String) = name.length > 1 && name !in NOT_AUTHORED_TAG_NAMES

private const val ROLE_HASHTAG = 0
private const val ROLE_TEXT = 1
private const val ROLE_KEYWORD = 2
private const val ROLE_WEBSITE = 3

/**
 * Every value the first pass of [forEachSearchableListField] does not own, in tag order, with the
 * role it plays: each `t` value that is not [isMachineValue] (only [withHashtags]), then every
 * value of every tag the author named ([isAuthoredTagName]) that is not [isNostrReference] and
 * does not start with `{` ([isJsonObject]: a JSON blob, whose ids and keys nobody searches for).
 *
 * The family's tag set is open — deployments add `author`, `isbn`, `github-username`, `phone`
 * and tags nobody has named yet — so a tag this NIP does not define is the author's data and is
 * indexed whatever its value looks like; only Nostr's own references (64-hex ids, addresses,
 * bech32, relay URLs), reachable through tags and filters already, and JSON objects stay out. A value is a
 * URL ([ROLE_WEBSITE]), prose ([isNaturalLanguageValue], [ROLE_TEXT]) or anything else
 * ([ROLE_KEYWORD]: usernames, codes, numbers).
 *
 * @return false when [visit] stopped the walk.
 */
private inline fun TagArray.forEachOtherValue(
    withHashtags: Boolean,
    visit: (value: String, role: Int) -> Boolean,
): Boolean {
    fastForEach { tag ->
        if (tag.size < 2) return@fastForEach
        val tagName = tag[0]
        if (tagName == HashtagTag.TAG_NAME) {
            if (withHashtags && !isMachineValue(tag[1]) && !visit(tag[1], ROLE_HASHTAG)) return false
        } else if (isAuthoredTagName(tagName)) {
            for (i in 1 until tag.size) {
                val value = tag[i]
                if (isNostrReference(value) || isJsonObject(value)) continue
                val role =
                    when {
                        isUrlValue(value) -> ROLE_WEBSITE
                        isNaturalLanguageValue(value) -> ROLE_TEXT
                        else -> ROLE_KEYWORD
                    }
                if (!visit(value, role)) return false
            }
        }
    }
    return true
}

/** The values of [role] from [forEachOtherValue], in tag order, without repeats. */
private fun TagArray.otherValues(role: Int): List<String> {
    val out = LinkedHashSet<String>()
    forEachOtherValue(withHashtags = false) { value, valueRole ->
        if (valueRole == role) out.add(value)
        true
    }
    return out.toList()
}

/**
 * The TITLE role of [forEachSearchableListField], for search engines that weight fields: what the
 * list or item is called — the header's `names` and `titles` (singular, then plural) and the
 * item's `name` and `title`. Same first-well-formed-tag rule as the walk.
 */
fun TagArray.searchableListTitles(): List<String?> {
    val names = names()
    val titles = titles()
    return listOf(names?.singular, names?.plural, titles?.singular, titles?.plural, name(), title())
}

/** The DESCRIPTION role of [forEachSearchableListField]: `description`, then `comments`. */
fun TagArray.searchableListDescriptions(): List<String?> = listOf(description(), comments())

/**
 * The TEXT role of [forEachSearchableListField]: the natural-language values of the tags the
 * author named, one per line, or null when the event has none. The `t` values are not here; the
 * search funnel carries them as hashtags.
 */
fun TagArray.searchableListExtraText(): String? = otherValues(ROLE_TEXT).joinToString("\n").ifEmpty { null }

/**
 * The KEYWORD role of [forEachSearchableListField]: the values of the tags the author named that
 * are neither prose nor URLs (usernames, codes, numbers), in tag order and without repeats.
 */
fun TagArray.searchableListKeywords(): List<String> = otherValues(ROLE_KEYWORD)

/** The WEBSITE role of [forEachSearchableListField]: the URLs in the tags the author named. */
fun TagArray.searchableListWebsites(): List<String> = otherValues(ROLE_WEBSITE)

/** The write-path join of [forEachSearchableListField]: one field per line. */
fun TagArray.searchableListContent() = joinFields { forEachSearchableListField(it) }

private inline fun joinFields(walk: (IndexableFieldVisitor) -> Boolean) =
    buildString {
        walk { field ->
            if (field != null) {
                if (isNotEmpty()) append('\n')
                append(field)
            }
            true
        }
    }
