package com.example.ffdemo.controller;

import com.example.ffdemo.model.CheckoutRequest;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.MutableContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);
    private final Client client;

    public DemoController(Client client) {
        this.client = client;
    }

    // ── Q1: Basic flag check — no restart needed ──────────────────────
    @GetMapping("/health")
    public Map<String, Object> health() {
        boolean brightMode = client.getBooleanValue("bright_mode", false);
        Map<String, Object> res = new HashMap<>();
        res.put("status", "ok");
        res.put("bright_mode", brightMode);
        res.put("note", "Toggle bright_mode in Datadog UI — this value updates without restart (Q1)");
        return res;
    }

    // ── Q1 + Q6: Theme with fallback ─────────────────────────────────
    @GetMapping("/theme")
    public Map<String, Object> theme() {
        FlagEvaluationDetails<Boolean> details = client.getBooleanDetails("bright_mode", false);
        Map<String, Object> res = new HashMap<>();
        res.put("bright_mode", details.getValue());
        res.put("variant", details.getVariant());
        res.put("reason", details.getReason());
        if (details.getErrorCode() != null) {
            res.put("error", details.getErrorCode().toString());
            res.put("note_q6", "Fallback value used because flag evaluation failed");
        }
        return res;
    }

    // ── Q2: Stickiness via targetingKey ──────────────────────────────
    @PostMapping("/checkout")
    public Map<String, Object> checkout(@RequestBody CheckoutRequest req) {
        MutableContext ctx = new MutableContext(req.getUserId());
        ctx.add("plan", req.getPlan());

        boolean newCheckout = client.getBooleanValue("new_checkout", false, ctx);

        log.info("[Q2] user={} plan={} new_checkout={} — sticky per targetingKey",
                req.getUserId(), req.getPlan(), newCheckout);

        Map<String, Object> res = new HashMap<>();
        res.put("userId", req.getUserId());
        res.put("new_checkout_enabled", newCheckout);
        res.put("flow", newCheckout ? "new_checkout_v2" : "legacy_checkout");
        res.put("note", "Same userId always gets the same variant (Q2 stickiness)");
        return res;
    }

    // ── Q3: Chained flags (feature_a gates feature_b) ────────────────
    @GetMapping("/features")
    public Map<String, Object> features() {
        boolean featureA = client.getBooleanValue("feature_a", false);
        boolean featureB = false;
        String chain = "feature_a=OFF → feature_b skipped";

        if (featureA) {
            featureB = client.getBooleanValue("feature_b", false);
            chain = "feature_a=ON → feature_b=" + (featureB ? "ON" : "OFF");
        }

        Map<String, Object> res = new HashMap<>();
        res.put("feature_a", featureA);
        res.put("feature_b", featureB);
        res.put("chain", chain);
        res.put("note", "feature_b is only evaluated when feature_a is ON (Q3)");
        return res;
    }

    // ── Q4: Geographic targeting ─────────────────────────────────────
    @GetMapping("/promotions")
    public Map<String, Object> promotions(
            @RequestHeader(value = "X-User-City", defaultValue = "unknown") String city,
            @RequestHeader(value = "X-User-Country", defaultValue = "unknown") String country) {

        MutableContext ctx = new MutableContext();
        ctx.add("city", city);
        ctx.add("country", country);

        boolean bangkokPromo = client.getBooleanValue("bangkok_promo", false, ctx);

        Map<String, Object> res = new HashMap<>();
        res.put("city", city);
        res.put("country", country);
        res.put("bangkok_promo", bangkokPromo);
        res.put("note", "Set X-User-City: Bangkok header to trigger geo-targeted promo (Q4)");
        return res;
    }

    // ── Q5: Per-user flag audit via getDetails ───────────────────────
    @GetMapping("/user/{id}/flags")
    public Map<String, Object> userFlags(@PathVariable("id") String userId) {
        MutableContext ctx = new MutableContext(userId);

        FlagEvaluationDetails<Boolean> brightDetails = client.getBooleanDetails("bright_mode", false, ctx);
        FlagEvaluationDetails<Boolean> checkoutDetails = client.getBooleanDetails("new_checkout", false, ctx);
        FlagEvaluationDetails<String> variantDetails = client.getStringDetails("checkout_variant", "control", ctx);

        Map<String, Object> brightAudit = new HashMap<>();
        brightAudit.put("value", brightDetails.getValue());
        brightAudit.put("variant", brightDetails.getVariant());
        brightAudit.put("reason", brightDetails.getReason());

        Map<String, Object> checkoutAudit = new HashMap<>();
        checkoutAudit.put("value", checkoutDetails.getValue());
        checkoutAudit.put("variant", checkoutDetails.getVariant());
        checkoutAudit.put("reason", checkoutDetails.getReason());

        Map<String, Object> variantAudit = new HashMap<>();
        variantAudit.put("value", variantDetails.getValue());
        variantAudit.put("variant", variantDetails.getVariant());
        variantAudit.put("reason", variantDetails.getReason());

        Map<String, Object> res = new HashMap<>();
        res.put("userId", userId);
        res.put("bright_mode", brightAudit);
        res.put("new_checkout", checkoutAudit);
        res.put("checkout_variant", variantAudit);
        res.put("note", "Full audit trail per user via getBooleanDetails / getStringDetails (Q5)");
        return res;
    }

    // ── Q6: Fallback when flag is missing ────────────────────────────
    @GetMapping("/fallback-test")
    public Map<String, Object> fallbackTest() {
        FlagEvaluationDetails<Boolean> details = client.getBooleanDetails("nonexistent_flag", true);

        Map<String, Object> res = new HashMap<>();
        res.put("flag_key", "nonexistent_flag");
        res.put("value", details.getValue());
        res.put("variant", details.getVariant());
        res.put("reason", details.getReason());
        res.put("error", details.getErrorCode() != null ? details.getErrorCode().toString() : null);
        res.put("note", "Default value (true) returned because flag doesn't exist (Q6)");
        return res;
    }

    // ── Q7: Flag independent of RUM sample rate ──────────────────────
    @GetMapping("/cart")
    public Map<String, Object> cart() {
        int maxItems = client.getIntegerValue("max_cart_items", 20);

        Map<String, Object> res = new HashMap<>();
        res.put("max_cart_items", maxItems);
        res.put("note", "Server-side flag evaluation is independent of RUM sample rate (Q7). "
                + "Even if RUM drops 90% of sessions, every request gets correct flag values.");
        return res;
    }

    // ── Q9: A/B test with string variant ─────────────────────────────
    @PostMapping("/ab-test")
    public Map<String, Object> abTest(@RequestBody Map<String, String> body) {
        String userId = body.getOrDefault("userId", "anonymous");
        MutableContext ctx = new MutableContext(userId);

        FlagEvaluationDetails<String> details = client.getStringDetails("checkout_variant", "control", ctx);

        String variant = details.getValue();
        Map<String, Object> experience = new HashMap<>();
        switch (variant) {
            case "treatment_a":
                experience.put("layout", "single_page");
                experience.put("cta", "Buy Now");
                break;
            case "treatment_b":
                experience.put("layout", "multi_step");
                experience.put("cta", "Continue to Payment");
                break;
            default:
                experience.put("layout", "classic");
                experience.put("cta", "Proceed to Checkout");
        }

        Map<String, Object> res = new HashMap<>();
        res.put("userId", userId);
        res.put("variant", variant);
        res.put("reason", details.getReason());
        res.put("experience", experience);
        res.put("note", "String flag for A/B testing — same user always gets same variant (Q9)");
        return res;
    }

    // ── Q10: Context from headers vs body ────────────────────────────
    @GetMapping("/context-demo")
    public Map<String, Object> contextDemo(
            @RequestHeader(value = "X-User-Id", defaultValue = "header-user") String headerUserId,
            @RequestHeader(value = "X-User-Tier", defaultValue = "free") String headerTier,
            @RequestParam(value = "userId", defaultValue = "param-user") String paramUserId,
            @RequestParam(value = "tier", defaultValue = "free") String paramTier) {

        MutableContext headerCtx = new MutableContext(headerUserId);
        headerCtx.add("tier", headerTier);
        headerCtx.add("source", "header");

        MutableContext paramCtx = new MutableContext(paramUserId);
        paramCtx.add("tier", paramTier);
        paramCtx.add("source", "query_param");

        boolean headerResult = client.getBooleanValue("bright_mode", false, headerCtx);
        boolean paramResult = client.getBooleanValue("bright_mode", false, paramCtx);

        Map<String, Object> headerInfo = new HashMap<>();
        headerInfo.put("targetingKey", headerUserId);
        headerInfo.put("tier", headerTier);
        headerInfo.put("bright_mode", headerResult);

        Map<String, Object> paramInfo = new HashMap<>();
        paramInfo.put("targetingKey", paramUserId);
        paramInfo.put("tier", paramTier);
        paramInfo.put("bright_mode", paramResult);

        Map<String, Object> res = new HashMap<>();
        res.put("from_headers", headerInfo);
        res.put("from_query_params", paramInfo);
        res.put("note", "Context can come from headers, query params, body — it's up to the app (Q10)");
        return res;
    }
}
