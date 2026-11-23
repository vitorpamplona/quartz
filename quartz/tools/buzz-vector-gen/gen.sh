#!/usr/bin/env bash
#
# Regenerates quartz/src/commonTest/resources/buzz/vectors/ against block/buzz's relay (Apache-2.0).
#
#   BUZZ_RELAY_BIN=/path/to/buzz-relay [BUZZ_SRC=/path/to/buzz] quartz/tools/buzz-vector-gen/gen.sh
#
# BUZZ_RELAY_BIN is a buzz-relay binary (`cargo build --profile ci -p buzz-relay` in a block/buzz
# checkout; BUZZ_SRC, when given, names the commit in the vectors). Needs docker compose (Postgres,
# Redis and MinIO from compose.yml, tmpfs, loopback ports PORT+10..12), node >= 20 and npm.
# Steps: (1) dump the templates Quartz's builders write (BuzzOursDump, a jvmTest that only writes
# when BUZZ_VECTORS_DUMP is set), (2) boot the relay with a fixed signing key and the fixture's
# owner as RELAY_OWNER_PUBKEY, (3) run gen.mjs, which sends our templates, drives the scenario and
# writes buzz.json and ours-read-by-buzz.json, (4) run the Buzz vector tests.
# Env: PORT (default 7600), GRADLE_ARGS (extra Gradle flags), SKIP_DUMP=1 (reuse the last dump).
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd -- "$HERE/../../.." && pwd)"
OUT="$ROOT/quartz/src/commonTest/resources/buzz/vectors"
DUMP="$ROOT/quartz/build/buzz-vectors/ours.templates.json"
WORK="$ROOT/quartz/build/buzz-vectors"
PORT="${PORT:-7600}"
PROJECT=quartz-buzz-vectors
: "${BUZZ_RELAY_BIN:?set BUZZ_RELAY_BIN to a buzz-relay binary}"

cd "$ROOT"
if [[ "${SKIP_DUMP:-0}" != 1 ]]; then
  # shellcheck disable=SC2086
  BUZZ_VECTORS_DUMP="$DUMP" ./gradlew ${GRADLE_ARGS:-} :quartz:jvmTest \
    --tests 'com.vitorpamplona.quartz.buzz.vectors.BuzzOursDump' --rerun
fi
[[ -s "$DUMP" ]] || { echo "no dump at $DUMP" >&2; exit 1; }

export BUZZ_PG_PORT=$((PORT + 10)) BUZZ_REDIS_PORT=$((PORT + 11)) BUZZ_MINIO_PORT=$((PORT + 12))
RELAY_PID=""
cleanup() {
  [[ -n "$RELAY_PID" ]] && kill "$RELAY_PID" 2>/dev/null
  docker compose -p "$PROJECT" -f "$HERE/compose.yml" down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT
docker compose -p "$PROJECT" -f "$HERE/compose.yml" down -v >/dev/null 2>&1 || true
docker compose -p "$PROJECT" -f "$HERE/compose.yml" up -d --wait postgres redis minio
docker compose -p "$PROJECT" -f "$HERE/compose.yml" run --rm minio-init

mkdir -p "$WORK/relay"
OWNER=$(cd "$HERE" && node -e "import('nostr-tools/pure').then(m => console.log(m.getPublicKey(Uint8Array.from(Buffer.from('11'.repeat(32), 'hex')))))")
(cd "$WORK/relay" && env DATABASE_URL="postgres://buzz:buzz_dev@127.0.0.1:$BUZZ_PG_PORT/buzz" \
  REDIS_URL="redis://127.0.0.1:$BUZZ_REDIS_PORT" RELAY_URL="ws://127.0.0.1:$PORT" \
  BUZZ_BIND_ADDR="127.0.0.1:$PORT" BUZZ_HEALTH_PORT=$((PORT + 1)) BUZZ_METRICS_PORT=$((PORT + 2)) \
  BUZZ_RELAY_PRIVATE_KEY="$(printf '77%.0s' $(seq 32))" \
  BUZZ_S3_ENDPOINT="http://127.0.0.1:$BUZZ_MINIO_PORT" BUZZ_S3_ACCESS_KEY=buzz_dev \
  BUZZ_S3_SECRET_KEY=buzz_dev_secret BUZZ_S3_BUCKET=buzz-media BUZZ_REQUIRE_AUTH_TOKEN=false \
  BUZZ_AUTO_MIGRATE=true RELAY_OWNER_PUBKEY="$OWNER" RUST_LOG=warn \
  "$BUZZ_RELAY_BIN" >"$WORK/relay.log" 2>&1) &
RELAY_PID=$!
for _ in $(seq 1 120); do curl -sf -m 1 "http://127.0.0.1:$PORT/health" >/dev/null 2>&1 && break; sleep 0.5; done
curl -sf -m 1 "http://127.0.0.1:$PORT/health" >/dev/null || { echo "buzz-relay did not come up (see $WORK/relay.log)" >&2; exit 1; }

REV="$( [[ -n "${BUZZ_SRC:-}" ]] && git -C "$BUZZ_SRC" rev-parse --short=9 HEAD 2>/dev/null || echo unknown)"
mkdir -p "$OUT"
(cd "$HERE" && { [[ -d node_modules/nostr-tools ]] || npm ci --no-audit --no-fund; })
(cd "$HERE" && RELAY="ws://127.0.0.1:$PORT" OURS_IN="$DUMP" VECTORS_OUT="$OUT" BUZZ_REV="$REV" node gen.mjs)
# shellcheck disable=SC2086
./gradlew ${GRADLE_ARGS:-} :quartz:jvmTest --tests 'com.vitorpamplona.quartz.buzz.vectors.*'
