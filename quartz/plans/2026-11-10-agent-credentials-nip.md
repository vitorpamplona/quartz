# Agent credentials: a NIP for the user's preferred AI agent

Status: draft for discussion, 2026-11-10. The spec is now NIP-FA, `FA.md` on
branch `claude/nip-fa-ai-agent-keys` of vitorpamplona/nips; the text below is the earlier copy. Owners: Quartz (event class), Amethyst and
Brainstorm (setup screens, then the clients that use it).

## What we want

A user sets up their AI provider once, and every Nostr app they sign into can use it to
run tasks for them: summarise a thread, translate, draft a reply, explain a trust score.
Bring-your-own-key: the user pays the provider; the app pays nothing and keeps nothing
on a server.

The event carries the least that makes a key usable: **the key, and the URL to send it
to**, in one tag. Nothing else.

## Why the URL and not just the key

A key alone doesn't say where it works, and guessing from its prefix fails:

- OpenAI, DeepSeek, PPQ.ai and Routstr keys all start with `sk-`.
- Local runners (Ollama, LM Studio) and Nostr-paid endpoints have no key at all.
- A self-hosted or proxy endpoint can't be inferred from anything.

With the URL, one API covers every provider: they all serve the OpenAI Chat Completions
format at `{url}/chat/completions`, Anthropic and Gemini included through their
OpenAI-compatible endpoints. So the event needs no "which API" field either.

Everything else the first draft carried is left to each app: model choice, labels,
per-task routing, extra headers. Clients must keep tags they don't know, so any of these
can come back as an optional tag later without breaking anyone.

## The NIP (draft text)

```
NIP-FA
======

AI Agent Key
------------

`draft` `optional`

This NIP defines a replaceable event in which a user stores the key to their preferred
AI provider, so the Nostr clients they use can run tasks on their behalf.

## Event

Kind `12000`, replaceable. `content` is a NIP-44 ciphertext, encrypted by the author to
their own public key, of a JSON array of tags:

    [
      ["key", "sk-or-v1-…", "https://openrouter.ai/api/v1"]
    ]

A `key` tag holds:

1. the key, sent as `Authorization: Bearer <key>`. An empty string for an endpoint that
   needs none (a local runner).
2. the root of an OpenAI-compatible API. Clients call `POST {url}/chat/completions` and
   list models with `GET {url}/models`.

Clients use the first `key` tag that has a URL. A user with more than one key MAY add more
tags, in order of preference; clients that only use one keep the rest when rewriting. The
URL is `https`, except for a loopback host or an empty key; readers strip one trailing `/`.

The event needs no public tags (NIP-31 `alt` is no longer recommended):

    "tags": []

Clients MAY add a NIP-70 `["-"]` tag so relays refuse to rebroadcast it.

## Client behaviour

- To change the key, fetch the newest event, decrypt, reorder or remove the tag arrays you
  read (so extra values and `key` tags you can't read survive), keep every other tag,
  re-encrypt and publish. If the relays couldn't be read, or the content doesn't decrypt to
  a tag array, don't write.
- Keep the decrypted key in memory or the platform's secure storage. Never log it, never
  send it anywhere but its URL.
- Test the key before saving it (`GET {url}/models`), so a typo shows up at setup.
- The user pays for every call: say which provider will be used before running a new kind
  of task, and on 401/403 report the key as rejected instead of retrying.

## Security considerations

Anyone with the user's nsec, or a signer session allowed to NIP-44 decrypt, can read the
key. Clients SHOULD suggest a key with a spending cap. Relays see only that the event
exists, its size and when it changes.
```

## Delegation to a server agent (sketch, not v1)

Brainstorm's Assistant has its own key, held by Brainstorm's server. For it to run tasks
while the user is offline, the user would publish a second event: addressable, `d` and
`p` set to the delegate's pubkey, with the same `key` tag NIP-44 encrypted to the delegate
instead of to the user. Revoking is deleting that event and rotating the key at the
provider, since the delegate may have kept a copy. The main event stays readable by the
user alone, and every grant stays visible and revocable.

## Providers

These are app presets that fill in the URL; the event only ever stores the key and its URL.
"Browser" is whether Brainstorm can call it directly from the page (CORS), probed on
2026-11-10.

