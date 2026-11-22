import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import { DatabaseSync } from "node:sqlite";
import test from "node:test";
import { billingRenewal, billingSubscribe, billingStatus, billingWebhook, initializeBilling } from "./billing.mjs";

const origin = "https://jot-companion.clark719664.workers.dev";
const account = "jot-account-one";
const customerId = "cus_UnitOne";
const subId = "sub_UnitOne";
const nowMs = Date.UTC(2026, 10, 22);
const nowSec = nowMs / 1000;
const periodEnd = nowSec + 30 * 86400;
const env = {
  STRIPE_SECRET_KEY: "sk_test_unit",
  STRIPE_WEBHOOK_SECRET: "whsec_unit",
  STRIPE_PRICE_ID: "price_Unit499",
  STRIPE_PRODUCT_ID: "prod_UnitJot",
  STRIPE_PUBLISHABLE_KEY: "pk_test_unit",
};

function database(t) {
  const db = new DatabaseSync(":memory:");
  t.after(() => db.close());
  db.exec("CREATE TABLE accounts(id TEXT PRIMARY KEY)");
  db.prepare("INSERT INTO accounts VALUES (?)").run(account);
  const storage = {
    sql: {
      exec(query, ...params) {
        const before = db.prepare("SELECT total_changes() AS n").get().n;
        let rows;
        if (!params.length && /;\s*\S/.test(query.trim().replace(/;$/, ""))) {
          db.exec(query);
          rows = [];
        } else {
          rows = db.prepare(query).all(...params);
        }
        return Object.assign(rows, {
          toArray: () => [...rows],
          one() { assert.equal(rows.length, 1); return rows[0]; },
          rowsWritten: db.prepare("SELECT total_changes() AS n").get().n - before,
        });
      },
    },
    transactionSync(run) {
      db.exec("BEGIN IMMEDIATE");
      try {
        const value = run();
        db.exec("COMMIT");
        return value;
      } catch (error) {
        db.exec("ROLLBACK");
        throw error;
      }
    },
  };
  initializeBilling(storage);
  return { db, storage };
}

function signedRequest(event, timestamp = nowSec, key = env.STRIPE_WEBHOOK_SECRET) {
  const body = JSON.stringify(event);
  const digest = createHmac("sha256", key).update(timestamp + "." + body).digest("hex");
  return new Request(origin + "/billing/webhook", {
    method: "POST",
    headers: { "Content-Type": "application/json", "Stripe-Signature": "t=" + timestamp + ",v1=" + digest },
    body,
  });
}

