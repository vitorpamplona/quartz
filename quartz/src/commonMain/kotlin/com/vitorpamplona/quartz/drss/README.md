# drss.io reader kinds (`com.vitorpamplona.quartz.drss`)

[drss.io](https://drss.io) bridges RSS and podcast feeds to Nostr. Each feed gets a bot key
(`kind:0` with `"bot": true` and a `feedurl`) that publishes one NIP-23 article (`kind:30023`) per
item, a podcast episode's audio in an `imeta`. Generated shows publish the same way from their own
bot. The reader keeps its lists and records under the reader's key, and several of its kinds are
numbers other apps use too.

drss's source and its specs (`docs/operations/nuds/` in its repo) are private. These classes follow
the events relays hold and the reader's public scripts (`https://drss.io/static/js/*.js`), as seen
on 2026-10-28 (listening progress re-checked on 2026-10-31, after drss moved it to 30078). Every
shape is kept addressable.

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
| 30078 | `DrssListenProgressAppDataEvent` | `d` = `drss-listen-progress:<episode d>`, `a` → the episode article, `L` = `drss`, `l` = `listen-progress`, `position`, `duration`, `completed`, `plays` | every app's NIP-78 preferences (`AppSpecificDataEvent`) |
| 30054 | `DrssListenProgressEvent` (legacy) | `d` = the episode's `d`, then the same tags without the labels | Podcasting-2.0 episodes (`Podcasting20EpisodeEvent`, checked first) |
| 30100 | `DrssFeedListEvent` | only `d`, `p` (feed bot, relay, title) and `title`; no content | CI job statuses, room listings, showcase projects, catalogs |
| 30101 | `DrssListFolderEvent` | every `a` names a list kind (30000, 30003, 30004, 30100–30102, 30104) | room memberships, game handshakes, voting rounds |
| 30102 | `DrssEventListEvent` | only `d`, `e` and `title` | votes, model-file announcements, vouch counters |
| 30104 | `DrssProvenanceFolderEvent` | every `a` names a 31985 record or a 30104 folder | voting-round results |
| 31341 | `DrssShowEvent` | `p` → show bot, `status`, `alt`, JSON settings | nobody |
| 31985 | `DrssProvenanceEvent` | `L` = `systems.buildtall.provenance` | Ditto ratings, a game log |
| 30078 | `DrssReadWriteListsEvent` | `d` = `read-write-lists`, an `a` per designated list, marked `read` or `write`, or unmarked for both | every app's NIP-78 preferences (`AppSpecificDataEvent`) |

`EventFactory` builds the drss class when its shape matches and an `UnrecognizedKind…Event`
otherwise (addressable, never indexed, no links). Kind-level probes (`EventFactory.probe`) answer as
the drss class. The reader's other events use standard kinds that
Quartz already types: NIP-51 follow sets of feed bots (30000), bookmark sets of feed URLs from an
OPML import (30003), article curation sets (30004), clips as NIP-84
highlights (9802), and its Android app's NIP-94 file metadata (1063) and release sets (30063).

## Writing

drss keeps a reader's lists in standard kinds: the feeds a reader follows are NIP-51 follow sets
(30000) and saved articles are article curation sets (30004), arranged in list folders (30101).
The read-and-write preference names the folder the river reads and the one follows and saves go
into; with no role on its `a`, the same folder does both, as drss writes it. The role markers
follow NIP-65's `read` / `write` and are inferred: drss's only public preferences carry none.

- `DrssListFolderEvent.addChild` / `removeChild` / `retitle` and `DrssProvenanceFolderEvent`'s
  `addChild` / `removeChild` keep the folder's other tags. A folder cannot lose its last child; delete
  it instead.
- Listening progress is a kind-30078 record, `DrssListenProgressAppDataEvent`. drss wrote it as
  kind 30054 until 2026-10 and now lifts those into 30078 and deletes them, so `DrssListenProgressEvent`
  (30054) only reads what is left; its `build` and `next` are deprecated. Both are a
  `DrssListenProgress`. Fetch a listener's records with `#l` = `listen-progress` on kind 30078:
  relays index the label, and the rest of 30078 is every other app's preferences.
- `DrssListenProgressAppDataEvent.next(earlier, …)` writes progress by drss's rules (`earlier` may
  be a 30054 record, whose completion and plays carry over; pass both records when an episode has
  a 30078 and a 30054 one, and they merge as drss's lift merges them): the playhead is the resume
  point and goes when the episode ends, the first completion stands, and plays only grow.
  `DrssListenPass` counts a play when playback crosses 90% of the episode, and again only after the
  listener goes back below 10%. drss saves every minute while playing (`RELAY_SAVE_INTERVAL_SECONDS`),
  on pause and at the end.

- `DrssEventListEvent.addItem` / `removeItem` edit the older collections (30102), which drss still
  edits but no longer creates. A collection cannot lose its last item; delete it instead.
- `DrssListPicture.set(list, image, thumb)` gives a folder or NIP-51 set its picture: `image` and,
  when there is one, a `thumb` (about 192 px on the shorter side, with its SHA-256) that readers draw
  first; `preview(tags)` picks it. A new picture drops the old thumbnail. Feed lists (30100) and
  the older collections (30102) carry no picture: their shapes allow no other tags.
- `DrssClip.build(episode, author, startMillis, endMillis, text, note)` makes an audio clip: a NIP-84
  highlight (9802) of the episode whose `start_ms` / `end_ms` tags hold the range, its transcript as
  the content when there is one, and the listener's note as the `comment`. drss builds clips on its
  server and none are public yet, so the range tags are inferred from its reader; `ClipTimeTag` keeps
  them in one place.
