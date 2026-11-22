import {
  generateAuthenticationOptions, generateRegistrationOptions,
  verifyAuthenticationResponse, verifyRegistrationResponse,
} from "@simplewebauthn/server";
import { decodeAttestationObject } from "@simplewebauthn/server/helpers";
import { issuePreparedDevice, prepareDevice, tokenDigest } from "./access.mjs";
import { readJson } from "./companion.mjs";
import { authenticationPage } from "./auth-page.mjs";
import {
  AuthError, base64url, bytesOf, canonicalOrigin, clientDigest, countAttempt,
  expired, limited, randomToken, requireToken, secureHeaders,
} from "./auth-security.mjs";

const minute = 60_000;
const dailyEnrollments = 4;
const algorithms = [-7, -257]; // ES256 and RS256
const invalidProof = () => new AuthError(403, "The passkey could not be verified.");

export function initializeAccountAuth(storage) {
  storage.sql.exec(`CREATE TABLE IF NOT EXISTS auth_tickets (
    ticket TEXT PRIMARY KEY, poll TEXT NOT NULL UNIQUE, expires INTEGER NOT NULL,
    phase TEXT NOT NULL DEFAULT 'open', attempts INTEGER NOT NULL DEFAULT 0,
    mode TEXT, challenge TEXT, handle TEXT, account TEXT, receipt TEXT)`);
  storage.sql.exec("CREATE INDEX IF NOT EXISTS auth_ticket_expiry ON auth_tickets(expires)");
  storage.sql.exec(`CREATE TABLE IF NOT EXISTS auth_passkeys (
    id TEXT PRIMARY KEY, account TEXT NOT NULL UNIQUE, public_key TEXT NOT NULL, counter INTEGER NOT NULL)`);
  storage.sql.exec("CREATE TABLE IF NOT EXISTS auth_hmac (singleton INTEGER PRIMARY KEY CHECK(singleton=1), secret TEXT NOT NULL)");
  storage.sql.exec(`CREATE TABLE IF NOT EXISTS auth_limits (
    identity TEXT NOT NULL, kind TEXT NOT NULL, attempts INTEGER NOT NULL, expires INTEGER NOT NULL,
    PRIMARY KEY(identity, kind))`);
  storage.sql.exec("CREATE INDEX IF NOT EXISTS auth_limit_expiry ON auth_limits(expires)");
}

function json(data, status = 200) {
  return Response.json(data, {status, headers: secureHeaders()});
}

async function start(request, storage, rp) {
  const ticket = randomToken();
  const pollToken = randomToken();
  const [ticketHash, pollHash, network] = await Promise.all([
    tokenDigest(ticket), tokenDigest(pollToken), clientDigest(storage, request),
  ]);
  const now = Date.now();
  const expiresAt = now + 5 * minute;
  storage.transactionSync(() => {
    storage.sql.exec("DELETE FROM auth_tickets WHERE expires <= ?", now);
    storage.sql.exec("DELETE FROM auth_limits WHERE expires <= ?", now);
    if (storage.sql.exec("SELECT count(*) AS n FROM auth_tickets").one().n >= 1024) throw limited();
    countAttempt(storage, network, "start", 10, 10 * minute, now);
    storage.sql.exec("INSERT INTO auth_tickets(ticket, poll, expires) VALUES (?, ?, ?)", ticketHash, pollHash, expiresAt);
  });
  await storage.sync();
  return json({browserUrl: rp.origin + "/auth#" + ticket, pollToken, expiresAt});
}

