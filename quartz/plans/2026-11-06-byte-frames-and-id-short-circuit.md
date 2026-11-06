# Byte frames: Kite on UTF-8 bytes, OkHttp without the String, and an id short-circuit

**Date:** 2026-11-06
**Status:** investigation. Nothing shipped; the measurements and the OkHttp probe below are
reproducible, and the plan at the end is staged so each step can be benchmarked before the next.

## The questions

1. Can Kite parse the frame's UTF-8 bytes directly without losing what `String.indexOf` gives it
   today (a SIMD intrinsic on HotSpot)?
2. Can OkHttp hand the frame's bytes to Kite, so no frame-sized `String` is built?
3. Can the parser, the moment it reads an EVENT's `id`, ask the app "do you already have this?"
   and, on a yes, stop parsing and reuse that `Event`?

## Short answers

1. **Yes, but on HotSpot the long-value scan gets slower.** The intrinsic exists only for
   `String`. A byte reader scans long values at about 10 GB/s with SWAR (8 bytes per step in a
   `long`), against about 60 GB/s for `String.indexOf`. That loss is small next to what the bytes
   save: the frame decode costs about 155 ns per ASCII frame and **about 1,080 ns and 5.3 KB per
   frame with any non-ASCII byte**. In the live sample, 15–40% of frames had one. On Kotlin/Native
   the byte path can call libc's `memchr`, which is SIMD; the current `String` path there has no
   SIMD at all. On Android (ART), whether the current path is vectorized at all is not measured.
2. **No fork needed.** Since 5.2.0, OkHttp exposes HTTP/1.1 upgrades as public API
   (`Response.socket`), and its own `RealWebSocket` is built on it. Quartz can send the upgrade
   request through the app's `OkHttpClient` (proxy/Tor, DNS, TLS, timeouts, interceptors all
   still apply). It then reads frames from the returned okio `Socket` into a reused `ByteArray`.
   **Proven against `wss://nos.lol` with OkHttp 5.5.1** (probe below). What Quartz takes on is
   the RFC 6455 framing: about 400 lines covering reader, writer, ping, close handshake and
   permessage-deflate. OkHttp's are Apache-2.0 and can be vendored with attribution.
3. **Yes, but it can't skip the scan, only the building.** Strfry, which runs most big relays,
   sorts keys alphabetically. So `content` comes *first*, and `id` comes third, after
   `created_at` (live sample below). The reader therefore records where each field before `id`
   sits instead of building it. It asks the hook once `id` is read, and on a hit returns the
   cached event without building anything. On a miss it builds the recorded spans, which costs
   no extra scan in strfry's order.

## What the receive path does today

```
OkHttp reader thread (one per relay)
  WebSocketReader.readMessageFrame()            okhttp3/internal/ws/WebSocketReader.kt
    source.readFully(messageFrameBuffer, len)   okio segments (8 KiB each, not contiguous)
    MessageInflater.inflate(buffer)             if permessage-deflate
    frameCallback.onReadMessage(buffer.readUtf8())   <-- the frame String, always
  RealWebSocket.onReadMessage(text) -> listener.onMessage(ws, text)
BasicOkHttpWebSocket.onMessage -> Channel<Any>(UNLIMITED).trySendBlocking(text)
IO coroutine -> BasicRelayClient.onMessageUnguarded(text)
  CachingEventDecoder.decodeFrom(text, url)     scan for "id":"…", hit = skip parse
  KiteReader.readMessage(text)                  on a miss
  listener.onIncomingMessage(client, text, msg) RelayStats uses text.length, RelayLogger logs it
```

There is no hook between `readMessageFrame` and `readUtf8`: for a text frame, OkHttp always
builds the `String`. The only byte callback, `onMessage(ws, ByteString)`, is for binary frames,
which Nostr relays don't send.

## Measurements

JVM 21 (HotSpot), 4-core x86 container, best of the warm passes. The scripts are plain Java in
the session scratchpad (`ScanBench.java`, `LongHop.java`, `NonAscii.java`). Re-run them in-repo
before relying on any number here. Corpus: `nostr_vitor_startup_data.json.gz`, wrapped as
`["EVENT","sub1",{…}]` frames.

### Building the frame `String` (what the byte path removes)

