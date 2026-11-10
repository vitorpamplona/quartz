# Agent credentials: a NIP for the user's preferred AI agent

Status: draft for discussion, 2026-11-10. Owners: Quartz (event class), Amethyst and
Brainstorm (setup screens, then the clients that use it).

## What we want

A user configures their AI provider once (which API, which endpoint, which key, which
model) and every Nostr app they sign into can use it to run tasks for them: summarise a
thread, translate, draft a reply, explain a trust score. Bring-your-own-key: the user
pays the provider, the app pays nothing and holds nothing on a server.

The setting travels the way NIP-60 wallets and NIP-51 private lists already travel: a
replaceable event whose content is NIP-44 encrypted to the author's own key. Any client
that can decrypt for the user can read it; relays and other users see only that the
event exists.

## Decisions (and what is still open)

| # | Decision | Recommendation | Why |
|---|---|---|---|
| 1 | One event or one per provider | **One replaceable event** holding a list of agents plus a default | One fetch, atomic edits, and it doesn't leak how many providers the user has. NIP-60's wallet is the precedent. |
| 2 | Content format | **JSON**, versioned (`"v": 1`) | Entries carry optional nested data (headers, per-task routing). Positional private tags get unreadable past four fields. The content is encrypted, so tag-indexing is moot. |
| 3 | Which wire APIs | **Store any; implement two first**: OpenAI Chat Completions and Anthropic Messages | Every remote provider we checked except Maple serves Chat Completions, so one adapter reaches them all, local runners included. Anthropic also answers Chat Completions, but its native Messages API is the full-featured one, and PPQ, LM Studio and DeepSeek now speak it too. Gemini and the Responses API (now "Open Responses", and the only API "Sign in with ChatGPT" allows) are named in the spec so they can be added later without a new version, as can agent protocols. |
| 4 | Providers | **Presets in the apps, not in the event** | The event stores `api` + `base_url`; the provider slug is only a hint for the UI. A new provider never needs a spec change. See the preset table below. |
| 5 | Model vs agent | **v1 stores model endpoints**; agent endpoints (MCP, A2A, Nostr-native agents) are reserved values of `api` | "Run tasks for me" with a model endpoint means the app is the agent harness. A remote agent that holds its own tools is a different trust model and can follow. **Open: confirm this is what "preferred agent" means for Brainstorm, whose Assistant is server-side.** |
| 6 | Server-side use (Brainstorm's Assistant) | **Out of v1**, with a delegation event sketched below | The v1 event is readable only by the user's own signer. A server that runs tasks while the user is away needs a copy encrypted to its key, which is a separate, explicit grant. **Open: does Brainstorm need this at launch?** |
| 7 | Kind number | `10777` as a placeholder | Free in the NIPs registry and in Quartz on 2026-11-10. Fixed when the NIP PR is opened. |

## The NIP (draft text)

```
NIP-XX
======

AI Agent Credentials
--------------------

`draft` `optional`

This NIP defines a replaceable event in which a user stores the endpoints and API keys
of the AI providers they want Nostr clients to use on their behalf.

## Event

Kind `10777`, replaceable. `content` is a NIP-44 ciphertext of a JSON object, encrypted
by the author to their own public key. Tags are not used to carry credentials.

    {
      "kind": 10777,
      "content": "<nip44(author, author, json)>",
      "tags": [
        ["alt", "AI agent credentials (encrypted)"]
      ]
    }

Clients MAY add a NIP-70 `["-"]` tag so that relays refuse to rebroadcast the event
(relays that don't support NIP-70 will reject it; publish without it if they do).

## Decrypted content

    {
      "v": 1,
      "default": "a1",
      "agents": [
        {
          "id": "a1",
          "label": "My OpenRouter key",
          "api": "openai-chat",
          "base_url": "https://openrouter.ai/api/v1",
          "key": "sk-or-v1-…",
          "model": "anthropic/claude-sonnet-4.5",
          "provider": "openrouter"
        },
        {
          "id": "b7",
          "label": "Ollama on my laptop",
          "api": "openai-chat",
          "base_url": "http://localhost:11434/v1",
          "model": "llama3.3"
        }
      ],
      "tasks": { "translate": "b7" }
    }

Top-level fields:

- `v` (int, required): format version, `1`. A client that does not know the version
  MUST NOT overwrite the event.
- `agents` (array, required): the configured agents.
- `default` (string, optional): the `id` of the agent clients use unless told otherwise.
  When absent, the first entry is the default.
- `tasks` (object, optional): task name → agent `id`, for users who want, say, a cheap
  local model for translation and a strong one for drafting. Task names are free-form
  lowercase strings; clients ignore names they don't know. Suggested names: `chat`,
  `summarize`, `translate`, `draft`, `moderate`, `search`.

Agent fields:

- `id` (string, required): unique within the event, chosen by the client that creates
  the entry (a short random string). Stable across edits.
- `api` (string, required): the wire protocol. One of:
  - `openai-chat`: OpenAI Chat Completions, `POST {base_url}/chat/completions`.
  - `anthropic`: Anthropic Messages, `POST {base_url}/messages`.
  - `gemini`: Google Gemini `generateContent`.
  - `openai-responses`: OpenAI Responses, `POST {base_url}/responses`.
  - Reserved for agent endpoints: `mcp` (Model Context Protocol server), `a2a`
    (Agent2Agent), `nostr` (an agent reachable by pubkey; `base_url` is then an
    `npub`/`nprofile`).
  A client MUST skip, and preserve, entries whose `api` it doesn't implement.
- `base_url` (string, required): the API root, without the trailing operation path.
- `key` (string, optional): the secret sent to the provider. Absent for endpoints that
  need none (a local runner).
- `auth` (string, optional): how the request is authenticated. `bearer` (default for
  `openai-chat`, `openai-responses`): `Authorization: Bearer <key>`. `x-api-key`
  (default for `anthropic`). `x-goog-api-key` (default for `gemini`). `nip98`: no `key`;
  the client signs a NIP-98 HTTP auth event with the user's own key on each request
  (providers that bill an npub, such as Shakespeare AI). `none`: no auth (a local runner).
- `model` (string, optional): the model id to request by default. When absent, clients
  ask the user or pick from the provider's model list.
- `headers` (object, optional): extra HTTP headers the provider requires
  (an `api-version`, an organisation id). Never used for the key itself.
- `provider` (string, optional): a lowercase slug (`openrouter`, `anthropic`, `openai`,
  `google`, `ollama`, `routstr`, …) that clients MAY use to show a logo or preset
  behaviour. It carries no protocol meaning; `api` and `base_url` do.
- `label` (string, optional): the user's name for the entry.
- `expires_at` (int, optional): unix time after which the key is known to stop working
  (an OAuth-issued or prepaid key).

Unknown fields, at either level, MUST be preserved by a client that rewrites the event.

## Client behaviour

- Edits are read-modify-write: fetch the newest event from the user's NIP-65 write
  relays, decrypt, change, re-encrypt, publish. Keep entries and fields you don't
  understand.
- Keep the decrypted keys in memory or in the platform's secure storage only. Never
  log them, never put them in URLs, never send them anywhere but `base_url`.
- Before saving an entry, clients SHOULD make one cheap call (list models, or a one-token
  completion) and show the result, so a typo is caught at setup and not mid-task.
- A client SHOULD tell the user, before each new kind of task, which agent and model it
  will use, because the user pays for it.
- When a call fails with 401/403, the client SHOULD say the key was rejected and link to
  the setup screen rather than retrying.

## Security considerations

- Anyone holding the user's nsec, or a signer session allowed to NIP-44 decrypt, can read
  the keys. Clients SHOULD encourage keys with a spending cap (OpenRouter and most
  providers let you set one per key) and SHOULD NOT offer to store an organisation-wide
  admin key.
- Relays see the event's size and update times, nothing else. Publishing only to the
  user's own write relays, or to relays that honour NIP-70, limits even that.
- A key stored here is usable by every client the user logs into. That is the point,
  but clients SHOULD name the agent and model before spending, as above.
```

## Delegation to a server agent (sketch, not v1)

Brainstorm's Assistant has its own key held by Brainstorm's server. To let it run tasks
while the user is offline, the user would publish a second event, addressable, `d` =
the delegate's pubkey, `p` = the delegate, with the content NIP-44 encrypted to the
delegate instead of to themselves, carrying only the one agent entry it may use. Revoking
is deleting that event and rotating the key at the provider (the delegate may have kept
a copy). This keeps the main event self-only and makes every grant visible and
revocable in the UI.

## Precedent (checked 2026-11-10)

- **No NIP, merged or open, stores AI credentials.** Nearby open PRs only announce
  agents or providers: #1929 (LLM/AI agents), #2226 (AI agent messages, kind 31340),
  #2291 (paid API announcements, kind 31402), #2221 (MCP/skill announcements), #1987
  (Routstr's provider announcements, kind 38421), #2246 (ContextVM). None of them hold a
  user's own key, so this NIP complements them: an `api: nostr` entry could later point
  at an agent one of them announces.
- **Shakespeare** (Soapbox) is the closest client. It keeps the same shape we propose
  (`{id, name, baseURL, apiKey}` per provider, plus MCP servers) but **only in the
  browser**, so it doesn't follow the user. Its preset list is the best map of what
  Nostr users actually use, and it would be a natural second implementer of this NIP.
  It also uses NIP-98 auth for its own credits, which is why `auth: nip98` is in the spec.
- **Routstr** (Cashu-paid, OpenAI-compatible) and **PPQ.ai** (Lightning, NWC auto
  top-up) are the Nostr-native ways to pay. Both fit as a plain entry: Routstr turns a
  Cashu token into an `sk-` balance key, and PPQ issues an ordinary key.

## Providers

The apps ship presets; the event stores only `api`, `base_url`, `key`, `model`. All of
these speak `openai-chat` except where the API column says otherwise. "Browser" is whether
Brainstorm can call it directly from the page (CORS), probed on 2026-11-10.

| Preset | `api` | `base_url` | Browser | Getting the key |
|---|---|---|---|---|
| **OpenRouter** (recommended default) | `openai-chat` | `https://openrouter.ai/api/v1` | yes | **OAuth PKCE, no copy-paste**: redirect to `openrouter.ai/auth`, exchange the code at `/api/v1/auth/keys`. One key reaches every major model, and the user can cap its spend. |
| **Anthropic** | `anthropic` | `https://api.anthropic.com/v1` | yes, with the header `anthropic-dangerous-direct-browser-access: true` | Paste an API key. **Never** reuse Claude Pro/Max sign-in tokens: Anthropic's terms forbid it in third-party apps and accounts get blocked. |
| **OpenAI** | `openai-chat` | `https://api.openai.com/v1` | likely (preflight passes; a successful call is unverified) | Paste an API key. "Sign in with ChatGPT" (OAuth, billed to the user's plan) exists but uses the Responses API only and is waitlisted for hosted apps, so it comes later. |
| **Google Gemini** | `openai-chat` (or `gemini` later) | `https://generativelanguage.googleapis.com/v1beta/openai` | yes | Paste an AI Studio key. |
| **Routstr** | `openai-chat` | `https://api.routstr.com/v1` | unverified (502 at test time) | Pay with Cashu, no account. Amethyst can fund it from the user's NIP-60 wallet. |
| **PPQ.ai** | `openai-chat` (or `anthropic`) | `https://api.ppq.ai/v1` | yes | Account key, Lightning top-up, NWC auto top-up. |
| **Venice**, **NEAR AI** (private inference) | `openai-chat` | `https://api.venice.ai/api/v1`, `https://cloud-api.near.ai/v1` | yes | Paste a key. |
| **Mistral, Groq, xAI, DeepSeek, Together, Fireworks, Cerebras** | `openai-chat` | their `/v1` roots | yes | Paste a key. One "OpenAI-compatible" form with these as quick picks. |
| **Ollama / LM Studio** (local) | `openai-chat` | `http://localhost:11434/v1`, `http://localhost:1234/v1` | only if the user enables CORS on the runner | No key (`auth: none`). Works on the device that runs it: a laptop key won't work from a phone. |
| **Custom** | any | any | depends | Base URL + key + model. |

Left out of v1: **Maple/OpenSecret** (needs an attestation handshake, not plain HTTP)
and **Sign in with ChatGPT** (Responses-only and waitlisted).

Every provider here also lists models at `GET {base_url}/models` (Ollama's native list is
`/api/tags`), so the model picker and the setup test are one call.

## Implementation plan

### Quartz (this repo)

Reuses the NIP-60 wallet shape (`nip60Cashu/wallet/CashuWalletEvent.kt`): a
`BaseReplaceableEvent` whose content is NIP-44 to self.

- `nipXXAgentCredentials/` until a number is assigned: `AgentCredentialsEvent`
  (kind 10777, `createAddress(pubKey)`, companion `build(credentials, signer)`), a
  `decrypt(signer): AgentCredentials?` returning `null` on an unknown `v`.
- `AgentCredentials` / `AgentEntry` value types with Kite JSON, keeping unknown fields
  (a raw `JsonObject` beside the typed ones) so a rewrite never drops another
  client's data. `AgentApi` as an open value (known constants + `Unknown(raw)`), not a
  closed enum.
- Register in `EventFactory`. Not a `SearchableEvent` (encrypted). `DiffableEvent` like
  the wallet, so the backup conflict UI can show a change.
- Tests: round-trip, unknown-field preservation, unknown `v` refused, unknown `api`
  kept. Ship a fixed-key test vector (event + decrypted JSON) in test resources that
  Brainstorm's vitest also checks, so both apps read each other's events.
- The HTTP clients for the provider APIs are not Nostr, so they don't go here.

### Amethyst

- `commons`: `AgentCredentialsState` per account (pattern: `model/nip60Cashu/
  CashuWalletState.kt`), kind added to `FilterAccountInfoAndListsFromKey`, save/remove
  actions on `Account`. An `agents/` package with a small `AgentClient` interface
  (`listModels()`, `complete(messages, model): Flow<String>` streaming) and two
  adapters, `OpenAiChatClient` and `AnthropicClient`, over the OkHttp stack in
  `jvmAndroid`. Every later feature calls `AgentClient`, never a provider directly.
- `commonsUI`: **Settings › AI agent** (`settings/AgentSettingsScreen.kt`, next to
  `NIP47SetupScreen.kt`): the list of agents with the default marked, and an add flow:
  pick a preset → connect (OpenRouter OAuth, paste key, or base URL for local) → test →
  pick model → save. Strings in `commonsUI` compose resources. Checked on the headless
  desktop run.
- Android shim: only the OpenRouter OAuth redirect (an intent filter for the callback
  URI). Desktop: a loopback redirect.
- `amy agent list | add | test | remove`: lets the interop tests write an event with
  `amy` and read it in Brainstorm, and the other way round.

### Brainstorm

- `client/src/services/agentCredentials.ts`: read kind 10777 from the EventStore
  (ADR 0005), decrypt with the active account's NIP-44, write through `signAs()`.
  Same read-modify-write and field preservation as Quartz; checks the shared vector.
- **Settings › Assistants › AI model** as a view of the existing Assistants tab, same
  add flow as Amethyst, plus the OpenRouter callback route.
- Naming: Brainstorm's "Assistant" already means the server-hosted Nostr identity. The
  UI should call this "AI model" or "AI provider" to avoid one word for two things.
- Browser constraint: the web app can only use providers that allow CORS (see the table).
  Others are greyed with "works in the Amethyst app", or routed through OpenRouter.

### Order

1. Agree on the open decisions above (5, 6) and the kind number.
2. Quartz event + vectors, released in the next minor.
3. Setup screens in both apps and `amy agent`. No task uses the key yet, so this
   ships safely on its own.
4. The first task in each app on top of `AgentClient`, chosen by each team.
5. Open the NIP PR on `nostr-protocol/nips` once two apps interoperate; then the
   delegation event if Brainstorm's server needs it.