async function options(request, body, storage, rp) {
  if (body.mode !== "register" && body.mode !== "login") throw new AuthError(400, "Invalid sign-in request.");
  const [hash, network] = await Promise.all([tokenDigest(requireToken(body.ticket)), clientDigest(storage, request)]);
  const register = body.mode === "register";
  const handle = register ? randomToken() : null;
  const challenge = randomToken();
  storage.transactionSync(() => {
    const now = Date.now();
    const row = storage.sql.exec(`UPDATE auth_tickets SET mode=?, handle=?, challenge=?, phase='challenge', attempts=attempts+1
      WHERE ticket=? AND expires>? AND phase IN ('open','challenge') AND attempts<6 RETURNING ticket`,
      body.mode, handle, challenge, hash, now).toArray();
    if (!row.length) throw expired();
    countAttempt(storage, network, "options", 20, 10 * minute, now);
    if (register && storage.sql.exec(`SELECT 1 FROM auth_limits WHERE identity=? AND kind='register'
      AND attempts>=? AND expires>?`, network, dailyEnrollments, now).toArray().length) throw limited();
  });
  const common = {rpID: rp.hostname, challenge: bytesOf(challenge), timeout: 90_000};
  const publicKey = register ? await generateRegistrationOptions({
    ...common, rpName: "Jot", userID: bytesOf(handle), userName: "Jot " + handle.slice(0, 6),
    attestationType: "none", supportedAlgorithmIDs: algorithms,
    authenticatorSelection: {residentKey: "required", requireResidentKey: true, userVerification: "required"},
  }) : await generateAuthenticationOptions({...common, userVerification: "required"});
  await storage.sync();
  return json({publicKey});
}

async function checkCredential(storage, record, credential, rp) {
  const common = {
    response: credential, expectedChallenge: record.challenge, expectedOrigin: rp.origin,
    expectedRPID: rp.hostname, requireUserVerification: true,
  };
  try {
    if (!credential || typeof credential.id !== "string" || credential.id.length > 1400 ||
        credential.id !== credential.rawId || credential.type !== "public-key") throw invalidProof();
    const data = JSON.parse(new TextDecoder().decode(bytesOf(credential.response.clientDataJSON)));
    if (data.crossOrigin === true || data.topOrigin !== undefined) throw invalidProof();
    if (record.mode === "register") {
      // The RP requests anonymous attestation. Accept the browser's anonymized or
      // self-attested response without processing third-party certificate chains.
      const attestation = decodeAttestationObject(bytesOf(credential.response.attestationObject));
      const format = attestation.get("fmt");
      const statement = attestation.get("attStmt");
      const selfAttested = format === "packed" && !statement.has("x5c") && !statement.has("ecdaaKeyId");
      if (format !== "none" && !selfAttested) throw invalidProof();
      const result = await verifyRegistrationResponse({...common, supportedAlgorithmIDs: algorithms});
      if (!result.verified || !result.registrationInfo) throw invalidProof();
      const key = result.registrationInfo.credential;
      if (key.id !== credential.id) throw invalidProof();
      return {id: key.id, account: record.handle, publicKey: base64url(key.publicKey), counter: key.counter};
    }
    const [key] = storage.sql.exec("SELECT * FROM auth_passkeys WHERE id=?", credential.id).toArray();
    // This username-less ceremony requires the discoverable credential's original user handle.
    if (!key || credential.response.userHandle !== key.account) throw invalidProof();
    const result = await verifyAuthenticationResponse({
      ...common, credential: {id: key.id, publicKey: bytesOf(key.public_key), counter: key.counter},
    });
    if (!result.verified) throw invalidProof();
    return {id: key.id, account: key.account, oldCounter: key.counter, counter: result.authenticationInfo.newCounter};
  } catch {
    throw invalidProof();
  }
}

