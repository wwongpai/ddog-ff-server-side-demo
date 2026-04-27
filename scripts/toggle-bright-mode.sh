#!/usr/bin/env bash
set -euo pipefail

# Quick toggle for the bright_mode flag during live demos.
# Looks up the flag by key, then updates default_variant_id for the target environment.
#
# Usage:
#   export $(grep -v '^#' .env | xargs)
#   ./scripts/toggle-bright-mode.sh on
#   ./scripts/toggle-bright-mode.sh off

: "${DD_API_KEY:?Set DD_API_KEY}"
: "${DD_APP_KEY:?Set DD_APP_KEY}"
DD_SITE="${DD_SITE:-datadoghq.com}"
API="https://api.${DD_SITE}/api/v2"

HEADERS=(
  -H "DD-API-KEY: ${DD_API_KEY}"
  -H "DD-APPLICATION-KEY: ${DD_APP_KEY}"
  -H "Content-Type: application/json"
  -H "Accept: application/json"
)

STATE="${1:-}"
if [[ -z "$STATE" ]]; then
  echo "Usage: $0 <on|off>"
  echo "  on  — set default variant to 'on'  (bright mode enabled)"
  echo "  off — set default variant to 'off' (bright mode disabled)"
  exit 1
fi

case "$STATE" in
  on)  TARGET_VARIANT_KEY="on" ;;
  off) TARGET_VARIANT_KEY="off" ;;
  *)   echo "Invalid state: ${STATE}. Use 'on' or 'off'."; exit 1 ;;
esac

echo "Looking up bright_mode flag..."
flag_resp=$(curl -s "${API}/feature-flags?filter[key]=bright_mode" "${HEADERS[@]}")

flag_id=$(echo "$flag_resp" | python3 -c "
import sys, json
data = json.load(sys.stdin)
flags = data.get('data', [])
for f in flags:
    if f.get('attributes', {}).get('key') == 'bright_mode':
        print(f['id'])
        break
" 2>/dev/null || true)

if [[ -z "$flag_id" ]]; then
  echo "✗ Could not find bright_mode flag. Create it first with ./scripts/create-flags.sh"
  echo "  API response: $(echo "$flag_resp" | head -c 200)"
  exit 1
fi
echo "  Found flag ID: ${flag_id}"

echo "Looking up variants..."
variant_id=$(echo "$flag_resp" | python3 -c "
import sys, json
data = json.load(sys.stdin)
target = '${TARGET_VARIANT_KEY}'
for f in data.get('data', []):
    if f.get('attributes', {}).get('key') == 'bright_mode':
        for v in f['attributes'].get('variants', []):
            if v.get('key') == target:
                print(v['id'])
                break
        break
" 2>/dev/null || true)

if [[ -z "$variant_id" ]]; then
  echo "✗ Could not find variant '${TARGET_VARIANT_KEY}'"
  exit 1
fi
echo "  Target variant '${TARGET_VARIANT_KEY}' ID: ${variant_id}"

echo "Looking up environment..."
env_id=$(echo "$flag_resp" | python3 -c "
import sys, json
data = json.load(sys.stdin)
for f in data.get('data', []):
    if f.get('attributes', {}).get('key') == 'bright_mode':
        envs = f['attributes'].get('feature_flag_environments', [])
        if envs:
            print(envs[0]['environment_id'])
        break
" 2>/dev/null || true)

if [[ -z "$env_id" ]]; then
  echo "✗ No environment found for bright_mode. Enable the flag in an environment in the Datadog UI first."
  exit 1
fi
echo "  Environment ID: ${env_id}"

echo "Setting default variant → ${TARGET_VARIANT_KEY}..."
update_resp=$(curl -s -w "\n%{http_code}" -X PUT \
  "${API}/feature-flags/${flag_id}/environments/${env_id}" \
  "${HEADERS[@]}" \
  -d "{
    \"data\": {
      \"type\": \"feature-flag-environments\",
      \"attributes\": {
        \"default_variant_id\": \"${variant_id}\",
        \"status\": \"ENABLED\"
      }
    }
  }")

code=$(echo "$update_resp" | tail -1)
if [[ "$code" == "200" || "$code" == "201" ]]; then
  echo "✓ bright_mode is now '${TARGET_VARIANT_KEY}'"
  echo ""
  echo "Test it:"
  echo "  curl http://localhost:8080/api/health   # Java"
  echo "  curl http://localhost:3000/api/health   # Node.js"
else
  body=$(echo "$update_resp" | sed '$d')
  echo "✗ Failed (HTTP ${code})"
  echo "${body}" | head -5
  echo ""
  echo "You can also toggle directly in the Datadog Feature Flags UI."
fi
