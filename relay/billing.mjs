// Stripe snapshot events are notifications. Only the current subscription and its paid
// invoice, retrieved using the Worker's key, can change an account's allowance.
const STRIPE_VERSION = "2026-11-18.endive";
const PRICE_CENTS = 499;
const ENCODER = new TextEncoder();
const BILLING_EVENTS = new Set([
  "charge.refunded", "charge.dispute.created", "charge.dispute.closed",
  "customer.subscription.created", "customer.subscription.updated",
  "customer.subscription.deleted", "customer.subscription.paused", "customer.subscription.resumed",
  "invoice.paid", "invoice.payment_failed", "invoice.marked_uncollectible", "invoice.voided",
]);

const storageOf = (ctx) => ctx.storage ?? ctx;
const record = (sql, statement, ...params) => sql.exec(statement, ...params).toArray()[0];
const failure = (status, error) => ({ status, body: { error } });
const unavailable = () => failure(503, "Billing is temporarily unavailable.");
const idMatches = (value, prefix) => typeof value === "string" && value.startsWith(prefix)
  && /^[A-Za-z0-9_]{1,255}$/.test(value) && value.length > prefix.length;

export function initializeBilling(ctx) {
  const { sql } = storageOf(ctx);
  sql.exec(`CREATE TABLE IF NOT EXISTS billing_accounts (
    account_id TEXT PRIMARY KEY, customer_id TEXT UNIQUE, customer_key TEXT,
    paid_until INTEGER NOT NULL DEFAULT 0, needs_management INTEGER NOT NULL DEFAULT 0,
    revision INTEGER NOT NULL DEFAULT 0, event_revision INTEGER NOT NULL DEFAULT 0,
    renews INTEGER NOT NULL DEFAULT 0, period_end INTEGER,
    attempt_key TEXT, attempt_subscription TEXT, attempt_started INTEGER
  )`);
  sql.exec("CREATE TABLE IF NOT EXISTS billing_events (id TEXT PRIMARY KEY, received INTEGER NOT NULL)");
  sql.exec("CREATE INDEX IF NOT EXISTS billing_events_received ON billing_events(received)");
}

function apiConfigured(env) {
  return /^(sk|rk)_(test|live)_[a-zA-Z0-9_]+$/.test(env.STRIPE_SECRET_KEY ?? "");
}

function lifecycleConfigured(env) {
  return apiConfigured(env) && idMatches(env.STRIPE_WEBHOOK_SECRET, "whsec_") &&
    idMatches(env.STRIPE_PRICE_ID, "price_") && idMatches(env.STRIPE_PRODUCT_ID, "prod_");
}

export const billingConfigured = (env) => lifecycleConfigured(env) &&
  idMatches(env.STRIPE_PUBLISHABLE_KEY, isLive(env) ? "pk_live_" : "pk_test_");

export function billingStatus(ctx, env, accountId) {
  const row = record(storageOf(ctx).sql, "SELECT customer_id, needs_management, renews, period_end FROM billing_accounts WHERE account_id=?", accountId);
  return {
    billingAvailable: billingConfigured(env),
    canManageBilling: !!row?.customer_id && !!row.needs_management && apiConfigured(env),
    renews: !!row?.renews,
    periodEnd: row?.period_end ?? null,
  };
}

function isLive(env) {
  return env.STRIPE_SECRET_KEY.split("_")[1] === "live";
}

async function stripe(env, fetchImpl, path, { form, idempotencyKey } = {}) {
  const headers = {
    Authorization: `Bearer ${env.STRIPE_SECRET_KEY}`,
    "Stripe-Version": STRIPE_VERSION,
  };
  if (form) headers["Content-Type"] = "application/x-www-form-urlencoded";
  if (idempotencyKey) headers["Idempotency-Key"] = idempotencyKey;
  const response = await fetchImpl(`https://api.stripe.com/v1/${path}`, {
    method: form ? "POST" : "GET", body: form ? new URLSearchParams(form) : undefined,
    headers, redirect: "manual", signal: AbortSignal.timeout(10_000),
  });
  if (!response.ok) throw new Error("Stripe unavailable");
  return response.json();
}

