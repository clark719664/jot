const productionOrigin = "https://jot-companion.clark719664.workers.dev";
const encoder = new TextEncoder();
const opaque = /^[A-Za-z0-9_-]{43}$/;

export class AuthError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

export function canonicalOrigin(env) {
  const configured = env.JOT_AUTH_ORIGIN ?? productionOrigin;
  const url = new URL(configured);
  if (url.protocol !== "https:" || url.origin !== configured) throw new Error("Invalid Jot RP origin");
  return url;
}

export function base64url(bytes) {
  return btoa(String.fromCharCode(...bytes)).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
}

export function bytesOf(text) {
  return Uint8Array.from(atob(text.replaceAll("-", "+").replaceAll("_", "/")), char => char.charCodeAt(0));
}

export function randomToken() {
  return base64url(crypto.getRandomValues(new Uint8Array(32)));
}

export function requireToken(value) {
  if (typeof value !== "string" || !opaque.test(value)) throw new AuthError(400, "Invalid sign-in request.");
  return value;
}

export function expired() {
  return new AuthError(410, "This sign-in expired. Start again in Jot.");
}

export function limited() {
  return new AuthError(429, "Too many attempts. Try again later.");
}

export function secureHeaders(nonce) {
  return {
    "cache-control": "no-store",
    "referrer-policy": "no-referrer",
    "x-content-type-options": "nosniff",
    "x-frame-options": "DENY",
    "strict-transport-security": "max-age=31536000",
    "permissions-policy": "publickey-credentials-create=(self), publickey-credentials-get=(self)",
    "content-security-policy": "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'" +
      (nonce ? `; script-src 'nonce-${nonce}'; style-src 'nonce-${nonce}'; connect-src 'self'` : ""),
  };
}

// Privacy addresses from the same IPv6 /64 share one limit. Cloudflare supplies
// CF-Connecting-IP on the public Worker; client-supplied forwarding headers are ignored.
function clientNetwork(request) {
  const ip = request.headers.get("cf-connecting-ip") ?? "unknown";
  if (!ip.includes(":")) return ip;
  try {
    const normalized = new URL("https://[" + ip + "]").hostname.slice(1, -1);
    const [left, right] = normalized.split("::");
    const head = left ? left.split(":") : [];
    const tail = right ? right.split(":") : [];
    const groups = [...head, ...Array(8 - head.length - tail.length).fill("0"), ...tail];
    return groups.slice(0, 4).join(":") + "/64";
  } catch {
    return "unknown";
  }
}

export async function clientDigest(storage, request) {
  let [row] = storage.sql.exec("SELECT secret FROM auth_hmac WHERE singleton = 1").toArray();
  if (!row) {
    row = {secret: randomToken()};
    storage.sql.exec("INSERT INTO auth_hmac (singleton, secret) VALUES (1, ?)", row.secret);
  }
  const key = await crypto.subtle.importKey("raw", bytesOf(row.secret), {name: "HMAC", hash: "SHA-256"}, false, ["sign"]);
  const signature = await crypto.subtle.sign("HMAC", key, encoder.encode(clientNetwork(request)));
  return base64url(new Uint8Array(signature));
}

// Called inside the transaction authorizing the corresponding action.
export function countAttempt(storage, digest, kind, limit, duration, now) {
  const row = storage.sql.exec(`INSERT INTO auth_limits (identity, kind, attempts, expires) VALUES (?, ?, 1, ?)
    ON CONFLICT(identity, kind) DO UPDATE SET
      attempts = CASE WHEN expires <= ? THEN 1 ELSE attempts + 1 END,
      expires = CASE WHEN expires <= ? THEN excluded.expires ELSE expires END
    WHERE expires <= ? OR attempts < ? RETURNING attempts`, digest, kind, now + duration, now, now, now, limit).toArray();
  if (!row.length) throw limited();
}
