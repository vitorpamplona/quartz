# nip29-vector-gen

Regenerates the NIP-29 interop vectors in `quartz/src/commonTest/resources/nip29/` against
[fiatjaf/relay29](https://github.com/fiatjaf/relay29) (MIT), the NIP-29 relay library by the NIP's
author, and go-nostr's `nip29` package it is built on. relay29 is pinned in `go.mod`.

```bash
quartz/tools/nip29-vector-gen/gen.sh      # needs Go >= 1.24; modules come from the Go proxy
```

| File | Direction | Holds |
|---|---|---|
| `relay29.json` | THEIRS → US | the relay-signed events relay29 emitted while `main.go` drove one group through create, edit, three joins, a role, a removal, a leave, private + closed and the legacy `public`/`open` tags: the 39000-39003 snapshots and the 9000/9001 it issues for 9021/9022, each with `expect`, what go-nostr's reader and relay29's parser make of it |
| `ours-read-by-relay29.json` | OURS → THEM | the events Quartz's builders write (`Nip29Relay29Fixture.ourCases`), each with `relay29`: its moderation action, and for an edit the group it leaves when applied to a private, closed one |

`Nip29Relay29VectorTest` checks Quartz reads relay29's events as go-nostr does, that our builders
still write the recorded events (a change fails until you regenerate), and that relay29 read ours
as Quartz does. Disagreements kept on purpose are listed in its `deviations`, with the spec section.
The generator runs relay29 in its own process on a loopback port and never runs in unit tests.
