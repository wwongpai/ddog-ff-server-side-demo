# Server-Side Datadog Feature Flags Demo

Two identical demo apps — **Java Spring Boot** and **Node.js (Express)** — showing server-side feature flag evaluation with the Datadog OpenFeature provider. Each endpoint maps to a specific Q&A question (Q1–Q10).

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
- **Datadog Agent** on port 8126 (APM) / 8125 (DogStatsD)
- **Java app** on port **8080**
- **Node.js app** on port **3000**

### 4. Test endpoints

Use the curl examples below. Both apps expose the same endpoints — just swap the port:
- Java: `http://localhost:8080/api/...`
- Node.js: `http://localhost:3000/api/...`

---

## Flags Reference

| Flag Key | Type | Variants | Purpose |
|---|---|---|---|
| `bright_mode` | Boolean | on / off | Theme toggle (Q1) |
| `new_checkout` | Boolean | enabled / disabled | Stickiness demo (Q2) |
| `feature_a` | Boolean | on / off | Chain demo part 1 (Q3) |
| `feature_b` | Boolean | on / off | Chain demo part 2 (Q3) |
| `bangkok_promo` | Boolean | on / off | Geo targeting (Q4) |
| `checkout_variant` | String | control / treatment_a / treatment_b | A/B test (Q9) |
| `max_cart_items` | Number | default (20) / high (50) | Number flag (Q7) |

---

## Endpoint Walkthrough (Q1–Q10)

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

**What to look for in the response:**
- `bright_mode: true/false` — the evaluated value
- `variant: "on"` or `"off"` — which variant was selected
- `reason: "TARGETING_MATCH"` — why this variant was chosen (e.g., targeting rule matched, default used, error fallback)

---

### Q2: How does stickiness work?

> **Short answer:** The `targetingKey` (typically the user ID) is hashed to deterministically assign a variant. The same user always gets the same result.

**How it works:** When you pass a `targetingKey` (e.g., a user ID like `"user-42"`) in the evaluation context, the Datadog provider uses consistent hashing to map that key to a variant. This means:
- `user-42` will **always** get the same variant (e.g., `enabled`) no matter how many times the flag is evaluated.
- `user-99` might get a different variant, but it will also be **stable** across repeated evaluations.
- This is deterministic — no session storage, cookies, or databases are needed. The hash of the targeting key alone decides the variant.

**Why this matters:** For gradual rollouts and experiments, you need users to have a consistent experience. Without stickiness, a user could see the new checkout flow on one request and the old one on the next, which creates confusion and corrupts experiment data.

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
```

**What to look for in the response:**
- `flow: "new_checkout_v2"` or `"legacy_checkout"` — which experience the user gets
- Run the same userId multiple times — the result never changes
- Try many different userIds to see the distribution across variants

---

### Q3: Can flags depend on other flags (chained evaluation)?

> **Short answer:** Yes. Your application code evaluates flags in sequence, so you can gate one flag behind another.

**How it works:** This is not a built-in platform feature — it's an application-level pattern. The code first evaluates `feature_a`. Only if `feature_a` is ON does it proceed to evaluate `feature_b`. If `feature_a` is OFF, `feature_b` is never even checked, saving an unnecessary evaluation and making the dependency explicit.

```
if feature_a == ON:
    check feature_b  →  use result
else:
    skip feature_b entirely
```

**Why this matters:** In complex systems, features often have prerequisites. For example, you might have a "redesigned dashboard" flag that should only take effect if the "new navigation" flag is also enabled. Chaining prevents nonsensical combinations (e.g., new dashboard with old nav) and gives you fine-grained rollout control.

**Try it yourself:**

```bash
# With both flags OFF (default), feature_b is skipped entirely
curl http://localhost:8080/api/features

# Now go to Datadog UI → turn ON feature_a only
curl http://localhost:8080/api/features
# feature_a=ON, feature_b=OFF (b is now evaluated but still off)

