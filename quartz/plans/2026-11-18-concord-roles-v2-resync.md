# Concord spec + Armada upstream review (2026-11-18)

Upstream review. Compares upstream Concord and Armada against Quartz
(HEAD `4187cf7fb`) and Amethyst (HEAD `7e3ab1e129`).
"Inferred" marks a claim not confirmed in source or by a test.

## Baselines and heads

| Repo | Last sync (baseline) | New head | Delta |
|---|---|---|---|
| concord-protocol/concord | `b84554e` (2026-08-15) | `e28efe0` (2026-11-09, merge of PR #25) | 81 commits, +1170/-154 lines over 01–08, README, examples |
| soapbox-pub/armada (nostr git) | `7588884` (2026-09-28) | `3d83aa7e` (2026-11-18, v0.69.8) | 717 commits; `src/concord/lib` +26.5k lines (204 files) |

Upstream PR / branch status (from `git branch -r` in the concord clone):

| PR | Branch | Status | Note |
|---|---|---|---|
| #23 | `jskitty/root-authentication` (`99b77f6`) | **merged** 2026-11-09 (`cc96c89`) | CORD-05 §1 genesis-anchor root authentication, now a MUST |
| #24 | `forum-channel` (`85be5ca`) | **merged** (`f84d555`) | `view` field + `subject` titled posts |
| #25 | `jskitty/channels-roles` | **merged** 2026-11-09 (`e28efe0`) | Roles v2, channel permissions, Tenure, restricted channels, Categories, access plane, Timeouts, Nicknames, P2P voice. Written as "the spec as Vector implements it" (`5393c99`) |
| #22 | `community-av-brokers` (`6dbfb4b`) | open | `av_brokers` in metadata. Armada writes it anyway |
| #17 | `community-signals` (`df898c2`) | open | vsk 12 `pause`. Armada implements it (`kinds.ts` `VSK_SIGNALS`) |
| #7 | `rm-ms` (`a776b4a`) | open | drop `ms`. The merged text goes the other way and makes `ms` normative |
| (none) | `spec-tests`, `CIP-04` | open, stale (July) | |

The headline: **PR #25 is a protocol revision with a flag day, and Armada shipped it.**
Armada commit `ce57bd7d` (2026-11-07) says it brings ranked roles "to parity with Vector". Since
then Armada **creates every new community on Roles v2**, with a sealed private starter channel at
channel epoch 1. Evidence: `src/concord/hooks/useCommunityActions.ts:530-580`, comment "On the
vsk-13 Roster from the start (CORD-04 §2, Vector `create_community_on_roles_v2`)". Armada also
offers owners the one-way switch (`RankedRolesView.tsx:344`). After the switch,
`publishEdition` marks every edition (`useControlPlane.ts:828-833`).

Quartz and Amethyst have none of Roles v2:

- `ControlEntityKind.kt` stops at vsk 11.
- `EditionHash.kt` computes only the unmarked hash.
- `ConcordPermissions.kt` has bits ≤ 11 only.
- Nothing in either repo mentions `eh`/`eb`, `@everyone`, Tenure, writers, categories, `wepoch`, 3314 or 3315.

CORD-04 §2 ("The flag day") says what follows for a non-v2 client: it still reads and writes chat,
"but it drops every action taken under authority it can't see, a Refounding included, so it stops
following the Community at the next rotation a new-style admin performs."

Since the baseline, Amethyst has done no Concord protocol work beyond fixes (155 commits since
2026-09-28 match "concord"; after 09-29 they are UI, performance, memory and list-entry fixes).
Quartz has had none since it moved to its own repo (`fdaf81a10`, 2026-10-21).

Usable test material: CORD-02 A.6 coordinate and derivation vectors (02.md), the marked-edition hash
vector (04.md §1), the writer-certificate vector (03.md §1), and Vector-generated fixtures in Armada
`src/concord/lib/fixtures/cord25/*.json` (guild, members, history, switched, refounded, writers,
writer_duty, owner_list).

## Table 1: spec changes since `b84554e`

Priority: P0 means Armada can't read what we send, or we can't read core Armada traffic. P1 means a
spec MUST, or a core feature Armada users rely on. P2 is a new optional feature. P3 is nice to have.

| # | Change (upstream evidence) | What it requires of us | Our state (evidence) | Repo | Pri |
|---|---|---|---|---|---|
| S1 | **Roles v2 roster** (`c4d1942`…`0142be1`, `85c189d`; 04.md §2–§3, 02.md App. B): vsk 13 Role (`rank` key over `0-9A-Za-z`, `display`, `mentionable`, deletion terminal), `@everyone` at `everyone_locator` (vsk 13), activation = the owner publishing `@everyone`, the `legacy` channel list, vsk 1 becomes history, migration rules, new permission bits 13–29 with `@everyone` defaults, "authority flows downward", fork-sibling rules, the owner's Roles for display only | Fold vsk 13 and `@everyone`, detect activation, resolve rank by rank key and permissions over 64 bits. Without it, every non-owner admin of a v2 community (Armada's default now) has no rank for us: we drop their bans, pins, channel edits, Grants and **Refoundings**, so we strand at the first non-owner rotation | **missing**: `quartz/.../cord04Roles/ControlEntityKind.kt` (vsk 0–11 only), `ConcordPermissions.kt:64-79` (bits 0–11), `AuthorityResolver.kt` (legacy `position` only) | quartz (fold) + amethyst (session, UI, amy) | **P0** |
| S2 | **Marked editions** (`2cd873f`, `220c069`; 04.md §1): `["eh","2"]`, `["eb", edition_binding(cid)]`; hash = `SHA-256(signing_bytes ‖ "concord/edition-signer" ‖ signer)`; tag order and duplicate rules; vsk 13–18 always marked; refuse a marked edition bound to another community; unmarked Channel editions count only for `legacy`-listed channels | Compute and verify marked hashes and write `eh`/`eb` once a community is v2. Otherwise `ep` links past the first marked edition don't resolve in our fold, and our edits chain onto a hash Armada doesn't know. Armada also ignores our unmarked new channels in a v2 community | **missing**: `crypto/EditionHash.kt` (unmarked preimage only); no `eh`/`eb` anywhere in quartz | quartz | **P0** |
| S3 | **Private Channels v2** (`d226877`, `a53878b`; 03.md §2): metadata sealed under the channel key (`{private, epoch, sealed}` with `prev`, `creator`), private channels born at channel epoch 1 (rotation to creator + owner committing to `(0, root)`), conversion with `public_until`, re-seal duty after rekey (06.md §3 "Rotation duties"), conversion-rotation exception | Open sealed metadata with the held key (no `name` in plaintext), take channel epoch 1, accept the conversion rotation exception, re-seal after our own rotations, never publish plain private metadata in v2 | **missing**: our channel gate refuses an edition without a name (I14), so a sealed channel folds to nothing (inferred from the gate). We create private channels at epoch 0 with a scoped access Role (review F7) | quartz + amethyst | **P0** |
| S4 | **Channel access plane and deliveries** (`fc594d7`, `2880751`, `0310840`, `b787c39`; 03.md §5, 06.md §1): `concord/channel-access` address; 3303 with `["delivery","access"|"history"]`, no chunk/prev tags; 104-byte history blobs (address + conv key); holders deliver after random waits; Read Message History; revocation by rekey; "a 3303 whose delivery form you don't understand carries no removal verdict" (MUST) | In v2, private-channel keys come by Direct Invite from the actor **or** on the access plane. Subscribe to it, adopt deliveries under the fold's entitlement, deliver as a holder | **missing**: no `delivery`, no access address. A Direct Invite catch-up is judged by legacy vend rules (`ConcordInviteVend`, `judgeCatchUp`), so v2 grants are probably refused (inferred). Armada sends both paths (`useRosterSettler.ts:128-145`, `accessDuty.ts`) | quartz + amethyst | **P0** |
| S5 | **Restricted Channels** (`fc594d7`…`7c84d10`, `b989a28`, `8d1735b`; 03.md §1, 03.md §3 `wepoch`): Writers ledger vsk 18 at `writers_locator`; writer addresses `concord/channel-writer`; writer and certifier secrets on the access plane; writer certificates (`wcert`, kind 3320 preimage); `["wepoch", n]` binding; held posts, take-backs, stops, backstops; typing moves to the writer address | Reading: fold the ledger, subscribe every live and retired writer address, accept `wepoch` posts with a valid `wcert`, and drop a `wepoch` rumor that arrives at the read address. Writing and managing: deliveries, minting, stops | **missing**: no ledger, no writer address, no `wepoch`. Posts in any Armada "announcements"-style channel never reach us | quartz + amethyst | **P0** (read), P1 (judge), P2 (manage) |
| S6 | **Channel permission overwrites and sync** (`3e4d014`, `9b8c522`, `8037142`; 04.md §8): vsk 14 ACL per (scope, kind, target), vsk 17 sync, mask `134209568`, Discord-style resolution, private channels closed to `@everyone`, creator admitted while they hold MANAGE_CHANNELS, collapse posts lacking SEND_MESSAGES, "how each permission is enforced" | Resolve VIEW_CHANNEL / SEND_MESSAGES per channel (key entitlement, restricted state, collapsing), and write overwrites from a permissions editor | **missing** | quartz + amethyst | P1 |
| S7 | **Tenure** vsk 16 (`5301214`, `b4c035f`; 04.md §9): a reduction (strip, role edit, ban, kick) seals the member's past heads; a banned moderator's decisions stand | Fold Tenures, so a demoted or banned Armada moderator's channels and bans don't vanish only for us. Write one on our own reductions | **missing** | quartz + amethyst | P1 |
| S8 | **Moderation hides** (03.md §3; Armada had them before the baseline, `chat.ts:440`): a kind 5 on the Chat Plane by a MANAGE_MESSAGES holder (per channel in v2) who strictly outranks the author, with `vac`, honored like the author's own delete | Honor these, and offer the action to our moderators | **missing**: no MANAGE_MESSAGES judge (only bit lists at `AccountConcordActions.kt:1679`, `ConcordMembership.kt:67`). Messages Armada staff hide stay visible to us | amethyst (+ quartz judge) | P1 |
| S9 | **Community List revisions** (`c0d919d`, `842f79d`; 02.md §8): `rev` per snapshot, higher rev wins within an epoch, writer sets rev = read + 1 on change; `channels` ordered by raw id bytes; canonical-bytes definition; reader accepts 64-hex as well as base64url; List published to own write relays **and** the stock relays; `key` may be absent | Same-user interop with Armada on two devices or clients. Without `rev`, our same-epoch update (e.g. a new private channel key) loses to Armada's higher-rev copy, and we may prefer a stale copy | **partial**: `rev` rides through as an unknown extra (`ConcordListFragments.kt:117`, `putExtras`) but is never compared or bumped; hex accepted (`b64ToHex`); missing `key` accepted (line 237); fragments go to the outbox only (`AccountConcordActions.kt:280`). Armada implements it (`communityList.ts:151-229`, `listFrag.ts`) | quartz (merge, serialize) + amethyst (relays) | P1 |
| S10 | **Genesis-anchor root authentication at accept** (PR #23, `99b77f6`/`cc96c89`; 05.md §1): before persisting a join, the owner's genesis vsk 0 at `eid == community_id` MUST open under the delivered keys; at epoch > 0, the community-bound metadata head plus one owner-signed edition. An empty plane defers. A bundle for a held community must match root, `control_pk` and epoch | Add the genesis or owner check to the join gate | **partial**: `joinConcordViaInvite` pages the plane and refuses an empty one (`AccountConcordActions.kt:~895-912`), but checks only the banlist, not owner or genesis. The held-community gate is done (review F6) | amethyst (+ quartz helper) | P1 |
| S11 | **Rotation extensions** (06.md §2): a rotator may add chunks numbered past the original `n` with a new total; the reader unions, the set is complete at the largest `n`, and a chunk holding our opening blob is enough to adopt | Armada extends base rotations for members a Refounding left out (`rekey.ts:188-239`, `useRekey.ts:2027`). We must take those | **divergent**: `ConcordChannelRekey.kt:211` drops the whole rotation when chunk totals disagree, and adoption requires `complete` (`:257`). Base-rotation receive is probably the same (inferred). The members Armada re-keys this way are exactly the stranded ones | quartz | P1 |
| S12 | **64 KB event ceiling and new chunk caps** (`f023493`, `4144e7c`; 02.md App. B, 06.md §1): every published event, wrap included, MUST serialize ≤ 65,536 bytes; 80 blobs per 3303 (60 base, 48 with certs); the reader accepts 120 | Re-budget our rekey chunks | **divergent**: `ConcordRekey.kt:191,201` caps 120 blobs and a 40,960-byte rumor. Wrapped twice in NIP-44 base64 that is ~70+ KB, so relays reject large rotations (size estimate inferred; Armada now uses 28,672, `rekey.ts:30-42`) | quartz | P1 |
| S13 | **Severing Refoundings** (`4c3b461`, `842f79d`; 06.md §3, 05.md §2/§4): `["sever","1"]` on base rotations (malformed if repeated or another value); the adopting link creator SHOULD tombstone links minted before the epoch and MUST NOT refresh them; Invite List entries carry `epoch` (absent = 0) and tombstones carry `expires_at`; the Invite List is published to own write, stock and community relays; `created_at` strictly greater than the newest copy; expiry pruning | Honor `sever` when we are a link creator, and write `epoch` on Invite List entries | **missing**: `ConcordInviteList.kt` keeps `epoch` only as an unknown extra. Our Refounding refreshes every link (review F5) | quartz + amethyst | P1 |
| S14 | **Lifting a Ban** (`7c64a24`; 04.md §6): the lifter first writes a Kick dated at the Ban (`ms` "0") for members whose Join predates it, and a Kick from a BAN holder is honored | Accept BAN-holder Kicks and write the departure Kick on unban | **missing**: `Guestbook.canKick` requires KICK (review F9). Armada does it (`guestbook.ts:387,548`) | quartz + amethyst | P2 |
| S15 | **Guestbook updates** (`7c64a24`, `d550f5e`; 02.md §5): Timeouts 3314 and Nicknames 3315 join the plane; tie-break by source (an entry beats a snapshot); a snapshot entry is superseded by an entry at or after its time; snapshot `i`/`n` are 1-based; absence from a complete snapshot means no state; a lost Join is restored, dated by the plane | Read snapshots (3312), restore our own Join, keep 3314/3315 off the error path | **partial**: snapshots not read (review F9 open). Join only on accept/rejoin (`announceConcordGuestbookJoin`). Unknown kinds probably ignored (inferred) | quartz + amethyst | P2 |
| S16 | **Timeouts** (04.md §8): kind 3314, MODERATE_MEMBERS server-wide plus outrank, `until` ≤ 28 days, collapse posts in the window, no composer; the Refounder re-issues them | Fold and enforce; optional writer UI | **missing** | quartz + amethyst | P2 |
| S17 | **Nicknames** (`67c985d`; 04.md §10): kind 3315, own (CHANGE_NICKNAME, or anyone on a legacy roster) or another's (MANAGE_NICKNAMES plus outrank, `vac`), ≤ 32 scalars | Display them (Armada sets them, `useMemberStanding`) | **missing** | quartz + amethyst | P2 |
| S18 | **Channel metadata fields** (03.md §2): `topic` (≤ 1024 bytes), `position` (rank key), `category`, `view` (`chat`/`forum`/`voice`; unknown renders as chat, MUST round-trip); `voice:true` reads as `view:"voice"`; a `name` over 64 bytes is cut, not refused; required types; an edition aimed at another entity's coordinate | Parse and render order, topic, category and view. Round-tripping already works | **partial**: unknown keys round-trip (`ConcordJson.encodePreserving`); none parsed. Our 64-byte gate refuses where the spec now cuts (review I14) | quartz + amethyst | P2 |
| S19 | **Categories** vsk 15 (03.md §4): `category_id`, name and position, at most 100 folded, terminal delete; a public channel created inside a Category syncs to it | Fold and group the sidebar | **missing** | quartz + amethyst | P2 |
| S20 | **Titled posts and forum view** (`85be5ca`; 03.md §3): kind 9 with `["subject", ≤ 256 bytes]`; a forum channel opens as a feed of titled roots | Render the title and optionally a forum list | **missing** (renders as a plain message, which the spec allows) | amethyst | P2 |
| S21 | **Role mentions** (`749322e`, `15bfed7d`; 03.md §3, 04.md §8): `["role", id]` (≤ 16, valid only beside `@RoleName` text), notify when `mentionable` or the author holds MENTION_EVERYONE | Parse and notify; offer in the composer | **missing** | amethyst | P2 |
| S22 | **CORD-06 receive refinements**: `newepoch` MUST be `prevepoch + 1`; a channel rotation is addressed under the root in force at mint, so look under every root held since; Channel rekeys honored from BAN holders; a base blob > 136 bytes is read by its prefix; a key-less member adopts on authority | Small fixes | **partial**: BAN honored (review F7); multi-root lookup and > 136-byte blobs probably missing (inferred; `RekeyBlob` drops other widths) | quartz | P2 |
| S23 | **CORD-07 voice revision** (`979af7c`…`ce48384`, `a3be3b0`; 07.md §1, §4, §7, §8; 02.md §6 `voice_relays`): P2P calls over iroh 1.x (ALPN `concord/voice/1`, datagram Opus framing, video streams, mesh ≤ 15); presence `muted`, `deafened`, `video`, `transport`, `endpoint`, `relay`; answer a newcomer's presence; staleness timed by arrival; drop presence > 60 s ahead or > 5 min behind; MUST NOT drop presence for a transport we don't speak; voice directives (ephemeral 23314, `p`/`do`/`to`/`vac`); SPEAK/VIDEO/PRIORITY_SPEAKER enforced by receivers; nonce MUST in the 27235 grant | Calls are deferred (review F3). The P2P route avoids LiveKit (and its jain-sip license block) but needs iroh in Kotlin. Fix presence parsing so iroh presence isn't dropped | **missing**: `VoicePresence.parse` drops an identity-less `joined`, which is every iroh presence (review F4 text); nonce done (F4) | quartz (+ amethyst UI) | P2 |
| S24 | **CORD-05 bounds** (`4144e7c`, 05.md): `label` ≤ 64 bytes and a 64-hex `creator_npub`, else echoed as absent; a bundle fits its event (drop public grants last-first); `bundle_key` is the NIP-44 conversation key directly; fragment version must be 4, flags bit 0, unknown ids skipped, trailing bytes refused; a fetcher checks the empty `d` itself; Direct Invite fallback relays are the sender's own write relays; refuse a Direct Invite from a community that banned us or that we hold as dissolved | Small hardening | **mostly done**: fragment strictness `ConcordInviteLink.kt:135-167`; readmission handling exists. Label cap, bundle fit and fallback relays not verified (inferred missing) | quartz + amethyst | P3 |
| S25 | **CORD-01 updates** (`d04cc10`; 01.md): fixed reader drop order; canonical decimals; a carried rumor id must match; `concord/wrap-owner` derived wrap `p` key (SHOULD); deletions get NIP-40 `expiration` once safe (SHOULD) | Optional | **partial**: rumor id checked (`ConcordStreamEnvelope.kt:232`); wrap-owner and expiring deletions missing | quartz + amethyst | P3 |
| S26 | **Channel deletion** (`f669565`; 03.md §2): judged on the Roster as it stands (a deleter who loses standing undoes it); SHOULD purge the plane, our uploads and our own wraps | Optional | **partial**: terminal deletion done (I14); purge missing | amethyst | P3 |
| S27 | **Forward-dated messages** (`d4e57c2`; 03.md §3): hold a kind 9/1111 dated seconds ahead until its time; keep the clock by relays' `Date` headers | Optional | **missing** | amethyst | P3 |
| S28 | **`ms` rules** (02.md §4): the first `ms` tag counts, absent = 0, malformed drops; Control and 3303 carry none | We drop duplicates, which is stricter than the spec (review I15) | **done, slightly stricter** | quartz | P3 |
| S29 | **CORD-08** (08.md): access planes never expire; a timer notice on a restricted channel goes to the writer address | Follows S4/S5 | n/a until S4/S5 | — | P3 |
| S30 | **Genesis channel name** (02.md §2): `general`, not `#general` | — | **done** (`ConcordCommunityFactory.kt:80`) | — | — |

## Table 2: Armada-only behaviour (sent or enforced by Armada, not in the spec)

| # | Behaviour (evidence) | Effect on us | Our state | Repo | Pri |
|---|---|---|---|---|---|
| A1 | **Every new community is created on Roles v2**, with a sealed private starter channel at epoch 1 and the `#general` legacy list (`ce57bd7d`; `useCommunityActions.ts:530-580`). Owners get a one-way switch (`useRankedRoles.ts:374`, `RankedRolesView.tsx`) | This is what turns S1–S5 into live breakage: every Armada community made since about 2026-11-07, and any legacy one whose owner switches | missing (S1–S5) | quartz + amethyst | **P0** |
| A2 | **Rotation extensions** to members a Refounding left out, with the `sever` mark repeated (`useRekey.ts:2000-2030`, "Key every member the Guestbook holds in a Refounding" `4ce7620e`) | We drop the extended set (S11), so the user it was meant to rescue stays stranded | divergent | quartz | P1 |
| A3 | **Moderator deletes** with `vac` (before the baseline; `chat.ts:440`, `channelJudges.ts:37-46`) | Hidden messages still show for us (S8) | missing | amethyst | P1 |
| A4 | **Private zaps** (kind 9735 rumor with `preimage`) and **on-chain zaps** (8333) on the Chat Plane (CORD.md "Private Zaps", "On-Chain Zaps"; `kinds.ts`) | Dropped by our Chat gate | missing: `ChannelChat` `CHAT_KINDS` = 9, 1111, 7, 5, 3302, 23311, 1740 | quartz (gate, parse) + amethyst | P2 |
| A5 | **Polls** 1068 and votes 1018 on the Chat Plane (CORD.md "Polls") | Polls are invisible to our users | missing (same gate) | quartz + amethyst | P2 |
| A6 | **Calendar events** 31922/31923 and RSVPs 31925 on the Chat Plane (CORD.md "Calendar Events") | invisible | missing | quartz + amethyst | P3 |
| A7 | **Community Signals** vsk 12 `pause` (open PR #17; `kinds.ts` `VSK_SIGNALS`; posts collapse during a pause, `33ccc07f`) | We carry vsk 12 through compaction (review I7) but don't apply the pause | partial | quartz + amethyst | P2 |
| A8 | **`av_brokers`** in metadata (open PR #22; written at genesis, `useCommunityActions.ts:497-500`); default broker `av.armada.buzz` (`b9b996e1`) | Round-tripped; matters only once calls exist | done (round-trip) | — | P2 (with calls) |
| A9 | **Legacy-roster cosmetics**: `custom["armada.order"].position`, `custom["armada.category"].name`, Role `display` (CORD.md "Channel Ordering", "Channel Categories", "Role Display"); on v2 these become the spec's `position`/`category`/vsk 15/`display` | Our channel order and grouping differ from Armada's on legacy communities | missing (round-tripped only) | amethyst | P3 |
| A10 | **Voice extensions**: in-call reactions and raise-hand on 23313 (CORD.md); a `["bridge", transport]` flag and two-transport `left` (`voice.ts:252-279`, `de15d5ab`); call recording (local) | Only relevant once calls exist | missing | quartz | P3 |
| A11 | **WebXDC**: 3310 state updates and Vector-compatible realtime over iroh gossip (before the baseline); Vector-sent `.xdc` attachments in NIP-17 kind 15 (`57bdb9ee`) | Our WebXDC runtime keeps updates on the device and has no realtime transport | partial: `commons/.../napplet/webxdc/WebxdcRuntime.kt:28,158`; 3310 plumbing in the session (review F10) | amethyst | P2 |
| A12 | **Discover**: a public kind 3314 "community announcement" with the invite URL as content (`inviteDiscovery.ts:19`), PoW NIP-32 "boost" labels (`1c42b007`, `lib/discoverBoost.ts`), curated lists. Kind 3314 here is a bare public event and collides in number with CORD-04's Guestbook Timeout rumor 3314. They sit in different layers, but it is worth raising upstream | No Discover integration in Amethyst | missing | amethyst | P3 |
| A13 | **@everyone** as a literal text token gated by MENTION_EVERYONE (`everyoneMention.ts`) | We don't notify for it | missing (inferred) | amethyst | P3 |
| A14 | **Typing** at most every 30 s, shown for 30 s, cleared when the typer's post lands (`d2b16b25`) | Cosmetic | not checked | amethyst | P3 |
| A15 | Asks Blossom servers to expire disappearing-message attachments (`0fc57169`) | Optional parity | missing | amethyst | P3 |
| A16 | **NIP-29 threads**: replies are kind 9 with a NIP-10 marked `e` plus `q`; kind 1111 is read but no longer sent (`ad82560b`; `src/lib/nip29Threads.ts`) | Compatible: we read both (`commons/.../model/chats/MinichatReply.kt:60-66`); we send 1111 (`ChannelNewMessageViewModel.kt:680`), which Armada still reads | done (read); P3 to send the Buzz style for wider NIP-29 reach | amethyst | P3 |
| A17 | **NIP-29 `q` replies**: a `q` whose id is also a `nostr:` reference in the content counts as an embed, not a reply (`7d75ec5b`, `40f65df3`, `src/lib/quoteReply.ts:26`) | Compatible with `ChatEvent.reply` | done | — | — |
| A18 | **NIP-29**: kind 5 honored only from the author or a group admin (`14792664`); 9002 edits built from the current 39000 so untouched fields carry (`6883ecbb`) | Check that our 9002 builds carry untouched fields | not verified: `AccountRelayGroupActions.kt:436,669` | amethyst | P2 (verify) |
| A19 | **10009**: each item stays in its public or private section, and new servers and groups are added privately (`3a41d9c9`) | We read and write private groups (`SimpleGroupListEvent.kt:60-160`). Private `r` server tags not verified | mostly done | quartz | P3 |
| A20 | Quick reactions list (kind 10077), `pronouns` in kind 0, NIP-78 settings documents, community blocklist | Armada-local or cosmetic | n/a | — | P3 / not relevant |

## Status of items the 2026-09-29 review left open

| Item | Was | Now |
|---|---|---|
| S4 / PR #23 (accept-time root gate) | open: PR text unavailable | **PR merged, now a MUST** (05.md §1). Still not implemented, apart from the empty-plane refusal. See S10, P1 |
| I12 (CLI heal step; a losing refounder re-issuing channel keys on the winning chain) | open | still open. 06.md §3 now also says a converging client adopts the sibling's control pair, `sever` mark and snapshot authority. P2 |
| I15 (`ms`, PR #7) | watch | PR #7 still open, but the merged text makes `ms` normative (first tag, absent = 0). Ours is stricter on duplicates. P3 |
| F1 open SHOULDs (republish the sealed Pin List after a private-channel rekey; Banlist revert re-heal; compaction omitting a deleted channel's Pin List) | open | still open. 06.md §3 "Rotation duties" adds re-sealing private channel metadata (S3). P2 |
| F3 A/V calls | deferred (LiveKit jain-sip license; 128-bit vs 256-bit frame keys) | still deferred. Spec §3 still mandates AES-256-GCM for brokered calls, so the issue we planned to raise is unresolved. New: §8 P2P calls over iroh need no LiveKit, but there is no Kotlin iroh (Vector has one; Armada has none and only reads iroh presence). P2 |
| F7 not-done items (Pin List re-seal after rotation; history across our own rotations after restart; role scope edits not reconciling) | open | still open, and partly superseded: v2 replaces channel-scoped Roles with overwrites (S6) and changes private channel birth (S3) and delivery (S4) |
| F8 (walk forward from `seed`; drop the `held_roots` extension) | open | still open: `held_roots` remains in `ConcordCommunityList.kt`, `ConcordRefounding.kt`, `ConcordChannelKeyring.kt`, `ConcordStrandedRecovery.kt`. 02.md §8 now cites CORD-06 §2 for the walk. P2 |
| F9 not-done items (double-read before self-removal; dissolution ordering for Kicks; Guestbook snapshots 3312) | open | still open. Snapshots matter more now that Armada keys every Guestbook member and dates snapshots at the rotation (`da450927`, `4ce7620e`). See S14/S15. P2 |
| F10 WebXDC (plumbing only) | partial | partial: a local WebXDC runtime now exists (`WebxdcRuntime.kt`), but no 3310 state sync and no realtime. P2 |
| Spec issues to raise upstream | 6 listed | **resolved upstream**: rekey byte budget (06.md §1 caps plus the 64 KB rule), base64 blob plaintext (06.md §1), 1-based `chunk` (06.md §2), dangling "dissolution payload" reference (02.md §8 "No standalone form exists today"), nonce MUST (07.md §2). **Still open**: 256-bit vs 128-bit frame keys (07.md §3). **New to raise**: kind 3314 is a public Discover announcement in Armada and a Guestbook Timeout in CORD-04 |

## Not relevant (skipped)

- Armada UI, styling, i18n, theming, perf harnesses, the cut-corner design language, settings drill-downs, desktop and Electron shell, reproducible builds, NIP-82 releases, Zapstore.
- Armada push and notification plumbing (service worker, Android/iOS notification services, nostr-push2), DM inbox UX, NIP-46/55 signer pacing.
- Armada storage internals: KV schema migrations, rumor stores, seal holding, snapshot caches, sync-cursor persistence, history-walk duty cycles.
- Media hold and trust, spam folding, muted words, blocklisted communities: client-local policy with no wire effect.
- Armada Discover curation UI (the wire parts are row A12).
- Spec editorial commits: the paragraph splits (`61caf76`, `d59d4dc`, `29e4433`, `efb0737`) and examples-only commits (`f0d3f0c`), except where they pin a wire detail already covered above.
- `173e970` (credit Armada in CORD-05): wording only.

## Status (implementation, 2026-11-18)

Quartz (`claude/awesome-mccarthy-6x0039`) and Amethyst (`wt/concord`, needs the Quartz release
with these changes). Tested against the spec vectors (04.md §1 marked hash, 02.md A.6 coordinates,
03.md §1 writer certificate) and Armada's Vector fixtures (`guild`, `switched`, `members`,
`history`, `writers`, `refounded`), copied to `quartz/src/commonTest/resources/concord/cord25/`.

| # | Status |
|---|---|
| S1 | **done (read)**: `RankedRoster` ports Armada's `foldRankedRoster` (Vector's `select_authorized`/`replay`) for a reader holding no floor: vsk 13 Roles and `@everyone`, activation, legacy list, rank keys as dense positions, downward support, fork siblings, Tenure seals, 100-Role cap; `AuthorityResolver` switches to it when activated (Banlist on Roles bits, `@everyone` in effective permissions); bits 13–29. Folds every fixture to Vector's Roles, Grants, `@everyone`, legacy list and per-member server permissions. Not ported: the floor/`StoredRoster` paths (Amethyst refolds from scratch). Writing vsk 13 Roles/Grants is P2: Amethyst refuses role and grant edits on a v2 Community |
| S2 | **done**: `eh`/`eb` parse and duplicate rules, `EditionHash.markedHash`, `ControlEdition.binding`/`isAdmissibleIn`, every fold drops foreign-bound and unmarked vsk ≥ 13 editions; `ControlEditionBuilder` writes the marker, `RankedRoster.bindingForWrite` says when; Amethyst marks every edition of a v2 Community |
| S3 | **done (read)**: `ConcordChannelSeal` reads, opens (prev check, creator, `public_until`) and seals; `ConcordCommunityState.fold` keeps sealed heads and opens them with the keys the caller holds; legacy-list rule for unmarked Channel editions. Not done: our own private-channel birth at epoch 1 and re-seal duty (Amethyst refuses to create or edit a private Channel on v2) |
| S4 | **done (read)**: `ConcordChannelAccess` (plane, `delivery` parse, trust judge, access and history blob opening); Amethyst subscribes each Private Channel's access plane on v2 and adopts deliveries. Not done: delivering as a holder (P2 duty) |
| S5 | **done (read)**: `ConcordWritersLedger` (parse with the malformed rules, windows, stops, `postStands`, certificates), `ChannelChat.acceptWriterOpened` and the `wepoch` drop at the read address; Amethyst subscribes and reads writer addresses. Held posts, take-backs, departures and minting not done |
| S6 | **done (read)**: overwrites (vsk 14) and syncs (vsk 17) folded, `channelPermissions` matches Vector per member per Channel in every fixture; `everyoneSends`. Collapse of posts lacking SEND_MESSAGES and the editor UI not done |
| S7 | **partial**: Tenures fold and lend sealed standing (roster, admits, Banlist). Writing a Tenure on our own reductions not done (bans of authors of Control editions should carry one) |
| S8 | **done**: `ConcordCommunityState.honorsModerationHide`; Amethyst hides a target already in cache. A hide that arrives before its target is not yet applied, and offering the action to our moderators is not done |
| S9 | **done**: `rev` read in the seed/current merges, `reviseSnapshots` bumps it in `planWrites`, channels ordered by raw id. Publishing the List to the stock relays not done |
| S10 | **done**: `ConcordRootAuthentication.verify`; Amethyst's invite join refuses a bundle whose root fails it (empty plane defers) |
| S11 | **done**: channel rotations union extension chunks, take the largest total, adopt from any chunk holding our blob, remove only on a complete set; `newepoch = prevepoch + 1` enforced (S22). Base rotations already adopted per chunk |
| S12 | **done**: 80 blobs per Channel rotation, 60 per base rotation, 28,672-byte rumor; test checks every wrap ≤ 65,536 bytes |
| S13 | **partial**: `sever` read (malformed refused, any chunk applies) and written (`buildBaseRekeyWraps(severed)`); Invite List `epoch` helpers; Amethyst stamps `epoch` on new links and, on a severed rotation, retires links minted under older epochs instead of refreshing them. Publishing the Invite List to stock and community relays not checked |
