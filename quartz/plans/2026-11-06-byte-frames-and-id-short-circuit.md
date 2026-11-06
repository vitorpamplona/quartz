# Byte frames: Kite on UTF-8 bytes, OkHttp without the String, and an id short-circuit

**Date:** 2026-11-06
**Status:** the byte path is built end to end, opt-in. `OkHttpBytesWebSocket` reads frames
off OkHttp's `Response.socket` and the relay client parses them with the SWAR byte reader
(`KiteBytesReader`). Against `BasicOkHttpWebSocket`, through `BasicRelayClient` and
`CachingEventDecoder` on nostrJsonBench's startup corpus:
- allocation per frame falls 40–71%;
- CPU falls 27–53% on compressed or duplicate traffic and 6% on paced plain traffic;
- CPU rises 20–30% only when a loopback relay floods uncompressed unique frames.

The id hook with deferred fields is in too (section 2d). The Latin-1 view (option A) was measured
and then removed, superseded by the byte reader. A review pass fixed three bugs (section 2f).
Still open: the device measurements. The
measurements and the OkHttp probe are reproducible, and the plan at the end is staged so each
step can be benchmarked before the next.

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
| A. Latin-1 shadow: `String(bytes, ISO_8859_1)` (a memcpy), walk it with today's Kite, build each value with `String(bytes, s, e - s, UTF_8)` from the original bytes | yes | yes, 1× the UTF-8 size (not 4.25×) | small | **Prototyped, below.** Removes the UTF-8 decode and the UTF-16 blow-up (−39% alloc, −33% CPU on non-ASCII frames), but not the per-frame copy, and a duplicate still pays the copy. |
| B. Byte reader with a per-platform `nextQuote` (SWAR / loop / `memchr`) | no on HotSpot, yes on native | none (reused scratch) | large | **The target.** |
| C. Vector API | yes | none | medium | Not for a library (incubator, JVM-only, needs a runtime flag). |
| D. Abstract the input behind an interface, one grammar | – | – | medium | A virtual call on every char. HotSpot inlines a bimorphic site, but Kotlin/Native doesn't inline it, and a performance regression on native is not acceptable. |

### Option A, prototyped, measured, then removed

Option B below beat it on every row, so it is gone from the code; its numbers stay here. It was
`KiteReader.readMessageUtf8(bytes, offset, length)` and `readEventUtf8` (internal), parsing UTF-8
bytes through a Latin-1 view:

- **The view:** `String(bytes, ISO_8859_1)` is a byte-for-char copy into a compact string, so
  `String.indexOf` keeps its intrinsic.
- **What changes in the reader:** every token it reads is ASCII and the same in the view. Only
  two places build string values, and they now take the bytes:
  - `slice`: `decodeToString` of the value's byte range;
  - `readEscapedUtf8`: raw runs are copied as bytes and escapes written as UTF-8, with an
    escaped surrogate pair becoming its 4-byte sequence; then one decode.
  - A lone escaped surrogate, which UTF-8 can't hold, falls back to a char-by-char path that
    keeps it lone, as the `String` reader does.
- **Native** decodes first, as before (`LATIN1_VIEW = false`). Its strings are UTF-16, so a view
  would only double the bytes.
- **Parity:**
  - `KiteReaderUtf8Test` checks it against decode-then-`readMessage`: non-ASCII, escapes next
    to non-ASCII, pairs, lone surrogates, escaped keys, malformed UTF-8 inside and outside
    strings, malformed JSON, offsets.
  - `KiteUtf8Benchmark` checks **all 31,161 events of nostrJsonBench's startup corpus**. Every
    one reads the same both ways.

Results, frame bytes → `Message` with both paths timed from the bytes (median of 9 passes, two
rounds; `KiteUtf8Benchmark`, corpus `nostr-2024-startup.ndjson.gz`, 883 B mean, JDK 21):

