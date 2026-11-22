import { bindings, defineConfig, exports } from "cf/config";

export default defineConfig({
  worker: {
    name: "jot-companion",
    entrypoint: "./worker.mjs",
    compatibilityDate: "2026-11-16",
    compatibilityFlags: ["nodejs_compat"],
    workersDev: true,
    previewUrls: false,
    observability: { enabled: false },
    exports: { CompanionBudget: exports.durableObject({ storage: "sqlite" }) },
    env: {
      BUDGET: bindings.durableObject({ worker: "jot-companion", exportName: "CompanionBudget" }),
      OPENAI_API_KEY: bindings.secret(),
    },
  },
});