async function verify(request, body, storage, rp) {
  const hash = await tokenDigest(requireToken(body.ticket));
  const [record] = storage.sql.exec(`UPDATE auth_tickets SET phase='checking'
    WHERE ticket=? AND expires>? AND phase='challenge' RETURNING mode, challenge, handle`, hash, Date.now()).toArray();
  if (!record) throw expired();
  try {
    const key = await checkCredential(storage, record, body.credential, rp);
    const receipt = randomToken();
    const digest = await tokenDigest(receipt);
    const network = record.mode === "register" ? await clientDigest(storage, request) : null;
    const now = Date.now();
    storage.transactionSync(() => {
      const ready = storage.sql.exec(`UPDATE auth_tickets SET phase='approved', challenge=NULL, handle=NULL,
        account=?, receipt=?, expires=min(expires,?) WHERE ticket=? AND expires>? AND phase='checking' RETURNING ticket`,
        key.account, digest, now + 2 * minute, hash, now).toArray();
      if (!ready.length) throw expired();
      if (record.mode === "register") {
        countAttempt(storage, network, "register", dailyEnrollments, 24 * 60 * minute, now);
        const saved = storage.sql.exec(`INSERT INTO auth_passkeys(id, account, public_key, counter)
          VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING RETURNING id`,
          key.id, key.account, key.publicKey, key.counter).toArray();
        if (!saved.length) throw invalidProof();
      } else {
        const saved = storage.sql.exec(`UPDATE auth_passkeys SET counter=? WHERE id=? AND counter=? RETURNING id`,
          key.counter, key.id, key.oldCounter).toArray();
        if (!saved.length) throw invalidProof();
      }
    });
    await storage.sync();
    return json({receipt});
  } catch (error) {
    // An unsuccessful verification also consumes the challenge. A fresh ceremony can be opened.
    storage.sql.exec("UPDATE auth_tickets SET phase='open', challenge=NULL, handle=NULL WHERE ticket=? AND phase='checking'", hash);
    throw error;
  }
}

async function poll(body, storage) {
  const pollHash = await tokenDigest(requireToken(body.pollToken));
  const [record] = storage.sql.exec("SELECT receipt FROM auth_tickets WHERE poll=? AND expires>?", pollHash, Date.now()).toArray();
  if (!record) throw expired();
  if (body.receipt === undefined) return json({pending: true}, 202);
  const digest = await tokenDigest(requireToken(body.receipt));
  if (digest !== record.receipt) throw expired();
  const prepared = await prepareDevice();
  const device = storage.transactionSync(() => {
    const [claimed] = storage.sql.exec(`DELETE FROM auth_tickets WHERE poll=? AND receipt=? AND
      phase='approved' AND expires>? RETURNING account`, pollHash, digest, Date.now()).toArray();
    if (!claimed) throw expired();
    return issuePreparedDevice(storage, claimed.account, prepared);
  });
  await storage.sync();
  return json(device);
}

export async function handleAccountRequest(request, storage, env) {
  try {
    const rp = canonicalOrigin(env);
    const url = new URL(request.url);
    if (url.origin !== rp.origin) return json({error: "Unknown sign-in origin."}, 403);
    if (url.pathname === "/auth" && request.method === "GET") {
      const nonce = randomToken();
      return new Response(authenticationPage(nonce), {
        headers: {...secureHeaders(nonce), "content-type": "text/html; charset=utf-8"},
      });
    }
    const path = url.pathname;
    const native = path === "/v1/auth/start" || path === "/v1/auth/poll";
    if (!native && path !== "/v1/auth/options" && path !== "/v1/auth/verify") return json({error: "Not found."}, 404);
    if (request.method !== "POST") return json({error: "Use POST."}, 405);
    const origin = request.headers.get("origin");
    if ((!native || origin !== null) && origin !== rp.origin) return json({error: "Invalid origin."}, 403);
    if (request.headers.get("content-type")?.split(";")[0].trim().toLowerCase() !== "application/json")
      return json({error: "Send JSON."}, 415);
    let body;
    try { body = await readJson(request, path === "/v1/auth/verify" ? 64 * 1024 : 1024); }
    catch (error) { return json({error: "Invalid request body."}, error instanceof RangeError ? 413 : 400); }
    if (!body || typeof body !== "object" || Array.isArray(body)) return json({error: "Invalid request body."}, 400);
    if (path === "/v1/auth/start") return await start(request, storage, rp);
    if (path === "/v1/auth/poll") return await poll(body, storage);
    if (path === "/v1/auth/options") return await options(request, body, storage, rp);
    return await verify(request, body, storage, rp);
  } catch (error) {
    if (error instanceof AuthError) return json({error: error.message}, error.status);
    return json({error: "Sign-in is temporarily unavailable."}, 503);
  }
}