| Frames | Mean size | `new String(bytes, UTF_8)` | Allocated |
|---|---:|---:|---:|
| ASCII only (54,771) | 939 B | 155 ns (6.1 GB/s) | 982 B (1.05× the bytes) |
| Any non-ASCII byte (5,229) | 1,246 B | **1,082 ns (1.15 GB/s)** | **5,292 B (4.25×)** |

A frame with one emoji or one CJK character leaves the JDK's ASCII fast path. It inflates to a
UTF-16 `String` (2 bytes per char) and pays a scratch array on the way. Kite's whole parse of a
frame is about 1 µs (`2026-11-06-performance-review.md`), so for those frames the decode
**doubles** the CPU before Kite starts. The corpus is 9% non-ASCII. The live relays sampled below
were 15–40%.

Today every **duplicate** pays this decode too, before `CachingEventDecoder` can find its id. At
startup that is 82% of frames (`DedupCapacityBenchmark`).

### Scanning: `String.indexOf` vs bytes

Time to find the closing quote of one string value:

| Value length | `String.indexOf('"')` | byte loop | SWAR (VarHandle long loads) |
|---:|---:|---:|---:|
| 16 B | 2.1 ns | 3.2 ns | 2.2 ns |
| 64 B (an id) | 2.9 ns | 13.1 ns | 6.3 ns |
| 256 B | 4.9 ns | 55 ns | 24 ns |
| 1 KiB | 15.7 ns (65 GB/s) | 189 ns (5.4 GB/s) | 105 ns (9.7 GB/s) |
| 64 KiB (long-form) | 1.1 µs (58 GB/s) | 11.4 µs | 6.1 µs (10.8 GB/s) |

On a whole frame, where most hops are short (keys, hex, tag values), the walks come out close:

| Whole-frame walk (quote to quote) | ns/frame | Allocated |
|---|---:|---:|
| decode + `String.indexOf` walk (today) | 470–580 | 1,187 B |
| byte loop, early exit | 290 | 0 |
| SWAR on bytes | 325–430 | 0 |
| Latin-1 shadow `String` + `indexOf` walk | 410–470 | 1,077 B |

So the intrinsic is worth a lot only on long values. On a typical frame, skipping the decode
more than pays for losing it. On long-form content the byte path is 5 µs slower on the scan, but
the decode it skips costs 11–55 µs (64 KiB at 6.1 GB/s for ASCII, 1.15 GB/s otherwise).

### Live wire order and non-ASCII share

Captured 500 events (kinds 0, 1, 3, 6, 7, 1111, 9735) from each relay, 2026-11-06:

| Relay | Key order on the wire | Non-ASCII frames | Mean frame |
|---|---|---:|---:|
| nos.lol | `content,created_at,id,kind,pubkey,sig,tags` (all 500) | 34% | 1,154 B |
| relay.primal.net | same (all 500) | 16% | 1,501 B |
| offchain.pub | same (all 500) | 46% | 1,517 B |
| nostr.mom | same (all 500) | 39% | 895 B |
| relay.snort.social | as published: 35% `id,pubkey,…`, 26% strfry order, 26% `pubkey,created_at,kind,tags,content,id,sig`, 5% `kind,created_at,tags,content,pubkey,id,sig` | 35% | 660 B |

Strfry stores events with sorted keys, so `content`, usually the longest value, comes before
`id`. Relays that keep the publisher's order put `id` anywhere, often last (nostr-tools builds
the template first and adds `id` and `sig` at the end). On relay.snort.social, 20% of frames have
a space after the colon (`"id": "…"`), which `CachingEventDecoder.scanEventFrame`'s literal
`"id":"` match misses, so those duplicates fall back to a full parse today.

## 1. Kite on bytes

### Where SIMD comes from, per platform

