# Jester chess: what the protocol leaves out, and how to fill it

Status: partly implemented (2026-10-31). Done in quartz: items 1, 2 (validated move-less endings,
including abandonment claims behind an app-given delay), 3, 4 (`JesterGameEvents.mainLine()`), 5
and the `KindNames` label in 6. Still open: 7 (notifications), 9 (chat), 10 (deprecating
30064–30068), and the NIP-32 `#l` end label proposed below. Amethyst's app-side rework (lobby and
board on the normal feed and subscription machinery) landed separately and needed no wire change.

## Where Jester stands (checked 2026-10-30)

- jesterui (`github.com/jesterui/jesterui`, branch `devel`) has had no commit since 2024-05-24.
  The `refactor/fen-to-pgn` branch (content `{fen, pgn}`, `root`/`reply` markers) was never
  merged or deployed. The live site still speaks v0.
- No NIP covers it. NIP-64 (kind 64) is a PGN *record*, not a live game. PR #212 (chess on kind
  30) closed unmerged in 2023. Kind 30 is now registered to NKBIP-03 internal references.
- Other v0 speakers have each invented their own extensions: nostr.blue (2026-05: `player_color`,
  `result`, `termination`, `draw_offered`, `p` on moves, private ref plus START_REF on direct
  challenges) and blindchess (2026-10: content kinds 101 join and 102 resign, `blindchess.creatorColor`).
- No other client uses Amethyst's kinds 30064 to 30068. Nothing in Amethyst still builds them.

## What v0 actually defines

- Kind 30. Content `{"version":"0","kind":0|1,"fen","move","history","nonce"}`.
- Start: `kind 0`, empty `history`. An open game has `["e", sha256(START_FEN)]` (START_REF). A
  direct game has only `["e", sha256(sha256hex(opponent) + START_REF)]` (the private ref) and no `p`.
- Move: `kind 1`, `["e", start], ["e", parent]` by position. `history` is the parent's plus `move`.
- The creator is always white. Black is whoever writes move 2.
- A game ends only when the position does (mate, stalemate). Chat is kind 1 `["e", start]`.

## Gaps, and the proposed fix for each

Each fix is additive. A v0 client ignores unknown fields, unknown content kinds and extra tags, so
it keeps working. It just doesn't see the new feature.

1. **Colour choice.** v0 has none. We write `playerColor`; nostr.blue writes `player_color`.
   Proposal: read both. Write `player_color` and keep `playerColor` for one release. Until jesterui
   can read either, a challenger who picks black against a jesterui player plays a different game
   from the one jesterui shows. The new-game dialog should say so, or offer black only on direct
   challenges.
2. **Resignation, draw agreement, abandonment.** v0 has nothing. We and nostr.blue put
   `result` (`1-0`, `0-1`, `1/2-1/2`) and `termination` (`checkmate`, `resignation`,
   `draw_agreement`, `stalemate`, `abandonment`) on a kind-1 move. For a resignation that event
   repeats the history with `move: null`, and jesterui rejects it.
   Proposal: allow `result`/`termination` on the move that ends the game. Add a content
   `kind: 3` "end" message with `["e", start], ["e", head]` and an unchanged `history`. Readers
   accept either form, and accept an end only from a seated player (`resignation` only from the loser).
3. **Draw offers.** Proposal: adopt nostr.blue's `draw_offered: true` on the offerer's move. The
   other player accepts with an end (`1/2-1/2`, `draw_agreement`), or declines by moving.
4. **Seat and move validation.** v0 trusts the longest `history`, so anyone can append moves.
   Amethyst now takes only the two seated players' moves into account. The second seat goes to the
   earliest mover (by `created_at`, then id), and on a direct game only to the player whose private
   ref matches. Proposal: also require each move's `history[:-1]` to equal its parent's (jesterui's
   own rule), and the author to be the player whose turn it was. Ship this as a quartz
   `JesterGameEvents.mainLine()` so every client agrees.
5. **Direct challenges.** Amethyst now writes jesterui's private ref plus `p`, and reads both.
   Proposal: move `jesterPrivateStartRef` and the challenge builder from Amethyst's `commons` into
   `JesterProtocol`/`JesterEvent`. Exclude the private ref from `eventHints()`, which today treats it
   as an event id.
6. **Kind 30 is shared.** Never ask relays for bare kind 30. Ask for `#e` START_REF, a private ref,
   a game id, `#p`, or chosen authors. Amethyst's lobby now does. `KindNames` labels kind 30
   "Chess Auth", which is wrong.
7. **Notifications.** Our moves `p`-tag the opponent, but the notification filter listens to
   30065/30066 only. Proposal: add kind 30 with `#p` me, and show "your turn" / "challenged you".
8. **Clocks and time control.** Out of v0's reach. Doing them right needs trusted timestamps
   (Sashité's suite uses timestamp attestations, NIP PR #2359). Leave out of this round.
9. **Chat.** Proposal: render jesterui's kind-1 `["e", start]` notes under the board. No new format.
10. **Kinds 30064 to 30068.** No one uses them, and each move is addressable, so it is replaceable.
    Proposal: deprecate them in quartz (`Removed in` the next major), and drop them from Amethyst's
    feeds and notifications.

## Proposed: a label relays can filter endings by

Relays filter on single-letter tags, never on content, so today a client finds the games someone
ended only by downloading their moves. A NIP-32 self-label on the ending event,
`["L","jester"], ["l","end","jester"]`, would make `{kinds:[30], authors:[u], "#l":["end"]}` and
`{kinds:[30], "#p":[u], "#l":["end"]}` exact: one event per finished game, for both players.
Additive; endings from clients that don't write it still need the author fetch.

## Decisions for the maintainer

- Extend v0 in place (items 1 to 9, compatible), or start a new kind and write a NIP. A new kind
  breaks jesterui and nostr.blue interop. The Sashité suite (kinds 3417 to 3427) is a fuller,
  actively maintained design worth evaluating before inventing a third one.
- Whether to propose the extension upstream: a PR to jesterui, or a NIP for the v0 format plus
  these fields. A NIP would also register kind 30's second use.
