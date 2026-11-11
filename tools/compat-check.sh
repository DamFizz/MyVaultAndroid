#!/usr/bin/env bash
# Full web <-> Kotlin vault compatibility check (Git Bash on Windows).
#   bash tools/compat-check.sh            # verify both directions
#   bash tools/compat-check.sh --regen    # also regenerate the web fixture first
set -euo pipefail
cd "$(dirname "$0")/.."
WEB="${MYVAULT_WEB_ROOT:-../DAMMYVAULT}"
VITEST="node $WEB/node_modules/vitest/vitest.mjs run --config tools/webfixture/vitest.config.mjs"
export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Android/Android Studio/jbr}"

if [[ "${1:-}" == "--regen" ]]; then
  echo "== web: generate fixtures/web-vault.json"
  $VITEST gen-web
fi
echo "== kotlin: unit + web->kotlin tests (also writes out/kotlin-vault.json)"
./gradlew :core:test --rerun-tasks --console=plain -q
echo "== web: verify kotlin->web"
$VITEST verify-kotlin
echo "OK: vaults are compatible in both directions"
