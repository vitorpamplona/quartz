# Contributing to Quartz

Thanks for your interest in improving Quartz. This document captures the
expectations, conventions, and review rules for code and documentation
contributions to every module in this repository (`core`, `social`, `dms`,
`relay-client`, `store`, `marmot`, … and the `quartz` umbrella).

By contributing, you agree to license your work under the MIT license. Any
work contributed where you are not the original author must contain its
license header with the original author(s) and source.

- [Ways to contribute](#ways-to-contribute)
- [Proof of testing (new / occasional contributors)](#proof-of-testing-new--occasional-contributors)
- [Reporting bugs and requesting features](#reporting-bugs-and-requesting-features)
- [Security issues](#security-issues)
- [Development setup](#development-setup)
- [Where code belongs](#where-code-belongs)
- [Workflow](#workflow)
- [Coding standards](#coding-standards)
- [Tests](#tests)
- [Interoperability tests](#interoperability-tests)
- [Public API](#public-api)
- [Commits](#commits)
- [Pull requests](#pull-requests)
- [Releases](#releases)

---

## Ways to contribute

- Fix bugs or implement NIPs (see issues and the bounty notes in the issue
  templates).
- Improve documentation in `README.md`, [GUIDE.md](GUIDE.md),
  [CLIENT.md](CLIENT.md), [RELAY.md](RELAY.md), `plans/`, the `.claude/`
  skill docs, and [`agent-skill/`](agent-skill/README.md), the skill other
  projects install to build on Quartz.
- Report bugs, suggest features, or open issues at
  [github.com/vitorpamplona/quartz/issues](https://github.com/vitorpamplona/quartz/issues).

## Proof of testing (new / occasional contributors)

We accept pull requests authored by humans and pull requests authored with
help from AI coding assistants (Claude Code, Copilot, Cursor, Codex, etc.)
under the same rules:

- **You are the author of record.** You are responsible for understanding,
  testing, and defending every line — including code an assistant wrote for
  you. "The AI did it" is not a valid response to review feedback.
- **No hallucinated APIs or imports.** Don't submit code that calls
  functions, classes, or libraries that don't exist in this repo or its
  declared dependencies. Build and run it before you push.
- **Disclose substantial AI involvement** with a one-line note in the PR
  description. Courtesy, not a gate.

If you are **not a regular contributor** to this repository — first PR, or
sporadic enough that maintainers wouldn't recognize your handle — the PR
description must include proof that you actually ran the change:

- **Code/logic changes:** paste the test output that shows the new path
  executing. CI green is necessary but not sufficient.
- **Protocol changes** (a new kind, tag, or reading of a NIP): the event JSON
  your code produces and, where it matters, the same event as another client
  or a relay returns it.
- **Build / Gradle / publishing changes:** paste the `./gradlew` command and
  the tail of its output.

Once you have an established track record, a short test plan is enough on
subsequent PRs.

If you can't run a particular target locally (e.g. no macOS, but your change
touches `appleMain`), say so explicitly. Honest "I couldn't run the iOS tests"
beats a silent guess.

If you used an AI coding assistant for a substantial portion of the diff,
also read [`CONTRIBUTING-WITH-AI.md`](CONTRIBUTING-WITH-AI.md). Its main
rule: no PR may be opened until a human has reviewed the whole diff and
tested the change. An agent must never open the PR itself.

## Reporting bugs and requesting features

Use the GitHub issue templates at
[`.github/ISSUE_TEMPLATE/`](.github/ISSUE_TEMPLATE/) rather than opening a
blank issue — they exist so triage can route the report without a follow-up
round trip.

**Bug report (`[BUG]` title prefix):** describe the bug, give the smallest
code, event JSON, or relay exchange that shows it, state the expected
behavior and the NIP that defines it, list the Quartz version, artifacts, and
platform, and include a bounty.

**Feature request (`[FEATURE]` title prefix):** describe the outcome, link the
NIP or spec, and include a bounty.

### Bounties

Maintainer time is allocated by bounty size. From the templates: *"If no
bounty is offered, not even a small one, this bug will not be worked on
because it doesn't matter to you."* Issues are prioritized by bounty ÷
effort, even a few hundred sats outrank a zero-bounty issue, and bounties
aren't refunded. If you can fix it yourself, just open the PR — bounties
exist to move issues nobody is currently working on. Security issues do not
need a bounty and should not be filed publicly; see
[SECURITY.md](SECURITY.md).

**A bounty is not, and will never be, a promise of payment.** Even if the
issue's author likes your solution and it is approved and merged, the author
can still decide, for any reason, whether to pay you or not. If you take on a
bounty, you accept the risk of not being paid even after the work is done.

## Security issues

**Do not file security vulnerabilities as public GitHub issues.** Use
[GitHub private vulnerability reporting](https://github.com/vitorpamplona/quartz/security/advisories/new)
instead. See [SECURITY.md](SECURITY.md) for scope, expected response times,
and disclosure policy.

## Development setup

Prerequisites:

1. **JDK 21+** (Zulu or Temurin recommended)
2. **Android SDK** (every module also targets Android; Android Studio or the
   command-line tools)
3. **Xcode** on macOS, only to run the iOS and macOS targets

Clone and build:

```bash
git clone https://github.com/vitorpamplona/quartz.git
cd quartz
./gradlew jvmTest
```

The first JVM compile installs the git hooks in `.git-hooks/`: `spotlessCheck`
before a commit and `jvmTest` before a push.

## Where code belongs

Quartz is one Gradle module per published artifact. [README.md § Modules](README.md#modules)
lists what each one holds and what it builds on, and [AGENTS.md](AGENTS.md)
has the rules that keep them separable. In short:

| What you're adding | Goes in |
|---|---|
| Events, tags, keys, signing, NIP-19, NIP-44, filters, relay messages | `core` |
| A social NIP (notes, reactions, lists, media, …) | `social` |
| DMs, payments, git, marketplace, … | the module named for it |
| Relay client, store, relay server | `relay-client`, `store`/`store-sqlite`, `relay-server` |
| A new protocol over Nostr | a new module (or a package in an existing one) |
| Fixtures shared by several modules' tests | `testing` (unpublished) |

Hard rules:

- **No UI.** Quartz is protocol only; UI belongs in the apps that use it.
- **Dependencies only point down.** `core` depends on no other module, and a
  cycle is a build error.
- **Heavy third-party libraries stay out of `core`.**
- **A new event kind registers in its module's `EventKindProvider`**, never in
  `EventFactory`.
- JVM-only APIs (Jackson, OkHttp, `java.*`) only in `jvmAndroid`, `jvmMain`, or
  `androidMain`. `./gradlew verifyKmpPurity` catches a leak on Linux.

Per-change design docs live in `plans/YYYY-MM-DD-<slug>.md`.

## Workflow

1. **Survey first.** Search the codebase for existing implementations before
   writing new code: most tag codecs, builders, and relay helpers already
   exist.
2. **Open a small PR.** One feature or one fix per PR. Refactors that
   support the change are fine; unrelated refactors are not.
3. **Branch off `main`.**
4. **Run tests + spotless locally** before pushing.
5. **Open the PR** with a description that explains *why*, not just *what*.

## Coding standards

- Kotlin, formatted with Spotless. Always run `./gradlew spotlessApply`
  before considering a task complete. CI runs `spotlessCheck` and will fail
  on unformatted code.
- Never use `--no-verify` to bypass the git hooks. If a hook fails, fix the
  cause.
- Hot event and parse paths use raw arrays and the inline `fast*` operators;
  don't "modernize" them into stdlib collection calls. [AGENTS.md § Kotlin style](AGENTS.md#kotlin-style)
  has the rest.
- Default to writing no comments. Add one only when the *why* is non-obvious
  (a workaround, a subtle invariant, a spec quirk). Don't restate what the
  code does, and don't reference the current task — that belongs in the PR
  description.

## Tests

```bash
./gradlew jvmTest                    # every module's JVM tests
./gradlew :social:jvmTest            # one module
./gradlew linuxX64Test               # Linux native
./gradlew iosSimulatorArm64Test      # iOS (macOS only)
./gradlew :core:testAndroidHostTest  # Android host tests, per module
```

Add tests when:

- You fix a bug — a regression test that fails before your fix and passes
  after.
- You add an event kind, tag, codec, or NIP: parse and build round trips,
  ideally against an event another client published.
- You touch crypto, signing, or the store: cover the edge cases, not only the
  happy path.

Common code that touches `expect`/`actual` needs a native run too
(`linuxX64Test` at least), not only the JVM.

To try a change inside an app before it is released, publish it locally and
point the app at that version:

```bash
./gradlew publishToMavenLocal -PquartzVersion=1.20.0-local
```

## Interoperability tests

These drive Quartz against other implementations. They are **not run in
CI**: they need extra checkouts and toolchains, and most PRs don't touch the
code they cover. If your change does, run the relevant suite and paste the
result into the PR description.

| Suite | Where | What it covers | When you must run it |
|---|---|---|---|
| **ts-mls (reverse)** | [`interop/verify-with-ts-mls.sh`](interop/verify-with-ts-mls.sh) | ts-mls reads the Welcome, commits, and messages `cordn` produces | Changes to `mls/` or `cordn/` |
| **Marmot / MLS (Whitenoise)** | Amethyst's [`cli/tests/marmot/`](https://github.com/vitorpamplona/amethyst/tree/main/cli/tests/marmot) | NIP-EE MLS groups between `amy` (built on Quartz) and the `whitenoise-rs` reference | Changes to `mls/` or `marmot/` |
| **NIP-17 DMs** | Amethyst's [`cli/tests/dm/`](https://github.com/vitorpamplona/amethyst/tree/main/cli/tests/dm) | Text and file DM round trips, kind 10050 routing | Changes to NIP-17, NIP-59 gift wraps, or NIP-44 |

The Amethyst suites run against the Quartz version Amethyst pins. To run them
against your change, publish it to Maven Local (see [Tests](#tests)) and set
`quartz` in Amethyst's `gradle/libs.versions.toml` to that version.

If a change is documentation-only or otherwise cannot affect wire bytes, MLS
state, or DM envelopes, skip the interop suites and say so in the PR
description.

## Public API

Quartz is published to Maven Central and other apps build on it, so the
public API is a contract:

- Prefer adding over changing. When a change breaks callers, say so in the PR
  description and in the release notes.
- Internals one module shares with another are `public` and marked
  `@InternalQuartzApi`, which apps must opt into.
- A public API change also updates [CLIENT.md](CLIENT.md), [RELAY.md](RELAY.md),
  and `agent-skill/quartz/`, and any code you add there must compile.

## Commits

- Use [Conventional Commits](https://www.conventionalcommits.org/): `feat:`,
  `fix:`, `refactor:`, `docs:`, `chore:`, `test:`, etc.
- One logical change per commit. Squashing during PR review is fine.
- Keep subject lines under ~72 characters. Use the body to explain *why*
  the change was made when it isn't obvious.
- Branch names follow `feat/<scope>-<short-name>` or
  `fix/<scope>-<short-name>` (e.g. `feat/nip88-polls`,
  `fix/relay-client-reconnect`).

## Pull requests

A good PR description has:

1. **Summary** — 1–3 sentences on what changed and why, with the NIP it
   follows.
2. **Test plan** — exactly what you ran, on what platform, with what result.
3. **Public API** — whether it changed, and the docs you updated.

The [PR template](.github/PULL_REQUEST_TEMPLATE.md) has the checklist.

We aim to give first-pass feedback within a few days. PRs may sit longer if
they're large, touch security-sensitive code, or arrive during a release
window.

## Releases

Every artifact shares one version, `quartz` in `gradle/libs.versions.toml`.
A maintainer sets it, commits, and pushes a matching `vX.Y.Z` tag; the
Release workflow publishes every artifact to Maven Central.

If your PR needs to be in a particular release, mention it in the PR
description and the maintainers will coordinate.

---

Thanks again for contributing.
