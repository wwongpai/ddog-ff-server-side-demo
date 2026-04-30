import { Router } from "express";
import { OpenFeature } from "@openfeature/server-sdk";
import tracer from "dd-trace";

export const demoRouter = Router();

function getClient() {
  return OpenFeature.getClient("ff-node-demo");
}

function trackFlag(flagKey: string, variant: string) {
  const span = tracer.scope().active();
  if (span) {
    span.setTag(`_dd.feature_flags.${flagKey}.variant`, variant);
    span.setTag(`ff.${flagKey}`, variant);
  }
}

// ── Q1: Basic flag check — no restart needed ──────────────────────────
demoRouter.get("/health", async (_req, res) => {
  const client = getClient();
  const brightMode = await client.getBooleanValue("bright_mode", false);
  trackFlag("bright_mode", brightMode ? "on" : "off");
  res.json({
    status: "ok",
    bright_mode: brightMode,
    note: "Toggle bright_mode in Datadog UI — this value updates without restart (Q1)",
  });
});

// ── Q1 + Q6: Theme with fallback ─────────────────────────────────────
demoRouter.get("/theme", async (_req, res) => {
  const client = getClient();
  const details = await client.getBooleanDetails("bright_mode", false);
  trackFlag("bright_mode", details.variant || (details.value ? "on" : "off"));
  res.json({
    bright_mode: details.value,
    variant: details.variant,
    reason: details.reason,
    ...(details.errorCode && {
      error: details.errorCode,
      note_q6: "Fallback value used because flag evaluation failed",
    }),
  });
});

// ── Q2: Stickiness via targetingKey ──────────────────────────────────
demoRouter.post("/checkout", async (req, res) => {
  const client = getClient();
  const { userId, plan } = req.body;
  const ctx = { targetingKey: userId || "anonymous", plan: plan || "basic" };

  const newCheckout = await client.getBooleanValue("new_checkout", false, ctx);
  trackFlag("new_checkout", newCheckout ? "enabled" : "disabled");

  console.log(
    `[Q2] user=${ctx.targetingKey} plan=${ctx.plan} new_checkout=${newCheckout}`
  );

  res.json({
    userId: ctx.targetingKey,
    new_checkout_enabled: newCheckout,
    flow: newCheckout ? "new_checkout_v2" : "legacy_checkout",
    note: "Same userId always gets the same variant (Q2 stickiness)",
  });
});

// ── Q3: Chained flags (feature_a gates feature_b) ────────────────────
demoRouter.get("/features", async (_req, res) => {
  const client = getClient();
  const featureA = await client.getBooleanValue("feature_a", false);
  trackFlag("feature_a", featureA ? "on" : "off");
  let featureB = false;
  let chain = "feature_a=OFF → feature_b skipped";

  if (featureA) {
    featureB = await client.getBooleanValue("feature_b", false);
    trackFlag("feature_b", featureB ? "on" : "off");
    chain = `feature_a=ON → feature_b=${featureB ? "ON" : "OFF"}`;
  }

  res.json({
    feature_a: featureA,
    feature_b: featureB,
    chain,
    note: "feature_b is only evaluated when feature_a is ON (Q3)",
  });
});

// ── Q4: Geographic targeting ─────────────────────────────────────────
demoRouter.get("/promotions", async (req, res) => {
  const client = getClient();
  const { city, country } = req.ffContext;
  const ctx = { targetingKey: "geo-user", city, country };

  const bangkokPromo = await client.getBooleanValue(
    "bangkok_promo",
    false,
    ctx
  );
  trackFlag("bangkok_promo", bangkokPromo ? "on" : "off");

  res.json({
    city,
    country,
    bangkok_promo: bangkokPromo,
    note: "Set X-User-City: Bangkok header to trigger geo-targeted promo (Q4)",
  });
});

// ── Q5: Per-user flag audit via getDetails ───────────────────────────
demoRouter.get("/user/:id/flags", async (req, res) => {
  const client = getClient();
  const userId = req.params.id;
  const ctx = { targetingKey: userId };

  const [brightDetails, checkoutDetails, variantDetails] = await Promise.all([
    client.getBooleanDetails("bright_mode", false, ctx),
    client.getBooleanDetails("new_checkout", false, ctx),
    client.getStringDetails("checkout_variant", "control", ctx),
  ]);

  res.json({
    userId,
    bright_mode: {
      value: brightDetails.value,
      variant: brightDetails.variant,
      reason: brightDetails.reason,
    },
    new_checkout: {
      value: checkoutDetails.value,
      variant: checkoutDetails.variant,
      reason: checkoutDetails.reason,
    },
    checkout_variant: {
      value: variantDetails.value,
      variant: variantDetails.variant,
      reason: variantDetails.reason,
    },
    note: "Full audit trail per user via getDetails (Q5)",
  });
});

// ── Q6: Fallback when flag is missing ────────────────────────────────
demoRouter.get("/fallback-test", async (_req, res) => {
  const client = getClient();
  const details = await client.getBooleanDetails("nonexistent_flag", true);

  res.json({
    flag_key: "nonexistent_flag",
    value: details.value,
    variant: details.variant,
    reason: details.reason,
    error: details.errorCode || null,
    note: "Default value (true) returned because flag doesn't exist (Q6)",
  });
});

// ── Q7: Flag independent of RUM sample rate ──────────────────────────
demoRouter.get("/cart", async (_req, res) => {
  const client = getClient();
  const maxItems = await client.getNumberValue("max_cart_items", 20);

  res.json({
    max_cart_items: maxItems,
    note:
      "Server-side flag evaluation is independent of RUM sample rate (Q7). " +
      "Even if RUM drops 90% of sessions, every request gets correct flag values.",
  });
});

// ── Q8: Context from headers vs body ────────────────────────────────
demoRouter.get("/context-demo", async (req, res) => {
  const client = getClient();

  const headerCtx = {
    targetingKey:
      (req.headers["x-user-id"] as string) || "header-user",
    tier: (req.headers["x-user-tier"] as string) || "free",
    source: "header",
  };

  const paramCtx = {
    targetingKey: (req.query.userId as string) || "param-user",
    tier: (req.query.tier as string) || "free",
    source: "query_param",
  };

  const [headerResult, paramResult] = await Promise.all([
    client.getBooleanValue("bright_mode", false, headerCtx),
    client.getBooleanValue("bright_mode", false, paramCtx),
  ]);

  res.json({
    from_headers: {
      targetingKey: headerCtx.targetingKey,
      tier: headerCtx.tier,
      bright_mode: headerResult,
    },
    from_query_params: {
      targetingKey: paramCtx.targetingKey,
      tier: paramCtx.tier,
      bright_mode: paramResult,
    },
    note: "Context can come from headers, query params, body — it's up to the app (Q8)",
  });
});