| Frames | Today: decode + `readMessage` | Latin-1 view | Change |
|---|---|---|---|
| all 31,161 | 1,127–1,205 ns, 2,618 B | 1,020–1,051 ns, 2,426 B | −9–13% CPU, −7% alloc |
| ASCII only (28,927) | 833–862 ns, 2,199 B | 767–942 ns, 2,232 B | same, within noise |
| non-ASCII (2,234) | 2,724–3,159 ns, 8,041 B | 1,829–2,094 ns, 4,938 B | **−33% CPU, −39% alloc** |
| ≥ 4 KiB (196) | 7,767–8,864 ns, 27,889 B | 6,522–7,447 ns, 23,249 B | −16% CPU, −17% alloc |

For reference, the parse alone, from a prebuilt `String` (what nostrJsonBench times), is 856–962
ns and 1,393 B. So about half of today's allocation per frame is the frame `String`.

**Why ASCII frames don't gain:** on an ASCII frame, the JDK's UTF-8 decode is already a
vectorized "any negative byte?" check plus a copy (903 B). The Latin-1 view is the same copy. The
view only wins where the decode leaves its fast path, which on this corpus is 7% of frames and on
the live relays above is 15–40%. The remaining ~900 B of frame copy on ASCII frames can only go
with option B (no view at all) or with the id hook (no parse at all).

### Option B, built and measured

`KiteBytesReader` reads `["EVENT", subId, {event}]` frames, and bare event objects, straight
from the bytes:

- **Grammar:** the same as `KiteReader`'s, translated function by function. A byte is compared
  as the Latin-1 char it is, which is exact for every ASCII token.
- **Values:** each one is decoded from its own byte range (`decodeToString`). Escaped values go
  through a UTF-8 scratch, as in option A.
- **Other frames:** any other label returns null, and `readMessageUtf8` decodes the frame and
  reads it with `KiteReader` as before. Those frames are rare, and their grammar stays in one
  place.
- **Scanning:** quotes and backslashes are found with `indexOfByte`, an expect/actual:
  - **JVM:** SWAR over `MethodHandles.byteArrayViewVarHandle` long loads, two words per turn.
    Kotlin compiles the call to the direct `VarHandle.get:([BI)J` signature, so there is no
    boxing.
  - **Android:** a plain loop. The VarHandle byte view is API 33+ and minSdk is 26, so an
    API-gated SWAR path waits for a device benchmark.
  - **Native:** a plain loop; `getLongAt` SWAR or `memchr` comes later. Neither Android nor
    native is measured.

**Parity:** `KiteReaderUtf8Test` covers number forms, odd tag values, unknown fields of every
shape, extra frame elements, an escaped label, nesting past the depth limit, whitespace, trailing
input, the escapes and malformed UTF-8 from option A. Plus all 31,161 corpus events, both readers,
against decode-then-`readMessage`.

Same benchmark as option A (`KiteUtf8Benchmark`, two rounds, median of 9 passes; allocation is
deterministic, time varies ±10% run to run on this VM):

| Frames | Today: decode + `readMessage` | Latin-1 view (A) | **Byte reader, SWAR (B)** |
|---|---|---|---|
| all 31,161 | 865–944 ns, 2,618 B | 778–857 ns, 2,426 B | **771–907 ns, 1,497 B (−43%)** |
| ASCII only | 697–748 ns, 2,199 B | 702–829 ns, 2,232 B | **697–707 ns, 1,328 B (−40%)** |
| non-ASCII | 2,764–2,792 ns, 8,041 B | 1,840–2,078 ns, 4,938 B | **1,816–2,057 ns, 3,677 B (−54%)** |
| ≥ 4 KiB | 7,437–8,655 ns, 27,889 B | 6,373–6,591 ns, 23,249 B | **6,292–6,370 ns, 15,346 B (−45%)** |

Parsing alone, from a prebuilt `String`, allocates 1,393 B per frame, which is about the `Event`
itself. So B leaves about 100 B of short-lived garbage per frame on top of what the app keeps,
against about 1,225 B today.

CPU is a wash on ASCII frames: the lost intrinsic costs about what skipping the decode saves.
Non-ASCII and large frames are 15–35% faster. **B supersedes A**: the same or better on every
row, with no frame-sized copy. A stays only as the comparison point and can go once B has a
caller.

