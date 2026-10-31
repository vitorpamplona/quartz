# EventFactory and registering a new kind

`quartz/src/commonMain/kotlin/com/vitorpamplona/quartz/utils/EventFactory.kt` turns a
parsed `(id, pubKey, createdAt, kind, tags, content, sig)` into the typed `Event`
subclass. Callers normally reach it through `Event.fromJson(...)` or the relay message
parser.

Shape (verbatim structure):

```kotlin
class EventFactory {
    companion object {
        val factories: MutableMap<Int, EventBuilder> = mutableMapOf()   // runtime registrations

        fun <T : Event> create(id, pubKey, createdAt, kind, tags, content, sig): T =
            (
                createPart1(…) ?: createPart2(…) ?: createPart3(…) ?: createPart4(…)   // each a `when (kind)`
                    ?: factories[kind]?.build(id, pubKey, createdAt, tags, content, sig)
                    ?: Event(id, pubKey, createdAt, kind, tags, content, sig)
            ) as T

        fun isKnownKind(kind: Int): Boolean   // typed class exists?
        fun probe(kind: Int): Event           // a tagless instance, used by the stores' searchable checks
    }
}
```

`create` is split into four `createPartN` functions because one `when` over every kind was
past HotSpot's 8,000-byte JIT limit; add a kind to any part and keep each under the limit
(`EventFactoryJitSizeTest`). Kinds other apps share pick their class from tags and fall back
to an `UnrecognizedKind…Event` (38000, 30301/30302, 30405/30406, 38384–38386, 30054, drss's
30100, 30101, 30102, 30104 and 31985); `probe(kind)` must answer as the primary class for those. A
kind every app writes its own way can split on one `d` instead: 30078 builds drss's
`DrssListenProgressAppDataEvent` for a `d` starting `drss-listen-progress:`, its
`DrssReadWriteListsEvent` for `d` = `read-write-lists` and the generic `AppSpecificDataEvent` for
the rest. Unknown kinds round-trip as plain `Event`.

## Adding a kind — the registrations

The full list, with the test that enforces each, is the table in `event-class-anatomy.md`. The
three that silently lose events when missed:

1. **The class**, in its `nipXX…/` package (or `experimental/…`): subclass `Event`,
   `BaseReplaceableEvent` or `BaseAddressableEvent` (see `event-hierarchy.md`), add
   `companion object { const val KIND = n; fun build(...) }`, tag codecs and builder
   extensions (see `tag-patterns.md`).
2. **`EventFactory.create`** — a branch, or the kind parses as plain `Event`. Also a
   `KindNames` entry (`kinds/KindNames.kt`), which `KindNamesCoverageTest` requires.
3. **`LocalCache` dispatch** — `justConsumeInnerInner` in
   Amethyst's [`EventCache.kt`](https://github.com/vitorpamplona/amethyst/blob/main/commons/src/commonMain/kotlin/com/vitorpamplona/amethyst/commons/model/cache/EventCache.kt)
   (`LocalCache` is `object LocalCache : EventCache()`). Without a branch the event hits the
   `else` and is dropped with only a `Log.w("Event Not Supported")` — including the
   account's own freshly signed copy. Add it next to a sibling of the same shape.

Then, if relevant: make it `SearchableEvent` (see `event-class-anatomy.md` › Searchable or
not), add it to the subscription filter that should fetch it, and add a test in
`quartz/src/commonTest/...`. Regenerate `nip-catalog.md` with `scripts/nip-catalog.py`.

`EventFactory.kt` is one large file every new kind edits — expect merge conflicts and
keep both sides.
