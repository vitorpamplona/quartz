# Anatomy of an event class

How every event class in Quartz is laid out, and everything a new kind has to register. The model
is `nip88Polls/` (NIP-88 polls); `drss/` shows the same layout for a kind other apps share. A class
you touch that does not follow this is brought in line in the same change: see "Drift" below.

## The package

One kind per sub-package, everything about its tags beside it:

```
nip88Polls/poll/
├── PollEvent.kt            the event class
├── TagArrayExt.kt          readers:  fun TagArray.options() = mapNotNull(OptionTag::parse)
├── TagArrayBuilderExt.kt   writers:  fun TagArrayBuilder<PollEvent>.options(o: List<OptionTag>) = addAll(OptionTag.assemble(o))
└── tags/
    ├── OptionTag.kt        TAG_NAME, parse(tag), assemble(...), isTag(tag)
    ├── EndsAtTag.kt
    └── …
```

- **One codec per tag** under `tags/`: `const val TAG_NAME`, a `parse(tag)` that returns null for
  anything malformed (guarded with `ensure`), and an `assemble(...)` that writes it. A tag that
  carries several values is a `data class` (`OptionTag(code, label)`); a single value returns the
  value. Reuse a codec that already exists (`TitleTag`, `DescriptionTag`, `ATag`, `ETag`, `PTag`,
  `QTag`, `DurationTag`, …) before writing one. Grep first.
- **No tag parsing in the event class**: no `it[0] == "name"` and no `tag.size > 1` lambdas. The
  class calls the `TagArray` readers and the readers call the codecs.
- **Writers are typed to the event** (`TagArrayBuilder<PollEvent>`) so a tag can only be added to
  the kinds that carry it; tags any kind may carry stay generic (`fun <T : Event> TagArrayBuilder<T>`).
- Hot-path readers use the `fast*` operators on the raw `TagArray` (AGENTS.md › Kotlin style).

## The class

```kotlin
@Immutable
class PollEvent(id: HexKey, pubKey: HexKey, createdAt: Long, tags: Array<Array<String>>, content: String, sig: HexKey) :
    Event(id, pubKey, createdAt, KIND, tags, content, sig),   // or BaseReplaceableEvent / BaseAddressableEvent
    RootScope,                                                 // NIP-22 comments may hang off it
    PubKeyHintProvider, EventHintProvider, AddressHintProvider, // one per reference type it carries
    SearchableEvent {                                          // when it carries text a person would search
    fun options() = tags.options()
    fun mentions(): List<PTag> = tags.mapNotNull(PTag::parse)          // semantic name for `p`
    fun mentionKeys(): List<HexKey> = tags.mapNotNull(PTag::parseKey)
    fun quotedEvents(): List<QEventTag> = tags.mapNotNull(QEventTag::parse)   // semantic name for `q`
    …
    companion object {
        const val KIND = 1068                                    // a literal: nip-catalog.py reads it
        fun build(description: String, options: List<OptionTag>, …, createdAt: Long = TimeUtils.now(),
                  initializer: TagArrayBuilder<PollEvent>.() -> Unit = {}) =
            eventTemplate(KIND, description, createdAt) { options(options); …; initializer() }
    }
}
```

### Semantic names for every `a`, `e`, `p` and `q`

Every reference tag the kind carries gets a method named for **what the reference is to this
event**, even when the body is one line over the generic codec: `mentions()`, `quotedEvents()`,
`board()`, `episode()`, `children()`, `items()`, `feeds()`, `showBot()`. Give the parsed tag and its
bare id/key/address side by side (`mentions()` / `mentionKeys()`, `children()` /
`childAddressIds()`), and name a single reference in the singular. A caller should never have to
know that "the board" is the first `a` of kind 30301.

### Hints and links read through those names

Implement `PubKeyHintProvider`, `EventHintProvider` and `AddressHintProvider`
(`nip01Core/hints/HintProviders.kt`) for each reference type the kind carries, and nothing else:

- `xxxHints()` returns the relay hints its tags carry (`PTag::parseAsHint`, a custom codec's
  `parseAsHint` when the tag's slots differ from NIP-01's);
- `linkedXxx()` returns the referenced ids through the semantic methods
  (`override fun linkedPubKeys() = mentionKeys()`), with a one-line KDoc naming the relation it
  is, in the graph-link vocabulary (`/** \`MEMBER\`: the feed bots (\`p\`). */`).
