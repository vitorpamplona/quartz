# Security Policy

Quartz is a Nostr library that handles private keys, signed events, and
end-to-end encryption (NIP-04, NIP-44, NIP-59 gift wraps, MLS) for the apps
built on it. We take security reports seriously and appreciate responsible
disclosure.

## Supported Versions

Only the latest release receives security fixes. We do not backport patches to
older versions. Fixes also land on the `main` branch ahead of the next release.

| Version        | Supported |
| -------------- | --------- |
| Latest release | ✅        |
| `main`         | ✅        |
| Older          | ❌        |

This covers every artifact built from this repository: `quartz` and each
`quartz-*` library on Maven Central.

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues.**

Use GitHub's private vulnerability reporting instead:

👉 [Report a vulnerability](https://github.com/vitorpamplona/quartz/security/advisories/new)

This keeps the details private until a fix is ready and coordinates disclosure
between you and the maintainers.

### What to include

To help us triage quickly, please provide:

- A clear description of the vulnerability and its impact (what an attacker
  could achieve — e.g. key material exposure, message confidentiality,
  signature forgery, integrity, availability).
- Affected artifact(s) and module(s), e.g. `quartz-core`, `quartz-marmot`.
- Affected version(s), commit SHA, and platform (JVM, Android, iOS, macOS,
  Linux).
- Steps to reproduce, a proof of concept, or a failing test.
- Any suggested remediation.

### What to expect

- **Acknowledgement within 48 hours** of your report.
- We will investigate and keep you informed of progress.
- We will coordinate a release and disclosure timeline with you. We aim to
  ship a fix within 90 days for high and critical issues, faster when key
  material or message confidentiality is at risk.
- Credit will be given to reporters in the security advisory and release
  notes (unless you prefer to remain anonymous).

## Scope

In scope:

- Source code in this repository across all modules.
- The artifacts published to Maven Central from this repository.
- Cryptographic handling: key generation, signing and verification, NIP-04 /
  NIP-44 encryption, NIP-59 gift wraps, NIP-46 bunker flows, NIP-55 external
  signer integration, the MLS engine and Marmot.
- Parsing of untrusted input (events, relay messages, NIP-19 entities) that
  could crash, hang, or mislead the app using it.
- Relay client and relay server behavior that could leak private data or
  bypass authorization.

Out of scope:

- Vulnerabilities in apps built on Quartz that come from the app's own code;
  report those to the app (for Amethyst, see its
  [SECURITY.md](https://github.com/vitorpamplona/amethyst/blob/main/SECURITY.md)).
- Vulnerabilities in third-party relays, bridges, media servers, or Nostr
  clients not built from this repository.
- Weaknesses inherent to the Nostr protocol itself — please report these
  upstream at <https://github.com/nostr-protocol/nips>.
- Denial-of-service from a malicious relay the app has explicitly connected
  to.

## Disclosure Policy

We follow a coordinated disclosure model. We ask that you:

- Give us reasonable time to investigate and release a fix before any public
  disclosure.
- Avoid accessing or modifying other users' data during research.
- Only interact with accounts and data you own or have explicit permission to
  test.
- Act in good faith.

We will not pursue or support legal action against researchers who follow
this policy. We commit to responding promptly and treating all reports
seriously.

Thank you for helping keep Quartz and the apps built on it safe.
