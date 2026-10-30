# NIP-50 search and event stores, for projects building on Quartz

For a project that runs its own `IEventStore` (a Postgres or Vespa store, geode, a mirror) or
its own search engine over Quartz events. Quartz's SQLite `EventStore` is the reference; its
behaviour is written as numbered rules (STORE-F/W/D/C/S/N/O) in
[store-rules.md](https://github.com/vitorpamplona/quartz/blob/main/.claude/skills/event-store-semantics/references/store-rules.md):
cite a rule id when you diverge.

## Prove a store conforms

```kotlin
val corpus = ConformanceCorpus.generate(ConformanceCorpus.Config(size = 5_000))
StoreConformance.load(reference, corpus) // EventStore(null)
StoreConformance.load(candidate, corpus) // yours
val report = StoreConformance.compare(reference, candidate, StoreConformance.battery(corpus))
check(report.ok) { report.summary() }
```

`nip01Core.store.conformance` is main-source code with no test-framework dependency. The corpus
runs supersession, deletions by id and address, deletions of deletions and re-offers; the
battery diffs sets, newest-first order and COUNTs, and each mismatch names its rules
(`report.suspectRules`). Search is not in it: relevance order is each engine's own.

## Write rules worth knowing before you write one

- Supersession: `event.supersedes(other)` (newest `created_at`, then lowest id) under the key
  `event.addressTagOrNull()`, both in `nip01Core.core`, kind-dispatched.
- NIP-09: a kind 5 deletes and blocks only its author's events; a kind 5 against a kind 5 or a
  kind 62 has no effect (STORE-D05). `deletionsCovering(...)` (`nip01Core.store`) finds the
  deletions that apply to a set of events.
- Stores never verify signatures; `verifyAndInsert` is the ingress helper.

## Who is asking: `StoreQueryContext`

The relay installs `StoreQueryContext` on the coroutine context around every REQ/COUNT for an
authenticated connection. `observer` is the FIRST key that authenticated (deterministic). A
store may rank by it and may gate by it, answering a subset of the filter's match set (a trust
floor); it must never widen past the filter, must answer `query`, `count` and negentropy
snapshots alike for one observer, and is not access control (that is the relay policy's).

## Parsing the search string: `SearchQuery`

`Filter.search` reaches the store verbatim. `SearchQuery.parse(search)` (`nip50Search`):

- `terms` (loose words), `phrases` (`"…"`), `notTerms` (`-word`), `notPhrases` (`-"…"`);
- `extensions`: any `lowercasekey:value` token (a value starting with `//` stays text); typed
  `includeSpam`, `domain`, `language` (raw) and `languageCode` (`pt-BR` → `pt`, null unless a
  2–3 letter code), `sentiment`, `nsfw`/`nsfwIncluded`, and the non-NIP-50 `observer`
  (`SearchQuery.OBSERVER`: exactly 64 hex, lowercased, else null).
- Extensions a store does not implement are IGNORED, never matched as text: strip them with
  `filter.strippingSearchExtensions()`.

`EventSearchMatcher(search).match(event)` is the in-memory reading of the same grammar (local
search, live subscriptions): terms ANDed as case-insensitive substrings, phrases as whole-word
runs, `-word` / `-"phrase"` drop an event holding them as a word run. Allocation-free, one
instance per scan.

## What text an event contributes

A kind is searchable iff its class implements `SearchableEvent` and `EventFactory` builds it;
the set is `SearchableKinds.ALL`. `indexableContent()` is the flat text (what SQLite's FTS
indexes); `SearchFieldExtractor.extract(event)` gives the same text decomposed by role
(`IndexableFields.Profile` or `Tiered`: primary / secondary / body, hashtags, websites) for a
weighted engine. Diff
[searchable-kinds.md](https://github.com/vitorpamplona/quartz/blob/main/.claude/skills/event-store-semantics/references/searchable-kinds.md)
at every Quartz bump: it lists each kind's exact `indexableContent()`, and existing rows keep
their old text until you re-index (`IEventStore.reindexFullTextSearch`).

## What a pointer event is about

`SubjectReferences.of(event)` (`nip01Core.hints`): a NIP-32 label's `e`/`p`/`a` targets, a
NIP-85 assertion's `d` subject, a Trusted List's members with their 0..100 scores, a NIP-51
people list's `p`s, canonical and kind-dispatched. Whether to expand a search with them, and
whose pointers to trust, is your policy.
