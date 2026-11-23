# concord-vector-gen

Regenerates the Concord app-event interop vectors in
`quartz/src/commonTest/resources/concord/app-events/` against
Armada (soapbox.pub), the reference Concord client.

```bash
ARMADA_DIR=/path/to/armada NODE_DIR=/opt/node22/bin quartz/tools/concord-vector-gen/gen.sh
```

`ARMADA_DIR` is an Armada checkout with `npm ci` done. Armada is AGPL-3.0, so nothing of it is
copied into Quartz: `gen.sh` copies `appEvents.vectors.test.tsx` into that checkout, runs it with
Armada's own vitest config, and removes it. Only the generated JSON (protocol messages and what
each client made of them) is committed.

| File | Direction | Holds |
|---|---|---|
| `armada.json` | THEIRS → US | rumors Armada's send paths wrote (`useSendMessage`, `useTypingPublisher`, its Control and Guestbook builders), each with `expect`: what Armada's read path decided |
| `ours-read-by-armada.json` | OURS → THEM | rumors Quartz's builders write (`ConcordAppEventFixture.ourCases`), each with `armada`: what Armada understood |

`ConcordAppEventVectorTest` checks Quartz reads Armada's rumors as Armada does, that our builders
still write the recorded rumors (a change fails until you regenerate), and that Armada read ours as
Quartz does. Disagreements kept on purpose are listed in its `deviations`, with the spec section.
The generator never runs in unit tests.
