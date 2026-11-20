#!/usr/bin/env bash
# =====================================================================
# End-to-end verification of the spare-part substitution chain feature.
#
# Usage:
#   ./scripts/verify-substitution.sh
#   BASE_URL=http://host:8080 ./scripts/verify-substitution.sh
#
# Requires: curl, jq and a running service seeded by DemoDataSeeder
# (APP_DEMO_ENABLED=true). Covers the four acceptance boundaries from the
# task: retired model, missing temperature condition, substitution cycle
# and last-unit stock contention, plus the immutable historical advice.
# =====================================================================
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
VIEWER="viewer:viewer123"
ENGINEER="engineer:engineer123"

pass=0
fail=0

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok()   { printf '  \033[32mPASS\033[0m %s\n' "$*"; pass=$((pass+1)); }
bad()  { printf '  \033[31mFAIL\033[0m %s\n' "$*"; fail=$((fail+1)); }

get()  { curl -sS -u "$VIEWER" "$BASE_URL$1"; }
post() { curl -sS -u "$2" -X POST "$BASE_URL$1" -H 'Content-Type: application/json' -d "$3"; }

advice() { # inventoryLabel alarmExternalId
  post /api/substitutions/advice "$ENGINEER" "{
    \"conceptCode\":\"GEARBOX_REPLACEMENT\",
    \"alarmExternalId\":\"$2\",
    \"catalogVersionLabel\":\"CAT-2025.02\",
    \"inventorySnapshotLabel\":\"$1\"
  }"
}

command -v jq >/dev/null || { echo "jq is required"; exit 1; }

# ---------------------------------------------------------------------
say "0. Health"
[ "$(get /actuator/health | jq -r .status)" = "UP" ] && ok "service is UP" || bad "service not UP"

# ---------------------------------------------------------------------
say "1. Retired gearbox model: obsolete fact + two different alternatives"
cold=$(advice INV-2025-06-01 ALT-NW-A01-20250620-GBX)
old=$(echo "$cold" | jq '.currentAdvice[] | select(.partNumber=="GBX-100")')
[ "$(echo "$old" | jq -r .lifecycleState)" = "OBSOLETE" ] \
  && ok "GBX-100 is explicitly OBSOLETE in the current catalog" || bad "GBX-100 not OBSOLETE"
[ "$(echo "$old" | jq -r '.paths[] | select(.terminalPartNumber=="GBX-200") | .pathStatus')" = "EXECUTABLE" ] \
  && ok "low-temperature GBX-200 is executable for the cold-climate alarm" || bad "GBX-200 not executable"
[ "$(echo "$old" | jq -r '.paths[] | select(.terminalPartNumber=="GBX-300") | .pathStatus')" = "PENDING_CONFIRM" ] \
  && ok "retrofit GBX-300 stays pending engineering confirmation" || bad "GBX-300 not pending"
[ "$(echo "$cold" | jq '[.cycleConflicts[] | select(.pathStatus=="CYCLE")] | length')" -ge 1 ] \
  && ok "the GBX-400/GBX-500 substitution cycle is returned as a conflict path" || bad "cycle conflict missing"
echo "$cold" | jq -e '.cycleConflicts[0].cyclePath == ["GBX-400","GBX-500","GBX-400"]' >/dev/null \
  && ok "cycle path shows the exact conflicting walk" || bad "cycle path content wrong"

# ---------------------------------------------------------------------
say "2. Temperature boundaries (warm fails, missing is an explicit boundary)"
warm=$(advice INV-2025-06-01 ALT-NW-A02-20250621-GBX)
echo "$warm" | jq -e '.currentAdvice[0].paths[] | select(.terminalPartNumber=="GBX-200")
  | .edgeChecks[0].failedChecks[] | select(startswith("TEMP_ABOVE_MAXIMUM"))' >/dev/null \
  && ok "warm environment fails the low-temperature edge with TEMP_ABOVE_MAXIMUM" || bad "warm edge not failed"
missing=$(advice INV-2025-06-01 ALT-NW-A02-20250622-GBX)
echo "$missing" | jq -e '.currentAdvice[0].paths[] | select(.terminalPartNumber=="GBX-200")
  | .edgeChecks[0].failedChecks[] | . == "AMBIENT_TEMP_MISSING"' >/dev/null \
  && ok "missing temperature fails with AMBIENT_TEMP_MISSING (no silent guess)" || bad "missing-temp edge wrong"
echo "$missing" | jq -e '.notSubstitutableReasons[] | select(contains("Ambient temperature is missing"))' >/dev/null \
  && ok "missing temperature is also surfaced as a not-substitutable boundary" || bad "missing-temp boundary absent"

