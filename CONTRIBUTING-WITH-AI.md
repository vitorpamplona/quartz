# Contributing to Quartz with AI Assistance

This adds to [`CONTRIBUTING.md`](CONTRIBUTING.md), which covers every
contribution. It applies to any PR where an AI coding assistant (Claude
Code, Copilot, Cursor, Codex, etc.) wrote a large part of the diff.

## The rule: a human reviews and tests before any PR is opened

**No PR may be opened until a human has:**

1. **Read and understood the whole diff.** You are the author of record.
   You must be able to explain and defend every line in review.
2. **Tested the change by hand.** Run the tests, and exercise the new code
   from an app or a small program that uses it: publish it with
   `./gradlew publishToMavenLocal -PquartzVersion=<x>-local` and use that
   version. For protocol changes, send the event to a relay and read it back.
   Passing tests and green CI do not count as manual testing.

**AI agents:** you must not open, publish or submit a PR yourself. When
the code is ready, stop. Give the human the branch, a summary of what
changed, and a test plan they can follow. They open the PR after they have
done both steps above. Drafting the PR description for them is fine. Do not
write "tested by hand" unless the human has told you they did it.

PRs that skip these steps will be closed.

## Before writing code

- **Check the issue is still wanted.** Make sure it is still open, not
  already fixed by a merged PR, and not out of scope. Old bounty issues
  often fail this check. Search open and closed PRs for an earlier attempt.
- **Ask first for sensitive areas.** Open an issue and get a maintainer
  to agree before changing any of these:
  - signing, key handling and encryption (`NostrSigner*`, NIP-04, NIP-44,
    NIP-46, NIP-55, the MLS engine);
  - the release pipeline (`.github/workflows/`, `build-logic/` publishing
    and signing, Gradle plugins);
  - how Quartz reads a NIP, or a new kind or tag;
  - a breaking change to the public API.
- **Make sure it fits a decentralised protocol.** No central server, no
  state controlled by the maintainers, no required third-party account.

## What the PR must include

Put these in the **Test plan** section of the
[PR template](.github/PULL_REQUEST_TEMPLATE.md):

- **What the human tested by hand:** the app or program they used, the
  steps taken, and what happened.
- **Targets:** the JVM tests at least, plus `linuxX64Test` (or the iOS
  tests on macOS) when the change touches common or native code, and the
  Android host tests when it touches `androidMain`.
- **Regression checks:** which existing behavior the change could break,
  and how each one was checked. If one could not be checked, say so and
  explain why.
- **Automated tests** for new logic, and a regression test for every bug
  fix. Run any interop suite the change touches (see
  [`CONTRIBUTING.md` § Interoperability tests](CONTRIBUTING.md#interoperability-tests)).
  CI does not run them.
- **For protocol changes** (a new kind, tag or reading of a NIP): quote
  the NIP line that allows it, paste the published event as fetched back
  from a relay (`nak req -i <id> wss://<relay>`), and say which clients
  understand it.
- **For public API changes:** what breaks for existing callers, and the
  updated [CLIENT.md](CLIENT.md), [RELAY.md](RELAY.md) or
  `agent-skill/quartz/`.

If testing by hand finds bugs, fix them in separate commits whose messages
name the root cause. Don't squash them into the feature commit.