### Shape of B (the original plan, for reference)

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

## 2c. The socket, built and measured

`OkHttpBytesWebSocket` (jvmAndroid) has the same contract and Builder as `BasicOkHttpWebSocket`.

- **Dial:** a plain HTTP/1.1 upgrade call through the app's client, run with `execute()` on the
  session's own thread. It holds no dispatcher slot and isn't capped by `maxRequestsPerHost`.
- **Framing:** `WsFrameReader` / `WsFrameWriter` (RFC 6455, written here, not vendored) on
  `Response.socket`. It covers:
  - fragments, with control frames between them;
  - ping/pong, and a ping timeout from `pingIntervalMillis`;
  - the close handshake;
  - permessage-deflate inflate, with or without context takeover;
  - masked writes, a size cap (`maxMessageBytes`), and OkHttp's 16 MiB send queue.
- **Buffers:** frames are read into `WsBufferPool`, shared by all connections. What it keeps
  follows how many connections are reading at once, not how many are open.
- **Parsing:** each text frame is parsed on the reader thread through `Utf8WebSocketListener`.
  `BasicRelayClient`'s listener routes it to `CachingEventDecoder.decodeFromUtf8`, whose
  duplicate scan reads the bytes, or to `MessageDecoder.decodeUtf8`.
- **Delivery:** decoded messages go to the delivery coroutine in batches, flushed right before any
  read that has to wait for the network (`WsFrameReader.Callback.onWouldBlock`), or at 128.
  Queuing them one at a time woke the coroutine per message: CPU for unique plain frames was 2.4×
  `BasicOkHttpWebSocket`'s before batching, 1.2–1.3× after. The first version flushed only
  between messages when okio's buffer was empty, which held messages back behind a partly
  arrived next frame (section 2f).
- **Listener API:** the relay client calls `RelayConnectionListener.onIncomingMessage(relay, msg,
  frameBytes)`, a new overload whose default bridges to the old one with an empty `msgStr`.
  `NostrClient`, `RelayPool` and `RedirectConnectionListener` forward it. `RelayStats` counts its
  size as the old string estimate, and `RelayLogger` re-serializes the event for debug logs.
  Amethyst's `msgStr` readers (`RelayUsageListener`, `RelaySpeedLogger`) only take its length and
  should override the new overload when Amethyst opts in.

**Tests:** `BasicOkHttpWebSocket`'s drain, close-handshake and dial-race suites now run against
both sockets, through a `newSocket` factory. `OkHttpBytesWebSocketTest` (19 tests, against a
scripted raw RFC 6455 relay) covers:
- every length encoding, fragments with a ping between them, and context takeover with and
  without reset;
- a masked server frame, an oversized message, and a relay close with its code and reason;
- a refused upgrade and a bad accept header;
- a missed pong, answered pings, an idle relay past `readTimeout`, and a stalled frame;
- masked UTF-8 sends from `onOpen`, binary frames, and the byte listener's thread and order;
- the relay client end to end, with the cached `Event` reused, plus a listener that overrides
  only the text overload.

`SocketIngestBenchmark`: a loopback relay streams the corpus as EVENT frames. Each run goes
through `BasicRelayClient` with a fresh `CachingEventDecoder`. Times are medians of 5 rounds (3
for paced), after warm-up. CPU and allocation are summed over every thread but the server's.
Three full runs agreed within about 10%.

| Workload (frames) | Basic: CPU, alloc per frame | Bytes: CPU, alloc per frame |
|---|---|---|
| unique, plain (31,161) | 3,103–3,500 ns, 3,712 B | 4,012–4,178 ns, **1,821 B (−51%)** |
| unique, deflate | 14,530–14,989 ns, 3,150 B | **7,921–8,201 ns (−45%)**, **1,881 B (−40%)** |
| 3× duplicates, plain (93,483) | 3,600–3,933 ns, 2,751 B | **2,695–2,839 ns (−27%)**, **784 B (−71%)** |
| 3× duplicates, deflate | 14,773–15,401 ns, 2,193 B | **7,011–7,106 ns (−53%)**, **844 B (−62%)** |
| unique, plain, paced 20 frames/ms | 7,815 ns, 3,736 B | **7,323 ns (−6%)**, **1,853 B (−50%)** |
| unique, deflate, paced 20 frames/ms | 17,641 ns, 3,163 B | **11,484 ns (−35%)**, **1,913 B (−40%)** |

