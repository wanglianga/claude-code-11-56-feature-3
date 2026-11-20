#!/usr/bin/env bash
# =====================================================================
# End-to-end verification of the fault-reference mapping service.
#
# Usage:
#   ./scripts/verify.sh                  # against http://127.0.0.1:8080
#   BASE_URL=http://host:8080 ./scripts/verify.sh
#
# Requires: curl, jq. Exits non-zero on the first failed expectation.
# The demo seed must be enabled (APP_DEMO_ENABLED=true) so fixtures exist.
# =====================================================================
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
VIEWER="viewer:viewer123"
ENGINEER="engineer:engineer123"
ADMIN="admin:admin123"

pass=0
fail=0

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok()   { printf '  \033[32mPASS\033[0m %s\n' "$*"; pass=$((pass+1)); }
bad()  { printf '  \033[31mFAIL\033[0m %s\n' "$*"; fail=$((fail+1)); }

get()  { curl -sS -u "$VIEWER" "$BASE_URL$1"; }
post() { curl -sS -u "$2" -X POST "$BASE_URL$1" -H 'Content-Type: application/json' -d "$3"; }

expect_eq() { # label actual expected
  if [ "$2" = "$3" ]; then ok "$1 ($2)"; else bad "$1 — expected '$3', got '$2'"; fi
}
expect_true() { # label condition
  if [ "$2" = "true" ]; then ok "$1"; else bad "$1"; fi
}

command -v jq >/dev/null || { echo "jq is required"; exit 1; }

# ---------------------------------------------------------------------
say "0. Health and OpenAPI"
health=$(get /actuator/health | jq -r '.status')
expect_eq "actuator health is UP" "$health" "UP"
openapi=$(curl -sS "$BASE_URL/v3/api-docs" | jq -r '.openapi')
expect_true "OpenAPI 3 document exposed ($openapi)" "$(case "$openapi" in 3.*) echo true;; *) echo false;; esac)"
swagger=$(curl -sSL -o /dev/null -w '%{http_code}' "$BASE_URL/swagger-ui.html")
expect_true "Swagger UI served (HTTP $swagger)" "$([ "$swagger" = "200" ] && echo true || echo false)"

# ---------------------------------------------------------------------
say "1. Authentication and roles"
code=$(curl -sS -o /dev/null -w '%{http_code}' "$BASE_URL/api/reference/units")
expect_eq "anonymous read is rejected" "$code" "401"
code=$(curl -sS -o /dev/null -w '%{http_code}' -u "$VIEWER" -X POST "$BASE_URL/api/reference/manufacturers" \
        -H 'Content-Type: application/json' -d '{"code":"X","name":"X"}')
expect_eq "viewer cannot write" "$code" "403"

# ---------------------------------------------------------------------
say "2. Two turbine types and firmware timeline are present"
units=$(get /api/reference/units | jq -r '[.[].manufacturerCode] | unique | join(",")')
expect_eq "alarm sources cover both manufacturers" "$units" "HAIXIN,NORDWIND"
fw=$(get /api/reference/firmware | jq -r '[.[] | select(.modelCode=="NW-8") | .version] | join(",")')
expect_eq "NW-8 firmware timeline" "$fw" "V1.0,V2.0,V2.1"

# run_containing_alarm <alarmExternalId> [mode] -> prints a run id and its full body on stderr
run_containing_alarm() {
  local alarm="$1" mode="${2:-}" id body
  for id in $(get '/api/diagnosis/runs?limit=100' | jq -r '.[].id'); do
    body=$(get "/api/diagnosis/runs/$id")
    if [ -n "$mode" ] && [ "$(echo "$body" | jq -r .mode)" != "$mode" ]; then continue; fi
    if [ "$(echo "$body" | jq -r --arg a "$alarm" 'any(.results[]; .alarmExternalId==$a)')" = "true" ]; then
      echo "$id"; return 0
    fi
  done
  return 1
}

# ---------------------------------------------------------------------
say "3. Same source code, different meaning across firmware/model"
latest=$(run_containing_alarm ALT-NW-A01-20250410 ASSERTION)
run=$(get "/api/diagnosis/runs/$latest")
new=$(echo "$run" | jq -r '[.results[] | select(.alarmExternalId=="ALT-NW-A01-20250410")][0].primaryConceptCode')
old=$(echo "$run"  | jq -r '[.results[] | select(.alarmExternalId=="ALT-NW-A01-20240115")][0].primaryConceptCode')
expect_eq "HYD-201 on V2.1 resolves to pump wear" "$new" "HYD_PUMP_WEAR"
expect_eq "HYD-201 on V1.0 (same code) resolves to low pressure" "$old" "HYD_LOW_PRESSURE"

# ---------------------------------------------------------------------
say "4. Offline backfill is interpreted at occurrence time"
bf=$(echo "$run" | jq -c '[.results[] | select(.alarmExternalId=="ALT-NW-A01-20240115")][0]')
expect_eq "backfilled alarm keeps OFFLINE_BACKFILL ingest type" "$(echo "$bf" | jq -r .ingestType)" "OFFLINE_BACKFILL"
expect_eq "backfilled alarm is judged at its 2024 occurrence" "$(echo "$bf" | jq -r .occurredAt)" "2024-01-15T03:40:00Z"
expect_eq "backfilled alarm arrives much later" "$(echo "$bf" | jq -r .receivedAt)" "2025-04-12T10:00:00Z"

