export const MODEL = "gpt-6-luna";
// Above any prompt notes.mjs accepts: 16 KiB of note text, escaped to at most six bytes
// per byte, plus instructions and API framing. A token spans at least one byte.
export const MAX_INPUT_TOKENS = 128_000;
export const MAX_OUTPUT_TOKENS = 512; // Includes reasoning tokens.
export const LIMIT_NANODOLLARS = 70_000_000_000;
const FREE_DAILY_CALLS = 5;
export const PAID_DAILY_CALLS = 100;
const MAX_CALLS_PER_MINUTE = 10;
const MAX_CONCURRENT_CALLS = 2;
const LEASE_MS = 45_000; // Longer than the upstream request timeout.

// Integer nanodollars/token. Use the highest cache-write input price even for uncached
// tokens, plus the published 10% regional uplift. Long-context premiums apply to all tokens.
// https://developers.openai.com/api/docs/models/gpt-6-luna
// https://developers.openai.com/api/docs/pricing
export function cost(inputTokens, outputTokens) {
  return inputTokens > 272_000
    ? inputTokens * 550 + outputTokens * 825
    : inputTokens * 275 + outputTokens * 550;
}

// Reserve for the most expensive tier available on Luna (2x Standard), even though
// the relay requests Standard and only settles a response that confirms that tier.
export const RESERVATION_NANODOLLARS = 2 * cost(MAX_INPUT_TOKENS, MAX_OUTPUT_TOKENS);

export function initializeLedger(storage) {
  const sql = storage.sql;
  // Keep existing monthly balances and unresolved reservations through migrations.
  sql.exec("CREATE TABLE IF NOT EXISTS months (month TEXT PRIMARY KEY, spent INTEGER NOT NULL CHECK (spent >= 0))");
  sql.exec("CREATE TABLE IF NOT EXISTS holds (id TEXT PRIMARY KEY, month TEXT NOT NULL, amount INTEGER NOT NULL CHECK (amount > 0))");
  sql.exec(`CREATE TABLE IF NOT EXISTS account_days (
    account_id TEXT NOT NULL, day TEXT NOT NULL, calls INTEGER NOT NULL CHECK (calls >= 0),
    PRIMARY KEY (account_id, day))`);
  sql.exec(`CREATE TABLE IF NOT EXISTS account_minutes (
    account_id TEXT PRIMARY KEY, minute INTEGER NOT NULL, calls INTEGER NOT NULL CHECK (calls >= 0))`);
  sql.exec(`CREATE TABLE IF NOT EXISTS account_leases (
    hold_id TEXT PRIMARY KEY, account_id TEXT NOT NULL, expires_ms INTEGER NOT NULL,
    free_day TEXT, minute INTEGER NOT NULL)`);
  sql.exec("CREATE INDEX IF NOT EXISTS account_leases_expiry ON account_leases(account_id, expires_ms)");
}

export function monthOf(timeMs) {
  return new Date(timeMs).toISOString().slice(0, 7);
}

function rejected(status, code, error) {
  return { status, body: { code, error } };
}

function deviceUsage(storage, deviceDigest, nowMs) {
  if (typeof deviceDigest !== "string" || !deviceDigest) return null;
  const day = new Date(nowMs).toISOString().slice(0, 10);
  const [row] = storage.sql.exec(
    `SELECT d.id AS device_id, d.account_id, COALESCE(b.paid_until, 0) AS paid_until,
            COALESCE(u.calls, 0) AS calls
       FROM devices d
       JOIN accounts a ON a.id = d.account_id
       LEFT JOIN billing_accounts b ON b.account_id = d.account_id
       LEFT JOIN account_days u ON u.account_id = d.account_id AND u.day = ?
      WHERE d.token_digest = ? AND d.revoked_at IS NULL`, day, deviceDigest,
  ).toArray();
  if (!row) return null;
  return {
    accountId: row.account_id, deviceId: row.device_id, calls: row.calls,
    paid: row.paid_until > Math.floor(nowMs / 1000),
  };
}

export function usageForDevice(storage, deviceDigest, nowMs = Date.now()) {
  const usage = deviceUsage(storage, deviceDigest, nowMs);
  if (!usage) return null;
  return {
    accountId: usage.accountId, deviceId: usage.deviceId, plan: usage.paid ? "paid" : "free",
    freeCallsRemaining: usage.paid ? null : Math.max(0, FREE_DAILY_CALLS - usage.calls),
  };
}

function previousMonth(month) {
  const date = new Date(month + "-01T00:00:00Z");
  date.setUTCMonth(date.getUTCMonth() - 1);
  return monthOf(date.getTime());
}

// An unresolved hold can only have been charged in the month it began or, for a
// call crossing midnight, the next one. Older holds no longer count.
export function hasBudget(storage, month = monthOf(Date.now())) {
  const [{ committed }] = storage.sql.exec(
    `SELECT COALESCE((SELECT spent FROM months WHERE month = ?), 0)
          + COALESCE((SELECT SUM(amount) FROM holds WHERE month >= ?), 0) AS committed`,
    month, previousMonth(month),
  ).toArray();
  return committed + RESERVATION_NANODOLLARS <= LIMIT_NANODOLLARS;
}