function fixture(t) {
  const { db, storage } = database(t);
  const requests = [];
  const product = { id: env.STRIPE_PRODUCT_ID, object: "product", active: true, livemode: false };
  const price = {
    id: env.STRIPE_PRICE_ID, object: "price", type: "recurring", active: true, livemode: false,
    product: product.id, unit_amount: 499, billing_scheme: "per_unit", currency: "usd",
    recurring: { interval: "month", interval_count: 1, usage_type: "licensed" },
  };
  const intent = { id: "pi_UnitOne", object: "payment_intent", livemode: false,
    status: "requires_payment_method", customer: customerId, currency: "usd",
    amount: 499, client_secret: "pi_UnitOne_secret_unit" };
  const charge = { id: "ch_UnitOne", object: "charge", customer: customerId,
    amount: 499, amount_refunded: 0, refunded: false, currency: "usd",
    status: "succeeded", paid: true, disputed: false, livemode: false };
  const subscription = {
    id: subId, object: "subscription", customer: customerId, status: "active", livemode: false,
    collection_method: "charge_automatically", cancel_at_period_end: false,
    metadata: {}, items: { object: "list", has_more: false,
      data: [{ id: "si_UnitOne", quantity: 1, price: { ...price }, current_period_start: nowSec, current_period_end: periodEnd }] },
    latest_invoice: { id: "in_UnitOne", object: "invoice", status: "paid", paid: true, amount_paid: 499,
      amount_due: 499, amount_remaining: 0, currency: "usd", customer: customerId, livemode: false,
      confirmation_secret: { type: "payment_intent", client_secret: intent.client_secret },
      parent: { type: "subscription_details", subscription_details: { subscription: subId } },
      lines: { has_more: false, data: [{ id: "il_UnitOne", amount: 499, currency: "usd",
        pricing: { type: "price_details", price_details: { price: price.id, product: product.id } },
        parent: { type: "subscription_item_details",
          subscription_item_details: { subscription: subId, subscription_item: "si_UnitOne", proration: false } },
        period: { start: nowSec, end: periodEnd },
      }] } },
  };
  const customer = { id: customerId, object: "customer", livemode: false, metadata: {} };
  const provider = {
    price, product, intent, charge, subscription, requests, subscribers: [], disputes: [], created: new Map(),
    // The fixture accepts only endpoints used by the integration. An unexpected
    // request is recorded so assertions surface it even when the relay wraps errors.
    async fetchImpl(input, init) {
      const request = new Request(input, init);
      const url = new URL(request.url);
      const form = new URLSearchParams(request.method === "GET" ? "" : await request.text());
      requests.push({ url, method: request.method, form, headers: request.headers });
      assert.equal(url.origin, "https://api.stripe.com");
      assert.equal(request.headers.get("Authorization"), "Bearer " + env.STRIPE_SECRET_KEY);
      let result;
      switch (request.method + " " + url.pathname) {
        case "GET /v1/prices/" + env.STRIPE_PRICE_ID:
          result = { ...price, product: url.searchParams.getAll("expand[]").includes("product") ? product : price.product };
          break;
        case "GET /v1/products/" + env.STRIPE_PRODUCT_ID: result = product; break;
        case "POST /v1/customers":
          for (const [key, value] of form) {
            const field = key.match(/^metadata\[(.+)\]$/);
            if (field) customer.metadata[field[1]] = value;
          }
          result = customer;
          break;
        case "GET /v1/customers/" + customerId: result = customer; break;
        case "GET /v1/subscriptions":
          assert.equal(url.searchParams.get("customer"), customerId);
          assert.equal(url.searchParams.get("status"), "all");
          if (provider.lookupFails) return Response.json({ error: {} }, { status: 503 });
          result = { object: "list", has_more: false, data: structuredClone(provider.subscribers) };
          if (provider.hideNextSubscriptionList) {
            provider.hideNextSubscriptionList = false;
            result.data = [];
          }
          if (provider.onSubscriptionRead) await provider.onSubscriptionRead();
          break;
        case "POST /v1/subscriptions": {
          const key = request.headers.get("Idempotency-Key");
          assert.ok(key, "subscription creation must be idempotent");
          if (provider.created.has(key)) {
            result = provider.created.get(key);
            break;
          }
          result = structuredClone(subscription);
          result.metadata.jot_attempt = form.get("metadata[jot_attempt]");
          if (provider.created.size) {
            const suffix = "Unit" + (provider.created.size + 1);
            provider.intent = { ...intent, id: "pi_" + suffix, client_secret: "pi_" + suffix + "_secret_unit" };
            result.id = "sub_" + suffix;
            result.latest_invoice.id = "in_" + suffix;
            result.latest_invoice.confirmation_secret.client_secret = provider.intent.client_secret;
          }
          result.status = "incomplete";
          result.latest_invoice.status = "open";
          result.latest_invoice.paid = false;
          result.latest_invoice.amount_paid = 0;
          result.latest_invoice.amount_remaining = 499;
          provider.subscribers.push(result);
          provider.created.set(key, structuredClone(result));
          if (provider.loseNextCreateResponse) {
            provider.loseNextCreateResponse = false;
            throw new Error("connection closed after Stripe committed");
          }
          break;
        }
        case "POST /v1/subscriptions/" + subId: {
          const current = provider.subscribers.find(sub => sub.id === subId);
          current.cancel_at_period_end = form.get("cancel_at_period_end") === "true";
          current.cancel_at = current.cancel_at_period_end ? periodEnd : null;
          result = current;
          break;
        }
        case "GET /v1/payment_intents/" + provider.intent.id: result = provider.intent; break;
        case "GET /v1/invoices/in_UnitOne": result = subscription.latest_invoice; break;
        case "GET /v1/invoice_payments":
          assert.equal(url.searchParams.get("invoice"), subscription.latest_invoice.id);
          assert.equal(url.searchParams.get("status"), "paid");
          result = { has_more: false, data: [{ id: "inpay_UnitOne", status: "paid",
            invoice: subscription.latest_invoice.id, amount_paid: 499, currency: "usd", livemode: false,
            payment: { type: "payment_intent", payment_intent: {
              ...intent, status: "succeeded", amount_received: 499, latest_charge: charge,
            } },
          }] };
          break;
        case "GET /v1/charges/" + charge.id: result = charge; break;
        case "GET /v1/disputes":
          assert.equal(url.searchParams.get("charge"), charge.id);
          result = { object: "list", data: provider.disputes, has_more: false };
          break;
        default: throw new Error("Unexpected Stripe endpoint " + request.method + " " + url);
      }
      return Response.json(result);
    },
  };
  const deps = { now: () => nowMs, fetchImpl: provider.fetchImpl };
  let sequence = 0;
  function event(type = "customer.subscription.updated", object = subscription) {
    return { id: "evt_Unit" + (++sequence), object: "event", type, livemode: false, created: nowSec, data: { object: structuredClone(object) } };
  }
  return {
    provider, storage, db, deps, event,
    subscribe: (settings = env) => billingSubscribe(storage, settings, account, deps),
    renewal: (renews, settings = env) => billingRenewal(storage, settings, account, renews, deps),
    publishSubscription() { provider.subscribers.splice(0, provider.subscribers.length, provider.subscription); },
    webhook: req => billingWebhook(storage, env, req, deps),
    paidUntil: () => db.prepare("SELECT paid_until FROM billing_accounts WHERE account_id = ?").get(account)?.paid_until ?? 0,
  };
}

