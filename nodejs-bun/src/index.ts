// dd-trace is loaded via --require dd-trace/init before this file runs.
// That auto-initializes the tracer using DD_* env vars.

import tracer from "dd-trace";
import { OpenFeature } from "@openfeature/server-sdk";
import express from "express";
import { demoRouter } from "./routes/demo";
import { ffContextMiddleware } from "./middleware/ffContext";

// Register the Datadog tracer as the OpenFeature provider.
// dd-trace exposes .openfeature when Remote Config is enabled on the Agent.
const provider = (tracer as any).openfeature;
if (provider) {
  OpenFeature.setProvider(provider);
  console.log("[init] Datadog OpenFeature provider registered");
} else {
  console.warn(
    "[init] tracer.openfeature not available — flags will return defaults. " +
      "Ensure DD_REMOTE_CONFIGURATION_ENABLED=true on the Agent."
  );
}

const app = express();
app.use(express.json());
app.use(ffContextMiddleware);
app.use("/api", demoRouter);

const PORT = Number(process.env.PORT) || 3000;
app.listen(PORT, () => {
  console.log(`ff-node-demo listening on :${PORT}`);
});