export function reserve(storage, month, nowMs, deviceDigest) {
  const sql = storage.sql;
  const day = new Date(nowMs).toISOString().slice(0, 10);
  const minute = Math.floor(nowMs / 60_000);
  return storage.transactionSync(() => {
    const device = deviceUsage(storage, deviceDigest, nowMs);
    if (!device) return rejected(401, "invalid_device", "This device is not authorized.");
    const account = device.accountId;
    if (device.calls >= (device.paid ? PAID_DAILY_CALLS : FREE_DAILY_CALLS)) {
      return rejected(429, "daily_quota", device.paid
        ? "All 100 calls for today have been used." : "All five free calls for today have been used.");
    }
    const [rate] = sql.exec("SELECT minute, calls FROM account_minutes WHERE account_id = ?", account).toArray();
    if (rate && (rate.minute > minute || (rate.minute === minute && rate.calls >= MAX_CALLS_PER_MINUTE))) {
      return rejected(429, "rate_limit", "Too many requests this minute.");
    }
    const [leases] = sql.exec(
      "SELECT COUNT(*) AS active FROM account_leases WHERE account_id = ? AND expires_ms > ?", account, nowMs,
    ).toArray();
    if (leases.active >= MAX_CONCURRENT_CALLS) return rejected(429, "concurrency_limit", "Two calls are already in progress.");
    if (!hasBudget(storage, month)) {
      return rejected(429, "monthly_budget", "Monthly AI budget reached.");
    }

    const id = crypto.randomUUID();
    sql.exec("INSERT INTO holds (id, month, amount) VALUES (?, ?, ?)", id, month, RESERVATION_NANODOLLARS);
    sql.exec("INSERT INTO account_leases (hold_id, account_id, expires_ms, free_day, minute) VALUES (?, ?, ?, ?, ?)",
      id, account, nowMs + LEASE_MS, day, minute);
    sql.exec(`INSERT INTO account_minutes (account_id, minute, calls) VALUES (?, ?, 1)
      ON CONFLICT (account_id) DO UPDATE SET
        calls = CASE WHEN minute = excluded.minute THEN calls + 1 ELSE 1 END, minute = excluded.minute`, account, minute);
    sql.exec(`INSERT INTO account_days (account_id, day, calls) VALUES (?, ?, 1)
      ON CONFLICT (account_id, day) DO UPDATE SET calls = calls + 1`, account, day);
    return { id };
  });
}

// Release only the concurrency slot when an attempt ends. A process crash leaves the
// slot usable after its deadline, while the financial reservation stays fully charged.
export function finishLease(storage, id) {
  storage.sql.exec("DELETE FROM account_leases WHERE hold_id = ?", id);
}

// Use only when the caller knows it never attempted external I/O. An ambiguous
// provider outcome must retain its financial reservation and its daily quota debit.
export function cancelBeforeDispatch(storage, id) {
  storage.transactionSync(() => {
    const sql = storage.sql;
    const [lease] = sql.exec(
      "SELECT account_id, free_day, minute FROM account_leases WHERE hold_id = ?", id,
    ).toArray();
    if (!lease) return;
    if (lease.free_day !== null) {
      sql.exec("UPDATE account_days SET calls = calls - 1 WHERE account_id = ? AND day = ? AND calls > 0",
        lease.account_id, lease.free_day);
    }
    sql.exec("UPDATE account_minutes SET calls = calls - 1 WHERE account_id = ? AND minute = ? AND calls > 0",
      lease.account_id, lease.minute);
    sql.exec("DELETE FROM holds WHERE id = ?", id);
    finishLease(storage, id);
  });
}

// Use when the provider rejected the request before running the model, so no charge
// is possible. The daily and per-minute debits stay, which keeps the abuse limits intact.
export function releaseHold(storage, id) {
  storage.sql.exec("DELETE FROM holds WHERE id = ?", id);
}

export function settle(storage, id, endMonth, amount) {
  storage.transactionSync(() => {
    const [hold] = storage.sql.exec("SELECT month, amount FROM holds WHERE id = ?", id).toArray();
    if (!hold || !Number.isSafeInteger(amount) || amount < 0 || amount > hold.amount || endMonth < hold.month) {
      throw new Error("Invalid settlement");
    }
    // A midnight-spanning call can be attributed to either month. Replace its
    // outstanding full reservation with the confirmed amount in each one.
    const date = new Date(hold.month + "-01T00:00:00Z");
    for (let month = hold.month; month <= endMonth; month = monthOf(date)) {
      storage.sql.exec(
        `INSERT INTO months (month, spent) VALUES (?, ?)
         ON CONFLICT (month) DO UPDATE SET spent = spent + excluded.spent`, month, amount,
      );
      date.setUTCMonth(date.getUTCMonth() + 1);
    }
    storage.sql.exec("DELETE FROM holds WHERE id = ?", id);
    finishLease(storage, id);
  });
}