async function startPayment(f) {
  const response = await f.subscribe();
  assert.equal(response.status, 200, JSON.stringify({ response, paths: f.provider.requests.map(row => row.url.pathname) }));
  assert.deepEqual(response.body, {
    publishableKey: env.STRIPE_PUBLISHABLE_KEY, paymentIntentClientSecret: f.provider.intent.client_secret,
  });
}

test("unconfigured billing disables purchase, grants no entitlement and makes no provider requests", async t => {
  const f = fixture(t);
  const status = billingStatus(f.storage, {}, account);
  assert.equal(status.billingAvailable, false);
  assert.equal(status.canManageBilling, false);
  assert.equal((await f.subscribe({})).status, 503);
  assert.equal(f.paidUntil(), 0);
  assert.equal(f.provider.requests.length, 0);
});

test("workerd sends a card subscription to Stripe and rejects redirects without contacting the redirect target", async t => {
  const { Miniflare, convertV4MiniflareOptions } = await import("miniflare");
  const { fileURLToPath } = await import("node:url");
  const f = fixture(t);
  const contacted = [];
  let redirect = false;
  const mf = new Miniflare(convertV4MiniflareOptions({
    name: "billing-test", compatibilityDate: "2026-11-16", cf: false,
    modulesRoot: fileURLToPath(new URL(".", import.meta.url)),
    modules: [
      {type: "ESModule", path: fileURLToPath(new URL("test-worker.mjs", import.meta.url)), contents: `
        import { DurableObject } from "cloudflare:workers";
        import { initializeBilling, billingSubscribe } from "./billing.mjs";
        export class Billing extends DurableObject {
          async purchase() {
            initializeBilling(this.ctx.storage);
            return billingSubscribe(this.ctx.storage, this.env, "fixture-account");
          }
        }
        export default { async fetch(_request, env) {
          const result = await env.BILLING.getByName("test").purchase();
          return Response.json(result.body, { status: result.status });
        }};
      `},
      {type: "ESModule", path: fileURLToPath(new URL("billing.mjs", import.meta.url))},
    ],
    durableObjects: { BILLING: { className: "Billing", useSQLite: true, unsafeUniqueKey: "b" } },
    bindings: env,
    outboundService: async request => {
      contacted.push(request.url);
      if (redirect) return Response.redirect("https://redirect.invalid/capture", 307);
      return f.provider.fetchImpl(request.url, {
        method: request.method, headers: new Headers([...request.headers]),
        body: request.method === "GET" ? undefined : await request.text(),
      });
    },
  }));
  t.after(() => mf.dispose());
  const purchase = () => mf.dispatchFetch("https://jot.invalid/billing/subscribe", { method: "POST" });
  const success = await purchase();
  assert.equal(success.status, 200, JSON.stringify(contacted));
  assert.equal((await success.json()).paymentIntentClientSecret, f.provider.intent.client_secret);
  assert.ok(contacted.some(url => url.endsWith("/subscriptions")));
  contacted.length = 0;
  redirect = true;
  const blocked = await purchase();
  assert.equal(blocked.status, 503);
  assert.deepEqual(await blocked.json(), {error: "Billing is temporarily unavailable."});
  assert.equal(contacted.length, 1);
  assert.equal(new URL(contacted[0]).origin, "https://api.stripe.com");
});