The one CPU loss, unique plain frames flooded over loopback, comes from the shape of that test.
`BasicOkHttpWebSocket`'s consumer coroutine is the slower side there, so it never parks, and the
parse overlaps OkHttp's reader on another core. Wall time to the last frame is 51–65 ms against
100–106 ms. Paced like a network, where its consumer parks per frame, the gap is gone. Over a
network, CPU matters more than time to the last frame. Deflate wins most because OkHttp inflates
into okio segments and then decodes those, while this inflates into a pooled array that is parsed
in place. A breakdown with raw sockets and no relay client (`SocketIngestBenchmark.breakdown`,
`SOCKET_BREAKDOWN=1`) shows framing alone at 65 B per frame. Most of its ~1.5 µs is kernel time
for the 8 KiB socket reads, which both sockets pay.

Not measured: Android (ART, the plain-loop `indexOfByte`, compact strings only for ASCII) and
Kotlin/Native (no socket there yet). Measure on a device before Amethyst Android opts in.

## 2d. The id hook, built

`KnownEventLookup.find(id)` is asked by `KiteBytesReader.readEventFrame(…, known)` the moment it
reads the id.

- **Deferral:** until then, `content`, `tags`, `pubkey` and `sig` are only skipped
  (`skipValue`), each with its start offset noted. `created_at` and `kind` are read, since they
  allocate nothing.
- **Hit:** the known event is the frame's, with the frame's subscription id, and nothing after
  the id is read. A frame broken after its id is a hit all the same, as with
  `CachingEventDecoder`'s old scan.
- **Miss:** the rest is read directly, then each skipped field is read from its offset, with
  `nextEscape` reset, since it only looks forward.
- **Duplicate keys:** the last occurrence still wins. A field read directly drops an earlier
  deferred one. A frame with two ids is looked up by its first and read with its last.

`CachingEventDecoder.decodeFromUtf8` now parses with that lookup when its full parser is Kite's
(`MessageDecoder.Default`). Its `FrameLookup` checks, in order:
1. an id under scrutiny gets no answer, so its copy is parsed and checked by `afterFullParse`,
   exactly as before;
2. the decoder's own cache;
3. the new `knownEvents` (`@Volatile var`, no constructor change), the app's events, e.g.
   Amethyst's in-memory cache, which reaches past the last 2 × 8,192 ids.

A revoked (reported) id is never asked of the app. The text path's scan also falls back to
`knownEvents`, and `reusedKnownCount` counts the app's answers. What the byte path gains over the
old scan-then-parse:
- one pass on a miss;
- frames with whitespace or escaped keys are caught, where the scan's literal `"id":"` missed
  20% of relay.snort.social's frames;
- the app's events count as hits.

**Tests:**
- Every `KiteReaderUtf8Test` case also runs through the deferred path with a lookup that never
  answers. New cases cover all four wire key orders, hits with whitespace and escaped keys, a
  frame broken after its id, last-wins for duplicate keys and two ids, and deferred values that
  must still fail.
- `CachingEventDecoderTest`, `…ForgeryTest` (19) and `…TrimTest` now run twice, as text and as
  bytes, through a `decodeAt` hook. `CachingEventDecoderKnownEventsTest` covers the app lookup
  on both paths: asked after the cache, and never for a scrutinized id, so a forged copy the
  app holds is never handed out.
- `KiteUtf8Benchmark` re-orders all 31,161 corpus events into strfry and nostr-tools key order:
  every one reads the same through the miss path, and the hit path returns the known event.

## 2f. Review fixes

A code review of the branch found the following, now fixed, each with a test that fails without
the fix.