asof=$(get '/api/diagnosis/runs?limit=50' | jq -r '[.[] | select(.basis=="AS_OF")][0].id')
asof_concept=$(get "/api/diagnosis/runs/$asof" | jq -r '.results[0].primaryConceptCode')
expect_eq "AS_OF 2024 replay reproduces the historical meaning" "$asof_concept" "HYD_LOW_PRESSURE"

# ---------------------------------------------------------------------
say "5. Preserved conflicts are not silently collapsed"
conflicts=$(get /api/mappings/conflicts)
expect_true "a vendor-term conflict is preserved" \
  "$(echo "$conflicts" | jq -r 'any(.[]; .sourceKind=="TERM" and (.assertions|length)>=2)' )"
expect_true "a controller-code conflict is preserved" \
  "$(echo "$conflicts" | jq -r 'any(.[]; .sourceKind=="CODE" and (.assertions|length)>=2)' )"
hx=$(echo "$run" | jq -r '[.results[] | select(.alarmExternalId=="ALT-HX-B01-20250409-TERM")][0].status')
expect_eq "unadjudicated term on HX-6 stays CONFLICT" "$hx" "CONFLICT"
nw=$(echo "$run" | jq -r '[.results[] | select(.alarmExternalId=="ALT-NW-A02-20250408-TERM")][0].primaryConceptCode')
expect_eq "adjudicated term on NW-8 resolves via engineer decision" "$nw" "GEARBOX_VIBRATION_HIGH"

# ---------------------------------------------------------------------
say "6. History and definition are traceable"
hist=$(get "/api/mappings/terms/$(get /api/reference/terms | jq -r '[.[] | select(.termText=="振动超限")][0].id')/history")
expect_eq "term has two competing assertions on record" "$(echo "$hist" | jq '.assertions|length')" "2"
explain=$(get "/api/diagnosis/results/$(echo "$run" | jq -r '[.results[] | select(.alarmExternalId=="ALT-NW-A02-20250408-TERM")][0].id')/explain")
expect_eq "explain returns the raw source code" "$(echo "$explain" | jq -r .alarm.rawCode)" "振动超限"
expect_true "explain carries a definition and an approval reference" \
  "$(echo "$explain" | jq -r '(.findings[0].definitionText|length>0) and (.findings[0].mappingChain.mappingApprovalRef!=null)')"
expect_true "explain links the adjudication decision" \
  "$(echo "$explain" | jq -r '.findings[0].mappingChain.adjudicationRef != null')"

# ---------------------------------------------------------------------
say "7. Disposition boundaries (stock and mutual exclusion)"
pend=$(post /api/diagnosis/runs "$ENGINEER" \
  '{"mode":"ASSERTION","basis":"LATEST","catalogVersionLabel":"CAT-2025.02","inventorySnapshotLabel":"INV-2025-06-01","from":"2025-06-01T00:00:00Z","to":"2025-07-01T00:00:00Z"}' \
  | jq -r .id)
pend_status=$(get "/api/diagnosis/runs/$pend" | jq -r '[.results[] | select(.alarmExternalId=="ALT-NW-A01-20250615")][0].status')
expect_eq "two exclusive pumps in stock leave the conclusion pending" "$pend_status" "PENDING_ENGINEER"

# ---------------------------------------------------------------------
say "8. Catalog version is pinned per run"
snap=$(get '/api/diagnosis/runs?limit=50' | jq -r '[.[] | select(.mode=="SNAPSHOT")][0].id')
pinned=$(get "/api/diagnosis/runs/$snap" | jq -r .pinnedCatalogVersionLabel)
expect_eq "snapshot run pins its release catalog" "$pinned" "CAT-2025.01"
live_catalog=$(get "/api/diagnosis/runs/$latest" | jq -r .pinnedCatalogVersionLabel)
expect_eq "latest run pins the newest published catalog" "$live_catalog" "CAT-2025.02"

# ---------------------------------------------------------------------
say "9. Release freeze and deterministic hash"
rel=$(get /api/releases/REL-2025.01/content)
expect_eq "release is PUBLISHED" "$(echo "$rel" | jq -r .release.status)" "PUBLISHED"
expect_eq "release content hash is 64 hex chars" "$(echo "$rel" | jq -r '.release.content_hash|length')" "64"
frozen=$(echo "$rel" | jq -r '.codeEntries | length')
expect_true "release froze $frozen code mapping entries" "$([ "$frozen" -gt 0 ] && echo true || echo false)"

# ---------------------------------------------------------------------
say "10. Statistics expose the boundaries"
stats=$(get "/api/diagnosis/runs/$latest/statistics")
expect_true "statistics report withheld conflicts" \
  "$(echo "$stats" | jq -r '.boundaries.withheldConflict > 0')"
expect_true "statistics report backfilled alarms" \
  "$(echo "$stats" | jq -r '.boundaries.offlineBackfilled > 0')"
expect_true "statistics report the concept distribution" \
  "$(echo "$stats" | jq -r '.concepts | length > 0')"

# ---------------------------------------------------------------------
printf '\n================ RESULT ================\n'
printf '  passed: %d\n  failed: %d\n' "$pass" "$fail"
if [ "$fail" -ne 0 ]; then
  echo "VERIFICATION FAILED"
  exit 1
fi
echo "VERIFICATION PASSED"
