# llama.garden (`com.vitorpamplona.quartz.llamagarden`)

[llama.garden](https://github.com/etemiz/llama.garden) shares LLM weights over BitTorrent: each
HuggingFace model becomes a v1 torrent with HuggingFace (and llama.garden's HTTP "pumps") as web
seeds, the `.torrent` goes to Blossom, and the listing goes to Nostr. Its single-file viewer
(`waifu-magnet-NN.html`) browses the catalog and lets anyone request models and seeders. There is
no NIP: these classes follow the viewer and the publishing scripts, and the events nos.lol and
nostr.mom held on 2026-11-18 (fixtures in `commonTest/.../llamagarden/LlamaGardenEventsTest.kt`).

| Kind | Class | Shape |
|---|---|---|
| 30099 | `torrent/ModelTorrentEvent` | catalog listing: `d` = infohash, `magnet`, `name`, `size`, `pieces`, `piece_length`, `x` (sha256 of the `.torrent`), `url` (Blossom mirrors of it), `webseed`, `tracker`, `source`, model metadata (`display_name`, `file_class`, `model_kind`, `quant_*`, `lab`, `model_name`, `repo_id`, `base_model`, …). User submission: `d` = `user:<pubkey>:<sha256(url:name)>`, a `.torrent` `url`, `name`, optional `size`, `hf`, `file_class`, … |
| 30100 | `release/ClientReleaseEvent` | `d` = `waifu-magnet`, `version`, `sha256`, `size`, `url` (Blossom) |
| 30102 | `seederRequest/SeederRequestEvent` | `d` = `<pubkey>:<listing d>`, `e` = the listing, `i` = the listing's `d` (its infohash, or `user:…` for a submission), `name` (the listing's title, not indexed), `client`, `nonce` |
| 30103 | `modelRequest/ModelRequestEvent` | `d` = requester pubkey, `name`, `url` links, `type` (`all`/`base`/`quant`), `vram_gb`, `client`, `nonce` |
| 1985 | `curation/LlamaGardenCuration` | NIP-32 labels `["L","llama.garden"]`, `["l","approve"\|"reject","llama.garden"]`, `e` = the listing |

- Every kind is shared with another app. `EventFactory` picks these classes by shape
  (`isModelTorrent`, `isClientRelease`, `isSeederRequest`, `isModelRequest`); drss's stricter shapes
  win first on 30100 and 30102, and the rest fall to the `UnrecognizedKind…Event`s.
- Listings, requests and seeder requests carry a NIP-13 `nonce` with no target; the viewer mines
  for about 10 seconds (≈20 bits). Mine before publishing (`PoWNostrSigner` or the app's queue).
- Never put `xs=` in a magnet before the `.torrent` from a `url` mirror hashed to `x`:
  `ModelTorrentEvent.magnetWithExactSource` is for a URL that passed that check.
- Whose listings, releases and approvals to trust (the viewer's `NPUBS`), and the seeder counts at
  `api.llama.garden/pumps` (HTTP, not Nostr), are the app's.