# Now also turn ON feature_b
curl http://localhost:8080/api/features
# feature_a=ON, feature_b=ON (both active)
```

**What to look for in the response:**
- `chain` field shows the evaluation flow in plain text (e.g., `"feature_a=OFF → feature_b skipped"`)
- When `feature_a` is OFF, `feature_b` always shows `false` regardless of its actual configuration in Datadog

---

### Q4: How does geographic targeting work?

> **Short answer:** Your app passes user attributes (city, country, region, etc.) as part of the evaluation context. You then configure targeting rules in the Datadog UI to match on those attributes.

**How it works:** The server-side application extracts location information from the request — this could come from HTTP headers (set by a CDN/load balancer), IP geolocation, user profile data, or any other source. These attributes are passed to the OpenFeature evaluation context. In the Datadog Feature Flags UI, you create targeting rules like "IF city == Bangkok THEN serve variant ON". The provider evaluates these rules locally using the context you provide.

**Why this matters:** Geographic targeting lets you roll out features region by region (e.g., launch a promotion only in Bangkok), comply with regional regulations (e.g., different checkout flows for EU vs US), or run location-specific experiments. Since the targeting happens server-side, it works regardless of VPNs or client-side spoofing.

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

> **Note:** You must configure the targeting rule in the Datadog Feature Flags UI for `bangkok_promo` to match on the `city` attribute. Without a targeting rule, the flag will return its default variant regardless of the context attributes.

**What to look for in the response:**
- `bangkok_promo: true` when city=Bangkok (if targeting rule is configured)
- `bangkok_promo: false` for other cities or when no headers are sent
- `city` and `country` echoed back so you can verify what the server received

---

### Q5: Can I audit which flags a user sees?

> **Short answer:** Yes. The OpenFeature SDK's `getDetails()` method returns the full evaluation result — not just the value, but also which variant was selected, why it was selected, and any errors.

**How it works:** Instead of calling `getBooleanValue()` (which returns only `true`/`false`), you call `getBooleanDetails()` or `getStringDetails()`. This returns an `EvaluationDetails` object containing:
- **value** — the resolved flag value
- **variant** — which named variant was selected (e.g., `"on"`, `"treatment_a"`)
- **reason** — why this variant was chosen (e.g., `"TARGETING_MATCH"`, `"DEFAULT"`, `"ERROR"`)
- **errorCode** — if evaluation failed, what went wrong (e.g., `"FLAG_NOT_FOUND"`, `"PROVIDER_NOT_READY"`)

**Why this matters:** For compliance, debugging, and experiment analysis, you need to know exactly what each user saw and why. This audit data can be logged, sent to analytics, or stored for regulatory requirements. It's especially useful when troubleshooting why a specific user is (or isn't) seeing a feature.

**Try it yourself:**

```bash
# Get full flag audit for user-42
curl http://localhost:8080/api/user/user-42/flags

# Compare with a different user
curl http://localhost:8080/api/user/user-99/flags
```

**What to look for in the response:**
- Each flag has `value`, `variant`, and `reason` fields
- `reason: "TARGETING_MATCH"` means a targeting rule matched this user
- `reason: "DEFAULT"` means no rule matched, so the default variant was served
- `reason: "ERROR"` with `errorCode` means something went wrong (e.g., provider not ready)

---

### Q6: What happens when a flag doesn't exist or the provider fails?

> **Short answer:** The SDK gracefully returns the default (fallback) value you specified in your code. Your application never crashes due to a missing or misconfigured flag.

**How it works:** Every OpenFeature evaluation call requires a default value:
```java
client.getBooleanValue("some_flag", false)  // false is the fallback
```
If the flag doesn't exist in Datadog, if the provider hasn't connected yet, or if any error occurs during evaluation, the SDK returns this fallback value instead of throwing an exception. The `getDetails()` call will additionally populate `errorCode` and `reason: "ERROR"` so you can detect and log these failures.

**Why this matters:** In production, resilience is critical. If your feature flag service goes down, you don't want your app to crash or behave unpredictably. The fallback pattern ensures degraded-but-functional behavior. You choose sensible defaults (e.g., fallback to the legacy checkout flow) so users are never left with a broken experience.

**Try it yourself:**

```bash
# This evaluates a flag that doesn't exist in Datadog
curl http://localhost:8080/api/fallback-test
```

**What to look for in the response:**
- `value: true` — the fallback value we specified in code
- `reason: "ERROR"` — the SDK knows it couldn't evaluate properly
- `error: "FLAG_NOT_FOUND"` or `"PROVIDER_NOT_READY"` — the specific failure reason
- `variant: null` — no variant was selected since evaluation failed

The `/api/theme` endpoint also demonstrates fallback behavior — if the `bright_mode` flag fails to evaluate, you'll see `error` and `note_q6` fields appear in the response.

---

### Q7: Are server-side flags affected by RUM sample rate?

> **Short answer: No.** Server-side flag evaluation is completely independent of client-side RUM (Real User Monitoring) sampling.

**How it works:** RUM sampling controls what percentage of browser/mobile sessions send telemetry data to Datadog. If your RUM sample rate is 10%, only 10% of user sessions report performance data, errors, etc. However, feature flag evaluation happens on the **server side** — it runs in your backend code on every single API request, regardless of whether the client's RUM session is being sampled or not.

**Why this matters:** This is a common source of confusion. Teams worry that if they set a low RUM sample rate (to save costs), their feature flags will also only work for the sampled sessions. That's not the case:
- **RUM sampling** affects observability data collection (sessions, views, actions)
- **Feature flag evaluation** affects application behavior (which code path to execute)

These are two completely separate systems. Every user gets the correct flag value on every request, even if their RUM session is discarded.

**Try it yourself:**

```bash
curl http://localhost:8080/api/cart
```

**What to look for in the response:**
- `max_cart_items: 20` (or 50 if you changed the flag) — this value is served on **every** request
- The `note` field explains the independence from RUM sampling

---

### Q9: How do A/B tests work with string flags?

> **Short answer:** Use a string-type flag with multiple named variants (e.g., `"control"`, `"treatment_a"`, `"treatment_b"`). The `targetingKey` provides consistent bucketing so each user always sees the same variant.

**How it works:** Unlike boolean flags (on/off), string flags can have any number of named variants. You configure the percentage allocation in the Datadog UI (e.g., 33% control, 33% treatment_a, 34% treatment_b). When the server evaluates the flag, it hashes the `targetingKey` to deterministically assign the user to one of the variants. Your application code then uses the returned string to decide which experience to render.

```
variant = evaluate("checkout_variant", userId)