function expectedPrice(price, env) {
  const product = typeof price?.product === "string" ? price.product : price?.product?.id;
  return price?.id === env.STRIPE_PRICE_ID && product === env.STRIPE_PRODUCT_ID &&
    price.livemode === isLive(env) && price.type === "recurring" && price.billing_scheme === "per_unit" &&
    price.unit_amount === PRICE_CENTS && price.currency === "usd" && !price.transform_quantity &&
    price.recurring?.interval === "month" && price.recurring.interval_count === 1 &&
    price.recurring.usage_type === "licensed";
}

async function verifyOffering(env, fetchImpl) {
  const price = await stripe(env, fetchImpl, `prices/${env.STRIPE_PRICE_ID}?expand[]=product`);
  return expectedPrice(price, env) && price.active === true && price.product.active === true;
}

function planItem(sub, env) {
  if (sub.items?.has_more || sub.items?.data?.length !== 1) return null;
  const [item] = sub.items.data;
  return item.quantity === 1 && expectedPrice(item.price, env) ? item : null;
}

function paidThrough(subscription, env, customerId, now) {
  const item = planItem(subscription, env);
  const invoice = subscription.latest_invoice;
  if (subscription.customer !== customerId || subscription.livemode !== isLive(env) ||
      subscription.status !== "active" || subscription.pause_collection ||
      !item || !Number.isSafeInteger(item.current_period_end) ||
      invoice?.status !== "paid" || invoice.customer !== customerId || invoice.currency !== "usd" ||
      !Number.isSafeInteger(invoice.amount_paid) || invoice.amount_paid < PRICE_CENTS) return 0;
  const ends = [item.current_period_end];
  if (subscription.cancel_at != null) {
    if (!Number.isSafeInteger(subscription.cancel_at)) return 0;
    ends.push(subscription.cancel_at);
  }
  const line = invoice.lines?.data?.find((line) =>
    line.pricing?.price_details?.price === env.STRIPE_PRICE_ID &&
    line.parent?.subscription_item_details?.subscription_item === item.id &&
    Number.isSafeInteger(line.period?.start) && line.period.start <= now &&
    Number.isSafeInteger(line.period?.end) && line.period.end > now);
  return line ? Math.max(0, Math.min(...ends, line.period.end)) : 0;
}

async function hasSettledCharge(env, fetchImpl, customerId, invoiceId) {
  if (!idMatches(invoiceId, "in_")) return false;
  const query = new URLSearchParams({invoice: invoiceId, status: "paid", "expand[]": "data.payment.payment_intent.latest_charge"});
  const payments = await stripe(env, fetchImpl, `invoice_payments?${query}`);
  for (const payment of payments.data) {
    const intent = payment.payment?.payment_intent;
    const charge = intent?.latest_charge;
    if (payment.invoice !== invoiceId || payment.status !== "paid" || payment.currency !== "usd" ||
        !Number.isSafeInteger(payment.amount_paid) || payment.amount_paid < PRICE_CENTS ||
        intent?.customer !== customerId || intent.livemode !== isLive(env) ||
        intent.status !== "succeeded" || charge?.customer !== customerId || charge.status !== "succeeded" ||
        !charge.paid || !Number.isSafeInteger(charge.amount_refunded) || !Number.isSafeInteger(charge.amount) ||
        charge.amount - charge.amount_refunded < PRICE_CENTS) continue;
    if (charge.disputed === false) return true;
    if (!idMatches(charge.id, "ch_")) continue;
    const disputes = await stripe(env, fetchImpl, `disputes?charge=${charge.id}`);
    if (!disputes.has_more && disputes.data?.length && disputes.data.every((d) =>
      ["won", "warning_closed", "prevented"].includes(d.status))) return true;
  }
  return false;
}

function renewalState(subscription) {
  const end = subscription?.items?.data?.[0]?.current_period_end;
  const cancel = subscription?.cancel_at;
  return {
    renews: !!subscription && !subscription.cancel_at_period_end && cancel == null,
    periodEnd: Number.isSafeInteger(end) ? Math.min(end, cancel ?? end) : null,
  };
}

