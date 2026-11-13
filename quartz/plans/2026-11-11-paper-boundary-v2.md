# Paper boundary v2 — the hardened validator both apps implement

Status: agreed 2026-11-11, implemented in Brainstorm (`client/src/lib/frontPage/validate.ts`) and
Amethyst (`JsoupPaperBoundary`). Quartz holds no validator: the paper boundary is app policy. It is
recorded here beside the Observer site plan, whose published pages it guards.

The audit (2026-11-11) bypassed the v1 validator in both apps with: declarative shadow DOM,
`<noscript>` comment smuggling, URLs browsers clean before following (leading space,
`//host`, tab/CR/LF inside, C0 controls), SVG/MathML links and `<set>` animation, outside
resources outside `<img>` (`<link>`, `poster`, `background`, `<svg><image>`), CSS escapes
(`@\69mport`, `\75rl(`), `image-set()`, and hidden text / generated content inside quotes.
v1 matched what it recognised (a denylist). v2 is an ALLOWLIST: anything not listed is
refused by `check` (and, where resolve can safely do it, removed by `resolve`).

## Elements (allowed; everything else → MARKUP violation)
html head body title meta style header footer main section article aside nav div span
h1 h2 h3 h4 h5 h6 p br hr a img figure figcaption blockquote q cite em strong b i u s
small sub sup mark time abbr ul ol li dl dt dd table thead tbody tfoot tr th td caption
colgroup col details summary

Explicitly refused (listed so tests name them): template, noscript, svg, math, link, base,
script, iframe, frame, frameset, object, embed, applet, form, input, button, select,
textarea, video, audio, source, track, picture, canvas, portal, slot, dialog, marquee.

## Attributes (allowed per element; everything else → MARKUP violation)
- Global: class, id, lang, dir, title, role, aria-* (except aria-hidden inside quotes), style
- a: href, target (only `_blank`), rel
- img: src, alt, width, height, loading, decoding
- meta: charset; or name ∈ {viewport, description} with content
- td/th: colspan, rowspan, scope · time: datetime · ol: start, reversed · col/colgroup: span
- Refused everywhere: srcset, sizes, poster, background, xlink:*, any on*, formaction,
  http-equiv, srcdoc, ping, shadowroot*, hidden (inside quotes; elsewhere allowed)

## URLs (href and img src)
Normalise as browsers do before matching: strip leading/trailing C0 controls and spaces,
remove every TAB/LF/CR, then parse with a dummy base `https://paper.invalid/`.
- href: allowed only if the result is `https:` and matches one of the permitted link forms
  (permalink, zap.stream, shopstr, njump calendar, author profile — as today), or is a pure
  same-document `#fragment` (raw value starts with `#`). Everything else: resolve unwraps the
  link to text; check reports LINK.
- img src: must equal (after normalisation) a shortlist URL; else IMAGE.

## CSS (every `<style>` and every `style` attribute)
Refuse (MARKUP/IMAGE): any backslash `\`; `@import`; `url(`; `image-set(` / `-webkit-image-set(`;
`expression(`; `behavior`; `-moz-binding`; `@font-face`; and `content:` with a string value
other than the house stylesheet's own declarations (compare against the set of `content`
values present in house.css; anything else refused). Art is only ever `<img>`.

## Quotes
Quote text = the text content of q / blockquote, excluding `cite` and `footer` descendants
(attribution). Inside a quote: no `style`, `class`, `hidden`, `aria-hidden` on the quote or
any descendant; descendants limited to p, br, em, strong, b, i, cite, footer. Elision `…`/
`...` splits fragments; EVERY fragment (any length ≥ 1 after normalisation) must appear, in
order, in one desk event (no short-fragment skipping).

## Fixpoint
Validate `parse(serialize(doc))`; if serialize(parse(serialize(doc))) differs from
serialize(doc), re-check the re-parsed form, up to 3 rounds; still changing → MARKUP.

## Published and downloaded pages
After a page passes, the app inserts as the first child of `<head>` (never written by the
model, never accepted from it):
`<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">`
— the Observer nsite and the Download have no sandbox; this is their only script barrier
besides the validator. (The in-app preview keeps its sandbox/CSP as well.)

## Stream and answer
- An SSE `{"error":…}` frame, or a `finish_reason` other than `stop`, is a failure
  (WriterError/WriterException), never a paper. The non-stream answer is checked the same way.
- Stop reading at `[DONE]`. A stream that ends without `[DONE]` and without `finish_reason:
  stop` is a failure.

## Desk links
Events whose naddr can't be encoded (d tag over 255 bytes, or any encode error) get no
desk link in the digest; resolve unwraps such a link instead of throwing; nothing in resolve
or check may throw on corpus content.

## Extensions settled while implementing in Brainstorm (2026-11-11) — Amethyst matches these
- A stylesheet rule that the house CSS didn't write may not hide, clip or shrink text inside
  a quote (display:none, visibility:hidden, opacity:0, font-size:0, clip, clip-path,
  text-indent tricks, color equal to background, position offscreen, …) — refuse rules whose
  selectors can match q/blockquote or their descendants and that set such properties.
- A byline link (author profile form) may sit inside the quote's `cite`/`footer`.
- `<details open>` is allowed (`open` attribute on details).
- `dir` is refused inside quotes.
- CSS `@` rules limited to: media, supports, keyframes, layer, container.
- CSS refuses `src(`, `image(`, `cross-fade(`, `element(` in addition to url()/image-set().
- `quotes:` may contain only quote marks.
- The non-streamed answer: a missing `finish_reason` is accepted there.
- The model may not write its own CSP meta (refused); the app inserts it.
