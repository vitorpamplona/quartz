# The Observer site: a person's front pages as one public nsite

Status: agreed design, 2026-11-11. Builds on NIP-5A (`nip5aStaticWebsites/`) and on the front
page that Brainstorm and Amethyst print with the reader's own AI key (NIP-FA). Producers:
Brainstorm (web), Amethyst (Android, desktop), later a server. Readers: any nsite viewer;
Amethyst also reads it as the reader's own edition history.

## Decisions

- **Public.** The site is an ordinary NIP-5A nsite: anyone can open it. It shows what the
  author's web of trust surfaced each day, under their name. Settled with the user.
- **One site per person**, a named site so it never takes over their root site.
- **Pictures stay where their authors published them.** Editions hotlink art exactly as the
  printed page does; nothing is downloaded or re-uploaded. A picture its host drops is gone
  from old editions; caption and alt remain. Accepted trade-off.
- **Rolled-off editions stay on Blossom**, unlisted. Nothing is deleted.
- **Only validated pages are published.** A producer publishes an edition only after the
  front page validator passed it (quotes, images, links, no scripts).

## The site

Kind `35128` (NIP-5A named site), authored by the reader:

| Tag | Value |
|---|---|
| `d` | `observer` |
| `title` | `<display name>'s Observer` (`The Nostr Observer` when the profile has no name) |
| `description` | `A daily front page from <display name>'s web of trust` |
| `path` | one per file below |
| `x` | NIP-5A aggregate hash of the `path` tags, `["x", <hex>, "aggregate"]` |
| `server` | the author's Blossom servers (kind 10063), in their order |

Files:

| Path | Content |
|---|---|
| `/index.html` | the newest edition: the same bytes, so the same hash, as its dated file (no extra upload) |
| `/YYYY-MM-DD.html` | one edition; the day is the **UTC** date of the print time |
| `/archive.html` | a client-built list of the editions, newest first, linking to the dated files with relative links; house style, no scripts, no model call |
| `/editions.json` | the machine-readable index below |

One print per day: a later print of the same UTC day replaces that day's file.

### `editions.json`

```json
{
  "v": 1,
  "title": "Vitor's Observer",
  "editions": [
    {
      "day": "2026-11-11",
      "path": "/2026-11-11.html",
      "sha256": "<64-hex of the html>",
      "headline": "Twelve relays, one lesson",
      "code": "D35280",
      "printedAt": 1794355200,
      "posts": 412,
      "quotesVerified": 3,
      "model": "anthropic/claude-sonnet-4.5"
    }
  ]
}
```

Newest first. `day`, `path` and `sha256` are required; the rest are optional and readers
ignore fields they don't know. Cost and token counts are private and never published.

## Publishing

Any producer, after a clean print:

1. **Read.** Fetch the newest kind `35128` `d=observer` by the author from their NIP-65 write
   relays. If there is one, start from its `path` tags and its `editions.json` (fetched by hash
   from the author's Blossom servers, hash verified). If the JSON can't be fetched, rebuild the
   entries from the dated `path` tags (day from the filename, other fields absent).
2. **Upload today's page** to the author's Blossom servers (BUD-02 upload with kind 24242
   auth). At least one server must accept it; the rest are best effort.
3. **Upsert** today's entry (replacing the same day).
4. **Roll off.** Keep the newest **90** editions. Then, while the signed event would exceed the
   size budget or the tag budget, drop the oldest edition (never today's). Size budget: the
   smallest NIP-11 `limitation.max_message_length` among the write relays that publish one,
   minus 1 KB of envelope; **60,000 bytes** when none does. Tag budget: the smallest
   `limitation.max_event_tags`; **100** when none does.
5. **Regenerate** `archive.html` and `editions.json` from the kept entries and upload them.
6. **Assemble** the manifest: `/index.html` → the newest edition's hash, every dated file,
   `/archive.html`, `/editions.json`, the `x` aggregate hash, `server`, `title`,
   `description`.
7. **Publish.** `created_at = max(now, previous.created_at + 1)`, so a manifest built from an
   older read never loses to it by a clock skew. Send to the write relays.

Two producers printing at once can still race: the last write wins, and the day the other
one added is gone from the manifest. Nothing repairs it later: the next print starts from the
manifest that won, which no longer names that day. Its page stays on Blossom, unlisted, and the
producer that printed it still has it locally, so it can publish it again. Producers narrow the
window by reading right before they write, and refuse to write when no write relay answered
the read (starting from nothing would drop every other edition).

### Rules every producer follows (settled 2026-11-11, Brainstorm and Amethyst)

- **Read before write.** The newest manifest across every write relay, read to EOSE, and the
  client's cached copy; if no relay answered and nothing is cached, don't publish.
- **Only servers the reader chose.** No kind 10063 means no publish ("Add a Blossom server"),
  never a fallback server. One upload auth per file (a single `x` tag), uploaded to every
  server in parallel; at least one must accept it.
- **Ask first.** The first publish waits for a confirm that the site is public (Publish / Not
  now). A scheduled print never publishes before that.
- **Hashes.** Downloads are verified by hashing the raw bytes; hashes compare lower-case.
- **Editions** are the dated `path` tags, described by the `editions.json` entry naming the same
  bytes (`ObserverSite.mergeEditions`).
- **Days** are real calendar dates. A day after tomorrow (UTC) is never `/index.html`
  (`ObserverSite.latestDay`), and today's edition is never rolled off.
- **Budgets.** Bytes from NIP-11 `max_message_length` (−1 KB), tags from `max_event_tags`;
  defaults 60,000 bytes and 100 tags.
- **Archive links** are built from the day, never from an entry's `path`.
- **Script barrier.** Published and downloaded pages get the app's CSP `<meta>` as the first
  child of `<head>`; the model may not write its own (`2026-11-11-paper-boundary-v2.md`).

## Reading

- **Any nsite viewer** opens the site normally: `/index.html` is today's paper.
- **The author's own clients** list editions from `editions.json` (falling back to the dated
  `path` tags), fetch a page by its `sha256` from the author's servers (or `server` hints),
  verify the hash, and show it in their script-free paper surface. Amethyst merges these with
  its local editions, so its history follows the reader across devices.
- Someone else's Observer opens as an ordinary nsite.

## Settings in the apps

- **Publish to my Observer**, per account, on by default. The first publish shows a one-time
  notice that the site is public and how to turn publishing off.
- An **Open my Observer** action, and sharing it as an `naddr`.

## Where the code lives

- Quartz `nip5aStaticWebsites/observer/`: the `d`/path conventions, the `editions.json`
  codec, roll-off (count and byte budget) and manifest assembly on top of `NamedSiteEvent`.
  Any client publishing or reading an Observer would need the same rules.
- Amethyst `commons`: the publisher (Blossom upload, relay read/write through the existing
  uploader and client) and the history reader; `commonsUI`: the settings and actions.
- Brainstorm: the same in TypeScript, `client/src/lib/frontPage/observerSite.ts` and service.

## The morning schedule, synced (2026-11-11)

Each Amethyst install used to keep its own schedule, so a phone and a desktop both set to
6:00 printed (and billed) twice, and Brainstorm could not show or change it. The schedule is
now one encrypted setting per person, naming the one install that prints.

### Event

Kind `30078` (NIP-78 app data), `d` = `observer-schedule`, no other public tags. `content`
is NIP-44 encrypted by the author to themselves:

```json
{
  "v": 1,
  "enabled": true,
  "time": "06:00",
  "tz": "America/New_York",
  "printer": "3f1c…",
  "installs": [
    { "id": "3f1c…", "name": "Desktop (Linux)", "app": "amethyst-desktop", "unattended": true,  "seen": 1794355200 },
    { "id": "9ab2…", "name": "Pixel 8",         "app": "amethyst-android", "unattended": false, "seen": 1794300000 }
  ]
}
```

- `time` is wall-clock in `tz` (an IANA zone, set by whoever last edited the time), so every
  device agrees on the instant; each app displays it in its own zone.
- `printer` is the `id` of the one install that runs the scheduled print. Absent or unknown:
  nobody prints, and the apps say "Choose a printer".
- `installs` lists the apps that can print: an install adds or refreshes itself (`seen`,
  `unattended` = it holds the key on the device and can sign alone) when it opens the front
  page and at most once a day. Web apps never list themselves: they can't print unattended.
- An install `id` is random per install and per account, kept in that install's settings.
- Unknown fields are kept on rewrite. Editing is read-modify-write of the newest event
  (relays to EOSE and the local cache), `created_at = max(now, prev + 1)`; when no relay
  answers and nothing is cached, nothing is written.

### Rules

- Only the install whose `id == printer` schedules the morning job; every other install
  cancels its own and shows "Printed each morning by <name> at <time>" with "Print on this
  device" (sets `printer` to itself; allowed only if this install is `unattended`).
- Migration: an install that had a local schedule and finds no synced one publishes it with
  itself as `printer`.
- Brainstorm reads and edits `enabled`, `time`/`tz` and `printer` (choosing among the listed
  installs) but never becomes the printer.

### Before any scheduled print: already printed today?

The printer skips the run when an edition already exists for today: one in its own store, or
one listed on the reader's Observer site, printed in the last 20 hours (`printedAt`), or, for a
site entry without `printedAt`, whose UTC `day` is today's UTC date. This covers a manual
print earlier that morning, another device, and Brainstorm. Manual prints are never skipped.