- **HotSpot:** `String.indexOf(char)` is an intrinsic (`StringLatin1.indexOfChar`, AVX2). No
  public JDK method searches a `byte[]` for a byte with an intrinsic, and C2 does not vectorize
  early-exit search loops: the counting loop vectorizes (the 267 ns row above), the "find next"
  loop doesn't. The Vector API (`jdk.incubator.vector`) is still incubating and needs
  `--add-modules` at runtime, so a library can't count on it, and Android doesn't have it.
  **SWAR is the portable option:** load 8 bytes with
  `MethodHandles.byteArrayViewVarHandle(LongArray::class.java, LITTLE_ENDIAN)` (fine on JVM 17)
  and find a `"` byte with `(x - 0x01…) & ~x & 0x80…`. The same word also tells whether a high
  byte (non-ASCII) or a `\` appears before the quote, which decides how to build the value.
  Unrolling to 16 bytes per step is the next thing to try.
- **Android (ART):** `byteArrayViewVarHandle` is API 33+, and Quartz's minSdk is 26. Building the
  `long` from 8 single-byte loads is slower than the plain loop (591 ns vs 290 on HotSpot), so
  below API 33 the byte loop is the fallback. I have not checked whether ART's `String.indexOf`
  uses NEON; if it doesn't, the current path has no SIMD to lose there. Measure on a device
  (`androidDeviceTest`) before choosing.
- **Kotlin/Native:** Kotlin `String`s are always UTF-16, and `indexOf` is a plain loop. A byte
  reader can call `platform.posix.memchr` on a pinned `ByteArray`. That is glibc's or Apple
  libc's SIMD `memchr`, so on native the byte path *gains* SIMD. (QuartzKit apps get their frames
  from `URLSessionWebSocketTask` as a `String`, so this helps Linux and any socket that hands
  over `Data`.)

### Options considered

| Option | Keeps intrinsic | Frame-size allocation | Diff size | Verdict |
|---|---|---|---|---|
| A. Latin-1 shadow: `String(bytes, ISO_8859_1)` (a memcpy), walk it with today's Kite, build each value with `String(bytes, s, e - s, UTF_8)` from the original bytes | yes | yes, 1× the UTF-8 size (not 4.25×) | small | Good interim step: removes the UTF-8 decode and the UTF-16 blow-up, but not the per-frame garbage, and a duplicate still pays the copy. |
| B. Byte reader with a per-platform `nextQuote` (SWAR / loop / `memchr`) | no on HotSpot, yes on native | none (reused scratch) | large | **The target.** |
| C. Vector API | yes | none | medium | Not for a library (incubator, JVM-only, needs a runtime flag). |
| D. Abstract the input behind an interface, one grammar | – | – | medium | A virtual call on every char. HotSpot inlines a bimorphic site, but Kotlin/Native doesn't inline it, and a performance regression on native is not acceptable. |

### Shape of B

- **Only the hot subset moves to bytes:** `["EVENT", sub, {event}]`, `EOSE`, `OK`, `CLOSED`,
  `NOTICE`. That is the frame label, strings, numbers, tags, the event object and `skipValue`,
  roughly 600 of `KiteReader`'s 989 lines. Any other label (`COUNT`, `AUTH`, `NEG-*`, `LIMITS`,
  …) decodes the bytes to a `String` and goes to today's reader. That frame is rare and already
  identified by its label.
- **One grammar, two inputs, kept honest by tests:** the pinned suites (`KiteReaderPinnedTest`,
  `KiteReaderFramePinnedTest`, `KiteStrictnessTest`, `KiteReaderRetentionTest`) run against both
  readers and must agree on every accept, reject and value.
- **Building values:**
  - ASCII span (no high bit seen by the scan): `String(bytes, s, len, ISO_8859_1)` on JVM, a
    straight copy into a compact `String`; `decodeToString` on native.
  - Non-ASCII span: `String(bytes, s, len, UTF_8)`, decoding only that value, usually `content`.
  - Escapes (`\n` is in most kind-1 notes, so this path is hot): decode run by run into the
    reused `CharArray`, as today, but each unescaped run is UTF-8, not chars. A handwritten
    UTF-8 → UTF-16 run decoder must replace malformed bytes exactly as `String(bytes, UTF_8)`
    and okio's `readUtf8` do (U+FFFD per maximal subpart), and keep a lone `\uD800`-style escape
    as a lone surrogate, as today. Pin both against the `String` reader.
- **Hex fields stay `String`s for now** (`Event.id`, `pubKey`, `sig` are `HexKey`). Reading
  them as 32/64 raw bytes would save more but changes the `Event` model, which is a separate
  question.

## 2a. Getting the bytes out of OkHttp

### `Response.socket`, no fork

OkHttp 5.2.0 changelog: *"Support HTTP 101 responses with `Response.socket`. This mechanism is
only supported on HTTP/1.1. We also reimplemented our websocket client to use this new
mechanism."*

`CallServerInterceptor` (5.5.1) gives any call whose request says `Connection: Upgrade`, and
whose response is a 101 with `Connection: upgrade`, a `Response.socket`: the call's okio `source`
and `sink`, with the call timeout ended (`RealCall.upgradeToSocket`). It isn't limited to
WebSockets. So Quartz can:

1. take the app's `OkHttpClient`, rebuilt with `.protocols(listOf(Protocol.HTTP_1_1))`, as
   `RealWebSocket.connect` does (ALPN would otherwise pick h2 for `wss`, and h2 rejects a 101);
2. send `GET` with `Upgrade: websocket`, `Connection: Upgrade`, `Sec-WebSocket-Key`,
   `Sec-WebSocket-Version: 13` and `Sec-WebSocket-Extensions: permessage-deflate`;
3. check the 101 and `Sec-WebSocket-Accept`, and parse the extensions;
4. run its own frame loop on `response.socket.source`. Unmasked server frames: read the
   header, `readFully` the payload into a **reused `ByteArray`** (a copy that also makes
   multi-segment frames contiguous), inflate into a second reused array when compressed, and
   pass the bytes to Kite.

**Probe:** a throwaway `jvmTest` with stock OkHttp 5.5.1 dialed `wss://nos.lol` this way,
through the container's HTTP proxy, which `OkHttpClient` picked up from the system properties.
It sent a masked REQ and read every text frame up to EOSE into one reused array. Result: see
"Probe result" at the end.