test("in-app purchase uses the server price, returns only PaymentSheet credentials and grants no paid time when opened", async t => {
  const f = fixture(t);
  await startPayment(f);
  const create = f.provider.requests.find(r => r.method === "POST" && r.url.pathname === "/v1/subscriptions");
  assert.equal(create.form.get("customer"), customerId);
  assert.equal(create.form.get("payment_behavior"), "default_incomplete");
  const methods = [...create.form].filter(([key]) => key.startsWith("payment_settings[payment_method_types]"));
  assert.deepEqual(methods, [["payment_settings[payment_method_types][0]", "card"]]);
  assert.equal(create.form.get("items[0][price]"), env.STRIPE_PRICE_ID);
  assert.equal(create.form.get("payment_settings[save_default_payment_method]"), "on_subscription");
  assert.equal(create.form.toString().includes(account), false);
  assert.ok(create.headers.get("Idempotency-Key"));
  assert.equal(f.paidUntil(), 0);
});

test("opening PaymentSheet twice reuses the first incomplete subscription and processing cannot start a second purchase", async t => {
  const f = fixture(t);
  await startPayment(f);
  await startPayment(f);
  assert.equal(f.provider.requests.filter(r => r.method === "POST" && r.url.pathname === "/v1/subscriptions").length, 1);
  f.provider.intent.status = "processing";
  assert.equal((await f.subscribe()).status, 409);
  assert.equal(f.paidUntil(), 0);
});

test("an ambiguous create response retries the same Stripe operation even before the new subscription reaches its list", async t => {
  const f = fixture(t);
  f.provider.loseNextCreateResponse = true;
  assert.equal((await f.subscribe()).status, 503);
  assert.equal(f.provider.created.size, 1);
  f.provider.hideNextSubscriptionList = true;
  await startPayment(f);
  const creates = f.provider.requests.filter(r => r.method === "POST" && r.url.pathname === "/v1/subscriptions");
  assert.equal(creates.length, 2);
  assert.equal(creates[0].headers.get("Idempotency-Key"), creates[1].headers.get("Idempotency-Key"));
  assert.equal(f.provider.created.size, 1);
  assert.equal(f.provider.subscribers.length, 1);
  assert.equal(f.paidUntil(), 0);
});

test("simultaneous first purchases share one subscription and can safely reopen PaymentSheet", async t => {
  const f = fixture(t);
  const receipts = await Promise.all([f.subscribe(), f.subscribe()]);
  assert.ok(receipts.some(r => r.status === 200));
  assert.ok(receipts.every(r => [200, 503].includes(r.status)));
  await startPayment(f);
  assert.equal(f.provider.created.size, 1);
  assert.equal(f.provider.subscribers.length, 1);
  assert.equal(f.paidUntil(), 0);
});

test("a previous canceled or expired subscription can be purchased again with a new attempt", async t => {
  for (const previous of ["canceled", "incomplete_expired"]) {
    await t.test(previous, async child => {
      const f = fixture(child);
      await startPayment(f);
      const originalSecret = f.provider.intent.client_secret;
      f.provider.subscribers[0].status = previous;
      await startPayment(f);
      assert.equal(f.provider.created.size, 2);
      assert.notEqual(f.provider.intent.client_secret, originalSecret);
      assert.deepEqual(f.provider.subscribers.map(s => s.status), [previous, "incomplete"]);
      assert.equal(f.paidUntil(), 0);
    });
  }
});

