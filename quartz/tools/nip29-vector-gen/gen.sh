#!/usr/bin/env bash
#
# Regenerates quartz/src/commonTest/resources/nip29/ against fiatjaf/relay29 (MIT).
#
#   quartz/tools/nip29-vector-gen/gen.sh
#
# Needs Go >= 1.24 (modules come from the Go proxy; relay29 is pinned in go.mod). Steps: (1) dump
# the events Quartz's builders write (Nip29OursDump, a jvmTest that only writes when
# NIP29_VECTORS_DUMP is set), (2) run the generator, which boots relay29 in its own process on a
# loopback port and writes relay29.json and ours-read-by-relay29.json, (3) run the NIP-29 vector
# tests. Env: GRADLE_ARGS (extra Gradle flags).
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd -- "$HERE/../../.." && pwd)"
OUT="$ROOT/quartz/src/commonTest/resources/nip29"
DUMP="$ROOT/quartz/build/nip29-vectors/ours.events.json"

cd "$ROOT"
# shellcheck disable=SC2086
NIP29_VECTORS_DUMP="$DUMP" ./gradlew ${GRADLE_ARGS:-} :quartz:jvmTest \
  --tests 'com.vitorpamplona.quartz.nip29RelayGroups.interop.Nip29OursDump' --rerun
[[ -s "$DUMP" ]] || { echo "no dump at $DUMP" >&2; exit 1; }

REV="$(cd "$HERE" && go list -m -f '{{.Version}}' github.com/fiatjaf/relay29)"
mkdir -p "$OUT"
(cd "$HERE" && RELAY29_VERSION="$REV" go run . -out "$OUT" -ours "$DUMP")
echo "wrote $OUT/relay29.json and $OUT/ours-read-by-relay29.json"
# shellcheck disable=SC2086
./gradlew ${GRADLE_ARGS:-} :quartz:jvmTest --tests 'com.vitorpamplona.quartz.nip29RelayGroups.interop.*'
