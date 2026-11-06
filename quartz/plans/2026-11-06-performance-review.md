# Library-wide performance review

**Date:** 2026-11-06
**Status:** review + first fixes shipped on this branch; the ranked backlog below is open
**Harness:** `quartz/src/jvmTest/.../nip01Core/prodbench/StageThroughputBenchmark.kt`
(`./gradlew :quartz:jvmTest --tests "*.StageThroughputBenchmark" -PstageBench=1 -i`)

## Where the time goes

20,000 unique events from `nostr_vitor_startup_data.json.gz` (mean 858 chars), JVM 21, 4-core
x86 cloud container, median of 7 warm passes:

| Stage | ns/event | events/s |
|---|---:|---:|
| `["EVENT",…]` frame → `Message` | 935–1,020 | ~1.0M |
| event JSON → `Event` | 910–1,000 | ~1.0M |
| `Event` → JSON | 1,560–1,700 | ~0.6M |
| id hash (preimage + SHA-256) | 1,750–1,940 | ~0.55M |
| **`verify()` (id + Schnorr)** | **36,000–38,700** | **~27k** |
| `Nip19Parser.parseAll(content)` | 2,550–2,830 | ~0.37M |
| `findHashtags(content)` | 400–720 | ~2M |
| `findIndexTagsWithPeople` | 130–590 | ~3M |

The core path is already lean. Kite parses at roughly 1 GB/s with no per-event scratch
allocation, and the id preimage streams into a thread-local digest. **Signature verification is
about 95% of ingest CPU**, so the levers that still matter are:

1. not verifying: duplicates, events already stored, stored rows re-read;
2. verifying in parallel, which the client already does (`ParallelEventVerifier`);
3. the store's read path.

Micro-tuning the parser further buys little.

## Shipped on this branch

| Change | Evidence |
|---|---|
| `Secp256k1.verifySchnorrBatch` uses BIP-340 weighted batch math | **Security.** The unweighted sum accepted `(r1, s1+d)` + `(r2, s2-d)`, each invalid alone (`batchRejectsCancellingInvalidSignatures` failed before). Each equation is now scaled by a 128-bit weight hashed from every input, so a bad batch passes with probability ~2⁻¹²⁸: `(Σaᵢsᵢ)·G − (Σaᵢeᵢ)·P − Σaᵢ·Rᵢ == O`, with one `mulDoubleG` for G and P and one interleaved wNAF-5 multiplication over the Rᵢ, whose odd-multiple tables share one inversion. **Measured (JVM, same pubkey):** against pure-Kotlin one-by-one, 0.9× at n=2, ~2.0× at 8, ~2.3× at 16, ~2.6–2.8× at 32–200; against native (C) one-by-one, 0.5× at 2, ~0.9× at 8–16, ~1.07–1.14× at 32–200. Nothing in production calls it; it pays only for 32+ events from one author. |
| `CustomEmoji.assembleAnnotatedList`: one `indexOf` pass, unknown codes stay text | **Bug + 18×.** An unknown `:code:` re-added the preceding text and dropped itself (`10:30:00` → `10`,`10`,`00`); its closing colon can now open the next code (`:nope:soapbox:`). Cost on the corpus went from 3,750 to 207 ns/event, measured A/B against a copy of the old algorithm. |
| Native `sha256Of`/`sha256Into` hash the writer buffer's prefix in place | Removes one content-sized `copyOf` per id hash and verify on iOS, macOS and Linux, via crypto-kmp's `sha256Into(out, data, length)`. |
| `IngestQueue` and `ParallelEventVerifier` verify with workers that each pull the next index (`forEachIndexInParallel`) | Measured on 4 cores over real events, two runs. **Batch 256 (client default):** ~106–110k/s vs ~84–91k/s for the fixed chunks `ParallelEventVerifier` used, and ~86–96k/s for one `async` per event. **Batch 64 (`IngestQueue` default):** ~80–100k/s vs ~78–84k/s for per-event `async` (the old `IngestQueue`). An intermediate fixed-chunk version of `IngestQueue` on this branch measured 64–76k/s, a regression; the audit caught it before release. `IngestQueue` starts one worker per core rather than a fixed 8, so relays with more than 8 cores use all of them; `ParallelEventVerifier` keeps its `parallelism` setting (default 8). |
| `TagArray.any(predicate, extras)`, `forEachTagged`, `anyTagged`, `mapValueTagged` are `inline` | 46 call sites pass bound references (`ETag::isTagged`), which allocate a function object per call. **No change measured on HotSpot** (123 → 131 ns, noise: escape analysis already removes it). Kept for ART and Kotlin/Native, which lack HotSpot's escape analysis; not measured there. Binary-compatible: the JVM methods remain. |
| Gift-wrap send computes its one-shot conversation key outside `SharedKeyCache` | Each wrap left a dead entry, holding a throwaway private key, in the 200-slot process-wide LRU. |
| `Nip44v2.EncryptedInfo.encodePayload` builds one buffer | Was three `+` copies of the ciphertext. |
| SQLite batch insert uses one savepoint name | `"ev$index"` put two new SQL strings in the statement cache per row; NDJSON import batches 10k rows. |

## Backlog, ranked

"Reported" means a reviewer read the code and, where noted, measured with a model. Each item
needs a benchmark in this repo before it lands.

### Store (largest remaining wins)

