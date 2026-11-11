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
now one encrypted setting per person, and any device where the reader turned printing on may
print a morning: the first to claim it does. It is a NIP-FB scheduled task (kind 34817,
`nips/FB.md`, the authority on the event, `cron`, claims, `ran` and editing); this section only
fixes what the Observer adds to it.

### Event

Kind `34817` (NIP-FB Scheduled Task), addressable, `d` = `observer` (fixed, so two apps setting
it up at once edit one event). Like a NIP-51 list its tags may be public or private (the content,
NIP-44 to self, a JSON tag array); readers merge both, writers keep each tag on the side they
found it, and new tags go private. Amethyst keeps everything but `d` private:

```json
[
  ["task", "observer"],
  ["cron", "0 6 * * *", "America/New_York", "1794300000"],
  ["claim", "1794394800", "3f1c9a0e5b7d4c21", "Desktop (Linux)", "1794394831"],
  ["ran", "1794394860"]
]
```

- `cron` (five POSIX fields) is read on the wall clock of its zone (IANA, set by whoever last
  edited the schedule), so every device agrees on the instant; each app displays it in its own
  zone. Amethyst's editor writes only the shape `M H * * <days or *>` (a time, optionally
  weekdays) and shows any other expression as written, read-only. A `cron` it can't parse is
  kept and never run.
- `cron`'s fourth value is when the schedule was last set (unix seconds, 0 when absent):
  changing `cron` or removing `["paused"]` sets it to now, and nothing due before it is ever
  claimed, caught up or reported missed.
- `["paused"]` stops it for every device. Other tags are kept as they are.
- `claim` is the latest claimed occurrence: its due time (the exact instant the cron runs it,
  compared for equality), the claiming install's random id, its name, and when it was claimed;
  `ran` is when that occurrence finished, and a claim is finished when `ran` is at or after its
  due time. A duplicate single tag reads private first, then first. A task without `task` =
  `observer` and a readable `cron` is never run.
- An install's id is random per install and per account, kept in that install's settings.
- Every edit, claims and `ran` included, is read-modify-write on the reader's NIP-65 write
  relays only (never a default, bootstrap or indexer relay): the newest event read to EOSE,
  `created_at = max(now, prev + 1)`, written to the same relays. More than half of them must
  answer the read and accept the write; too few answers is not "no schedule". An event that
  exists but can't be decrypted is never overwritten.
- Quartz: the event (`ScheduledTaskEvent` on `PrivateTagArrayEvent`), its tag codecs (`task`,
  `cron`, `paused`, `claim`, `ran`), the merged model (`ScheduledTask`), cron (`CronExpression`,
  `SimpleCron`, `CronZone`; `ZoneIdCronZone` over `java.time` on JVM and Android, since
  `commonMain` has no zone database) and the claim protocol's decisions (`ScheduledTaskClaims`)
  in `nipFBScheduledTasks/`; the Observer's `d`, `task` and the printed-today check in
  `nip5aStaticWebsites/observer/` (`ObserverSchedule`, `ObserverSite.alreadyPrintedToday`).

### Rules

- **Print from this device** is a per-account, per-install setting, off unless the reader turns
  it on (it is the old local schedule's on/off). Only an install that can print alone (the key
  on the device, a platform that can print) may turn it on. Turning the shared schedule on from
  a device that can print also turns it on there.
- At each due occurrence, on a device where it is on and the schedule isn't paused: wait a
  random 0 to 60 s; read the newest event from the write relays (more than half must answer, or
  don't run; no write relays, or a content that doesn't decrypt: never run, never write); stop
  if `claim` holds this occurrence (unless it is unfinished and its claim time is an hour old);
  if today's edition already exists (below), write the claim and `ran` in one event without
  printing; otherwise subscribe to the event on every write relay, publish the claim (more
  than half of the write relays must accept it, or don't run), and after 20 s
  print only if the newest event seen (`created_at`, then lowest `id`) holds this device's
  claim; when it holds no claim for this occurrence (another client's edit that didn't see
  the claim), go back to the read, at most three rounds and only while it is still the same
  occurrence. The subscription stays open during the print; a newer event claiming this occurrence
  for another device stops it, before anything is published to the Observer site. A finished
  print sets `ran` (a fresh read-modify-write that changes only `ran`). A failed print sets
  nothing, so its claim goes stale after an hour.
- A missed occurrence (the device was off or the app closed) may be claimed on waking until the
  next one is due; Amethyst tries once when an account loads.
- Migration: an install that had a local schedule (`on|7|30`, now `30 7 * * *` in its zone,
  set at the migration's time) and finds no synced one (more than half of the write relays
  answered and hold none) publishes it; the local on/off stays as this device's "Print from
  this device".
- Brainstorm reads and edits the schedule (pause, `cron`) and shows who printed last; it never
  claims, since it can't print with nobody there. It writes only `d` publicly and signs without
  a `client` tag; Amethyst's signer adds its `client` tag publicly (when the reader keeps that
  setting on), which every writer keeps
  where it found it.

### What every app shows

"Last printed by <claim's name>, <time ago>" from `claim` and `ran` ("Not printed yet" before
the first), and, unless paused, a warning on every device, "Not printed since <day>", when
`ran` is older than the newest `cron` occurrence that was due more than 3 hours ago.

### Before any scheduled print: already printed today?

The printer skips the run when an edition already exists for today: one in its own store, or
one listed on the reader's Observer site, printed in the last 20 hours (`printedAt`), or, for a
site entry without `printedAt`, whose UTC `day` is today's UTC date. This covers a manual
print earlier that morning, another device, and Brainstorm. Manual prints are never skipped.
If the site can't be read, the printer decides from its own store alone (and prints). A skipped
run writes the claim and `ran`, so it counts as done for every device.