**Bugs:**
- **A session that never reported its end.** `OkHttpBytesWebSocket` caught only `IOException`
  from the dial and `Exception` from the read loop. An interceptor's `IllegalStateException`, or
  an `OutOfMemoryError` growing a frame buffer, ended the reader thread with no `onFailure`. The
  relay client then kept a dead socket and never redialed. Both catches now take `Throwable`,
  report it, and rethrow `Error`s.
- **Messages held back behind a partial frame.** The reader flushed parsed messages only between
  messages and only when okio's buffer was empty. A read that ended partway into a large next
  frame held everything already parsed until that frame finished arriving: seconds on a slow
  link. It now flushes right before any read that would block.
  - The cost shows in flood benchmarks only. okio reads at most 8 KiB, so in a flood most reads
    end partway into a frame, and the reader now wakes the delivery coroutine about every 9
    frames instead of every 128.
  - `SocketIngestBenchmark` on a host ~20% slower than the earlier runs, as the bytes socket's
    CPU over Basic's:
    - flooded duplicates, plain: 0.72 → 1.20;
    - flooded unique, plain: 1.2–1.3 → 1.56;
    - deflate: about 0.5–0.6 either way;
    - paced traffic, plain and deflate: 0.97 and 0.68, about as before.
  - Not worth avoiding: only time tells a frame about to arrive from one that will take seconds,
    and a timer-driven flush would cost about what a wakeup does.
- **Forged-copy spam parsed in full on the byte path.** Copies of a scrutinized id past
  `MAX_FORGED_COPIES`, or carrying a signature already proven bad, were parsed before being
  dropped. `FrameLookup` now answers them with a drop at the id, the latter after a byte scan of
  the `sig`, as the text path's `decodeScrutinized` does.

**Cleanups:**
- **Dead code removed:**
  - the Latin-1 view (option A, its platform actuals and tests);
  - `CachingEventDecoder.lookup()`;
  - the byte pre-scan (`scanEventFrameUtf8`): a decoder with a custom `fullParser` now decodes
    to text and takes `decodeFrom`, so one dedup path remains per input type.
- **Duplication:**
  - `RelayStats` uses `stringBytesUsedInMemory(length)` instead of a copy of the formula;
  - `SocketIngestBenchmark` uses `ScriptedRelay` instead of its own handshake.
- **Android:** the parity and decoder suites (`KiteReaderUtf8Test`, the decoder suites through
  bytes, the spam test) pass under `testAndroidHostTest`, on the Android `indexOfByte`. Kotlin/Native
  is still unrun: its toolchain could not be installed in this container.

## 2e. nostrLibBench: the whole client path

