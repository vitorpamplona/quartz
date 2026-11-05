# NIP-34 git: what blame, file history and content search download

_Status: shipped 2026-11-05. Measured against the amethyst repository on relay.ngit.dev (a GRASP
server, git 2.56) from a desktop JVM._

## Outcome

- History and blame fetch the window's commits (`tree:0`), then the distinct trees on the path one
  level per request (wants split at 4,000; a tree git leaves out because it sits inside another
  wanted tree is asked for again, up to 3 rounds), with the `blob:none` fetch as the fallback for a
  server that refuses. The last 2 windows are kept per (clone URL, tip, depth), so blame after
  history, or the history of a nearby file, fetches no commits or trees again. On relay.ngit.dev:
  `.github/dependabot.yml` 3.6 MB in 3 requests (was 11.5 MB), an 11-level path 4.3 MB in 12;
  blame in the desktop app answered in under 6 s where it took 30 to 60.
- Content search skips binary file types by name (`GitBinaryFiles`) and reads each batch's pack as
  it arrives (`PackfileStream`): an object over the size limit is inflated through a 64 KB buffer to
  find the next entry and never kept, and a delta on a skipped base is reported missing, never
  rebuilt from the wrong bytes.
- The blame default depth stayed at 300, so blame reuses the history window. The path-tree cache
  lives in `GitFileHistory`, not on `GitRepoSnapshot`.

File history, blame and full-text search in a client's code browser are built on
`GitFileHistory` and `GitRepoSnapshot.searchContents` (`quartz/src/jvmAndroid/kotlin/com/vitorpamplona/quartz/nip34Git/git/`).
Both are correct (checked against `git log` and `git blame`, merges included), but they download
far more than they use. This plan replaces the two fetches with ones bounded by what the screen
needs.

## 1. History and blame read every tree of the window

### Measured

`history()` fetches the window with `filter=blob:none, deepen=300`: every tree of every commit, to
read one path in each.

| Path | Today (blob:none) | Commits (`tree:0`) + only the trees on the path |
|---|---|---|
| `.github/dependabot.yml` (2 levels) | 11.5 MB, 104,409 objects (100,744 trees), 2.5 s fetch; `history()` 5.5 s | 3.6 MB, 3 requests, 2.2 s |
| `commonsUI/src/commonMain/kotlin/com/vitorpamplona/amethyst/commons/ui/note/GitDiffView.kt` (11 levels) | same 11.5 MB, 19 to 27 s for `history()` | 4.3 MB, 12 requests, 7.8 s |

- `deepen=300` is 300 generations, not 300 commits: on this repository it brought 3,665 commits.
- Asking the server for trees by id with `filter=tree:0` works: it sends exactly the trees asked
  for, nothing below them. That held on relay.ngit.dev, which serves any reachable object by id.
- Most of today's time is the server packing 100k trees, and the client then parsing all of them
  (`GitObjectParser.parseTree` for each one, on the main fetch path).
- In the desktop app, blame on a two-level path took 30 to 60 s while the feed was also loading.

### Change

1. `history()` fetches commits only (`filter=tree:0`, the same `deepen`).
2. Then one request per path segment: want the distinct trees at that level (root trees first,
   then each one's entry for the next segment), with `filter=tree:0`. Unchanged subtrees share an
   oid, so each level asks only for distinct ones; a level shrinks as history converges.
3. The rest of `history()` (TREESAME, parent versions, committer order) is unchanged: it already
   reads only `entryAt(commit)`, which now walks the fetched path trees.
4. Fallback: a server that refuses tree wants (an `ERR`, or a pack without the asked trees) gets
   today's `blob:none` fetch.
5. Rename or document `depth` as generations, and lower the blame default (blame stops being useful
   long before 3,665 commits: it reads at most 60 versions).
6. Keep the commits and path trees on the `GitRepoSnapshot` for its tip, so file history, then
   blame, then history of another file in the same folder do not fetch them again.

Expected: about a third of the bytes, an order of magnitude fewer objects to parse, and a wait set
by the path depth instead of the repository's size.

### Tests

- `GitFileHistoryGitTest` already checks history and blame against git, merges included; it must
  pass unchanged with the new fetch.
- A new case counting requests and objects on a deep path (the fixture can count packs).
- A fixture server with `uploadpack.allowAnySHA1InWant=false` and v0 only, for the fallback.

## 2. Content search pulls large files into memory

### Measured

- At HEAD the repository holds 222 MB in 7,278 files. 26 files over 1 MB hold 133 MB; the
  largest is a 72 MB `.gz`. By extension: `.gz` 69 MB, `.so` 33 MB, `.png` 10 MB, `.jpeg`/`.jpg`
  7 MB, so binary types are about 119 MB.
- `searchContents` fetches 64 blobs per request and holds the whole pack, then skips anything over
  `maxFileBytes` (1 MB) or containing a NUL byte. A batch holding the 72 MB file holds it in full,
  twice (pack bytes and inflated blob): an out-of-memory risk on Android.
- `filter=blob:limit=N` does not help: a blob asked for by id comes back whatever its size (a 6 MB
  `.so` with `blob:limit=1000` returned 3 MB of pack).
- Neither relay.ngit.dev nor GitHub advertises protocol v2 `object-info`, so sizes can't be asked
  for first.

### Change

1. Skip binary file types by name before fetching (a list in quartz: archives, native libraries,
   images, fonts, media, `.jar`/`.aar`/`.apk`). Here that alone avoids about 119 MB.
2. A streaming pack reader for search: parse objects from the response body as they arrive,
   and for a blob whose entry header declares more than `maxFileBytes`, skip it without inflating
   or keeping it. Peak memory is then one file's worth, not one batch's.
3. Keep the batch size at 64. The download of an unlisted large file is still paid; the memory is
   not.

### Tests

- Fixture repository with a 20 MB blob and a large unknown-extension blob: search finds the text
  matches, skips both, and keeps peak heap growth under a bound.
- Pack reader against `PackfileWriter` output and against real `git pack-objects` output (offset
  and ref deltas: a skipped base must still let later deltas fail cleanly, not corrupt).

## Order

1, then 2. Both are additive inside `GitFileHistory`/`GitRepoSnapshot`; no public signature needs
to change beyond the `depth` documentation. Amethyst picks them up with the next quartz release.
