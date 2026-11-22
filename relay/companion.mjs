import { initializeAccess, tokenDigest } from "./access.mjs";
import { initializeBilling } from "./billing.mjs";
import {
  MODEL, MAX_INPUT_TOKENS, MAX_OUTPUT_TOKENS, cost, initializeLedger, monthOf,
  reserve, cancelBeforeDispatch, releaseHold, settle, finishLease,
} from "./budget.mjs";
import { buildNotePrompt } from "./notes.mjs";

export { MODEL, MAX_INPUT_TOKENS, MAX_OUTPUT_TOKENS, LIMIT_NANODOLLARS, RESERVATION_NANODOLLARS, cost } from "./budget.mjs";

export function initializeBudget(storage) {
  initializeAccess(storage);
  initializeBilling({ storage });
  initializeLedger(storage);
}

const failure = (status, error) => ({ status, body: { error } });

function withinCount(value, max) {
  return Number.isSafeInteger(value) && value >= 0 && value <= max;
}

function confirmedUsage(response) {
  if (response?.object !== "response" || response.status !== "completed" ||
      response.model !== MODEL || response.service_tier !== "default" ||
      response.error != null || response.incomplete_details != null) return null;
  const usage = response.usage;
  if (!usage || !withinCount(usage.input_tokens, MAX_INPUT_TOKENS) || !usage.input_tokens ||
      !withinCount(usage.output_tokens, MAX_OUTPUT_TOKENS) || !usage.output_tokens ||
      usage.total_tokens !== usage.input_tokens + usage.output_tokens) return null;
  const cached = usage.input_tokens_details?.cached_tokens ?? 0;
  const written = usage.input_tokens_details?.cache_write_tokens ?? 0;
  const reasoning = usage.output_tokens_details?.reasoning_tokens ?? 0;
  if (!withinCount(cached, usage.input_tokens) || !withinCount(written, usage.input_tokens - cached) ||
      !withinCount(reasoning, usage.output_tokens)) return null;
  return cost(usage.input_tokens, usage.output_tokens);
}

export async function readJson(message, byteLimit) {
  if (Number(message.headers.get("content-length")) > byteLimit) throw new RangeError();
  if (!message.body) throw new SyntaxError();
  const reader = message.body.getReader();
  const decoder = new TextDecoder("utf-8", { fatal: true });
  let size = 0;
  let text = "";
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > byteLimit) throw new RangeError();
      text += decoder.decode(value, { stream: true });
    }
    return JSON.parse(text + decoder.decode());
  } catch (error) {
    await reader.cancel().catch(() => {});
    throw error;
  } finally {
    reader.releaseLock();
  }
}

export async function runCompanion(storage, prompt, apiKey, { now = Date.now, fetchImpl = fetch, timeoutMs = 20_000, deviceDigest } = {}) {
  if (!apiKey) return failure(503, "Relay is not configured.");
  const start = now();
  const reservation = reserve(storage, monthOf(start), start, deviceDigest);
  if (!reservation.id) return reservation;
  const { id } = reservation;
  try {
    await storage.sync();
  } catch (error) {
    cancelBeforeDispatch(storage, id);
    throw error;
  }
  const signal = AbortSignal.timeout(timeoutMs);
  try {
    const upstream = await fetchImpl("https://api.openai.com/v1/responses", {
      method: "POST", redirect: "manual", signal,
      headers: { Authorization: "Bearer " + apiKey, "Content-Type": "application/json" },
      body: JSON.stringify({
        model: MODEL, input: prompt.input, instructions: prompt.instructions, service_tier: "default", reasoning: { effort: "none" },
        max_output_tokens: MAX_OUTPUT_TOKENS, tools: [], store: false, stream: false,
        prompt_cache_options: { mode: "explicit" }, // No explicit breakpoints: no prompt cache.
      }),
    });
    if (!upstream.ok) {
      // OpenAI answers 4xx before running the model, so that request cost nothing.
      if (upstream.status >= 400 && upstream.status < 500) releaseHold(storage, id);
      return failure(502, "AI service unavailable.");
    }
    const response = await readJson(upstream, 256 * 1024);
    const amount = confirmedUsage(response);
    const parts = (response?.output ?? [])
      .filter(item => item?.type === "message" && item.role === "assistant")
      .flatMap(item => item.content ?? [])
      .filter(part => part?.type === "output_text" && typeof part.text === "string");
    const text = parts.map(part => part.text).join("").trim();
    if (amount === null || !text) return failure(502, "AI response unavailable.");
    settle(storage, id, monthOf(now()), amount);
    return { status: 200, body: { text } };
  } catch {
    // A timeout, disconnect, bad response or process death can hide a real charge.
    // The reservation stays on disk and counts against this month and the next.
    return signal.aborted ? failure(504, "AI request timed out.") : failure(502, "AI service unavailable.");
  } finally {
    finishLease(storage, id);
  }
}

function json(result) {
  return Response.json(result.body, { status: result.status, headers: { "Cache-Control": "no-store" } });
}

export async function handleRequest(request, env) {
  const url = new URL(request.url);
  if (url.pathname !== "/v1/companion" || url.search) return json(failure(404, "Not found."));
  if (request.method !== "POST") return json(failure(405, "POST required."));
  const credential = /^Bearer ([A-Za-z0-9_-]{42}[AEIMQUYcgkosw048])$/.exec(request.headers.get("Authorization") ?? "");
  if (!credential) return json({ status: 401, body: { code: "invalid_device", error: "Sign in again." } });
  if (!env.OPENAI_API_KEY) return json(failure(503, "AI service unavailable."));
  if (request.headers.get("content-type")?.split(";")[0].trim().toLowerCase() !== "application/json") {
    return json(failure(415, "JSON required."));
  }
  let prompt;
  try {
    const body = await readJson(request, 192 * 1024);
    prompt = buildNotePrompt(body);
  } catch (error) {
    return json(error instanceof RangeError ? failure(413, "Note request too large.") : failure(400, "Invalid note request."));
  }
  try {
    const digest = await tokenDigest(credential[1]);
    return json(await env.BUDGET.getByName("personal").companion(prompt, digest));
  } catch {
    return json(failure(503, "Relay unavailable."));
  }
}