if variant == "treatment_a":   → single-page layout, "Buy Now" button
if variant == "treatment_b":   → multi-step layout, "Continue to Payment" button
else (control):                → classic layout, "Proceed to Checkout" button
```

**Why this matters:** A/B testing requires:
1. **Consistent assignment** — a user must always see the same variant (stickiness via targetingKey)
2. **Multiple variants** — not just on/off, but several different experiences
3. **Measurable** — Datadog can correlate flag variants with performance metrics, error rates, and business outcomes

String flags give you all three. You can run sophisticated experiments with more than two variants and analyze the results in Datadog's Feature Flags UI alongside your APM traces and RUM data.

**Try it yourself:**

```bash
# user-42 always gets the same variant
curl -X POST http://localhost:8080/api/ab-test \
  -H "Content-Type: application/json" \
  -d '{"userId": "user-42"}'

# user-99 might get a different variant
curl -X POST http://localhost:8080/api/ab-test \
  -H "Content-Type: application/json" \
  -d '{"userId": "user-99"}'

# Try more users to see the distribution
curl -X POST http://localhost:8080/api/ab-test \
  -H "Content-Type: application/json" \
  -d '{"userId": "user-123"}'
```

**What to look for in the response:**
- `variant: "control"` / `"treatment_a"` / `"treatment_b"` — which bucket the user landed in
- `experience.layout` and `experience.cta` — the actual UI differences per variant
- `reason: "TARGETING_MATCH"` — shows the assignment came from a targeting rule, not just a default

---

### Q10: Where does the evaluation context come from?

> **Short answer:** It's entirely up to your application. The evaluation context is a bag of key-value attributes that your server-side code constructs from whatever source makes sense — HTTP headers, query parameters, request body, JWT claims, database lookups, etc.

**How it works:** Before evaluating a flag, your app builds an `EvaluationContext` object. The most important field is `targetingKey` (used for stickiness/bucketing), but you can add any custom attributes you want. These attributes are then available for Datadog targeting rules to match against.

Common context sources:
| Source | Example | Use case |
|---|---|---|
| HTTP headers | `X-User-Id`, `X-User-City` | Set by API gateway, CDN, or client |
| Query parameters | `?userId=bob&tier=enterprise` | Quick testing, debugging |
| Request body | `{"userId": "alice"}` | POST payloads |
| JWT claims | `sub`, `role`, `org_id` | Authentication tokens |
| Database lookup | User profile, subscription tier | Enriched user data |

**Why this matters:** The flexibility of the context model means you can target flags on **any** attribute. For example:
- Target by subscription tier ("premium users get the new feature first")
- Target by organization ("Company X opted into the beta")
- Target by custom attributes ("users who signed up after 2024 get the redesign")

There's no rigid schema — your app decides what context to provide, and your Datadog targeting rules decide how to use it.

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

**What to look for in the response:**
- `from_headers` — flag evaluation using context built from HTTP headers (alice, premium)
- `from_query_params` — flag evaluation using context built from query params (bob, enterprise)
- Both evaluations happen in the same request, showing that context is per-evaluation, not per-request
- Different targetingKeys may resolve to different flag values depending on your targeting rules

---

## Live Demo Script

For a live demo, use this flow:

1. **Start services:** `docker-compose up --build`
2. **Show health endpoint:** `curl localhost:8080/api/health` → bright_mode = false
3. **Toggle flag live:** `./scripts/toggle-bright-mode.sh on`
4. **Show instant update:** `curl localhost:8080/api/health` → bright_mode = true (no restart!)
5. **Show stickiness:** POST to `/api/checkout` with same userId twice → same result
6. **Show geo targeting:** `/api/promotions` with/without Bangkok header
7. **Show A/B test:** `/api/ab-test` with different userIds
8. **Show audit:** `/api/user/user-42/flags` for full evaluation details
9. **Show fallback:** `/api/fallback-test` for missing flag behavior

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