// Each fetch gets a generation before it yields. An older fetch cannot overwrite a
// newer account snapshot. A failed/superseded webhook stays eligible for Stripe retry.
async function reconcile(storage, env, accountId, customerId, now, fetchImpl, eventId) {
  const { sql } = storage;
  const { revision, event_revision } = record(sql, `UPDATE billing_accounts
    SET revision=revision+1, event_revision=event_revision+? WHERE account_id=? AND customer_id=?
    RETURNING revision,event_revision`, Number(!!eventId), accountId, customerId);
  const query = new URLSearchParams({customer: customerId, status: "all", limit: "100", "expand[]": "data.latest_invoice.confirmation_secret"});
  const subscriptions = [];
  for (;;) {
    const page = await stripe(env, fetchImpl, `subscriptions?${query}`);
    if (!Array.isArray(page.data)) throw new Error("Invalid subscription list");
    subscriptions.push(...page.data);
    if (!page.has_more) break;
    const lastId = page.data.at(-1)?.id;
    if (!idMatches(lastId, "sub_") || query.get("starting_after") === lastId) throw new Error("Invalid Stripe cursor");
    query.set("starting_after", lastId);
  }
  const owned = subscriptions.filter((sub) => sub.customer === customerId && sub.livemode === isLive(env));
  const open = owned.filter((sub) => sub.status !== "canceled" && sub.status !== "incomplete_expired");
  const manageable = open.filter((sub) => sub.status !== "incomplete" && planItem(sub, env));
  const management = manageable.length === 1 ? manageable[0] : null;
  const {renews, periodEnd} = renewalState(management);
  let paidUntil = 0;
  if (eventId) {
    for (const sub of open) {
      const end = paidThrough(sub, env, customerId, now);
      if (end > now && await hasSettledCharge(env, fetchImpl, customerId, sub.latest_invoice.id)) paidUntil = Math.max(paidUntil, end);
    }
  }
  storage.transactionSync(() => {
    const updated = record(sql, `UPDATE billing_accounts SET needs_management=?,renews=?,period_end=?
      WHERE account_id=? AND customer_id=? AND revision=? RETURNING account_id`,
    Number(!!management), Number(renews), periodEnd, accountId, customerId, revision);
    if (!updated && !eventId) throw new Error("Subscription snapshot superseded");
    if (eventId) {
      // A UI refresh during an in-flight webhook must never suppress a revocation.
      const committed = record(sql, `UPDATE billing_accounts SET paid_until=?
        WHERE account_id=? AND customer_id=? AND event_revision=? RETURNING account_id`,
      paidUntil, accountId, customerId, event_revision);
      if (!committed) throw new Error("Billing event superseded");
      sql.exec("DELETE FROM billing_events WHERE received < ?", now - 32 * 86_400);
      sql.exec("INSERT OR IGNORE INTO billing_events(id,received) VALUES(?,?)", eventId, now);
    }
  });
  return {open, owned, management};
}

async function getCustomer(sql, env, accountId, fetchImpl) {
  sql.exec("INSERT OR IGNORE INTO billing_accounts(account_id) VALUES(?)", accountId);
  const row = record(sql, `UPDATE billing_accounts SET customer_key=COALESCE(customer_key, ?)
    WHERE account_id=? RETURNING customer_id,customer_key`, crypto.randomUUID(), accountId);
  if (row.customer_id) return row.customer_id;
  const customer = await stripe(env, fetchImpl, "customers", {
    form: { "metadata[application]": "jot" }, idempotencyKey: `jot.customer.${row.customer_key}`,
  });
  if (!idMatches(customer.id, "cus_") || customer.livemode !== isLive(env)) throw new Error("Invalid Stripe customer");
  return record(sql, `UPDATE billing_accounts SET customer_id=COALESCE(customer_id, ?)
    WHERE account_id=? RETURNING customer_id`, customer.id, accountId).customer_id;
}

