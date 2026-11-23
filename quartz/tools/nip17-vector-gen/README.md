# nip17-vector-gen

Regenerates the NIP-17 / NIP-59 interop vectors in `quartz/src/commonTest/resources/nip17/` with
[nostr-tools](https://github.com/nbd-wtf/nostr-tools) (Unlicense), offline.

```bash
quartz/tools/nip17-vector-gen/gen.sh      # needs node >= 20 and npm
```

| File | Direction | Holds |
|---|---|---|
| `nostr-tools.json` | THEIRS → US | gift wraps nostr-tools makes from the fixture's keys: kind 14 through its `nip17.wrapEvent` / `wrapManyEvents` (text, a reply with a subject, the sender's copy, a "group"), kind 7 and kind 15 as NIP-25 / NIP-17 rumors through `nip59.wrapEvent`, NIP-17's own example (`nip17-example.json`, copied from the spec) and an impersonation attempt; each with the recipient and `expect`, the rumor read in `Nip17VectorTest`'s vocabulary |
| `ours-read-by-nostr-tools.json` | OURS → THEM | wraps Quartz's NIP-17 path makes (`Nip17VectorFixture.ourCases`), each with what nostr-tools' `nip59.unwrapEvent` found inside |

`Nip17VectorTest` opens nostr-tools' wraps and reads them as nostr-tools wrote them, checks our
builders still write the recorded rumors, and that nostr-tools read ours as Quartz does. Two
differences are recorded rather than fixed: nostr-tools' `wrapManyEvents` writes a separate rumor
per recipient, each p-tagging only that recipient (so its "group" is a set of 1:1 messages, not a
NIP-17 room), and on an impersonation attempt nostr-tools throws while Quartz keeps the message
under the seal's signer, never the claimed author. Never runs in unit tests.
