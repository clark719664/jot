import assert from "node:assert/strict";
import { createHash, generateKeyPairSync, randomBytes, sign } from "node:crypto";
import { DatabaseSync } from "node:sqlite";
import test from "node:test";
import { isoCBOR } from "@simplewebauthn/server/helpers";
import { accountForDevice, initializeAccess, tokenDigest } from "./access.mjs";
import { handleAccountRequest, initializeAccountAuth } from "./accounts.mjs";

const origin = "https://jot-companion.clark719664.workers.dev";
const rpID = new URL(origin).hostname;
const opaque = /^[A-Za-z0-9_-]{43}$/;
const encode = bytes => Buffer.from(bytes).toString("base64url");
const hash = bytes => createHash("sha256").update(bytes).digest();

function fixture(t) {
  const db = new DatabaseSync(":memory:");
  t.after(() => db.close());
  const storage = {
    sql: {
      exec(statement, ...params) {
        const rows = db.prepare(statement).all(...params);
        return { toArray: () => rows, one: () => rows[0], [Symbol.iterator]: () => rows[Symbol.iterator]() };
      },
    },
    transactionSync(work) {
      db.exec("BEGIN");
      try {
        const value = work();
        db.exec("COMMIT");
        return value;
      } catch (error) {
        db.exec("ROLLBACK");
        throw error;
      }
    },
    async sync() {},
  };
  initializeAccess(storage);
  initializeAccountAuth(storage);

  async function post(path, body, { ip = "192.0.2.7", browserOrigin = origin } = {}) {
    const headers = { "Content-Type": "application/json", "CF-Connecting-IP": ip };
    if (browserOrigin) headers.Origin = browserOrigin;
    const request = new Request(origin + "/v1/auth/" + path, {
      method: "POST", headers, body: JSON.stringify(body),
    });
    const response = await handleAccountRequest(request, storage, {});
    return { status: response.status, body: await response.json() };
  }

  async function start() {
    const result = await post("start", {}, { browserOrigin: null });
    assert.equal(result.status, 200, JSON.stringify(result.body));
    const browser = new URL(result.body.browserUrl);
    assert.equal(browser.origin, origin);
    assert.equal(browser.pathname, "/auth");
    assert.equal(browser.search, "");
    assert.match(browser.hash.slice(1), opaque);
    assert.match(result.body.pollToken, opaque);
    assert.notEqual(browser.hash.slice(1), result.body.pollToken);
    assert.ok(result.body.expiresAt > Date.now());
    return { ticket: browser.hash.slice(1), pollToken: result.body.pollToken, expiresAt: result.body.expiresAt };
  }

  async function begin(mode) {
    const flow = await start();
    const result = await post("options", { ticket: flow.ticket, mode });
    assert.equal(result.status, 200, JSON.stringify(result.body));
    const options = result.body.publicKey;
    if (mode === "register") {
      assert.equal(options.rp.id, rpID);
      assert.equal(options.authenticatorSelection.userVerification, "required");
      assert.equal(options.authenticatorSelection.residentKey, "required");
    } else {
      assert.equal(options.rpId, rpID);
      assert.equal(options.userVerification, "required");
      assert.deepEqual(options.allowCredentials ?? [], []);
    }
    return { ...flow, options };
  }
  return { db, storage, post, start, begin };
}

// A zero-counter, backed-up discoverable passkey, as returned by a synced provider.
function passkey() {
  const keyPair = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  const id = randomBytes(32);
  const publicKey = keyPair.publicKey.export({ format: "jwk" });
  const coseKey = isoCBOR.encode(new Map([
    [1, 2], [3, -7], [-1, 1],
    [-2, new Uint8Array(Buffer.from(publicKey.x, "base64url"))],
    [-3, new Uint8Array(Buffer.from(publicKey.y, "base64url"))],
  ]));
  const envelope = { id: encode(id), rawId: encode(id), type: "public-key", clientExtensionResults: {} };
  let userHandle;
  function credential(options, register, { verified = true, clientOrigin = origin } = {}) {
    const clientData = Buffer.from(JSON.stringify({
      type: register ? "webauthn.create" : "webauthn.get",
      challenge: options.challenge, origin: clientOrigin, crossOrigin: false,
    }));
    const flags = 0x01 | 0x08 | 0x10 | (verified ? 0x04 : 0) | (register ? 0x40 : 0);
    const header = Buffer.concat([hash(rpID), Buffer.from([flags]), Buffer.alloc(4)]);
    const response = { clientDataJSON: encode(clientData) };
    if (register) {
      userHandle = options.user.id;
      const idLength = Buffer.alloc(2);
      idLength.writeUInt16BE(id.length);
      const authData = Buffer.concat([header, Buffer.alloc(16), idLength, id, coseKey]);
      response.attestationObject = encode(isoCBOR.encode(new Map([
        ["fmt", "none"], ["attStmt", new Map()], ["authData", new Uint8Array(authData)],
      ])));
      response.transports = ["internal", "hybrid"];
    } else {
      response.userHandle = userHandle;
      response.authenticatorData = encode(header);
      response.signature = encode(sign("sha256", Buffer.concat([header, hash(clientData)]), keyPair.privateKey));
    }
    return { ...envelope, response };
  }
  return {
    register: (options, overrides) => credential(options, true, overrides),
    login: (options, overrides) => credential(options, false, overrides),
  };
}

async function complete(api, key, mode) {
  const { ticket, pollToken, options } = await api.begin(mode);
  const credential = key[mode](options);
  const verified = await api.post("verify", { ticket, credential });
  assert.equal(verified.status, 200, JSON.stringify(verified.body));
  assert.match(verified.body.receipt, opaque);
  const result = await api.post("poll", { pollToken, receipt: verified.body.receipt });
  assert.equal(result.status, 200, JSON.stringify(result.body));
  assert.match(result.body.token, opaque);
  const account = accountForDevice(api.storage, await tokenDigest(result.body.token));
  assert.ok(account);
  return { account, ...result.body };
}

