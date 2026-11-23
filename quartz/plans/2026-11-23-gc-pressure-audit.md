# Library-wide allocation and GC-pressure audit

**Date:** 2026-11-23
**Status:** measurements and ranked backlog; fixed so far: items 1 (hint providers), 3 and 4
(client listeners), 5 (caching decoder, in part), 10 (write buffers), 11 (`ContactTag`), 12 (kind-0
search), 17 (address assembly, in part), 20 (`EventInterner`)
**Harnesses:**
- `quartz/src/jvmTest/.../nip01Core/prodbench/IngestAllocationBenchmark.kt` (ingest stages, retained size)
  `./gradlew :quartz:jvmTest --tests "*.IngestAllocationBenchmark" -i`
- `quartz/src/jvmTest/.../nip01Core/prodbench/GcPressureBenchmark.kt` (new: serialization, hashing,
  filters, hints, event-class accessors, REQ serialization, SQLite insert and read)
  `./gradlew :quartz:jvmTest --tests "*.GcPressureBenchmark" -PgcBench=1 -i`

The 2026-11-06 performance review measured CPU. This audit measures **bytes allocated**,
because on Android the cost shows up as GC work charged to the threads doing the allocating
(the SM-T220 ingest profile in `IngestAllocationBenchmark`'s KDoc). It also counts objects
HotSpot's escape analysis would remove, since ART and Kotlin/Native don't remove them.

## Method

- Every number in the **Measured** tables comes from `ThreadMXBean.getThreadAllocatedBytes`.
  The run used the first 20,000 unique events of `nostr_vitor_startup_data.json.gz`: 858 chars
  each on average, 2.6 tags and 6.4 tag values per event, 12% of events with non-Latin-1
  content. It ran on JVM 21, warmed up.
- The store rows count every thread's allocation (`getTotalThreadAllocatedBytes`), so treat
  them as upper bounds.
- Everything else came from four code audits (core and codecs, relay client and server,
  stores, event classes). Their top claims were re-read in the source before landing here.
  Byte counts marked *est.* are estimates for a 64-bit JVM with compressed oops: a String is
  24 B + 16 B array header + its length, so a 64-char hex string is about 104 B. On ART a
  `StringBuilder` is backed by `char[]`, so builder garbage there is about twice as large.
- Codegen claims (continuations, lazies, string concatenation, `IntRange` in
  `contains(ignoreCase)`) were checked with `javap`.

## Measured

### Ingest (per event)

| Stage | Allocated | × wire |
|---|---:|---:|
| UTF-8 bytes → `String` (the socket's decode) | 1,419 B | 1.61 |
| `["EVENT",…]` frame → `Message` | 1,381 B | 1.57 |
| event JSON → `Event` | 1,309 B | 1.49 |
| `verify()` | 15 B | 0.02 |
| content scans (hashtags, NIP-19, index tags) | 250 B | 0.28 |
| **retained** by a parsed `Event` | **1,343 B** | 1.53 |

**The parser is not the problem.** Kite allocates about what the `Event` keeps (1.1 bytes of
garbage per byte kept, and most of that is the frame's own String), and `verify()` allocates
nothing. The garbage comes from what runs after the parse.

### Everything else (per operation)

| Operation | Allocated | Note |
|---|---:|---|
| `Event.toJson()` | 1,188 B | mostly the result String |
| `EventHasher.hashIdBytes` | 63 B | the 32-byte result |
| `EventHasher.hashId` (hex) | 311 B | `Hex.encode` costs about 250 B for a 104 B result |
| `Filter.match`, 500 authors / `#p` | 15–19 B | lean |
| hint providers (event + pubkey + address, both forms) | **596 B / event** | runs on every relay copy; ~120 B after item 1 |
| `ContactListEvent.follows()` | **532 B / p tag** | 0.5 MB per 1,000-follow list; 35 B after item 11 |
| `ContactListEvent.linkedPubKeys()` | 12 B / tag | lean |
| `MetadataEvent.contactMetaData()` | **2,290 B / kind 0** | uncached; search called it per event per term per keystroke (98 B per profile per keystroke after item 12) |
| `SearchableEvent.indexableContent()` | 684 B | on every store insert of a searchable kind |
| `AddressSerializer.assemble(30023, pk, d)` | 455 B | for a ~110 B result; 150–200 B after item 17 |
| `ReqCmd` (2 filters, 500 authors) `.toJson()` | **164,173 B** | for a ~34 KB frame; 69 KB after item 10 |
| `SeenIds()` default constructor | **16 MB** | eager `LongArray(2^21)` |
| SQLite `batchInsert` (500 per batch) | 1,644 B / event | |
| SQLite `insert` (one by one) | 1,621 B / event | |
| SQLite query, `kinds=[1]` | 1,513 B / row | |
| SQLite query, follow feed (merge executor) | 1,916 B / row | |
| SQLite query, `#p` notifications | 1,986 B / row | |

## Where a kept event's 1,343 bytes go

Per event: 2.6 tags, 6.4 values, 191 tag chars, 306 content chars.

| Part | Bytes | Share |
|---|---:|---:|
| `id` + `pubKey` + `sig` as hex Strings | ~376 | 28% |
| content String | ~370 | 28% |
| tag arrays and values | ~460 | 34% |
| `Event` object and subclass fields | ~50–130 | 10% |

Hex costs 2× the bytes of the binary form (176 B as three `ByteArray`s). That is a 15% cut in
retained memory per event, but `HexKey = String` is the public API, so it is a major-version
change (see **Structural**). `Event.shareStrings` already removes repeated pubkeys and tag
values in caches.

## Ranked backlog

The ranking weighs frequency × bytes. Each item notes whether it is compatible: almost all are
internal changes or additive overloads that keep the old signatures (see AGENTS.md
**Versioning**).

### Tier 1: per frame or per event, cheap to fix

1. **Fixed: the hint providers allocated about 600 B per event per call; now about 120 B.**
   *Measured* over the whole corpus: kind 1 went from 1,490 to ~370 B, kind 42 from 1,424 to
   ~260 B, kind 1311 from 2,075 to ~180 B. The remainder is the result lists themselves, the
   semantic accessors' own lists, and about 12 B per call of harness overhead.
   - The cause was lists built and thrown away:
     - `tags.mapNotNull(...)` per tag type, joined with `+`;
     - the NIP-19 helpers in `ListEntityExt.kt` (`mapNotNull{}.flatten()`) building lists even
       for the ~94% of notes that cite nothing;
     - `Nip19Parser.parseAll*` returning a new, cached `ArrayList` when empty. They now return
       the shared `emptyList()`; the list itself stays a `val`, because creating it lazily
       from a `var` assigned inside the inlined scan lambda came back empty on Kotlin/Native
       (`linuxX64`) while passing on the JVM.
   - Fix:
     - `TagArray.fastMapNotNull` (returns the shared `emptyList()` and allocates its list on
       the first match) and `fastMapNotNullTo(destination?)`, which chains scans into one list;
     - `eventHintsTo`, `eventIdsTo`, `addressHintsTo`, `addressIdsTo`, `pubKeyHintsTo` and
       `pubKeysTo` for the NIP-19 lists;
     - `List.appendTo(destination?)`, which adds a semantic accessor's list to the chain and
       costs nothing when that list is empty;
     - ~220 event classes rewritten onto them. The list kinds (nip02, nip51) keep the stdlib's
       `mapNotNull`, because those scans are dense.
   - `linkedXxx()` still reads through the semantic accessors, per the event-class anatomy.
     Those accessors now use `fastMapNotNull`, and `quotedEventIds()`/`quotedAddressIds()` were
     added beside `quotedEvents()`/`quotedAddresses()`. Calling the parsers directly measured
     96 B; the rule costs ~25 B.
   - `event-pattern-audit.py` now recognises the `fast*` helpers, so they can't hide a raw read
     from its check.
   - Accessors that parsed an object only to read one field now use the field's own parser:
     `ATag`/`QTag`/`ReportedAddressTag`/`CommunityTag`/`AddressBookmark.parseValidAddress` go
     through the new `AddressSerializer.canonicalAddressIdOrNull`, `ReportEvent` reads ids
     directly, and `LiveActivitiesChatMessageEvent` no longer parses an `Address`.
   - Verified: the six hint lists of all 31,161 unique corpus events (43 kinds, 153,832 lines)
     are byte-identical before and after. The corpus has no `q` quoting an `naddr1`; a review
     caught that the first version dropped those quotes from `linkedAddressIds()`.
     `quotedAddressIds()` now reads through `QAddressableTag.parseAddressId`, which decodes them,
     and `QuotedNaddrLinkTest` pins it. Pinned by `FastMapNotNullTest` and
     `AddressShapeTest.canonicalAddressIdMatchesTheParseRoundTrip`.
2. **A continuation object on every frame once any message listener is registered.** *Est.*
   ~72 B per frame, plus ~48 B per listener whose override calls `super`.
   - Where: `NostrClient.onIncomingMessageInFull` (`NostrClient.kt:557-594`). The EVENT fast
     path applies only when `forMessages` is empty.
   - Quartz installs none of these listeners itself, but an app that installs `RelayStats`,
     `RelayLogger`, `RelayAuthenticator`, `RelayNotifier` or `RelayLimitsTracker` sends every
     EVENT down the slow path.
   - The no-op `super.onIncomingMessage(...)` calls that aren't in tail position are
     `RelayNotifier.kt:48`, `RelayReqStats.kt:45` and `NostrClientPublishExt.kt:242`.
   - Fix:
     - delete those three `super` calls;
     - add an internal "ignores EVENT" marker for listeners that never read EVENTs, and widen
       the fast-path test;
     - count bytes for `RelayStats` once in `BasicRelayClient` instead of through a listener.
3. **Fixed: `RelayLogger` built its tag on every frame with logging off.** *Measured* through the
   listener: 176 → 0 B per EVENT frame. The tag is built only where a line is written.
4. **Fixed: `RelayReqStats` boxed, allocated a lambda and leaked per EVENT.** *Measured*: 40 → 0 B
   per frame on HotSpot, which had already scalarized part of it.
   - The empty `super.onIncomingMessage` call, which made the override a state machine, is gone.
   - `ReqStatsRepository` keys by the frame's subscription id with a per-kind atomic count, so
     two subscriptions no longer share a hashed counter. A REQ shares one id across every relay,
     so the hot path takes no lock; only a new subscription or kind does.
   - It keeps at most 1,000 subscriptions; past that, the one with the fewest events goes, so
     long-lived feeds outlast one-off fetches.
5. **Fixed in part: `CachingEventDecoder` materialized the subId and id twice for new events.**
   *Measured* over 50,000 capture frames (27% new, 73% duplicates) under 40 random 10-char
   subscription ids: 614 → 505 B per frame.
   - The scan returns its positions packed in a `Long`, not a holder.
   - A new event's parse reuses the id string the scan cut for the lookup, through an internal
     Kite hint that applies only where the frame holds exactly those characters.
   - Subscription ids come from a 64-slot table of recent ones (ids up to 64 chars, cleared
     with the cache), so a subscription's frames share one instance.
   - Still open: a duplicate still cuts a 104 B id string to look it up. Avoiding it means
     keying the cache by the id's bits rather than by String.
6. **The `by lazy` delegates in `ZapReceiptEvent` cost 80 B per receipt at construction.**
   - Where: `ZapReceiptEvent.kt:110,113`. Each delegate is a `SynchronizedLazyImpl` plus a
     lambda capturing `this`, and receipts are built for every relay copy before dedup.
   - Fix: `amount` becomes a `@Volatile` field with a sentinel. `zapRequest` keeps one
     instance through an `AtomicReference` or stays lazy.
   - The same pattern is in 10 more classes: `Bolt12ZapEvent`, `HighlightEvent`,
     `JesterEvent`, three experimental classes and `CallIceCandidateEvent`. They are colder.
   - Binary compatible: the `$delegate` fields are private.
7. **Kite allocates a new subId per frame and every tag name longer than one char.** *Est.*
   ~50 B per frame plus 1–4 × ~45 B per event.
   - Examples: `alt`, `client`, `emoji`, `imeta`, `expiration`, and the `root`/`reply`/`mention`
     markers.
   - Fix: remember the last 1–4 subIds in the thread's `KiteReader`. Before `substring`, match
     short unescaped values against a small constant table (and the remembered subIds) with
     `regionMatches`, as `oneCharString` already does for single chars.
   - Benchmark the CPU cost against the bytes saved first.
8. **`Hex.encode` allocates a `CharArray` and then a String.** *Measured.* About 250 B per
   32-byte encode, for a 104 B result.
   - Where: `Hex.kt:318-328`.
   - Fix: write into a thread-local `CharArray` and `concatToString(0, n)` (only the String
     allocates), or into a `ByteArray` with `decodeToString()`. Check `HexBenchmark` first.
   - Hit on: signing, id hashing, `toHexKey()`, and NIP-19 decoding in content.

### Tier 2: per insert, per query, per large event

9. **The SQLite insert path allocates about 1.6 KB per event.** *Measured.* Its parts:
   - **Tag hashing**, about 130 B per indexed tag (*est.*). `EventIndexesModule.kt:207-256`
     collects hashes in an `ArrayList<Long>` (one box per tag) and sorts the list.
     `TagNameValueHasher.hash` (`:55-66`) calls `encodeToByteArray()` on both key and value
     for every hash. A kind 3 with 1,000 `p` tags is about 135 KB (*est.*).
     - Fix: a String overload of `hash128x64Half` with an ASCII fast path (byte-identical
       output), precomputed seeds per tag name, and a reused `LongArray` sorted in place.
       This also applies to the query-side hashing in `QueryBuilder` and
       `MergeQueryExecutor`.
   - **The statement cache** (`StatementCachingConnection.kt:76-78`) does `remove` and then
     `put` on a `LinkedHashMap` for every hit: a new map entry per `prepare`, about 6 per
     insert. Use a `HashMap` with a last-use tick.
   - **Savepoint SQL** (`SQLiteEventStore.kt:513-528`): `val sp = "ev"` is a local, so
     `"SAVEPOINT $sp"` and `"RELEASE SAVEPOINT $sp"` are concatenated per row. Make them
     `const`.
   - **`indexableContent()`** (*measured*: 684 B per searchable event) is bound into FTS on
     every insert. 124 of the 212 overrides `joinToString` a `listOfNotNull(...)`; kind 1 is
     `listOfNotNull(subject(), content).joinToString("\n")`, which copies `content` through an
     unsized builder even when there is no subject.
     - Fix: return `content` when it is the only part; otherwise fill one presized builder.
   - **Duplicates**: each duplicate insert throws an `SQLiteException` with a full stack trace
     (*est.* 0.5–2 KB on ART), which is how the batch path detects UNIQUE conflicts.
     - Fix: `INSERT … ON CONFLICT DO NOTHING RETURNING row_id`, gated on the SQLite version,
       which also removes the `SELECT last_insert_rowid()` statement.
     - This overlaps backlog Store-4 (skip duplicates before the write). Doing Store-4 first
       removes most of these throws.
10. **Fixed for writes: large outputs regrew the per-thread buffers from scratch every time.**
    *Measured*: a REQ with 500 authors went from 164 KB to 69 KB (the builder plus the result
    String, the floor for a String-returning writer). Events over 16K chars went from 4.4× to
    2.4× their JSON.
    - The thread's `StringBuilder` is still dropped after a write past 16K chars, but the next
      large write sizes it once from a cheap length estimate (`KiteWriter.reserve`: events,
      tag arrays, templates, and every filter of a REQ or COUNT at once) instead of doubling
      from 1K.
    - On native, where the id-hash preimage cannot be streamed into the digest
      (`SHA256_STREAMS`), the byte buffer is sized to the preimage once.
    - Still open: the reader's escape buffer (`cb`) and tag scratch regrow the same way for a
      large escaped article or a 5,000-follow list.
11. **Fixed: `ContactTag.parseValid` ran a regex per `p` tag.** *Measured*: 532 → 35 B per tag (the
    `ContactTag` itself). A 64-hex key now keeps the tag's own string, lower-cased if needed;
    anything else still takes the `decodePublicKey` path, so even a 64-char value hiding an
    `npub1…` parses as before (`ContactTagParseValidTest`).
12. **Fixed: kind-0 search re-parsed the profile JSON through kotlinx for every check.**
    *Measured*: a two-term search over the corpus's profiles went from 3,311 to 98 B per profile
    per keystroke.
    - `EventSearchMatcher` walks a searchable event's fields once for all its terms, phrases and
      exclusions.
    - Its case-insensitive substring check no longer allocates an `IntRange` per field.
    - `MetadataEvent` keeps the nine searchable fields after its first parse. That retains
      ~670 B per searched profile (the corpus mean), recovered on the next keystroke.
13. **Published events are serialized once per relay.**
    - Where: `BasicRelayClient.kt:553` calls `cmd.toJson()` for each relay, and
      `EventCmd.toJson()` (`EventCmd.kt:41`) re-serializes every time.
      `PoolEventOutboxState.command()` builds a new `EventCmd` on every reconnect.
    - Cost: a 70 KB contact list to 20 relays is about 1.4 MB (*est.*).
    - Fix: memoize the frame in a private field and keep one `EventCmd` per outbox entry.
14. **`RequestSubscriptionState` allocates 32 locks per subscription.** *Est.* ~2.2 KB per
    subId on the JVM; 32 `NSRecursiveLock`s on Apple.
    - Where: `RequestSubscriptionState.kt:128`.
    - Hit on: every fetch, every page of `walkPages`, every by-id batch, and every relay
      connect.
    - Fix: one stripe array shared through the companion object, with a per-instance seed. Keep
      the `@PublishedApi` getter.
15. **Merge-executor reads.** *Measured* ~1.9 KB per row on the follow feed.
    - `MergeQueryExecutor.kt:126,132,191,252` calls `distinct()` on authors, kinds and tag
      values in both `streamCount` and `run`: about 78 KB per 500-author query (*est.*).
    - The stream head's id is read at `:371,401`, then read again by `toEvent()` for every
      emitted row (another 104 B String per row).
    - `QueryBuilder.kt:52,105` presizes the result list to `ArrayList(limit)`, which allocates
      `Object[limit]` up front however few rows come back. Not reproduced: whether an
      `Int.MAX_VALUE` limit can reach this line.
    - Fix: dedupe once into a plan object, pass the head id into the row decoder, and presize
      with `minOf(limit, 256)`.
16. **Query building.** *Est.* 2–10 KB per non-merge query, plus about 180 B per IN value.
    - What allocates:
      - runtime `trimIndent()` on interpolated SQL (`QueryBuilder.kt:500-510`);
      - conditions built as strings and then re-joined;
      - `toFilterWithDTags()` twice;
      - every number bound as a String.
    - Fix: one `StringBuilder`, bind numbers with `bindLong`, and cache SQL text by shape. That
      also fixes backlog Store-6's statement-cache misses.
17. **Partly fixed: `AddressSerializer.assemble` allocated 455 B for a ~110 B result.** It is
    now presized, measured at 150–200 B across runs (about the result String). `parseValidAddress`
    returns its input when it is already canonical (see item 1). Still open:
    `AddressSerializer.parse` calls `split(":")`, which matters for `Address.parse` callers that
    need the object.
18. **Live fan-out on the relay server.**
    - `FilterIndex.plusBucket` (`FilterIndex.kt:181-186`) copies the candidate sets into a
      `LinkedHashSet` whenever two buckets match.
    - `kinds[event.kind]` boxes kinds of 128 or more.
    - Iterating a `PersistentHashSet` costs about 200 B per event.
    - `sub.filters.any {}` allocates an iterator per candidate.
    - Fix: an inline `forEachCandidate` that walks the buckets in place, an int-keyed kind
      table, and indexed loops.
19. **`FilterMatcher` allocates a map-entry iterator per match on filters with tags**
    (`FilterMatcher.kt:55,62`). Fix: flatten the tag map into private arrays on `Filter`. That
    is also where backlog Relay #1's `HashSet`s would live.

### Tier 3: retained memory and leaks

20. **Fixed: `EventInterner` (JVM/Android) kept the entry of every collected event.** It removed
    an entry only when that id was looked up again. Each dead entry kept its map node, its
    `WeakReference` and the 104 B id String, about 170 B per event ever interned.
    - Repro: intern 100,000 events and drop them. After GC, 99,000 entries remained.
    - Fix: entries go through a `ReferenceQueue`.
      - The sweep runs on the insert path only, every 64 inserts, one thread at a time, at most
        256 polls per sweep.
      - Android's `ReferenceQueue.poll()` takes a monitor on every call, even when the queue is
        empty (AOSP libcore; OpenJDK checks a volatile head first). Polling on every `intern`/
        `get`, as `RelayUrlInterner` does on its rarer `intern`, would make the ingest threads
        contend on one lock.
      - `clear()` no longer drains the queue, which could have stranded an entry added during
        it.
    - Pinned by `EventInternerTest.collectedEventsLeaveTheMapWithoutTheirIdsBeingLookedUpAgain`,
      which fails on the old code with 1,001 entries.
21. **`BaseNoteEvent` keeps empty collections in its caches.** About 170 B retained per cached
    note.
    - Where:
      - `:72` and `:91` cache `mutableSetOf()` even when empty;
      - `:93` makes a `.toMutableSet()` copy that is thrown away;
      - `:64` keeps `parseAllStandalone`'s list even when it is empty.
    - Fix: store `emptySet()`/`emptyList()`.
22. **`RelayActiveRequestStates` keeps every subId a relay has seen until reconnect**
    (`RelayActiveRequestStates.kt:39,55`). On native, each new subId also copies the whole
    copy-on-write map.
23. **`SeenIds()` allocates 16 MB eagerly, and `reset()` reallocates 16 MB regardless of the
    constructor's size** (`SeenIds.kt:171-172,193`).
    - Quartz's only caller uses `initialSlotsPow2 = 12` and never resets. This hits apps that
      use the default constructor.
    - Fix: `reset()` keeps the instance's own initial size and `fill(0)`s a table already at
      that size; and a smaller default.
24. **The negentropy snapshot holds about 135 B per entry**: an `IdAndTime` plus a 64-char hex
    String. `IdAndTime`'s KDoc says ~40 B (`QueryBuilder.kt:459-473`). Stream rows straight
    into the storage vector instead.
25. **Native copy-on-write collections.** `ConcurrentMap.native.kt` and
    `ConcurrentSet.native.kt` copy the whole map or set on every write. `ConcurrentLruCache.put`
    copies twice once full. Native `LargeCache.values()` copies every entry, and
    `PoolEventOutbox.needsToUpdateRelays` calls it in a loop over relays. The KDoc says native
    "never runs the crawl", but iOS relay-client code writes to these maps. Back them with
    `StripedHashMap`, as `ConcurrentHashCache` already is.

### Smaller items (each low on its own)

- **Client:**
  - `RelayReqRefusals.stripRefusedKinds` always builds new lists. Return `filters` when nothing
    overlaps.
  - `NostrClient.allRelays` builds its relay union twice per tick (`:189,207`).
  - `RelayAuthenticator.checkAuthResults` snapshots a 200-entry LRU on every OK.
  - `NostrClient.reconnect` emits a new `Reconnect` through `debounce` on every
    subscribe/count/publish, even when no relay changed.
  - `startReq`'s suspend `onEvent` allocates a continuation per event in the stream and batch
    APIs.
- **Server:** `PolicyStack` iterators and a new `Accepted` per policy per command.
- **Store:**
  - `EventStoreProjection` boxes `nowProvider()` and builds a `Slot` and `MutableStateFlow`
    before the filter check, on every insert.
  - `ObservableEventStore.batchInsert` collects indices in an `ArrayList<Int>`.
  - `useWriter`/`useReader` aren't `inline`.
  - `DeletionRequestModule` builds new SQL per kind 5, which fills the statement cache with
    one-off arities.
  - Deferred-FTS catch-up decodes id, pubkey and sig only to call `indexableContent()`.
  - `FsEventStore.walkDir` lists and sorts the whole index directory per query (only if the FS
    store is deployed).
- **Event classes and tags:**
  - `BaseDMGroupEvent.chatroomKey`/`groupMembers` build 4–5 sets per call (~700 B).
  - `DeletionIndex.hasBeenDeleted` builds a key object, plus an address String for addressable
    events, per event checked.
  - `ChannelMessageEvent.channelId()` and 9 similar sites build a `MarkedETag` to read one id.
  - `CommentEvent.notifies` builds a list per check.
  - `CustomEmoji.assembleAnnotatedList` rebuilds its emoji map per call.
  - `IMetaTag.parse` copies its property map once per URL.
  - `LnInvoiceUtil.getAmount` (a regex, two copies of the bech32 values array, and
    `BigDecimal`s, ~1–1.5 KB per receipt).
  - `RelayListRecommendationProcessor` re-transposes its map every greedy round.
  - `isAnyLowercaseTagged` lowercases every `t` tag.
  - Dense `mapNotNull` over 1,000+ tag lists grows its `ArrayList` through about 12 copies
    (98 sites in nip02/nip51). Presize for lists that are mostly one tag type.
- **Crypto and utilities:**
  - NIP-44 decrypt: stdlib `Base64.decode` makes a temporary copy of the whole payload first.
  - `EventSearchMatcher`: `contains(ignoreCase = true)` allocates an `IntRange` per field
    check.
  - `Hex.isHex` and `isHex64` throw and catch on chars above U+00FF.
  - `EventFactory` boxes kinds of 128 or more for unknown kinds.
  - `MachineReadablePrefix.parse` makes a substring per call.

## Structural (for a major version)

- **Binary ids.** Holding `id`, `pubKey` and `sig` as 32/32/64-byte arrays (or `id` as two or
  four longs) cuts about 200 B (15%) from every retained event. Hex would be produced only when
  serializing. Every `HexKey` signature in the API changes, so this needs a deprecation path
  and a major release.
- **Byte frames end to end.** Today a frame is decoded to a String (1.6× wire), then substrings
  copy every field again (1.5× wire). Reading straight from the socket's UTF-8 bytes would
  remove one of those copies. OkHttp only hands text frames over as Strings, so this needs a
  byte-level reader, and Strings for `content` at the end. That is a large change for about 1×
  wire per event; do it after Tier 1.
- **Store rows as UTF-8.** The relay's read path copies a row about four times (column
  Strings, `RawEvent`, frame builder, transport encode). `getBlob` on TEXT columns returns
  SQLite's UTF-8 bytes, which could be written into a byte frame directly. This needs a
  byte-level `RawEvent`.

## Checked and lean

- **Kite reader:**
  - one reader per thread;
  - keys matched in place;
  - one- and two-element tags built from locals;
  - single-char values shared;
  - numbers parsed in place;
  - escapes decoded into a reused buffer.
- **Kite writer:** reused builder and byte buffer. The JVM id preimage streams into a
  thread-local `MessageDigest`.
- **Verification:** `EventVerifier` allocates nothing per verify (15 B measured).
- **Filtering and dedup:** `Filter.match` (15–19 B measured); the `SeenIds` add path; the
  `fast*` TagArray helpers; and the tag codecs `parseKey`/`parseId`/`parseAsHint`, which
  allocate only their result.
- **Relay client dispatch:** `BasicRelayClient` → `RelayPool` → `NostrClient` fast path →
  `PoolRequests` are all tail calls, use the inline striped lock and do `LargeCache` lookups
  that allocate nothing on a hit.
- **Relay server:**
  - `RelaySession` builds each event's wire body once per fan-out (`SharedBody`), and the
    frame prefix once per REQ;
  - `IngestQueue` and `ParallelEventVerifier` batch their work;
  - `RelayUrlNormalizer` hits allocate nothing.
- **Hints and bloom filter:** `BloomFilterMurMur3` and `HintIndexer.add` allocate only the
  32-byte decode.
- **Encoding and gift wrap:** Bech32 encoding and decoding, the NIP-19 content scanner and TLV
  parsing, and NIP-59 (one parse per layer).
- **kotlinx-serialization:** no hot event or relay path goes through JsonElement trees. The
  one hot use is kind-0 content (item 12).

## Suggested order

1. Items 1–6 and 8: per-event client garbage, all internal or additive, a few hours each.
   Re-run both harnesses before and after each one.
2. Items 9–13: the store insert path and the large-object spikes. Item 9 needs the STORE
   conformance suite; its `RETURNING` change needs a driver-version gate.
3. Items 20–25: leaks and native copy-on-write. Item 20 grows with session length on Android.
4. Items 7 and 14–19: worth doing once a benchmark shows they matter.
5. Structural items: only in a planned major.

Confirm the ordering on a device (the SM-T220 profile): the measured ratios transfer from
HotSpot, but the absolute numbers don't.