[nostrLibBench](https://github.com/vitorpamplona/nostrLibBench) times a client from its REQ to
the EOSE of 31,161 events streamed by a local Rust relay. Each event is parsed, its id
recomputed and its signature verified. The relay sends no permessage-deflate, keeps the corpus's
id-first key order, and is never paced. The harness turns off dedup caches, so the default
decoder runs and the id hook does not.

Two rows were added to its JVM harness, `quartz-bytes-fetch` and `quartz-bytes-parallelverifier`:
the same two Quartz configurations on `OkHttpBytesWebSocket`. The patch is a `QuartzClient(bytes
= true)` flag plus two `Registry` entries. All four Quartz rows ran against this checkout
(`QUARTZ_DIR`, commit 7b998a3) on Temurin JDK 27.0.1, `-Xmx2g -XX:+UseG1GC`, on the 4-core
container. Three passes, each the harness's median of its timed runs, on the `all` workload:

| Client | µs/event | CPU µs/event | Alloc B/event | Memory growth |
|---|---|---|---|---|
| `quartz-fetch` | 44.1–45.3 | 48.8–50.7 | 3,569–3,575 | 97–276 MB |
| `quartz-bytes-fetch` | 42.2–44.9 | 50.0–50.4 | **1,606–1,866** | 192–281 MB |
| `quartz-parallelverifier` | 16.6–17.3 | 52.0–56.2 | 3,490–3,522 | 259–264 MB |
| `quartz-bytes-parallelverifier` | 16.6–16.8 | 52.3–54.1 | **1,558–1,777** | 197–237 MB |

- **Correctness:** every row delivers exactly the 29,614 events and passes the three forged
  workloads (content, id and sig forgeries).
- **Allocation:** about half (−48% to −56%).
- **Time and CPU:** the same within the harness's noise. Signature verification is about 37 µs
  of each event's ~50 µs of CPU, so a parse saving well under 1 µs can't show. The harness reads
  process CPU from `times()`, in 10 ms ticks, which is about 0.3 µs/event over this run.
- **Memory growth:** peak RSS minus RSS after setup varies 2–3× between passes for the same
  client (G1 sizes the heap as it likes under `-Xmx2g`). This harness can't tell the sockets
  apart on it.

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
2. **Done: the text path's duplicate scan accepts JSON whitespace.** `CachingEventDecoder`'s
   `scanEventFrame` and `scanSig` (and the byte `scanSigUtf8`) accept whitespace between the
   frame's tokens and around the colon of `"id"`/`"sig"`, so relay.snort.social's `"id": "…"`
   frames (20% of its sample) hit the cache instead of being parsed again. A string can't hold
   `"id"` unescaped and a string value is never followed by `:`, so the first `"id"` followed by a
   colon is a key. Compact frames take the same `indexOf` path as before. Tests: spaced and
   pretty-printed copies are recognized as duplicates; a spaced copy of another event is parsed;
   `"id"` as a tag value or quoted in the content is not taken for the key. The two spaced tests
   fail on the old scan.
3. **Done, then removed: the Latin-1 view (option A)**, superseded by option B.
4. **Done: the byte reader for EVENT frames (option B)**, `KiteReader.readMessageUtf8` /
   `KiteBytesReader`. Still open in this step: SWAR on Android API 33+ and on native, measured on
   devices; and whether ASCII values should skip `decodeToString`'s non-ASCII check, since the
   scan could report it. Originally planned as: **Byte reader for the hot frames (option B),** behind an internal API, with the pinned suites
   run against both readers, and `nextQuote` as expect/actual (SWAR on JVM, loop or SWAR on
   Android by API level, `memchr` on native). Gate: ASCII unique frames no slower than decode +
   String parse; non-ASCII frames faster.
5. **Done: deferred fields + `KnownEventLookup`** (section 2d). Originally: **Deferred fields + `KnownEventLookup`,** wired into `CachingEventDecoder`, keeping every
   forgery rule. Gate: duplicate frames allocate under 200 B; `CachingEventDecoder` tests
   unchanged.
6. **Done: `OkHttpBytesWebSocket`**, on `Response.socket`, next to `BasicOkHttpWebSocket`,
   opt-in (section 2c). Still open: amy against a few hundred real relays. Originally:
   `OkHttpRawWebSocket` on `Response.socket`, next to `BasicOkHttpWebSocket`, opt-in. It
   needs tests for fragmentation, control frames between fragments, deflate (with and without
   context takeover), close handshake, ping timeout and oversized frames. Gate: amy against a
   few hundred real relays, same events delivered as `BasicOkHttpWebSocket`.
7. **Done, as built: the listener API**: `Utf8WebSocketListener`, the
   `onIncomingMessage(relay, msg, frameBytes)` overload, `MessageDecoder.decodeUtf8`, and the
   CLIENT.md and agent-skill notes. Amethyst moving over is still open. Originally: the listener
   API (`FrameDecodingListener`, the new `onIncomingMessage` overload,
   deprecations), the `quartz/CLIENT.md` and agent-skill updates, then Amethyst moves over.
8. **Upstream:** open the OkHttp issue for public framing API.

## Open questions

- ART: compact strings there hold only ASCII, so a Latin-1 view of a frame with any high byte
  is UTF-16 (2× the bytes). Does option A still beat the decode there, and does option B beat
  both? `LATIN1_VIEW` is shared by JVM and Android today; measure before Android calls it.
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