test("fresh verified subscription events follow Stripe's current paid status regardless of delivery order", async t => {
  const f = fixture(t);
  await startPayment(f);
  const sub = f.provider.subscription;
  f.publishSubscription();
  const first = f.event();
  assert.equal((await f.webhook(signedRequest(first))).status, 204);
  assert.equal(f.paidUntil(), periodEnd);
  initializeBilling(f.storage);
  assert.equal(f.paidUntil(), periodEnd);
  assert.equal(billingStatus(f.storage, env, account).canManageBilling, true);
  assert.equal((await f.webhook(signedRequest(first))).status, 204);
  const staleActive = f.event();
  sub.cancel_at_period_end = true;
  sub.cancel_at = periodEnd;
  assert.equal((await f.webhook(signedRequest(f.event()))).status, 204);
  assert.equal(f.paidUntil(), periodEnd);
  sub.status = "past_due";
  sub.latest_invoice.status = "open";
  assert.equal((await f.webhook(signedRequest(f.event("invoice.payment_failed", sub.latest_invoice)))).status, 204);
  assert.equal(f.paidUntil(), 0);
  assert.equal(billingStatus(f.storage, env, account).canManageBilling, true);
  assert.equal((await f.webhook(signedRequest(staleActive))).status, 204);
  assert.equal(f.paidUntil(), 0);
  sub.status = "active";
  sub.latest_invoice.status = "paid";
  assert.equal((await f.webhook(signedRequest(f.event("invoice.paid", sub.latest_invoice)))).status, 204);
  assert.equal(f.paidUntil(), periodEnd);
  sub.status = "canceled";
  assert.equal((await f.webhook(signedRequest(f.event("customer.subscription.deleted")))).status, 204);
  assert.equal(f.paidUntil(), 0);
});

test("invalid or expired webhook signatures and unknown customers cannot grant paid access", async t => {
  const f = fixture(t);
  await startPayment(f);
  f.publishSubscription();
  const payload = f.event();
  const count = f.provider.requests.length;
  assert.equal((await f.webhook(signedRequest(payload, nowSec, "whsec_other"))).status, 400);
  assert.equal((await f.webhook(signedRequest(payload, nowSec - 600))).status, 400);
  const valid = signedRequest(payload);
  const edited = new Request(valid.url, { method: "POST", headers: valid.headers,
    body: JSON.stringify({ ...payload, type: "invoice.paid" }) });
  assert.equal((await f.webhook(edited)).status, 400);
  assert.equal(f.provider.requests.length, count);
  const other = structuredClone(f.provider.subscription);
  other.customer = "cus_Other";
  assert.equal((await f.webhook(signedRequest(f.event("customer.subscription.updated", other)))).status, 204);
  assert.equal(f.paidUntil(), 0);
});

test("a transient Stripe lookup failure leaves an event retryable", async t => {
  const f = fixture(t);
  await startPayment(f);
  f.publishSubscription();
  const payload = f.event();
  f.provider.lookupFails = true;
  assert.ok((await f.webhook(signedRequest(payload))).status >= 500);
  assert.equal(f.paidUntil(), 0);
  f.provider.lookupFails = false;
  assert.equal((await f.webhook(signedRequest(payload))).status, 204);
  assert.equal(f.paidUntil(), periodEnd);
});

test("overlapping webhooks cannot restore a canceled subscription from a slow earlier Stripe response", async t => {
  const f = fixture(t);
  await startPayment(f);
  const sub = f.provider.subscription;
  f.publishSubscription();
  const started = Promise.withResolvers();
  const release = Promise.withResolvers();
  f.provider.onSubscriptionRead = async () => {
    f.provider.onSubscriptionRead = undefined;
    started.resolve();
    await release.promise;
  };
  const older = f.webhook(signedRequest(f.event()));
  await started.promise;
  sub.status = "canceled";
  const newer = f.webhook(signedRequest(f.event()));
  // A serialized integration waits for the old read. A concurrent one can
  // commit the cancellation now and must reject or refresh the stale snapshot.
  await Promise.race([newer, new Promise(resolve => setTimeout(resolve, 40))]);
  release.resolve();
  await Promise.all([older, newer]);
  assert.equal(f.paidUntil(), 0);
});