- Text that can cite `nostr:` URIs (a note, a description, a question) adds them, as `PollEvent`
  does: `citedNIP19()` parses `content` once into a `@Transient` cache, and each provider appends
  `citedNIP19().pubKeyHints()` / `.pubKeys()` / `.eventHints()` / `.eventIds()` /
  `.addressHints()` / `.addressIds()`.
- A value that only looks like a reference (a hash in an `e`, a `k` that is not a kind) is not
  linked; say so in the KDoc.

Record what each reference means in `quartz/plans/2026-09-29-graph-link-vocabulary-appendix.md`, one
row per class.

### Searchable or not

A kind is `SearchableEvent` when it carries text a person would search for: a title, a name, a
description, a note. Implement both `indexableContent()` (the joined write path) and
`forEachIndexableField(visitor)` (the read path; the same fields, in the same order, without
joining). Then, in the same change: add the kind to `nip50Search/SearchableKinds.kt` `ALL`, add a
`SearchFieldExtractor` branch when titles should outrank the body, add its row to the
`event-store-semantics` skill's `references/searchable-kinds.md`, and regenerate
`jvmTest/resources/indexable-content.golden` (delete it and run `IndexableContentGoldenTest`; the
diff must show only your kind). Numbers about another event, ciphertext and key material are not
searchable.

### Builders

`companion fun build(...)` returns an unsigned `EventTemplate<T>` from `eventTemplate(KIND, content,
createdAt) { … }`, built only from the `TagArrayBuilderExt` writers, and ends in `initializer()`.
Take references as `EventHintBundle<T>`, `ATag`, `PTag` or the kind's own tag classes, never raw
strings. A `build()` must parse back as its own class: test it.

## A kind other apps share

When another app publishes a different shape on the same number, the class gets a classifier on its
companion (`fun isPodcastEpisode(tags): Boolean`, built from the codecs' `TAG_NAME`s), and every
other shape becomes an `UnrecognizedKindNNNNNEvent` beside it: same base class, the shared `KIND`, no
hints, not searchable, and implementing `UnrecognizedShape` (`nip01Core/core/`), which tells clients
the kind's name is not this event's. Then:

- `EventFactory.create` picks the class with the classifier (an `if` or a `when`), in whichever of
  the four `createPartN` it lives in; keep each part under the JIT limit (`EventFactoryJitSizeTest`).
- `EventFactory.probe(kind)` answers as your class, since a tagless probe falls to the fallback.
- `KindNames` names the kind after your class; say in a comment that the kind is shared.
- Document the split in the class KDoc, and in `searchable-kinds.md` when the class is searchable.

`drss/` (30054, 30100, 30101, 30102, 30104, 31985) and `nip99Classifieds/` (30405, 30406) are worked examples.

## Registrations a new kind needs

| Where | What | Enforced by |
|---|---|---|
| `utils/EventFactory.kt` | a branch in a `createPartN` (and `probe` for a shared kind) | the kind parses as plain `Event` without it |
| `kinds/KindNames.kt` | a name | `KindNamesCoverageTest` |
| `nip50Search/` | `SearchableKinds.ALL` and the golden file, when searchable | `SearchableKindsTest`, `IndexableContentGoldenTest` |
| skill references | `searchable-kinds.md` row; `nip-catalog.md` regenerated with `scripts/nip-catalog.py` (never hand-edited) | review |
| graph-link appendix | one row per class | review |
| Amethyst `EventCache.justConsumeInnerInner` | a branch, or the event is dropped with "Event Not Supported" | its tests |
| Amethyst `NoteCompose` `RenderNoteRow` | a card, the unknown-kind card, or an explicit "draw nothing" branch | `NoteComposeKindCoverageTest` |
| Amethyst `RepostRenderability` / `RenderableKinds` | `TYPED_WITHOUT_A_CARD` and search's omitted list, when there is no card | `RepostRenderabilityTest`, `RenderableKindsTest` |

## Tests

In `quartz/src/commonTest/…/<package>/`: parse real events from relays (cut long tag lists, say
where and when they came from), one per shape and one per other app's shape on a shared kind; read
every accessor; check `linkedXxx()` and the hints, including a `nostr:` URI in the text; sign each
`build()` and assert it parses back as its class; check `SearchFieldExtractor.extract` when the kind
is searchable.

## Drift

Many older classes predate this layout. `scripts/event-pattern-audit.py` lists them: event classes
with no `build()`, inline tag parsing, `linkedXxx()` reading raw tags with no semantic method, a
reference tag with no hint provider, or a title with no search. It is a heuristic, so read the class
before changing it. When you add or change a kind, run it for the package you touch and bring what
it lists there in line in the same change; do not widen an unrelated change into a sweep.
