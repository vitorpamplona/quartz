# buzz-vector-gen

Regenerates the Buzz interop vectors in `quartz/src/commonTest/resources/buzz/vectors/` against
a real `buzz-relay` from [block/buzz](https://github.com/block/buzz) (Apache-2.0), run as a separate
program.

```bash
BUZZ_RELAY_BIN=/path/to/buzz-relay BUZZ_SRC=/path/to/buzz quartz/tools/buzz-vector-gen/gen.sh
```

`BUZZ_RELAY_BIN` comes from `cargo build --profile ci -p buzz-relay` in a block/buzz checkout
(`BUZZ_SRC` names its commit in the files). Needs docker compose for Postgres, Redis and MinIO
(`compose.yml`, tmpfs, loopback ports `PORT+10..12`, default `PORT=7600`), node >= 20 and npm
(`nostr-tools`, Unlicense). The relay starts empty, with a fixed signing key and the fixture's
owner key as `RELAY_OWNER_PUBKEY`.

| File | Direction | Holds |
|---|---|---|
| `buzz.json` | THEIRS → US | relay-signed events Buzz emitted while `gen.mjs` ran the scenario: channel state (39000/39001/39002) of a listed stream, a private forum and a DM, the NIP-PM pin list (30623) after two pins and an unpin, and a NIP-CW thread summary (39005, from the bridge's `POST /query` window), each with `expect`, what the scenario set up |
| `ours-read-by-buzz.json` | OURS → THEM | the commands Quartz's builders write (`BuzzVectorFixture.ourCases`: create and edit a channel, put-user with a Buzz role, pin and unpin, a listed name already taken, DM open and add with and without history), each with Buzz's OK and, for a DM command, the `response:{…}` it carries decoded as JSON |

Ids that only exist once the relay runs (the messages pinned, the DM's channel) are placeholders in
the fixture that `gen.mjs` swaps for the live ones before signing; the files keep the placeholders.
`BuzzVectorTest` checks Quartz reads Buzz's state as the scenario set it up, that our builders still
write the recorded templates (a change fails until you regenerate), that Buzz took (or refused) each
command, and that `BuzzDmRules` reads the DM OKs as a JSON parser does. Never runs in unit tests.
