#!/usr/bin/env bash
set -euo pipefail

# Creates all demo feature flags in Datadog via the Feature Flags API.
# Requires DD_API_KEY and DD_APP_KEY environment variables.
# Usage: export $(grep -v '^#' .env | xargs) && ./scripts/create-flags.sh

: "${DD_API_KEY:?Set DD_API_KEY}"
: "${DD_APP_KEY:?Set DD_APP_KEY}"
DD_SITE="${DD_SITE:-datadoghq.com}"
BASE="https://api.${DD_SITE}/api/v2/feature-flags"

HEADERS=(
  -H "DD-API-KEY: ${DD_API_KEY}"
  -H "DD-APPLICATION-KEY: ${DD_APP_KEY}"
  -H "Content-Type: application/json"
  -H "Accept: application/json"
)

create_flag() {
  local name="$1" payload="$2"
  echo "Creating flag: ${name}"
  resp=$(curl -s -w "\n%{http_code}" -X POST "${BASE}" "${HEADERS[@]}" -d "${payload}")
  code=$(echo "$resp" | tail -1)
  body=$(echo "$resp" | sed '$d')
  if [[ "$code" == "201" || "$code" == "200" ]]; then
    echo "  ✓ ${name} created"
  elif [[ "$code" == "409" ]]; then
    echo "  ⊘ ${name} already exists — skipping"
  else
    echo "  ✗ ${name} failed (HTTP ${code})"
    echo "    ${body}" | head -3
  fi
}

# ── bright_mode (Boolean) — Q1 theme toggle ──────────────────────────
create_flag "bright_mode" '{
  "data": {
    "type": "feature-flags",
    "attributes": {
      "key": "bright_mode",
      "name": "Bright Mode",
      "description": "Toggle bright/dark theme — demonstrates no-restart updates (Q1)",
      "value_type": "BOOLEAN",
      "default_variant_key": "off",
      "variants": [
        { "key": "on",  "name": "On",  "value": "true" },
        { "key": "off", "name": "Off", "value": "false" }
      ]
    }
  }
}'

# ── new_checkout (Boolean) — Q2 stickiness ───────────────────────────
create_flag "new_checkout" '{
  "data": {
    "type": "feature-flags",
    "attributes": {
      "key": "new_checkout",
      "name": "New Checkout Flow",
      "description": "Enable new checkout — demonstrates stickiness via targetingKey (Q2)",
      "value_type": "BOOLEAN",
      "default_variant_key": "disabled",
      "variants": [
        { "key": "enabled",  "name": "Enabled",  "value": "true" },
        { "key": "disabled", "name": "Disabled", "value": "false" }
      ]
    }
  }
}'

# ── feature_a (Boolean) — Q3 chain part 1 ────────────────────────────
create_flag "feature_a" '{
  "data": {
    "type": "feature-flags",
    "attributes": {
      "key": "feature_a",
      "name": "Feature A",
      "description": "First flag in the chain — must be ON for feature_b to evaluate (Q3)",
      "value_type": "BOOLEAN",
      "default_variant_key": "off",
      "variants": [
        { "key": "on",  "name": "On",  "value": "true" },
        { "key": "off", "name": "Off", "value": "false" }
      ]
    }
  }
}'

# ── feature_b (Boolean) — Q3 chain part 2 ────────────────────────────
create_flag "feature_b" '{
  "data": {
    "type": "feature-flags",
    "attributes": {
      "key": "feature_b",
      "name": "Feature B",
      "description": "Second flag in the chain — only evaluated when feature_a=ON (Q3)",
      "value_type": "BOOLEAN",
      "default_variant_key": "off",
      "variants": [
        { "key": "on",  "name": "On",  "value": "true" },
        { "key": "off", "name": "Off", "value": "false" }
      ]
    }
  }
}'

# ── bangkok_promo (Boolean) — Q4 geo targeting ───────────────────────
create_flag "bangkok_promo" '{
  "data": {
    "type": "feature-flags",
    "attributes": {
      "key": "bangkok_promo",
      "name": "Bangkok Promo",
      "description": "Geographic targeting — ON for city=Bangkok (Q4)",
      "value_type": "BOOLEAN",
      "default_variant_key": "off",
      "variants": [
        { "key": "on",  "name": "On",  "value": "true" },
        { "key": "off", "name": "Off", "value": "false" }
      ]
    }
  }
}'

# ── checkout_variant (String) — used in Q5 audit demo ─────────────────
create_flag "checkout_variant" '{
  "data": {
    "type": "feature-flags",
    "attributes": {
      "key": "checkout_variant",
      "name": "Checkout Variant",
      "description": "String flag with three variants — used in Q5 audit demo",
      "value_type": "STRING",
      "default_variant_key": "control",
      "variants": [
        { "key": "control",     "name": "Control",     "value": "control" },
        { "key": "treatment_a", "name": "Treatment A", "value": "treatment_a" },
        { "key": "treatment_b", "name": "Treatment B", "value": "treatment_b" }
      ]
    }
  }
}'

# ── max_cart_items (Number) — Q7 number flag ──────────────────────────
create_flag "max_cart_items" '{
  "data": {
    "type": "feature-flags",
    "attributes": {
      "key": "max_cart_items",
      "name": "Max Cart Items",
      "description": "Server-side number flag independent of RUM sample rate (Q7)",
      "value_type": "NUMERIC",
      "default_variant_key": "default",
      "variants": [
        { "key": "default", "name": "Default (20)", "value": "20" },
        { "key": "high",    "name": "High (50)",    "value": "50" }
      ]
    }
  }
}'

echo ""
echo "Done! All demo flags created. Configure targeting rules in the Datadog UI."
