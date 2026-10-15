<!--
Thanks for contributing to Quartz! Delete any section below that doesn't apply.
-->

## Summary

<!-- 1–3 sentences. What changed and why, and which NIP or spec it follows
(link the NIP or the spec section). -->

## Test plan

<!-- Required. What did you run, with what result? A new event kind, tag or
codec comes with a test beside it (JVM at least; native too when the code
touches expect/actual). -->

- [ ] Ran `./gradlew spotlessApply` — repo is formatted
- [ ] Ran `./gradlew jvmTest` (or the changed modules' `jvmTest`)
- [ ] Ran `./gradlew verifyKmpPurity` if common code gained a dependency

## Public API

<!-- Quartz is published to Maven Central and used by other apps. -->

- [ ] No public API change
- [ ] Public API changed: CLIENT.md / RELAY.md / `agent-skill/quartz/` updated
- [ ] New event kind registered in its module's `EventKindProvider`

## AI assistance

- [ ] Drafted with AI assistance, manually reviewed and tested
- [ ] Written by hand

If "Drafted with AI assistance" is ticked, a human must have reviewed the
whole diff and tested the change by hand before this PR was opened. See
[`CONTRIBUTING-WITH-AI.md`](CONTRIBUTING-WITH-AI.md).

## License

- [ ] By submitting this PR, I agree to license my contribution under the
      MIT license. Any code I did not author personally carries its
      original license header.
