import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import test from "node:test";
import { Miniflare, convertV4MiniflareOptions } from "miniflare";
import { build } from "vite";

const note = "call Ben at 4";
const readResult = {
  note: "Call Ben at 4.", question: false, actions: ["reminder"], title: "Call Ben", message: "",
};
const question = "What is the gate code?";
const savedNotes = [{ date: "Nov 22", text: "The gate code is 42." }];

test("the Worker shares a free account's allowance across devices in a real SQLite Durable Object", async t => {
  const root = fileURLToPath(new URL(".", import.meta.url));
  const bundle = await build({
    root, configFile: false, logLevel: "silent",
    build: {
      write: false, minify: false,
      lib: { entry: fileURLToPath(new URL("worker.mjs", import.meta.url)), formats: ["es"] },
      rollupOptions: { external: [/^cloudflare:/, /^node:/], output: { inlineDynamicImports: true } },
    },
  });
  const output = Array.isArray(bundle) ? bundle[0].output : bundle.output;
  const script = output.find(file => file.type === "chunk" && file.isEntry).code;
  const urls = [];
  const mf = new Miniflare(convertV4MiniflareOptions({
    modules: true, script, name: "jot-companion", compatibilityDate: "2026-11-16",
    cf: false, // The relay does not need Request.cf or a network lookup.
    // Short test-only namespace key accommodates Windows temp directory path limits.
    durableObjects: { BUDGET: { className: "CompanionBudget", useSQLite: true, unsafeUniqueKey: "jot" } },
    bindings: { OPENAI_API_KEY: "mock-upstream-key" },
    outboundService: async request => {
      urls.push(request.url);
      assert.equal(request.url, "https://api.openai.com/v1/responses");
      assert.equal(request.headers.get("authorization"), "Bearer mock-upstream-key");
      if (urls.length === 2) return Response.redirect("https://redirect.invalid", 302);
      const { model, instructions, input, store, tools } = await request.json();
      assert.equal(model, "gpt-6-luna");
      assert.equal(store, false);
      assert.deepEqual(tools, []);
      assert.ok(instructions?.length);
      const isAnswer = urls.length === 6;
      assert.deepEqual(JSON.parse(input), isAnswer ? { question, notes: savedNotes } : { note });
      const text = isAnswer ? "The gate code is 42." : JSON.stringify(readResult);
      return Response.json({
        object: "response", model, status: "completed", service_tier: "default",
        usage: { input_tokens: 300, output_tokens: 38, total_tokens: 338 },
        output: [{ type: "message", role: "assistant", content: [{ type: "output_text", text }] }],
      });
    },
  }));
  t.after(() => mf.dispose());
  function call(path, { method = "POST", token, body } = {}) {
    const headers = {};
    if (token) headers.Authorization = "Bearer " + token;
    if (body !== undefined) headers["Content-Type"] = "application/json";
    return mf.dispatchFetch("https://jot.invalid" + path, { method, headers, body: JSON.stringify(body) });
  }
  const sendNote = token => call("/v1/companion", { token, body: { operation: "read", note } });
  assert.equal((await sendNote("wrong")).status, 401);
  assert.equal((await sendNote()).status, 401);

  // Provision fixture sessions directly through the same trusted RPC as verified
  // browser sign-in. All note requests below use the production HTTP entrypoint.
  const namespace = await mf.getDurableObjectNamespace("BUDGET", "jot-companion");
  const ledger = namespace.get(namespace.idFromName("personal"));
  const first = await ledger.issueDevice("verified-fixture-one");
  const second = await ledger.issueDevice("verified-fixture-one");
  const independent = await ledger.issueDevice("verified-fixture-two");
  assert.ok(first.token && second.token && independent.token);
  assert.notEqual(first.token, second.token);
  assert.equal((await call("/v1/companion", { token: first.token, body: { prompt: "arbitrary prompt" } })).status, 400);
  assert.equal(urls.length, 0);
  for (const [index, device] of [first, second, first, second, first].entries()) {
    const result = await sendNote(device.token);
    if (index === 1) {
      assert.equal(result.status, 502);
    } else {
      assert.equal(result.status, 200);
      assert.deepEqual(JSON.parse((await result.json()).text), readResult);
    }
  }
  assert.equal((await sendNote(second.token)).status, 429);
  assert.equal(urls.length, 5);
  const answer = await call("/v1/companion", {
    token: independent.token, body: { operation: "answer", question, notes: savedNotes },
  });
  assert.equal(answer.status, 200);
  assert.deepEqual(await answer.json(), { text: "The gate code is 42." });
  assert.deepEqual(urls, Array(6).fill("https://api.openai.com/v1/responses"));
});