### What Quartz takes on

From `RealWebSocket`/`WebSocketReader`/`WebSocketWriter`/`MessageInflater`:

- the frame reader (header, 16/64-bit lengths, continuations, control frames between fragments,
  rsv bits, a size cap);
- the writer (masked text frames, ping, pong, close);
- the close handshake (answer the relay's CLOSE, `webSocketCloseTimeout`), ping interval and
  pong timeout;
- permessage-deflate on read (`Inflater(true)` and the `00 00 ff ff` tail;
  `no_context_takeover`). Deflate on send can wait: REQs are small.

These files are Apache-2.0, which is compatible with MIT but must keep its notice. Vendoring
them is a WARN-level licensing note for the PR, under AGENTS.md's licensing rules, and is
safer than calling `okhttp3.internal.ws.*`. Those classes are public but internal, and can change
in any OkHttp patch.

### Gotchas found in the code

- **Read timeout.** `WebSocketReader.readHeader` clears the source timeout while it waits for
  the next frame, so a quiet relay isn't killed by `readTimeout`. Our loop must do the same and
  rely on pings.
- **Network interceptors.** `RealCall` skips `client.networkInterceptors` only when
  `forWebSocket` is set, and that constructor is internal. A plain call runs them. An app
  interceptor that reads `response.body` on a 101 gets OkHttp's `UnreadableResponseBody` and
  throws. Amethyst's interceptors need checking. Alternatively, build the client without network
  interceptors for relay dials.
- **Dispatcher slots.** `RealWebSocket` runs its read loop inside the `enqueue` callback, so each
  open WebSocket holds one of the dispatcher's `maxRequests` (default 64) async slots for its
  lifetime. That is worth knowing at 2,000 relays. Our loop can run on its own thread. Then the
  slot is freed after the handshake, but with `enqueue` the handshake counts against
  `maxRequestsPerHost` (5), which `RealWebSocket` is exempt from. Use `execute()` on our own
  thread, which neither limit applies to.
- **Threads.** `BasicOkHttpWebSocket` already has one blocking reader per relay; this keeps that
  count.

### Fork or upstream?

- **Fork: no.** Quartz is a library. A forked `okhttp3` on the classpath collides with the app's
  own OkHttp, and with Coil's and Retrofit's. Avoiding that means shading, which is the "recode
  OkHttp" cost the request wants to avoid, plus a rebase on every OkHttp release.
- **Upstream: worth an issue, not a blocker.** Two asks would make sense to Square:
  1. a listener opt-in that delivers *text* frames as a `ByteString`. It is small, but still
     allocates a frame-sized array per frame, so it would not get us the reused buffer;
  2. publishing the framing (`WebSocketReader`/`WebSocketWriter` and the extensions parser) as
     API for `Response.socket` users. This is the more natural one, since they built
     `Response.socket` for exactly this. If it lands, the vendored copy goes away.

## 2b. The id hook

### API sketch

```kotlin
/** Called on the socket's reader thread with an EVENT frame's id; must be thread-safe and must not block. */
fun interface KnownEventLookup {
    fun find(id: HexKey): Event?
}

// KiteReader (internal), byte input:
fun readMessage(utf8: ByteArray, offset: Int, length: Int, known: KnownEventLookup?): Message
```

An id `String` per frame costs about 88 B and keeps the lookup a plain `ConcurrentHashMap.get`.
A later version could hash the 64 hex bytes in place and compare them against `event.id` without
building it, if profiles show the id string matters once the frame string is gone.

### Reading an event when `id` isn't first

`readEventFields` gets a deferred mode, used only when `known` is set:

- **Before `id` is read:**
  - `content` and any other string: record `(start, end, hasEscape, hasHigh)` from the
    quote scan, which runs anyway; build nothing.
  - `tags`: record its start and `skipValue` past it. This scans without building and is
    already allocation-free.
  - `created_at`, `kind`: read them; they're cheap.
- **At `id`:**
  - read it and call `known.find(id)`.
  - **Hit:** return `EventMessage(subId, cached)` at once, unless the hook declined (see
    scrutiny below). The rest of the frame is never scanned.
  - **Miss:** read the remaining fields directly. Build the recorded spans: content from its
    span; tags by re-running `readTags` at the recorded offset.
- **Cost of a miss:**
  - strfry order (content, created_at, id, …, tags): no re-scan. The content's quote search
    ran once and its decode is only postponed. Tags come after `id`, so they're read once.
  - nostr-tools order (…, tags, content, pubkey, id, sig): tags are scanned twice. They are
    usually short, except on kind 3 and big lists.
  - `id` first: identical to today.

The hook must not replace `CachingEventDecoder`'s forgery handling. An id under scrutiny must
still be parsed and verified, and a relay on the suspect list still has its copies verified on
arrival. So the decoder *is* the lookup: it passes a `KnownEventLookup` that returns null for a
scrutinized id or a suspect relay, and its own cache hit otherwise.

An app can chain its own store behind it. Amethyst's `LocalCache` holds every event the app has
seen, which hits far more often than the decoder's 2 × 8,192 ids. The semantics don't change:
the first copy wins, unverified, which `CachingEventDecoder`'s KDoc already describes.

### Where the parse runs

With bytes in a reused buffer, the frame must be decoded before that buffer is reused. So the
parse moves from the IO consumer coroutine to the socket's reader thread, and the channel
carries `Message`s instead of `String`s:

- a duplicate never allocates more than the subscription id and the id;
- a slow consumer's backlog in the `UNLIMITED` channel holds references to cached `Event`s
  instead of frame copies;
- the parse (about 1 µs) now runs on the reader thread. Verify (about 37 µs) stays downstream,
  in `ParallelEventVerifier`.

### API changes this needs

- **`WebSocketListener`:** a socket that can decode on its reader thread needs the client's
  decoder. Add an optional interface, e.g. `FrameDecodingListener`, with
  `decode(utf8, off, len): Message?` and `suspend fun onDecoded(msg: Message, frameBytes: Int)`.
  `BasicRelayClient.MyWebsocketListener` implements it. Sockets that don't know it (Swift, the
  in-process relay, custom ones) keep calling `onMessage(text)`.