async function getOrCreateSubscription(storage, env, accountId, customerId, now, fetchImpl) {
  const {open, owned} = await reconcile(storage, env, accountId, customerId, now, fetchImpl);
  if (open.length > 1) return null;
  if (open.length === 1) return open[0];

  const {sql} = storage;
  const current = record(sql, "SELECT attempt_key,attempt_subscription,attempt_started FROM billing_accounts WHERE account_id=?", accountId);
  // A response can be lost after Stripe creates the subscription. Retry the same
  // provider operation until the recorded subscription or its tagged attempt has ended.
  const previous = owned.find((sub) => sub.id === current.attempt_subscription ||
    (current.attempt_key && sub.metadata?.jot_attempt === current.attempt_key));
  if (current.attempt_subscription && !previous) {
    return stripe(env, fetchImpl, `subscriptions/${current.attempt_subscription}?expand[]=latest_invoice.confirmation_secret`);
  }
  if (!current.attempt_key || previous || now - current.attempt_started >= 86_400) {
    sql.exec(`UPDATE billing_accounts SET attempt_key=?,attempt_subscription=NULL,attempt_started=?
      WHERE account_id=? AND attempt_key IS ?`, crypto.randomUUID(), now, accountId, current.attempt_key);
  }
  const { attempt_key: key } = record(sql, "SELECT attempt_key FROM billing_accounts WHERE account_id=?", accountId);
  const form = {
    customer: customerId, currency: "usd", collection_method: "charge_automatically",
    payment_behavior: "default_incomplete", "items[0][price]": env.STRIPE_PRICE_ID, "items[0][quantity]": "1",
    "payment_settings[save_default_payment_method]": "on_subscription",
    "payment_settings[payment_method_types][0]": "card", "expand[]": "latest_invoice.confirmation_secret",
    "metadata[jot_attempt]": key,
  };
  const sub = await stripe(env, fetchImpl, "subscriptions", {form, idempotencyKey: `jot.subscription.${key}`});
  if (!idMatches(sub.id, "sub_") || sub.customer !== customerId || sub.livemode !== isLive(env)) throw new Error("Invalid subscription");
  sql.exec("UPDATE billing_accounts SET attempt_subscription=? WHERE account_id=? AND attempt_key=?", sub.id, accountId, key);
  return sub;
}

export async function billingSubscribe(ctx, env, accountId, {fetchImpl = fetch, now = Date.now} = {}) {
  if (!billingConfigured(env)) return unavailable();
  const storage = storageOf(ctx);
  try {
    if (!await verifyOffering(env, fetchImpl)) return unavailable();
    const customerId = await getCustomer(storage.sql, env, accountId, fetchImpl);
    const sub = await getOrCreateSubscription(storage, env, accountId, customerId, Math.floor(now() / 1000), fetchImpl);
    if (!sub || !["incomplete", "past_due", "unpaid"].includes(sub.status)) {
      return failure(409, "A subscription or payment already exists. Refresh AI settings.");
    }
    const invoice = sub.latest_invoice;
    if (sub.customer !== customerId || sub.livemode !== isLive(env) || !planItem(sub, env) ||
        invoice?.customer !== customerId || invoice.currency !== "usd" ||
        invoice.amount_due !== PRICE_CENTS || invoice.status !== "open") return unavailable();
    const secret = invoice.confirmation_secret?.client_secret;
    const match = typeof secret === "string" && /^(pi_[A-Za-z0-9_]+)_secret_[A-Za-z0-9_]+$/.exec(secret);
    if (!match) return failure(409, "Refresh AI settings to check your payment.");
    const intent = await stripe(env, fetchImpl, `payment_intents/${match[1]}`);
    if (intent.id !== match[1] || intent.livemode !== isLive(env) || intent.customer !== customerId ||
        intent.amount !== PRICE_CENTS || intent.currency !== "usd" || intent.client_secret !== secret) return unavailable();
    if (!["requires_payment_method", "requires_confirmation", "requires_action"].includes(intent.status)) {
      return failure(409, "Payment is already processing or finished. Refresh AI settings.");
    }
    return {status: 200, body: {publishableKey: env.STRIPE_PUBLISHABLE_KEY, paymentIntentClientSecret: secret}};
  } catch { return unavailable(); }
}

