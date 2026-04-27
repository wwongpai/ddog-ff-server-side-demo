# Server-Side Datadog Feature Flags Demo

Two identical demo apps — **Java Spring Boot** and **Node.js (Bun)** — showing server-side feature flag evaluation with the Datadog OpenFeature provider. Each endpoint maps to a specific Q&A question (Q1–Q10).

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
source .env
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

**No.** The Datadog provider streams updates via Remote Config.

```bash
# Check current value
curl http://localhost:8080/api/health

# Toggle in Datadog UI (or use the script)
./scripts/toggle-bright-mode.sh on

# Check again — value updated, no restart
curl http://localhost:8080/api/health
```

**Endpoint:** `GET /api/theme` — returns full details including variant and reason.

```bash
curl http://localhost:8080/api/theme
```

---

### Q2: How does stickiness work?

The `targetingKey` (user ID) ensures the same user always gets the same variant.

```bash
# Same user always gets the same result
curl -X POST http://localhost:8080/api/checkout \
  -H "Content-Type: application/json" \
  -d '{"userId": "user-42", "plan": "premium"}'

# Different user may get a different result
curl -X POST http://localhost:8080/api/checkout \
  -H "Content-Type: application/json" \
  -d '{"userId": "user-99", "plan": "basic"}'
```

---

### Q3: Can flags depend on other flags?

Yes — evaluate them in sequence. Here `feature_b` is only checked when `feature_a` is ON.

```bash
curl http://localhost:8080/api/features
```

Try toggling `feature_a` on/off in Datadog UI and observe how `feature_b` is skipped when A is off.

---

### Q4: How does geographic targeting work?

Pass user attributes (city, country) in the evaluation context. Configure targeting rules in the Datadog UI to match on these attributes.

```bash
# With Bangkok header — should match geo rule
curl http://localhost:8080/api/promotions \
  -H "X-User-City: Bangkok" \
  -H "X-User-Country: TH"

# Without geo headers — default behavior
curl http://localhost:8080/api/promotions
```

---

### Q5: Can I audit which flags a user sees?

Yes — use `getDetails()` / `getBooleanDetails()` to get the full evaluation result including variant, reason, and error info.

```bash
curl http://localhost:8080/api/user/user-42/flags
```

Response includes per-flag audit data: value, variant name, and evaluation reason.

---

### Q6: What happens when a flag doesn't exist?

The SDK returns the default (fallback) value you provide.

```bash
curl http://localhost:8080/api/fallback-test
```

This evaluates `nonexistent_flag` with default `true` — you'll see the fallback returned with an error reason.

---

### Q7: Are flags affected by RUM sample rate?

**No.** Server-side flag evaluation happens on every request regardless of RUM sampling. Even if RUM drops 90% of browser sessions, every API call still gets the correct flag value.

```bash
curl http://localhost:8080/api/cart
```

---

### Q9: How do A/B tests work with string flags?

Use a string flag with multiple variants. The `targetingKey` ensures consistent bucketing.

```bash
curl -X POST http://localhost:8080/api/ab-test \
  -H "Content-Type: application/json" \
  -d '{"userId": "user-42"}'

# Try different users to see different variants
curl -X POST http://localhost:8080/api/ab-test \
  -H "Content-Type: application/json" \
  -d '{"userId": "user-99"}'
```

---

### Q10: Where does evaluation context come from?

It's up to the app — context can come from HTTP headers, query parameters, request body, JWT claims, or any other source.

```bash
# Context from headers
curl "http://localhost:8080/api/context-demo" \
  -H "X-User-Id: alice" \
  -H "X-User-Tier: premium"

# Context from query params
curl "http://localhost:8080/api/context-demo?userId=bob&tier=enterprise"

# Both at once — compare results
curl "http://localhost:8080/api/context-demo?userId=bob&tier=free" \
  -H "X-User-Id: alice" \
  -H "X-User-Tier: premium"
```

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
| Runtime | Java 17 | Bun |
| Framework | Spring Boot 3.x | Express |
| Tracing | dd-java-agent (javaagent) | dd-trace |
| Feature Flags | OpenFeature SDK + Datadog provider | OpenFeature Server SDK + dd-trace provider |
| Build | Gradle | Bun |
