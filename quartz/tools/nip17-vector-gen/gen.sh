#!/usr/bin/env bash
#
# Regenerates quartz/src/commonTest/resources/nip17/ with nostr-tools.
#
#   quartz/tools/nip17-vector-gen/gen.sh
#
# Steps: (1) dump the wraps Quartz's NIP-17 path makes (Nip17OursDump, a jvmTest that only writes
# when NIP17_VECTORS_DUMP is set), (2) run gen.mjs, which writes nostr-tools.json and
# ours-read-by-nostr-tools.json, (3) run the NIP-17 vector tests. Env: GRADLE_ARGS (extra Gradle flags).
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd -- "$HERE/../../.." && pwd)"
OUT="$ROOT/quartz/src/commonTest/resources/nip17"
DUMP="$ROOT/quartz/build/nip17-vectors/ours.wraps.json"

cd "$ROOT"
# shellcheck disable=SC2086
NIP17_VECTORS_DUMP="$DUMP" ./gradlew ${GRADLE_ARGS:-} :quartz:jvmTest \
  --tests 'com.vitorpamplona.quartz.nip17Dm.interop.Nip17OursDump' --rerun
[[ -s "$DUMP" ]] || { echo "no dump at $DUMP" >&2; exit 1; }
(cd "$HERE" && { [[ -d node_modules/nostr-tools ]] || npm ci --no-audit --no-fund; })
mkdir -p "$OUT"
(cd "$HERE" && OURS_IN="$DUMP" VECTORS_OUT="$OUT" node gen.mjs)
# shellcheck disable=SC2086
./gradlew ${GRADLE_ARGS:-} :quartz:jvmTest --tests 'com.vitorpamplona.quartz.nip17Dm.interop.*'