export async function billingRenewal(ctx, env, accountId, renews, {fetchImpl = fetch, now = Date.now} = {}) {
  if (typeof renews !== "boolean") return failure(400, "Choose whether this subscription renews.");
  if (!apiConfigured(env)) return unavailable();
  const storage = storageOf(ctx);
  const row = record(storage.sql, "SELECT customer_id FROM billing_accounts WHERE account_id=?", accountId);
  if (!row?.customer_id) return failure(409, "This account has no subscription to manage.");
  try {
    const refresh = () => reconcile(storage, env, accountId, row.customer_id, Math.floor(now() / 1000), fetchImpl);
    let {management} = await refresh();
    if (!management || !idMatches(management.id, "sub_")) return failure(409, "This account has no subscription to manage.");
    if (renewalState(management).renews !== renews) {
      await stripe(env, fetchImpl, `subscriptions/${management.id}`, {form: {
        cancel_at_period_end: String(!renews), proration_behavior: "none",
      }});
      ({management} = await refresh());
    }
    const state = renewalState(management);
    if (!management || state.renews !== renews) return failure(409, "Subscription changed. Refresh AI settings.");
    return {status: 200, body: state};
  } catch { return unavailable(); }
}

async function signedEvent(request, secret, now) {
  const fields = (request.headers.get("stripe-signature") ?? "").split(",").map((part) => part.trim());
  const stamp = fields.find((part) => /^t=\d{1,12}$/.test(part))?.slice(2);
  const signatures = fields.filter((part) => /^v1=[a-f0-9]{64}$/.test(part));
  if (!stamp || !signatures.length || Math.abs(Number(stamp) - now) > 300 || !request.body) return null;
  const reader = request.body.getReader();
  const prefix = ENCODER.encode(stamp + ".");
  const chunks = [prefix];
  let size = prefix.byteLength;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > 1024 * 1024) { await reader.cancel(); return null; }
    chunks.push(value);
  }
  const signed = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { signed.set(chunk, offset); offset += chunk.byteLength; }
  const key = await crypto.subtle.importKey("raw", ENCODER.encode(secret), {name: "HMAC", hash: "SHA-256"}, false, ["verify"]);
  for (const signature of signatures) {
    const bytes = Uint8Array.from(signature.slice(3).match(/../g), (pair) => parseInt(pair, 16));
    if (await crypto.subtle.verify("HMAC", key, bytes, signed)) return JSON.parse(new TextDecoder().decode(signed.subarray(prefix.byteLength)));
  }
  return null;
}

export async function billingWebhook(ctx, env, request, { fetchImpl = fetch, now = Date.now } = {}) {
  if (!lifecycleConfigured(env)) return new Response("Billing unavailable", { status: 503 });
  const seconds = Math.floor(now() / 1000);
  let event;
  try { event = await signedEvent(request, env.STRIPE_WEBHOOK_SECRET, seconds); } catch { /* malformed payload */ }
  if (!event || !idMatches(event.id, "evt_")) return new Response("Invalid event", { status: 400 });
  if (!BILLING_EVENTS.has(event.type) || event.livemode !== isLive(env) || event.account) return new Response(null, {status: 204});
  const storage = storageOf(ctx);
  const { sql } = storage;
  if (record(sql, "SELECT id FROM billing_events WHERE id=?", event.id)) return new Response(null, {status: 204});
  let customer = event.data?.object?.customer;
  if (event.type.startsWith("charge.dispute.") && idMatches(event.data?.object?.charge, "ch_")) {
    try { customer = (await stripe(env, fetchImpl, `charges/${event.data.object.charge}`)).customer; }
    catch { return new Response("Please retry", {status: 503}); }
  }
  if (!idMatches(customer, "cus_")) return new Response(null, {status: 204});
  const row = record(sql, "SELECT account_id FROM billing_accounts WHERE customer_id=?", customer);
  if (row) {
    try { await reconcile(storage, env, row.account_id, customer, seconds, fetchImpl, event.id); }
    catch { return new Response("Please retry", {status: 503}); }
  }
  return new Response(null, {status: 204});
}
