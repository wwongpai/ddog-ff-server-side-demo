# Server-Side Datadog Feature Flags Demo

Two identical demo apps — **Java Spring Boot** and **Node.js (Express)** — showing server-side feature flag evaluation with the Datadog OpenFeature provider. Each endpoint maps to a specific Q&A question (Q1–Q8).

Both apps instrument flag evaluations for observability via:
- **APM span tags** (`ff.<flag_key>`) on every trace, enabling variant-level filtering in APM Trace Explorer
- **`feature_flag.evaluations` OTel counter metric** (Node.js with `dd-trace ≥ 5.99.0`), enabling metric-based dashboards

> **Custom Dashboard:** This demo includes a custom Datadog dashboard that combines both data sources to visualize flag variant distribution, evaluation counts, and cross-service comparisons. See [Observability & Dashboard Setup](#observability--dashboard-setup) below.

---

## How Feature Flags Work

### Key Terminology

| Term | Definition |
|---|---|
| **Flag key** | Unique string identifier for a flag (e.g., `"bright_mode"`). Passed into SDK calls and used in metrics as `feature_flag.key`. |
| **Variant** | The concrete value a flag returns for a given evaluation — boolean `true`/`false`, string `"on"`/`"off"`, number, or JSON. Recorded as `feature_flag.result.variant`. |
| **Evaluation** | One call to resolve a flag for a given subject + context (e.g., `client.getBooleanValue("my-flag", false, ctx)`). This is the atomic unit counted by `feature_flag.evaluations`. |
| **Evaluation context** | A bag of attributes describing the subject and environment at evaluation time — `user_id`, `org_id`, `city`, `device_type`, `service`, `env`, etc. Targeting rules match against these attributes. |
| **Targeting key** | A special string in the evaluation context that uniquely identifies the "subject" (user, device, org). Used for deterministic percentage rollouts so the same subject always gets the same variant. |
| **Randomization key** | The attribute fed into the hashing function for percentage-based bucketing. Defaults to `targetingKey` if not explicitly configured. `hash(randomizationKey, flagKey) → bucket → variant`. |
| **Targeting rules** | Configuration in the Feature Flags UI that decides who is eligible and what variant they see. Conditions are expressed over **evaluation context attributes** (e.g., `city == "Bangkok"`, `org_id IN [2,3]`). |
| **Allocation** | Internal unit combining targeting rules + exposure percentage + variant weights. A flag can have multiple allocations evaluated in order until one matches. |
| **Default value** | Fallback value provided in every SDK call. If evaluation fails (flag not found, provider error), the SDK returns this default. |

### Client-Side vs. Server-Side: Communication Direction

The fundamental difference between client-side and server-side feature flags is **where evaluation happens** and **how flag configuration flows**.

```
CLIENT-SIDE (Web / Mobile)                    SERVER-SIDE (Backend)
─────────────────────────────                 ──────────────────────────

  ┌──────────┐    context     ┌──────────┐     ┌──────────┐  poll RC   ┌──────────┐
  │  Browser  │──────────────►│ Datadog  │     │ Datadog  │◄──────────│ Datadog  │
  │  or       │               │  Edge    │     │  Agent   │           │ Backend  │
  │  Mobile   │◄──────────────│ (Fastly) │     │ (local)  │──────────►│ (Remote  │
  │  App      │   variants    │          │     │          │  config   │  Config) │
  └──────────┘                └──────────┘     └────┬─────┘           └──────────┘
                                                    │ push config
       SDK sends context                            ▼
       Edge evaluates                          ┌──────────┐
       Edge returns variants               │  Your App │
                                                    │ (tracer + │
                                                    │ OpenFeature│
                                                    │  provider) │
                                                    └──────────┘
                                                    App evaluates
                                                    locally in-memory
```

#### Client-side flow (browser / mobile)

1. **SDK builds evaluation context** — attributes like `targetingKey`, `city`, `device_type`, `language`
2. **SDK sends context to Datadog's edge** (Fastly Compute) along with the flag keys
3. **Edge evaluates** — loads cached flag configuration, applies targeting rules against context attributes, uses randomization key for percentage rollouts
4. **Edge returns only the evaluated variants** to the SDK — no flag configuration is exposed to the client
5. **Telemetry** — evaluations are logged as RUM events (`flagevaluation` EVP track), tied to RUM sessions
6. **Billing** — each SDK initialization / context change generates a Monthly Flag Configuration Request (MFCR); repeated evaluations within a session use cached config

#### Server-side flow (this demo)

1. **Datadog Agent polls Remote Config** for the org's flag configuration (default interval: 60s, configurable)
2. **Agent pushes flag rules to the tracer** — the OpenFeature provider keeps rules in local memory
3. **App builds evaluation context** — `targetingKey`, `org_id`, `plan`, `city`, etc.
4. **App evaluates locally in-process** — `client.getBooleanValue("my-flag", false, ctx)` resolves using cached rules, zero network latency per evaluation
5. **Telemetry** — aggregated `feature_flag.evaluations` OTel metrics + APM span tags are emitted for observability (no evaluation context is sent to Datadog per-call)
6. **Billing** — MFCRs are per configuration download; all local evaluations are effectively free

#### Key differences summarized

| Aspect | Client-Side | Server-Side |
|---|---|---|
| **Where evaluation happens** | Datadog's edge (Fastly) | In your app process (local memory) |
| **Config delivery** | Edge caches config; SDK fetches on init/context change | Agent polls Remote Config → pushes to tracer |
| **Network per evaluation** | Yes — SDK calls edge | No — fully local, zero latency |
| **Context sent to Datadog** | Yes — on every config fetch | No — only aggregated metrics/traces |
| **Telemetry** | RUM sessions + `flagevaluation` EVP track | APM span tags + `feature_flag.evaluations` OTel metric |
| **Session-level correlation** | Built-in — tied to RUM sessions | Not built-in — request/trace level only |
| **Flag config visibility** | Config stays at edge; only variants returned | Full config cached in-process |

### How Targeting Rules Use Context (Not the Randomization Key)

A common point of confusion: **targeting rules** and **randomization** are two separate steps in the evaluation pipeline.

```
Step 1: TARGETING RULES (filter)          Step 2: RANDOMIZATION (bucket)
─────────────────────────────────         ──────────────────────────────
Uses: evaluation context attributes       Uses: randomization key (default: targetingKey)

"IF city == Bangkok AND env == prod"  →   "Of matched traffic, 50% get variant A"
                                          hash(targetingKey, flagKey) → bucket
```

- **Targeting rules** (configured in the Feature Flags UI as "New targeting rule") evaluate conditions against **evaluation context attributes** like `city`, `org_id`, `env`, `plan`. These decide *which allocation matches*.
- **Randomization** uses the **randomization key** (defaults to `targetingKey`) to deterministically hash and assign the matched subject to a variant at the configured percentage. This decides *which variant within the matched allocation*.

Changing a non-randomization context attribute (e.g., `city`) does **not** change the variant unless a targeting rule references that attribute and causes the subject to match a different allocation.

---

## Prerequisites

| Requirement | Notes |
|---|---|
| Docker & Docker Compose | For running all services |
| Datadog account | With Feature Flags enabled |
| `DD_API_KEY` | From Datadog → Organization Settings → API Keys |
| `DD_APP_KEY` | From Datadog → Organization Settings → Application Keys |

## Quick Start

### 1. Configure environment

```bash
cp .env.example .env
# Edit .env with your real DD_API_KEY, DD_APP_KEY, DD_SITE
```

### 2. Create flags in Datadog

```bash
export $(grep -v '^#' .env | xargs)
./scripts/create-flags.sh
```

This creates all 7 flags: `bright_mode`, `new_checkout`, `feature_a`, `feature_b`, `bangkok_promo`, `checkout_variant`, `max_cart_items`.

### 3. Start all services

```bash
docker-compose up --build
```

This starts:
- **Datadog Agent** on port 8126 (APM) / 8125 (DogStatsD) / 4318 (OTLP)
- **Java app** on port **8080**
- **Node.js app** on port **3000**

### 4. Test endpoints

Use the curl examples below. Both apps expose the same endpoints — just swap the port:
- Java: `http://localhost:8080/api/...`
- Node.js: `http://localhost:3000/api/...`

---

## Server-Side Feature Flag Configuration

> Reference: [Datadog docs — Server-Side Feature Flags](https://docs.datadoghq.com/feature_flags/server#application-configuration)

Server-side feature flags require specific environment variables on the **application** (not the Agent):

```bash
# Enable Remote Configuration so the Agent can push flag updates
DD_REMOTE_CONFIG_ENABLED=true

# Enable the feature flagging provider (required for most SDKs)
DD_EXPERIMENTAL_FLAGGING_PROVIDER_ENABLED=true

# Enable flag evaluation metrics (required for flag evaluation tracking)
DD_METRICS_OTEL_ENABLED=true
```

| Variable | Purpose | Notes |
|---|---|---|
| `DD_REMOTE_CONFIG_ENABLED=true` | Allows the Agent to stream flag configuration changes to the app | Also set on the Agent side via `DD_REMOTE_CONFIGURATION_ENABLED=true` |
| `DD_EXPERIMENTAL_FLAGGING_PROVIDER_ENABLED=true` | Activates the Datadog OpenFeature provider inside the tracer | Java also supports `-Ddd.experimental.flagging.provider.enabled=true`. Node.js and Ruby support code-based config as an alternative. |
| `DD_METRICS_OTEL_ENABLED=true` | Emits `feature_flag.evaluations` OTel counter metric on each evaluation | Tagged with flag key, result variant, and evaluation reason. Without this, the SDK does not emit evaluation metrics. |

### What works today vs. what's experimental

| Capability | Status | Details |
|---|---|---|
| Flag evaluation via OpenFeature SDK | **GA** | Both Java and Node.js providers are stable |
| Real-time flag updates via Remote Config | **GA** | No restart required when flags change |
| APM span tags for flag evaluations | **Manual** | This demo manually tags spans with `ff.<flag_key>` for searchability in APM Trace Explorer. The built-in `_dd.feature_flags.*` tags are set by the provider but are not indexed for facet search by default. |
| `feature_flag.evaluations` OTel metric | **Experimental** | Requires `DD_METRICS_OTEL_ENABLED=true`. Available in `dd-trace-js ≥ 5.99.0`. Java support is pending a future `dd-java-agent` release. |
| Feature Flags UI — Real-Time Metric Overview | **Depends on OTel metric** | The dedicated Feature Flags UI panel relies on the `feature_flag.evaluations` metric. It populates automatically once the metric is flowing. |

---

## Observability & Dashboard Setup

This demo uses two complementary approaches to observe flag evaluations:

### 1. APM Span Tags (both Java and Node.js)

Every flag evaluation is tagged on the active APM span:

```
ff.bright_mode = "on"
ff.new_checkout = "enabled"
_dd.feature_flags.bright_mode.variant = "on"
```

The `ff.*` tags are custom tags added by the `trackFlag()` helper in both apps. They can be used as facets in APM Trace Explorer:

```
@ff.bright_mode:on service:ff-java-demo
```

### 2. OTel Metrics (Node.js only, currently)

With `DD_METRICS_OTEL_ENABLED=true`, the Node.js tracer (`dd-trace ≥ 5.99.0`) emits:

```
feature_flag.evaluations{
  flag_key: "bright_mode",
  variant: "on",
  reason: "TARGETING_MATCH"
}
```

This counter metric can be queried in Metrics Explorer and used in dashboard widgets.

### Custom Dashboard

Create a dashboard with widgets that combine both data sources:

| Widget Type | Data Source | Example Query |
|---|---|---|
| Timeseries (Node.js) | `feature_flag.evaluations` metric | `sum:feature_flag.evaluations{flag_key:bright_mode} by {variant}.as_count()` |
| Timeseries (Java) | APM span tag analytics | `count(*) by ff.bright_mode` on `trace-search` for `service:ff-java-demo` |
| Top List | Either source | Top variants by count for a given flag |
| Query Value | Either source | Total evaluations in a time window |

> **Tip:** Build separate widget groups per scenario (Q1, Q2, Q3, Q4) so you can compare variant distributions after each test.

---

## Flags Reference

| Flag Key | Type | Variants | Purpose |
|---|---|---|---|
| `bright_mode` | Boolean | on / off | Theme toggle (Q1) |
| `new_checkout` | Boolean | enabled / disabled | Stickiness demo (Q2) |
| `feature_a` | Boolean | on / off | Chain demo part 1 (Q3) |
| `feature_b` | Boolean | on / off | Chain demo part 2 (Q3) |
| `bangkok_promo` | Boolean | on / off | Geo targeting (Q4) |
| `max_cart_items` | Number | default (20) / high (50) | Number flag (Q7) |

---

## Endpoint Walkthrough (Q1–Q8)

### Q1: Do flag changes require a restart?

> **Short answer: No.** Flag updates are streamed in real time — your application never needs to restart.

**How it works:** The Datadog Agent runs alongside your app and maintains a persistent connection to Datadog's Remote Configuration service. When you change a flag's value in the Datadog UI (or via the API), the Agent receives the update within seconds and pushes it to the OpenFeature provider inside your application. Your app's next flag evaluation call immediately returns the new value — zero downtime, zero redeployment.

**Why this matters:** In traditional config-file approaches, changing a feature flag means editing a config, redeploying, and restarting the service. With server-side flags via Remote Config, you can safely enable/disable features in production while the app is running under load.

**Try it yourself:**

```bash
# 1. Check the current value (should be false by default)
curl http://localhost:8080/api/health

# 2. Toggle the flag ON — via script or in the Datadog Feature Flags UI
./scripts/toggle-bright-mode.sh on

# 3. Check again — value is now true, no restart happened
curl http://localhost:8080/api/health
```

The `/api/theme` endpoint returns richer details including the variant name and evaluation reason:

```bash
curl http://localhost:8080/api/theme
```

**Dashboard & expected results:**

| Step | Action | Expected `bright_mode` | Dashboard Signal |
|---|---|---|---|
| 1 | Initial curl (default) | `false` | `ff.bright_mode:off` span tag; `variant:off` in OTel metric |
| 2 | Toggle ON in UI | — | — |
| 3 | Curl after toggle | `true` | `ff.bright_mode:on` span tag; `variant:on` in OTel metric |
| 4 | Toggle OFF in UI | — | — |
| 5 | Curl after toggle | `false` | `ff.bright_mode:off` — variant distribution shifts visible in dashboard timeseries |

After running several curls with different flag states, the dashboard timeseries shows the variant distribution changing over time. APM Trace Explorer shows individual traces tagged with the variant received.

---

### Q2: How does stickiness work?

> **Short answer:** The `targetingKey` (typically the user ID) is hashed together with the flag key to deterministically assign a variant. The same user always gets the same result — no session storage, cookies, or databases needed.

**How it works — evaluation, context, and randomization:**

An **evaluation** is one call to resolve a flag for a given subject and context (e.g., `client.getBooleanValue("new_checkout", false, ctx)`). Each evaluation is the atomic unit tracked in telemetry (`feature_flag.evaluations` metric and APM span tags).

Before evaluating, your app builds an **evaluation context** — a bag of key-value attributes describing the subject and environment:

```json
{
  "targetingKey": "user-42",
  "plan": "premium",
  "city": "Bangkok",
  "service": "ff-java-demo",
  "env": "production"
}
```

The context has two roles:
1. **Targeting rules** read from these attributes to decide *which allocation matches* (e.g., "IF plan == premium THEN 100% → enabled").
2. **Randomization** uses one specific attribute (by default `targetingKey`) to deterministically assign the subject to a variant within that allocation.

**The randomization key** is the attribute whose value is fed into the hashing function. By default, `randomization key = targetingKey`. The engine computes:

```
bucket = hash(targetingKey, flag_key) → stable bucket → stable variant
```

This means:
- `user-42` will **always** get the same variant for `new_checkout`, no matter how many times the flag is evaluated, across any number of requests, sessions, or devices.
- `user-99` might get a different variant, but it is also **stable** across all evaluations.
- Changing non-randomization attributes (e.g., `plan`, `city`) does **not** change the variant — only rules that reference those attributes or a change in `targetingKey` can change the assignment.

**When does the variant change?**

| What Changed | Variant Changes? | Why |
|---|---|---|
| Nothing — same user, same rules | No | `hash(targetingKey, flag_key)` is deterministic |
| Non-randomization context attribute (e.g., `city`, `plan`) | No | These attributes are not used in the hash; only targeting rule eligibility may change |
| `targetingKey` value (different user) | Possibly | Different hash input → different bucket → may land on different variant |
| Allocation percentages in the UI (e.g., 50% → 80%) | Possibly | More buckets now map to the variant; some users who were "off" become "on" |
| Targeting rule changed to match a different allocation | Yes | The user now matches a different rule with different variant weights |
| Randomization key explicitly changed to a different attribute | Yes | Hash input changes → different bucket assignment |

**Typical `targetingKey` choices:**

| Context | Typical `targetingKey` | Use Case |
|---|---|---|
| Browser / mobile (client-side) | RUM device ID or user ID | Per-user stickiness across sessions |
| Backend API (server-side) | `user_id` or `org_id` as string | Per-user or per-organization rollouts |
| Infrastructure rollout | `hostname` or `service_id` | Per-host or per-service canary |

**Why this matters:** For gradual rollouts and experiments, you need users to have a consistent experience. Without stickiness, a user could see the new checkout flow on one request and the old one on the next — corrupting experiment data and confusing users. The deterministic hash approach guarantees stability without any external state.

**Try it yourself:**

```bash
# Same user always gets the same result — run this multiple times
curl -X POST http://localhost:8080/api/checkout \
  -H "Content-Type: application/json" \
  -d '{"userId": "user-42", "plan": "premium"}'

# Different user may get a different variant
curl -X POST http://localhost:8080/api/checkout \
  -H "Content-Type: application/json" \
  -d '{"userId": "user-99", "plan": "basic"}'

# Same user, different plan — variant stays the same (plan is not the randomization key)
curl -X POST http://localhost:8080/api/checkout \
  -H "Content-Type: application/json" \
  -d '{"userId": "user-42", "plan": "basic"}'
```

**Dashboard & expected results:**

| User | Repeated Calls | Expected `new_checkout` | Dashboard Signal |
|---|---|---|---|
| `user-42` | 5x | Same variant every time (e.g., `enabled`) | All 5 traces show `ff.new_checkout:enabled` |
| `user-42` (different `plan`) | 3x | Still the same variant — `plan` is not the randomization key | Same `ff.new_checkout` value as above |
| `user-99` | 5x | Same variant every time (may differ from user-42) | All 5 traces show consistent variant |
| `user-1` through `user-20` | 1x each | ~50/50 split (with 50% rollout) | Dashboard pie/bar chart shows variant distribution |

The dashboard's "new_checkout variant distribution" widget should show a roughly even split when you test with many different user IDs and a 50% rollout is configured. The key insight: variant assignment is stable per `targetingKey` regardless of what other context attributes change between requests.

---

### Q3: Can flags depend on other flags (chained evaluation)?

> **Short answer:** Yes. Your application code evaluates flags in sequence, so you can gate one flag behind another.

**How it works:** This is an application-level pattern, not a built-in platform feature. The code first evaluates `feature_a`. Only if `feature_a` is ON does it proceed to evaluate `feature_b`. If `feature_a` is OFF, `feature_b` is never even checked.

```
if feature_a == ON:
    check feature_b  →  use result
else:
    skip feature_b entirely
```

**Why this matters:** In complex systems, features often have prerequisites. For example, you might have a "redesigned dashboard" flag that should only take effect if the "new navigation" flag is also enabled. Chaining prevents nonsensical combinations and gives you fine-grained rollout control.

**Try it yourself:**

```bash
# Scenario 1: Both flags OFF (default)
curl http://localhost:8080/api/features

# Scenario 2: Turn ON feature_a only (in Datadog UI)
curl http://localhost:8080/api/features

# Scenario 3: Turn ON both feature_a and feature_b
curl http://localhost:8080/api/features
```

**Dashboard & expected results:**

| Scenario | `feature_a` | `feature_b` | Chain | Dashboard Signal |
|---|---|---|---|---|
| 1: Both OFF | `false` | `false` (skipped) | `feature_a=OFF → feature_b skipped` | Only `ff.feature_a:off` tag; no `ff.feature_b` tag on span |
| 2: A=ON, B=OFF | `true` | `false` | `feature_a=ON → feature_b=OFF` | Both `ff.feature_a:on` and `ff.feature_b:off` tags |
| 3: Both ON | `true` | `true` | `feature_a=ON → feature_b=ON` | Both `ff.feature_a:on` and `ff.feature_b:on` tags |

The dashboard's chained evaluation widgets show `feature_a` and `feature_b` variant counts side by side. When `feature_a` is OFF, the `feature_b` evaluation count drops to zero — visually confirming the gating behavior.

---

### Q4: How does geographic targeting work?

> **Short answer:** Your app passes user attributes (city, country, etc.) as part of the evaluation context. You then configure targeting rules in the Datadog UI to match on those attributes.

**How it works:** The server-side application extracts location information from the request — this could come from HTTP headers (set by a CDN/load balancer), IP geolocation, user profile data, or any other source. These attributes are passed to the OpenFeature evaluation context. In the Datadog Feature Flags UI, you create targeting rules like "IF city == Bangkok THEN serve variant ON".

**Why this matters:** Geographic targeting lets you roll out features region by region, comply with regional regulations, or run location-specific experiments. Since the targeting happens server-side, it works regardless of VPNs or client-side spoofing.

**Try it yourself:**

```bash
# With Bangkok header — should match the geo targeting rule
curl http://localhost:8080/api/promotions \
  -H "X-User-City: Bangkok" \
  -H "X-User-Country: TH"

# Without geo headers — falls back to default (promo OFF)
curl http://localhost:8080/api/promotions

# Try a different city
curl http://localhost:8080/api/promotions \
  -H "X-User-City: London" \
  -H "X-User-Country: UK"
```

> **Note:** You must configure a targeting rule in the Datadog Feature Flags UI for `bangkok_promo` to match on the `city` attribute. Without a targeting rule, the flag returns its default variant regardless of the context.

**Dashboard & expected results:**

| Request | `city` | `bangkok_promo` | Dashboard Signal |
|---|---|---|---|
| With Bangkok header | Bangkok | `true` | `ff.bangkok_promo:on` in trace; `variant:on` in OTel metric |
| No headers | unknown | `false` | `ff.bangkok_promo:off` |
| With London header | London | `false` | `ff.bangkok_promo:off` |

The dashboard's geo-targeting widgets show the variant split. When all traffic includes `X-User-City: Bangkok`, the chart shows 100% `on`. Mixed traffic shows the split by city.

---

### Q5: Can I audit which flags a user sees and how they impact performance?

> **Short answer:** Yes — through (1) **APM traces** with flag evaluation tags, (2) the **`feature_flag.evaluations` OTel metric**, (3) **custom dashboards** correlating variants with performance, and (4) **SDK-level `getDetails()`** for per-request audit.

**How it works:** For server-side feature flags, every flag evaluation is observable through the Datadog platform at multiple levels:

| Layer | What You See | Where |
|---|---|---|
| **APM Traces** | Each request's span is tagged with the flag variant it received (e.g., `ff.bright_mode:on`) | APM → Trace Explorer → filter by `@ff.<flag_key>:<variant>` |
| **OTel Metrics** | `feature_flag.evaluations` counter broken down by flag key, variant, and reason | Metrics Explorer → `feature_flag.evaluations` |
| **Custom Dashboards** | Cross-flag, cross-service views combining metrics and span analytics | Your custom dashboard |
| **Feature Flags UI** | Variant distribution, evaluation count, error rate per flag (once OTel metric flows) | Feature Flags → select a flag → Real-Time Metric Overview |
| **SDK-level audit** | Per-evaluation detail (value, variant, reason, errorCode) via `getDetails()` | Your application code / API response |

**Client-side vs Server-side:** For client-side SDKs (React, iOS, Android), flag evaluations are carried in **RUM sessions**. For server-side SDKs (Java, Node.js — our case), flag evaluations are carried in **APM traces**, so you can correlate variants with backend latency, error rates, and throughput.

**Try it yourself:**

```bash
# SDK-level audit — per-user evaluation details
curl http://localhost:8080/api/user/user-42/flags
curl http://localhost:3000/api/user/user-99/flags
```

**Dashboard & expected results:**

| Data Source | What to Check | Expected |
|---|---|---|
| SDK Response | `value`, `variant`, `reason` fields per flag | Each flag shows its evaluated variant and why |
| APM Trace Explorer | `@ff.bright_mode:on service:ff-java-demo` | Traces filtered by variant show latency distribution |
| OTel Metric (Node.js) | `feature_flag.evaluations{flag_key:bright_mode}` | Counter increments grouped by variant |
| Custom Dashboard | Timeseries widgets | Variant distribution over time; compare Java (span tags) vs Node.js (OTel metric) |

---

### Q6: What happens when a flag doesn't exist or the provider fails?

> **Short answer:** The SDK gracefully returns the default (fallback) value you specified in your code. Your application never crashes due to a missing or misconfigured flag.

**How it works:** Every OpenFeature evaluation call requires a default value:
```java
client.getBooleanValue("some_flag", false)  // false is the fallback
```
If the flag doesn't exist, if the provider hasn't connected yet, or if any error occurs, the SDK returns this fallback value. The `getDetails()` call will additionally populate `errorCode` and `reason: "ERROR"` so you can detect these failures.

**Why this matters:** In production, resilience is critical. If your feature flag service goes down, you don't want your app to crash. The fallback pattern ensures degraded-but-functional behavior.

**Try it yourself:**

```bash
# This evaluates a flag that doesn't exist in Datadog
curl http://localhost:8080/api/fallback-test
```

**Dashboard & expected results:**

| Field | Expected Value | Meaning |
|---|---|---|
| `value` | `true` | The fallback value specified in code |
| `reason` | `"ERROR"` | The SDK knows it couldn't evaluate properly |
| `error` | `"FLAG_NOT_FOUND"` | The specific failure reason |
| `variant` | `null` | No variant was selected since evaluation failed |

The `/api/theme` endpoint also demonstrates fallback — if `bright_mode` fails to evaluate, the `error` and `note_q6` fields appear in the response. In the dashboard, error evaluations appear as a separate category (reason = ERROR).

---

### Q7: Are server-side flags affected by RUM sample rate?

> **Short answer: No.** Server-side flag evaluation is completely independent of client-side RUM sampling.

**How it works:** RUM sampling controls what percentage of browser/mobile sessions send telemetry to Datadog. However, feature flag evaluation happens on the **server side** — it runs in your backend code on every single API request, regardless of whether the client's RUM session is being sampled.

**Why this matters:** This is a common source of confusion. Teams worry that a low RUM sample rate will affect feature flags. That's not the case:
- **RUM sampling** affects observability data collection (sessions, views, actions)
- **Feature flag evaluation** affects application behavior (which code path to execute)

These are two completely separate systems. Every user gets the correct flag value on every request, even if their RUM session is discarded.

**Try it yourself:**

```bash
curl http://localhost:8080/api/cart
```

**Dashboard & expected results:**

| Field | Expected Value | Meaning |
|---|---|---|
| `max_cart_items` | `20` (default) or `50` (if changed) | Served on **every** request regardless of RUM |
| Dashboard | Consistent evaluation count | Flag evaluations happen at 100% rate even if RUM is sampled at 10% |

---

### Q8: Where does the evaluation context come from?

> **Short answer:** It's entirely up to your application. The evaluation context is a bag of key-value attributes that your server-side code constructs from whatever source makes sense — HTTP headers, query parameters, request body, JWT claims, database lookups, etc.

**How it works:** Before evaluating a flag, your app builds an `EvaluationContext` object. The most important field is `targetingKey` (used for stickiness/bucketing), but you can add any custom attributes. These attributes are then available for Datadog targeting rules to match against.

**Evaluation context in detail:**

The `EvaluationContext` is the bridge between your application's request data and the feature flag system. It consists of:

1. **`targetingKey`** (required for stickiness) — a stable identifier for the entity being evaluated. Typically a user ID, but could be a session ID, organization ID, or device ID depending on your use case. This key is hashed for consistent variant assignment.

2. **Custom attributes** — any key-value pairs your app provides. The Datadog provider makes these available for targeting rules. Common patterns:

| Source | How to Extract | Example Attributes | Use Case |
|---|---|---|---|
| HTTP headers | `req.headers["X-User-Id"]` | `targetingKey`, `city`, `country` | Set by API gateway, CDN, or client app |
| Query parameters | `req.query.userId` | `targetingKey`, `tier` | Quick testing, debugging |
| Request body | `req.body.userId` | `targetingKey`, `plan` | POST payloads (e.g., checkout) |
| JWT claims | Decode `Authorization` header | `sub` (targetingKey), `role`, `org_id` | Authentication tokens |
| Database lookup | Query user profile | `subscription_tier`, `signup_date` | Enriched user data for progressive rollouts |

3. **How targeting rules use context:** In the Datadog Feature Flags UI, you define rules like:
   - "IF `city` == `Bangkok` THEN serve `on`" (geographic targeting, Q4)
   - "IF `tier` == `premium` THEN serve `enabled`" (tier-based rollout)
   - "IF `targetingKey` is in list THEN serve `treatment_a`" (user allowlist)

   The provider evaluates these rules locally using the context your app provides. No context attributes means only the default variant is served.

**Why this matters:** The flexibility of the context model means you can target flags on **any** attribute. There's no rigid schema — your app decides what context to provide, and your Datadog targeting rules decide how to use it. This enables:
- Target by subscription tier ("premium users get the new feature first")
- Target by organization ("Company X opted into the beta")
- Target by custom attributes ("users who signed up after 2024 get the redesign")
- Combine multiple attributes in a single rule for precise targeting

**Try it yourself:**

```bash
# Context from HTTP headers
curl "http://localhost:8080/api/context-demo" \
  -H "X-User-Id: alice" \
  -H "X-User-Tier: premium"

# Context from query parameters
curl "http://localhost:8080/api/context-demo?userId=bob&tier=enterprise"

# Both at once — the response shows how each source produces different context
curl "http://localhost:8080/api/context-demo?userId=bob&tier=free" \
  -H "X-User-Id: alice" \
  -H "X-User-Tier: premium"
```

**Dashboard & expected results:**

| Context Source | `targetingKey` | `tier` | `bright_mode` Result | Why |
|---|---|---|---|---|
| Headers only | `alice` | `premium` | Depends on targeting rules | Hash of "alice" determines variant |
| Query params only | `bob` | `enterprise` | Depends on targeting rules | Hash of "bob" determines variant |
| Both (same request) | `alice` (headers) / `bob` (params) | `premium` / `free` | Two separate evaluations in one request | Each evaluation uses its own context independently |

In APM Trace Explorer, you can see both evaluations on the same trace span, each with its own context and result. This demonstrates that evaluation context is per-call, not per-request.

---

## Project Structure

```
datadog-ff-server-demo/
├── README.md
├── docker-compose.yml
├── .env.example
├── java-springboot/
│   ├── build.gradle
│   ├── Dockerfile
│   └── src/main/java/com/example/ffdemo/
│       ├── Application.java
│       ├── config/FeatureFlagConfig.java
│       ├── controller/DemoController.java
│       └── model/CheckoutRequest.java
├── nodejs-bun/
│   ├── package.json
│   ├── Dockerfile
│   └── src/
│       ├── index.ts
│       ├── routes/demo.ts
│       └── middleware/ffContext.ts
└── scripts/
    ├── create-flags.sh
    └── toggle-bright-mode.sh
```

## Tech Stack

| Component | Java | Node.js |
|---|---|---|
| Runtime | Java 17 | Node.js 20 |
| Framework | Spring Boot 3.x | Express |
| Tracing | dd-java-agent (javaagent) | dd-trace (--require dd-trace/init) |
| Feature Flags | OpenFeature SDK + Datadog provider | OpenFeature Server SDK + dd-trace provider |
| Build | Gradle | TypeScript → tsc |
| Flag Observability | APM span tags (`ff.*`) | APM span tags (`ff.*`) + `feature_flag.evaluations` OTel metric |
