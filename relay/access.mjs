export function initializeAccess(storage) {
  // An account has the free tier until billing records a separate, active entitlement.
  storage.sql.exec("CREATE TABLE IF NOT EXISTS accounts (id TEXT PRIMARY KEY NOT NULL)");
  storage.sql.exec(`CREATE TABLE IF NOT EXISTS devices (
    id TEXT PRIMARY KEY NOT NULL,
    account_id TEXT NOT NULL REFERENCES accounts(id),
    token_digest TEXT UNIQUE NOT NULL,
    revoked_at INTEGER,
    created_at INTEGER NOT NULL
  )`);
  storage.sql.exec("CREATE INDEX IF NOT EXISTS devices_by_account ON devices(account_id)");
}

export async function tokenDigest(token) {
  const bytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token));
  return Array.from(new Uint8Array(bytes), byte => byte.toString(16).padStart(2, "0")).join("");
}

function validAccountId(id) {
  return typeof id === "string" && id.length <= 256 && id.trim().length > 0 && !/[\u0000-\u001f\u007f]/.test(id);
}

export async function prepareDevice() {
  const data = crypto.getRandomValues(new Uint8Array(32));
  const token = btoa(String.fromCharCode(...data)).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
  const digest = await tokenDigest(token);
  return { id: crypto.randomUUID(), digest, token };
}

// Run within the caller's synchronous transaction to consume sign-in proof and issue
// credentials together. The caller syncs storage before returning the token.
export function issuePreparedDevice(storage, accountExternalId, prepared, now = Date.now) {
  if (!validAccountId(accountExternalId)) throw new TypeError("Invalid account identifier.");
  storage.sql.exec("INSERT INTO accounts (id) VALUES (?) ON CONFLICT(id) DO NOTHING", accountExternalId);
  storage.sql.exec(
    "INSERT INTO devices (id, account_id, token_digest, created_at) VALUES (?, ?, ?, ?)",
    prepared.id, accountExternalId, prepared.digest, Math.floor(now() / 1000),
  );
  return { token: prepared.token, id: prepared.id };
}

export async function issueDevice(storage, accountExternalId, now = Date.now) {
  const prepared = await prepareDevice();
  const device = storage.transactionSync(() => issuePreparedDevice(storage, accountExternalId, prepared, now));
  await storage.sync();
  return device;
}

export function accountForDevice(storage, digest) {
  if (typeof digest !== "string") return null;
  const [device] = storage.sql.exec(
    "SELECT account_id FROM devices WHERE token_digest = ? AND revoked_at IS NULL", digest,
  ).toArray();
  return device?.account_id ?? null;
}

// Only trusted administrative code can revoke without specifying the signed-in account.
export async function revokeDevice(storage, id, now = Date.now) {
  if (typeof id !== "string") return false;
  const rows = storage.sql.exec(
    "UPDATE devices SET revoked_at = COALESCE(revoked_at, ?) WHERE id = ? RETURNING id",
    Math.floor(now() / 1000), id,
  ).toArray();
  await storage.sync();
  return rows.length === 1;
}

export async function revokeAccountDevice(storage, accountId, id, now = Date.now) {
  if (!validAccountId(accountId) || typeof id !== "string") return false;
  const rows = storage.sql.exec(
    "UPDATE devices SET revoked_at = COALESCE(revoked_at, ?) WHERE account_id = ? AND id = ? RETURNING id",
    Math.floor(now() / 1000), accountId, id,
  ).toArray();
  await storage.sync();
  return rows.length === 1;
}