1. **Covering tag index.** `event_tags` is a rowid table, and `query_by_tags_hash_kind
   (tag_hash, kind, created_at DESC)` leaves out `event_header_row_id`, so every `#e/#p/#t` hit
   pays a table seek. The repo's own plan assertions say `USING INDEX`, not `COVERING`.
   - Reported, with a SQLite model at 2M tag rows: REQ 0.70 → 0.16 ms, COUNT 3.6 → 0.73 ms,
     136 vs 158 MB.
   - Fix: a `WITHOUT ROWID` table keyed on `(tag_hash, kind, created_at DESC,
     event_header_row_id)`, and dedup repeated tags before insert.
   - Needs a schema migration.
2. **Multi-filter REQs skip the merge executor and the profiles pin.** `QueryBuilder` routes
   only `filters.size == 1` to `MergeQueryExecutor`, so a follow feed inside a multi-filter REQ
   is back on the 97 ms plan (vs 18.8 ms). Fix: run each filter on its own path and k-way merge
   the results, as `FsEventStore` does.
3. **Deferred-FTS catch-up runs `SELECT DISTINCT kind FROM event_headers` every batch and before
   every search REQ**, under the writer lock, even when caught up. Reported: 27 ms at 1M rows
   without `sqlite_stat1`. Fix: check `MAX(row_id) > watermark` first.
4. **Sync ingest verifies duplicates and commits one event per transaction**
   (`VerifyAndInsert`, `NegentropyStoreSync`). Fix:
   - recompute the id, which is cheap;
   - skip the Schnorr check and the write when that id is already stored;
   - verify the rest in parallel and insert them as one batch.
5. `MergeQueryExecutor` reads full rows for every stream head and picks the winner by a linear
   scan over k streams. Fix: project `(created_at, id, row_id)`, keep the heads in a heap, and
   fetch only the winners.
6. Smaller items:
   - query SQL varies per request (inlined `LIMIT`, `IN` arity), so the statement cache misses;
   - the expiration sweep has no index on `expiration`;
   - ids, pubkeys and sigs are stored as hex TEXT, about 15–25% of the database;
   - `FsIndexer` calls `createDirectories` before every hardlink;
   - `InterningEventStore` decodes rows it then discards.

### Relay client / server

1. `FilterMatcher` checks `ids`, `authors` and tag values with linear `List.contains`, per event
   per filter, in live fan-out, paging, negentropy and projections. Fix: cache a `HashSet` on
   `Filter` for lists longer than about 16.
2. Every message reaches `RelayStats` (a synchronized `LruCache` get, one global lock per frame)
   and `RelayLogger` (builds its tag string before checking the level), even though neither
   reads EVENTs. Fix: an `IgnoresEventMessages` marker, a hash map for stats, and building the
   log tag lazily.
3. `PoolRequests.updateRelays()` rescans every (sub, relay) pair on every subscribe or
   unsubscribe, and the connect, disconnect and cannot-connect handlers walk all subs. Fix: a
   relay → subs index.
4. `PoolRequests` keeps subscription state in `LargeCache`, which is a `ConcurrentSkipListMap`
   on the JVM, and an EVENT does two O(log S) string-compare walks. This is real but about
   0.1–0.3 µs against 37 µs of verify, so it's low priority.
5. `_connectedRelays.update { it + url }` copies the whole set on every connect, O(R²) in a
   connect storm. Fix: a persistent set.
6. Every `Filter(...)` or `copy()` validates `a` values by fully parsing them into `Address`.
   Fix: a structural pre-check.

### Event classes / content

1. Thread accessors build full `MarkedETag` objects, with relay-hint normalisation and interning,
   just to read an id:
   - `replyingTo()` makes up to three scans, plus `threadRootIdOrSelf`;
   - `CommentEvent.replyingTo*` builds four lists to take the last element of each.

   Fix: id-only parsers.
2. Hint providers build lists with `a + b + c` (41 expressions; kind 1 and kind 1111 are the
   hottest). Fix: fill one `ArrayList`.
3. `RepostEvent.containedPost()` re-parses the content on every call, and throws and catches an
   exception for empty content. Fix: an early return plus a cached field.
4. Smaller items: `IMetaTag.parse` (`split` per entry, a map copy per URL), bolt11 amounts
   (checksum plus regex plus `BigDecimal`), and the hint bloom filter (10 Murmur3 passes per
   add; double hashing would change the stored format).

### Crypto

1. Gift-wrap **receive** still inserts each one-shot ephemeral key into the 200-slot
   `SharedKeyCache`, which can evict real peers during a large DM sync and force a second ECDH
   for the seal. Fix: an admission policy (cache on the second sighting). The hit-rate effect is
   unmeasured.
2. A new JCA `Mac` per NIP-44 message (`MacInstance(...)` in `Nip44MessageKey`). Fix: a
   per-thread instance re-`init`ed per message. Benchmark it first.
3. Scrypt (NIP-49) allocates two `IntArray(16)` per Salsa round, about 4M per unlock. This is
   login latency, not ingest.
4. Missing APIs:
   - secp256k1-kmp exposes no parsed-pubkey or batch API, so the production path can't cache an
     author's lifted key across verifies;
   - crypto-kmp has no offset/length ChaCha20 or MAC overloads, which would let NIP-44 decode
     skip three `copyOfRange`s.

   Both are upstream requests.

## Checked and fine

- Kite reader and writer.
- `EventFactory` dispatch: four `lookupswitch` parts, sized under the JIT limit.
- The NIP-19 content scanner, `findHashtags`, and the `#[n]` scan.
- `SeenIds`.
- `EventVerifier`'s thread-local buffers.
- NIP-13 midstate mining.
- Gift-wrap unwrap: one parse per layer.
- `BasicRelayClient` send and receive.
- `CachingEventDecoder`.

The stdlib's `Array.mapNotNull` and `firstOrNull` are already inline, indexed loops. Rewriting
them to `fast*` gains nothing; the real costs are non-inline helpers and lists built and then
thrown away.
