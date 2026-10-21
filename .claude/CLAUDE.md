@../AGENTS.md

# Claude Code specifics

Every project rule lives in the root `AGENTS.md`, imported above, so Claude Code and
Codex follow the same guidance. Add new rules there, not here; this file only
holds what is specific to Claude Code.

- **Hooks** (`.claude/settings.json`): before any `git push` or PR tool call, the
  shared scripts in `tools/agent-hooks/` run spotless and the agent-config check, and
  block the call on failure. A Stop hook runs `./gradlew spotlessApply` when Kotlin
  files changed. `session-start.sh` sets up proxy, SSL, the Android SDK and the
  Kotlin/Native toolchain on Claude Code on the web.
- **Skills** load from `.claude/skills/`; each one is also invocable as `/<name>`.
