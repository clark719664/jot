import { DurableObject } from "cloudflare:workers";
import { initializeAccess, issueDevice, revokeAccountDevice, tokenDigest } from "./access.mjs";
import { initializeAccountAuth, handleAccountRequest } from "./accounts.mjs";
import { hasBudget, initializeLedger, usageForDevice } from "./budget.mjs";
import { billingRenewal, billingStatus, billingSubscribe, billingWebhook, initializeBilling } from "./billing.mjs";
import { handleRequest, readJson, runCompanion } from "./companion.mjs";

const routes = new Map([
  ["/auth", "GET"], ["/v1/auth/start", "POST"], ["/v1/auth/poll", "POST"],
  ["/v1/auth/options", "POST"], ["/v1/auth/verify", "POST"],
  ["/v1/account", "GET"], ["/v1/account/device", "DELETE"],
  ["/billing/subscribe", "POST"], ["/billing/renewal", "POST"], ["/billing/webhook", "POST"],
]);
const json = (body, status = 200) => Response.json(body, {status, headers: {"Cache-Control": "no-store"}});

export class CompanionBudget extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    initializeAccess(ctx.storage);
    initializeBilling(ctx);
    initializeLedger(ctx.storage);
    initializeAccountAuth(ctx.storage);
  }

  async companion(prompt, deviceDigest) {
    return runCompanion(this.ctx.storage, prompt, this.env.OPENAI_API_KEY, {deviceDigest});
  }

  issueDevice(accountId) {
    return issueDevice(this.ctx.storage, accountId);
  }

  async fetch(request) {
    const path = new URL(request.url).pathname;
    const storage = this.ctx.storage;
    if (path === "/auth" || path.startsWith("/v1/auth/")) return handleAccountRequest(request, storage, this.env);
    if (path === "/billing/webhook") return billingWebhook(this.ctx, this.env, request);
    const bearer = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(request.headers.get("authorization") || "");
    const account = bearer && usageForDevice(storage, await tokenDigest(bearer[1]));
    if (!account) return json({error: "Sign in to continue."}, 401);
    const {accountId, ...usage} = account;
    if (path === "/v1/account") {
      const billing = billingStatus(this.ctx, this.env, accountId);
      return json({...usage, ...billing, billingAvailable: billing.billingAvailable && hasBudget(storage)});
    }
    if (path === "/v1/account/device") {
      await revokeAccountDevice(storage, accountId, usage.deviceId);
      return new Response(null, {status: 204, headers: {"Cache-Control":"no-store"}});
    }
    if (request.headers.get("content-type")?.split(";")[0].trim().toLowerCase() !== "application/json") {
      return json({error: "JSON required."}, 415);
    }
    let body;
    try { body = await readJson(request, 512); }
    catch { return json({error: "Invalid request."}, 400); }
    if (!body || typeof body !== "object" || Array.isArray(body)) return json({error: "Invalid request."}, 400);
    let result;
    if (path === "/billing/subscribe" && Object.keys(body).length === 0) {
      if (!hasBudget(storage)) return json({code:"monthly_budget", error:"AI is paused for this month."}, 429);
      result = await billingSubscribe(this.ctx, this.env, accountId);
    } else if (path === "/billing/renewal" && Object.keys(body).length === 1 && typeof body.renews === "boolean") {
      result = await billingRenewal(this.ctx, this.env, accountId, body.renews);
    } else {
      return json({error: "Invalid request."}, 400);
    }
    return json(result.body, result.status);
  }
}

export default {
  async fetch(request, env) {
    const {pathname, search} = new URL(request.url);
    if (pathname === "/v1/companion") return handleRequest(request, env);
    if (search || !routes.has(pathname)) return json({error:"Not found."}, 404);
    if (request.method !== routes.get(pathname)) return json({error:"Method not allowed."}, 405);
    try { return await env.BUDGET.getByName("personal").fetch(request); }
    catch { return json({error:"Service temporarily unavailable."}, 503); }
  },
};