test("only the configured paid monthly subscription unlocks AI", async t => {
  const mutations = {
    "wrong subscription price": sub => { sub.items.data[0].price.id = "price_Other"; },
    "wrong product": sub => { sub.items.data[0].price.product = "prod_Other"; },
    "wrong price currency": sub => { sub.items.data[0].price.currency = "eur"; },
    "wrong invoice currency": sub => { sub.latest_invoice.currency = "eur"; },
    "wrong recurring amount": sub => { sub.items.data[0].price.unit_amount = 500; },
    "wrong interval": sub => { sub.items.data[0].price.recurring.interval = "year"; },
    "zero paid": sub => { sub.latest_invoice.amount_paid = 0; },
    "unpaid invoice": sub => { sub.latest_invoice.status = "open"; },
    "trial": sub => { sub.status = "trialing"; },
    "expired period": sub => { sub.items.data[0].current_period_end = nowSec - 1; },
    "previously paid line from an expired period": sub => { sub.latest_invoice.lines.data[0].period.end = nowSec - 1; },
    "unrelated invoice price": sub => { sub.latest_invoice.lines.data[0].pricing.price_details.price = "price_Other"; },
    "unrelated invoice item": sub => { sub.latest_invoice.lines.data[0].parent.subscription_item_details.subscription_item = "si_Other"; },
  };
  for (const [label, change] of Object.entries(mutations)) {
    await t.test(label, async child => {
      const f = fixture(child);
      await startPayment(f);
      f.publishSubscription();
      change(f.provider.subscription);
      assert.equal((await f.webhook(signedRequest(f.event()))).status, 204);
      assert.ok(f.paidUntil() <= nowSec);
    });
  }
});

test("bad Stripe configuration or an unrelated payment intent cannot start PaymentSheet", async t => {
  for (const [label, change] of [
    ["wrong price", provider => { provider.price.unit_amount = 999; }],
    ["foreign payment customer", provider => { provider.intent.customer = "cus_Other"; }],
    ["incorrect payment amount", provider => { provider.intent.amount = 999; }],
    ["incorrect payment currency", provider => { provider.intent.currency = "eur"; }],
    ["live intent in test mode", provider => { provider.intent.livemode = true; }],
    ["mismatched intent confirmation secret", provider => { provider.intent.client_secret = "pi_UnitOne_secret_unrelated"; }],
  ]) {
    await t.test(label, async child => {
      const f = fixture(child);
      change(f.provider);
      const result = await f.subscribe();
      assert.ok(result.status >= 400);
      assert.equal(result.body?.paymentIntentClientSecret, undefined);
      assert.equal(f.paidUntil(), 0);
    });
  }
});

test("in-app cancellation preserves the paid period and permits renewal before expiry", async t => {
  const f = fixture(t);
  assert.ok((await f.renewal(false)).status >= 400);
  await startPayment(f);
  f.publishSubscription();
  await f.webhook(signedRequest(f.event()));
  assert.equal(f.paidUntil(), periodEnd);
  for (const renews of [false, true, false]) {
    const result = await f.renewal(renews);
    assert.equal(result.status, 200);
    assert.deepEqual(result.body, { renews, periodEnd });
    const state = billingStatus(f.storage, env, account);
    assert.equal(state.renews, renews);
    assert.equal(state.periodEnd, periodEnd);
    assert.equal(f.paidUntil(), periodEnd);
  }
  assert.deepEqual(f.provider.requests.filter(r => r.url.pathname === "/v1/subscriptions/" + subId)
    .map(r => r.form.get("cancel_at_period_end")), ["true", "false", "true"]);
});

test("a refund or an open dispute removes paid access while an old charge refund leaves the current paid period intact", async t => {
  const f = fixture(t);
  await startPayment(f);
  f.publishSubscription();
  const { charge } = f.provider;
  assert.equal((await f.webhook(signedRequest(f.event("invoice.paid", f.provider.subscription.latest_invoice)))).status, 204);
  assert.equal(f.paidUntil(), periodEnd);
  const oldCharge = { ...charge, id: "ch_Old", amount_refunded: 499, refunded: true };
  assert.equal((await f.webhook(signedRequest(f.event("charge.refunded", oldCharge)))).status, 204);
  assert.equal(f.paidUntil(), periodEnd);
  charge.disputed = true;
  const dispute = { id: "dp_UnitOne", object: "dispute", charge: charge.id, status: "needs_response", livemode: false };
  f.provider.disputes.push(dispute);
  assert.equal((await f.webhook(signedRequest(f.event("charge.dispute.created", dispute)))).status, 204);
  assert.equal(f.paidUntil(), 0);
  dispute.status = "won";
  assert.equal((await f.webhook(signedRequest(f.event("charge.dispute.closed", dispute)))).status, 204);
  assert.equal(f.paidUntil(), periodEnd);
  charge.amount_refunded = 499;
  charge.refunded = true;
  assert.equal((await f.webhook(signedRequest(f.event("charge.refunded", charge)))).status, 204);
  assert.equal(f.paidUntil(), 0);
});
