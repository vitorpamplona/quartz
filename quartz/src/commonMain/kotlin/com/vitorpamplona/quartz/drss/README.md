# drss.io reader kinds (`com.vitorpamplona.quartz.drss`)

[drss.io](https://drss.io) bridges RSS and podcast feeds to Nostr. Each feed gets a bot key
(`kind:0` with `"bot": true` and a `feedurl`) that publishes one NIP-23 article (`kind:30023`) per
item, a podcast episode's audio in an `imeta`. Generated shows publish the same way from their own
bot. The reader keeps its lists and records under the reader's key, and several of its kinds are
numbers other apps use too.

drss's source and its specs (`docs/operations/nuds/` in its repo) are private. These classes follow
the events relays hold and the reader's public scripts (`https://drss.io/static/js/*.js`), as seen
on 2026-10-28. Every shape is kept addressable.

Each package follows Quartz's layout: tag classes under `tags/` (`parse` / `assemble`), accessors
in `TagArrayExt.kt`, builder steps in `TagArrayBuilderExt.kt` and a `build()` on the event. Every
`a`, `e` and `p` has a method named for what it is (`episode()`, `children()`, `items()`,
`feeds()`, `feed()`, `foundThrough()`, `showBot()`), and the hint and link providers read those.
A provenance note's `nostr:` URIs are hints and links too. The lists, folders, shows and
provenance notes are searchable by their titles, names and notes; listening progress is not.
A `build()` that leaves out what the shape needs (an empty folder, a progress record with no
numbers) parses back as the `UnrecognizedKind…Event`, as drss's own empty ones would.

| Kind | Class | Shape | Shared with |
|---|---|---|---|
| 30054 | `DrssListenProgressEvent` | `a` → the episode article, `position`, `duration`, `completed`, `plays` | Podcasting-2.0 episodes (`Podcasting20EpisodeEvent`, checked first) |
| 30100 | `DrssFeedListEvent` | only `d`, `p` (feed bot, relay, title) and `title`; no content | CI job statuses, room listings, showcase projects, catalogs |
| 30101 | `DrssListFolderEvent` | every `a` names a list kind (30000, 30003, 30004, 30100–30102, 30104) | room memberships, game handshakes, voting rounds |
| 30102 | `DrssEventListEvent` | only `d`, `e` and `title` | votes, model-file announcements, vouch counters |
| 30104 | `DrssProvenanceFolderEvent` | every `a` names a 31985 record or a 30104 folder | voting-round results |
| 31341 | `DrssShowEvent` | `p` → show bot, `status`, `alt`, JSON settings | nobody |
| 31985 | `DrssProvenanceEvent` | `L` = `systems.buildtall.provenance` | Ditto ratings, a game log |

`EventFactory` builds the drss class when its shape matches and an `UnrecognizedKind…Event`
otherwise (addressable, never indexed, no links). Kind-level probes (`EventFactory.probe`) answer as
the drss class. The reader's other events use standard kinds that
Quartz already types: NIP-51 follow sets of feed bots (30000), bookmark sets of feed URLs from an
OPML import (30003), article curation sets (30004), NIP-78 preferences (30078), clips as NIP-84
highlights (9802), and its Android app's NIP-94 file metadata (1063) and release sets (30063).