test("a synced passkey signs a second phone into the same account with a distinct device token", async t => {
  const api = fixture(t);
  const key = passkey();
  const first = await complete(api, key, "register");
  const second = await complete(api, key, "login");
  const third = await complete(api, passkey(), "register");
  assert.equal(first.account, second.account);
  assert.notEqual(first.account, third.account);
  assert.notEqual(first.token, second.token);
  assert.notEqual(first.id, second.id);
  assert.equal(api.db.prepare("SELECT COUNT(*) AS n FROM accounts").get().n, 2);
  assert.equal(api.db.prepare("SELECT COUNT(*) AS n FROM devices").get().n, 3);
});

test("one verified ceremony and both return factors deliver exactly one device token", async t => {
  const api = fixture(t);
  const flow = await api.begin("register");
  const other = await api.start();
  const credential = passkey().register(flow.options);
  const concurrent = await Promise.all([
    api.post("verify", { ticket: flow.ticket, credential }),
    api.post("verify", { ticket: flow.ticket, credential }),
  ]);
  assert.equal(concurrent.filter(result => result.status === 200).length, 1);
  const { receipt } = concurrent.find(result => result.status === 200).body;
  assert.match(receipt, opaque);
  assert.deepEqual(await api.post("poll", { pollToken: flow.pollToken }), { status: 202, body: { pending: true } });
  assert.equal((await api.post("poll", { pollToken: flow.pollToken, receipt: encode(randomBytes(32)) })).status, 410);
  assert.notEqual((await api.post("poll", { pollToken: other.pollToken, receipt })).status, 200);
  assert.equal(api.db.prepare("SELECT COUNT(*) AS n FROM devices").get().n, 0);
  const claims = await Promise.all(Array.from({ length: 4 }, () =>
    api.post("poll", { pollToken: flow.pollToken, receipt })));
  assert.deepEqual(claims.map(result => result.status).sort(), [200, 410, 410, 410]);
  assert.equal(api.db.prepare("SELECT COUNT(*) AS n FROM devices").get().n, 1);
});

test("browser origin and authenticator user verification are required", async t => {
  const api = fixture(t);
  const key = passkey();
  const unopened = await api.start();
  const foreign = { browserOrigin: "https://other.example" };
  const offOrigin = await api.post("options", { ticket: unopened.ticket, mode: "register" }, foreign);
  assert.ok(offOrigin.status >= 400 && offOrigin.status < 500);

  for (const overrides of [{ verified: false }, { clientOrigin: foreign.browserOrigin }]) {
    const { ticket, options } = await api.begin("register");
    const denied = await api.post("verify", { ticket, credential: key.register(options, overrides) });
    assert.ok(denied.status >= 400 && denied.status < 500, JSON.stringify(denied));
    assert.equal(denied.body.receipt, undefined);
  }
  assert.equal(api.db.prepare("SELECT COUNT(*) AS n FROM devices").get().n, 0);
  await complete(api, key, "register");
  const flow = await api.begin("login");
  const denied = await api.post("verify", { ticket: flow.ticket, credential: key.login(flow.options, { verified: false }) });
  assert.ok(denied.status >= 400 && denied.status < 500);
  assert.equal(denied.body.receipt, undefined);
  const unsigned = await api.begin("login");
  const invalidSignature = key.login(unsigned.options);
  const bytes = Buffer.from(invalidSignature.response.signature, "base64url");
  bytes[bytes.length - 1] ^= 1;
  invalidSignature.response.signature = encode(bytes);
  const signatureResult = await api.post("verify", { ticket: unsigned.ticket, credential: invalidSignature });
  assert.ok(signatureResult.status >= 400 && signatureResult.status < 500);
  assert.equal(signatureResult.body.receipt, undefined);
  assert.equal(api.db.prepare("SELECT COUNT(*) AS n FROM devices").get().n, 1);
});

test("the start limit applies per network address", async t => {
  const api = fixture(t);
  for (let i = 0; i < 10; i++) assert.equal((await api.post("start", {})).status, 200);
  assert.equal((await api.post("start", {})).status, 429);
  assert.equal((await api.post("start", {}, { ip: "192.0.2.91" })).status, 200);
});

test("an expired ticket cannot register or deliver a device", async t => {
  const api = fixture(t);
  const flow = await api.begin("register");
  const credential = passkey().register(flow.options);
  t.mock.timers.enable({ apis: ["Date"], now: flow.expiresAt + 1 });
  const denied = await api.post("verify", { ticket: flow.ticket, credential });
  assert.equal(denied.status, 410);
  assert.equal((await api.post("poll", { pollToken: flow.pollToken })).status, 410);
  assert.equal(api.db.prepare("SELECT COUNT(*) AS n FROM devices").get().n, 0);
});

test("daily enrollment limit leaves existing accounts able to sign in", async t => {
  const api = fixture(t);
  const key = passkey();
  await complete(api, key, "register");
  for (let i = 0; i < 3; i++) await complete(api, passkey(), "register");
  const flow = await api.start();
  const options = await api.post("options", { ticket: flow.ticket, mode: "register" });
  if (options.status === 200) {
    const credential = passkey().register(options.body.publicKey);
    assert.equal((await api.post("verify", { ticket: flow.ticket, credential })).status, 429);
  } else {
    assert.equal(options.status, 429);
  }
  await complete(api, key, "login");
  assert.equal(api.db.prepare("SELECT COUNT(*) AS n FROM accounts").get().n, 4);
});