| Preset | URL | Browser | Getting the key |
|---|---|---|---|
| **OpenRouter** (recommended default) | `https://openrouter.ai/api/v1` | yes | **OAuth PKCE, no copy-paste**: redirect to `openrouter.ai/auth`, exchange the code at `/api/v1/auth/keys`. One key reaches every major model, and the user can cap its spend. |
| **Anthropic** | `https://api.anthropic.com/v1` (its OpenAI-compatible endpoint) | yes, with the header `anthropic-dangerous-direct-browser-access: true` | Paste an API key. **Never** reuse Claude Pro/Max sign-in tokens: Anthropic's terms forbid it in third-party apps and accounts get blocked. |
| **OpenAI** | `https://api.openai.com/v1` | likely (the preflight passes; a successful call is unverified) | Paste an API key. "Sign in with ChatGPT" uses the Responses API only and is waitlisted for hosted apps, so it's out. |
| **Google Gemini** | `https://generativelanguage.googleapis.com/v1beta/openai` | yes | Paste an AI Studio key. |
| **Routstr** | `https://api.routstr.com/v1` | unverified (502 at test time) | Pay with Cashu, no account. Amethyst can fund it from the user's NIP-60 wallet. |
| **PPQ.ai** | `https://api.ppq.ai/v1` | yes | Account key, Lightning top-up, NWC auto top-up. |
| **Venice**, **NEAR AI** (private inference) | `https://api.venice.ai/api/v1`, `https://cloud-api.near.ai/v1` | yes | Paste a key. |
| **Mistral, Groq, xAI, DeepSeek, Together, Fireworks, Cerebras** | their `/v1` roots | yes | Paste a key. |
| **Ollama / LM Studio** (local) | `http://localhost:11434/v1`, `http://localhost:1234/v1` | only if the user enables CORS on the runner | No key. Works only on the device that runs it. |
| **Custom** | any OpenAI-compatible root | depends | URL + optional key. |

Out: **Maple/OpenSecret**, which needs an attestation handshake rather than plain HTTP.

## Precedent (checked 2026-11-10)

- **No NIP, merged or open, stores a user's AI key.** Nearby open PRs announce agents or
  providers: #1929 (LLM/AI agents), #2226 (AI agent messages, kind 31340), #2291 (paid API
  announcements, kind 31402), #2221 (MCP/skill announcements), #1987 (Routstr provider
  announcements, kind 38421), #2246 (ContextVM).
- **Shakespeare** (Soapbox) stores `{baseURL, apiKey}` per provider, the same two fields,
  but only in the browser, so the setting doesn't follow the user. It would be a natural
  second implementer.
- **Routstr** (Cashu) and **PPQ.ai** (Lightning, NWC) are the Nostr-native ways to pay.
  Both issue an ordinary `sk-` key, so they fit as is.

## Implementation plan

### Quartz (this repo): done

- `nipFAAgentKeys/AgentKeyEvent` (kind 12000): extends `PrivateTagArrayEvent`, like the
  NIP-51 private lists, so Amethyst's `PrivateTagArrayEventCache` decrypts it.
  `agentKeys(signer)`, `preferredKey(signer)`; builders `build(keys, signer)`,
  `replaceKeys`, `setPreferred`, `remove`, all keeping private tags they don't know.
  Content is always encrypted JSON, `[]` included, because NIP-44 refuses an empty string.
- `tags/AgentKeyTag(key, url)`; its `toString()` redacts the key.
- Registered in `EventFactory` and `KindNames`. Not searchable (all encrypted).
- `AgentKeyVectorTest` holds a fixed event signed by the test key `0x…01`: Brainstorm's
  vitest should verify and decrypt the same JSON.

### Amethyst

- `commons`: per-account state (pattern: `model/nip60Cashu/CashuWalletState.kt`), the
  kind added to `FilterAccountInfoAndListsFromKey`, save/clear on `Account`, and one small
  Chat Completions client (`listModels()`, streaming `complete()`) on the `jvmAndroid`
  OkHttp stack.
- `commonsUI`: **Settings › AI agent** next to `NIP47SetupScreen.kt`: pick a preset (or
  type a URL), connect (OpenRouter OAuth or paste the key), test, save. Strings in
  `commonsUI` compose resources; checked on the headless desktop run.
- Android shim: the OpenRouter OAuth redirect. Desktop: a loopback redirect.
- `amy agent show | set | test | clear`, for interop tests against Brainstorm.

### Brainstorm

- `client/src/services/agentKey.ts`: read kind 12000 from the EventStore (ADR 0005),
  decrypt with the active account's NIP-44, write through `signAs()`; checks the shared
  vector.
- **Settings › Assistants › AI model**, same flow, plus the OpenRouter callback route.
  Call it "AI model", since "Assistant" already means the server-hosted identity.
- Providers without CORS are shown as "works in the Amethyst app".

### Order

1. Agree on the format. Kind 12000: not in the NIPs registry, not in Quartz, not seen in
   the 2026-10-08 relay kind census, and no NIP PR or issue claims it (checked 2026-11-10).
2. Quartz event + vector, in the next minor.
3. Setup screens in both apps and `amy agent`. Nothing spends the key yet.
4. The first task in each app.
5. The NIP PR once both apps interoperate; the delegation event if Brainstorm's server
   needs it.