# ---------------------------------------------------------------------
say "3. Stock contention: diagnosis flags, the warehouse decides"
may=$(advice INV-2025-05-10 ALT-NW-A01-20250620-GBX)
[ "$(echo "$may" | jq -r '.currentAdvice[0].paths[] | select(.terminalPartNumber=="GBX-600") | .pathStatus')" = "PENDING_CONFIRM" ] \
  && ok "CLAIM_PENDING only flags GBX-600 advice as possibly invalid" || bad "CLAIM_PENDING handling wrong"
june=$(advice INV-2025-06-01 ALT-NW-A01-20250620-GBX)
[ "$(echo "$june" | jq -r '.currentAdvice[0].paths[] | select(.terminalPartNumber=="GBX-600") | .pathStatus')" = "BLOCKED" ] \
  && ok "warehouse CONFIRMED_ALLOCATED blocks the GBX-600 suggestion" || bad "confirmed allocation not blocking"
echo "$june" | jq -e '.currentAdvice[0].paths[] | select(.terminalPartNumber=="GBX-600")
  | .blockedReasons[] | select(contains("competing demand"))' >/dev/null \
  && ok "blocked reason cites the warehouse allocation rather than a diagnosis decision" || bad "blocked reason wrong"

# ---------------------------------------------------------------------
say "4. Historical work order keeps its own recommendation; no backfill"
hist=$(post /api/substitutions/advice "$ENGINEER" '{
  "conceptCode":"GEARBOX_REPLACEMENT",
  "workOrderExternalId":"WO-2023-0510-NW",
  "unitCode":"NW-A01",
  "ambientTempC":-25,
  "interfaceCode":"IF-A",
  "occurredAt":"2023-05-10T00:00:00Z",
  "catalogVersionLabel":"CAT-2025.02",
  "inventorySnapshotLabel":"INV-2025-06-01"
}')
[ "$(echo "$hist" | jq -r .originalRecommendation.partNumber)" = "GBX-100" ] \
  && [ "$(echo "$hist" | jq -r .originalRecommendation.catalogVersionLabel)" = "CAT-2023.01" ] \
  && ok "historical work order keeps its 2023 GBX-100 recommendation under CAT-2023.01" || bad "historical advice changed"
[ "$(echo "$hist" | jq -r .originalRecommendation.recommendationStatus)" = "ISSUED" ] \
  && ok "the recorded ISSUED status is preserved immutably" || bad "recommendation status changed"
# Current alternatives are a separate object, never written back into the history.
[ "$(echo "$hist" | jq -r '.currentAdvice[0].paths[] | select(.pathStatus=="EXECUTABLE") | .terminalPartNumber')" != "GBX-100" ] \
  && ok "current executable alternatives are kept separate from the historical recommendation" || bad "backfill suspected"
wo_id=$(get /api/evidence/work-orders | jq -r '.[] | select(.external_id=="WO-2023-0510-NW").id')
[ "$(get /api/evidence/work-orders/$wo_id | jq -r '.partRecommendations[0].partNumber')" = "GBX-100" ] \
  && ok "work order detail exposes the linked historical recommendation" || bad "work order link missing"

# ---------------------------------------------------------------------
say "5. Release freeze carries the substitution graph"
rel=$(get /api/releases/REL-2025.02/content)
[ "$(echo "$rel" | jq '.substitutions | length')" -ge 6 ] \
  && ok "release freezes the substitution edges" || bad "release missing substitution edges"
[ "$(echo "$rel" | jq -r '.partLifecycle[] | select(.part_number=="GBX-100") | .state')" = "OBSOLETE" ] \
  && ok "release freezes the GBX-100 retirement fact" || bad "release missing lifecycle"
[ "$(echo "$rel" | jq '.stockContentions | length')" -ge 1 ] \
  && ok "release freezes warehouse contention facts" || bad "release missing contentions"

# ---------------------------------------------------------------------
say "6. Stored diagnosis result exposes part advice against its pinned snapshot"
run=$(get '/api/diagnosis/runs?limit=50' | jq -r '[.[] | select(.basis=="LATEST")][0].id')
result=$(get "/api/diagnosis/runs/$run" | jq -r '[.results[] | select(.alarmExternalId=="ALT-NW-A01-20250620-GBX")][0].id')
pa=$(get "/api/substitutions/diagnosis/results/$result/part-advice")
[ "$(echo "$pa" | jq -r '.[0].catalogVersionLabel')" = "CAT-2025.02" ] \
  && ok "part advice reuses the run-pinned catalog context" || bad "part advice context wrong"
[ "$(echo "$pa" | jq -r '.[0].currentAdvice[0].lifecycleState')" = "OBSOLETE" ] \
  && ok "part advice reproduces the obsolete/alternative analysis on the stored result" || bad "part advice content wrong"

# ---------------------------------------------------------------------
printf '\n================ RESULT ================\n'
printf '  passed: %d\n  failed: %d\n' "$pass" "$fail"
if [ "$fail" -ne 0 ]; then
  echo "SUBSTITUTION VERIFICATION FAILED"
  exit 1
fi
echo "SUBSTITUTION VERIFICATION PASSED"
