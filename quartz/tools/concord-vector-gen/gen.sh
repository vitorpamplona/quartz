#!/usr/bin/env bash
#
# Regenerates quartz/src/commonTest/resources/concord/app-events/ against Armada.
#
#   ARMADA_DIR=/path/to/armada quartz/tools/concord-vector-gen/gen.sh
#
# ARMADA_DIR is an Armada checkout with `npm ci` done. Armada is AGPL-3.0: nothing of it is
# copied here. This script copies appEvents.vectors.test.tsx INTO that checkout (so its `@/…`
# imports resolve), runs it with Armada's vitest, and removes it again.
#
# Steps: (1) dump the rumors Quartz's builders produce (ConcordAppEventOursDump, a jvmTest that
# only writes when CONCORD_VECTORS_DUMP is set), (2) run the generator, which writes armada.json
# (Armada's rumors + what Armada decided) and ours-read-by-armada.json (our rumors + what Armada
# understood), (3) run the Concord tests, which check Quartz against both.
# Env: NODE_DIR (dir holding node >= 22; default: node on PATH), GRADLE_ARGS (extra Gradle flags).
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd -- "$HERE/../../.." && pwd)"
OUT="$ROOT/quartz/src/commonTest/resources/concord/app-events"
DUMP="$ROOT/quartz/build/concord-app-events/ours.rumors.json"
: "${ARMADA_DIR:?set ARMADA_DIR to an Armada checkout with npm ci done}"
[[ -n "${NODE_DIR:-}" ]] && export PATH="$NODE_DIR:$PATH"

cd "$ROOT"
# shellcheck disable=SC2086
CONCORD_VECTORS_DUMP="$DUMP" ./gradlew ${GRADLE_ARGS:-} :quartz:jvmTest \
  --tests 'com.vitorpamplona.quartz.concord.appEvents.ConcordAppEventOursDump' --rerun
[[ -s "$DUMP" ]] || { echo "no dump at $DUMP" >&2; exit 1; }

TARGET="$ARMADA_DIR/src/__quartz_vectors__"
mkdir -p "$TARGET"
trap 'rm -rf "$TARGET"' EXIT
cp "$HERE/appEvents.vectors.test.tsx" "$TARGET/appEvents.vectors.test.tsx"
REV="$(git -C "$ARMADA_DIR" rev-parse --short=8 HEAD 2>/dev/null || echo unknown)"
(cd "$ARMADA_DIR" && VECTORS_OUT="$OUT" OURS_IN="$DUMP" ARMADA_REV="$REV" \
  npx vitest run src/__quartz_vectors__/appEvents.vectors.test.tsx --reporter=dot)
echo "wrote $OUT/armada.json and $OUT/ours-read-by-armada.json"