- **`RelayConnectionListener.onIncomingMessage(relay, msgStr: String, msg)`:** inside Quartz,
  only `RelayStats` (`msgStr.bytesUsedInMemory()`) and `RelayLogger` (debug `"Received: $msgStr"`)
  read `msgStr`. Add `onIncomingMessage(relay, msg, frameBytes: Int)`, with a default that
  delegates to the old method, and deprecate the old one per [Versioning](../../AGENTS.md#versioning)
  (`Removed in 3.0.0`). The raw text is rebuilt only when a listener asks for it, e.g. when
  `RelayLogger.debugReceiving` is on. `RelayStats` then counts wire bytes instead of an
  in-memory estimate, which needs a line in the release notes. Check Amethyst for other readers
  of `msgStr` before deprecating.

## Expected gain (estimate, not measured end to end)

Per frame, against today's decode cost on the corpus mix, and before any change to Kite's own
parse:

| Frame | Today | With bytes and the hook |
|---|---|---|
| duplicate, ASCII | ~155 ns + 1 KB decode, then the id scan | id scan on bytes, ~90 B |
| duplicate, non-ASCII | ~1,080 ns + 5.3 KB decode, then the id scan | id scan on bytes, ~90 B |
| unique, ASCII | 155 ns decode + ~1 µs parse | ~1 µs parse, slower long-value scans |
| unique, non-ASCII | 1,080 ns decode + ~1 µs parse | ~1 µs parse + decode of `content` only |

Ingest CPU is still mostly signature verification (about 95%), so this doesn't make ingest much
faster. It **removes most of the allocation churn while relays stream**: at 82% duplicates and
~30% non-ASCII frames, that is several KB per frame that never gets allocated, which matters most
on Android's GC.

## Plan (each step gated on a benchmark in this repo)

1. **Benchmark first.** Move the scratchpad measurements into `prodbench/` (frame decode by
   ASCII/non-ASCII, scans by value length, the end-to-end dedup path with the decode
   included). Add a recorded *wire-order* corpus, since the current one is re-serialized with
   `id` first and hides the strfry case.
2. **Quick win, no API change:** let `CachingEventDecoder.scanEventFrame` accept whitespace
   around `:`. Today it misses relay.snort.social's spaced frames.
3. **Byte reader for the hot frames (option B),** behind an internal API, with the pinned suites
   run against both readers, and `nextQuote` as expect/actual (SWAR on JVM, loop or SWAR on
   Android by API level, `memchr` on native). Gate: ASCII unique frames no slower than decode +
   String parse; non-ASCII frames faster.
4. **Deferred fields + `KnownEventLookup`,** wired into `CachingEventDecoder`, keeping every
   forgery rule. Gate: duplicate frames allocate under 200 B; `CachingEventDecoder` tests
   unchanged.
5. **`OkHttpRawWebSocket`** on `Response.socket`, next to `BasicOkHttpWebSocket`, opt-in. It
   needs tests for fragmentation, control frames between fragments, deflate (with and without
   context takeover), close handshake, ping timeout and oversized frames. Gate: amy against a
   few hundred real relays, same events delivered as `BasicOkHttpWebSocket`.
6. **The listener API** (`FrameDecodingListener`, the new `onIncomingMessage` overload,
   deprecations), the `quartz/CLIENT.md` and agent-skill updates, then Amethyst moves over.
7. **Upstream:** open the OkHttp issue for public framing API.

## Open questions

- ART: is `String.indexOf` vectorized? How fast are byte loops and VarHandle loads on a mid-range
  arm64 device?
- What share of Amethyst's real traffic is non-ASCII, and what wire order does it see? It
  connects to many more relays than the five sampled here.
- Does any Amethyst network interceptor read response bodies, which would break on a plain 101?
- Is it worth making `Event.id/pubKey/sig` bytes instead of hex `String`s? That is a much larger
  model change, out of scope here.

## Probe result

A throwaway `jvmTest`, not committed, using stock `okhttp 5.5.1` from `libs.versions.toml`. A plain
`client.newCall(request).execute()` on a client with `protocols(listOf(HTTP_1_1))`, sending the
upgrade headers above (no deflate offered), to `wss://nos.lol`:

```
PROBE code=101 protocol=http/1.1 acceptOk=true socket=okhttp3.internal.connection.Exchange$upgradeToSocket$1@…
PROBE read 201 text frames, 153751 bytes, straight into a reused ByteArray
```

It sent one hand-masked REQ frame (`kinds:[1], limit:200`) on `socket.sink`, then parsed frame
headers off `socket.source` and `read` each payload into one 16 KiB array until EOSE: 200 EVENTs
and the EOSE. No `String` was built per frame, and no OkHttp internals were used.
