import assert from 'node:assert/strict';
import { mkdtempSync, rmdirSync, unlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import test from 'node:test';
import { issueDevice, revokeAccountDevice, tokenDigest } from './access.mjs';
import { PAID_DAILY_CALLS } from './budget.mjs';
import { buildNotePrompt } from './notes.mjs';
import {
  handleRequest, initializeBudget, runCompanion, cost, LIMIT_NANODOLLARS,
  MAX_INPUT_TOKENS, MAX_OUTPUT_TOKENS, MODEL, RESERVATION_NANODOLLARS,
} from './companion.mjs';

const november = Date.UTC(2026, 10, 30, 23, 59, 59);
const december = Date.UTC(2026, 11, 1);
const token = Buffer.alloc(32, 97).toString('base64url');
const providerKey = 'fixture-upstream-key';
const endpoint = 'https://jot.example/v1/companion';
const note = { operation: 'read', note: 'hello' };
const prompt = { instructions: 'Read the note.', input: 'a private note' };
const devices = new WeakMap();

// Run production SQL on SQLite. Only the Durable Object cursor and transaction
// API are adapted here. A file-backed instance also exercises process restarts.
async function openStorage(t, path = ':memory:') {
  const db = new DatabaseSync(path);
  let closed = false;
  const close = () => {
    if (!closed) db.close();
    closed = true;
  };
  t.after(close);
  const storage = {
    sql: {
      exec(query, ...params) {
        const before = db.prepare('SELECT total_changes() AS n').get().n;
        let rows;
        if (!params.length && /;\s*\S/.test(query.trim().replace(/;$/, ''))) {
          db.exec(query);
          rows = [];
        } else {
          rows = db.prepare(query).all(...params);
        }
        return Object.assign(rows, {
          rowsWritten: db.prepare('SELECT total_changes() AS n').get().n - before,
          toArray: () => [...rows],
          one: () => {
            assert.equal(rows.length, 1, 'expected exactly one SQLite row');
            return rows[0];
          },
        });
      },
    },
    transactionSync(fn) {
      db.exec('BEGIN IMMEDIATE');
      try {
        const result = fn();
        db.exec('COMMIT');
        return result;
      } catch (error) {
        db.exec('ROLLBACK');
        throw error;
      }
    },
    async sync() {},
  };
  initializeBudget(storage);
  devices.set(storage, await newDevice(storage, 'verified-account'));
  return { db, storage, close };
}

async function newDevice(storage, accountId) {
  const issued = await issueDevice(storage, accountId, () => november);
  return tokenDigest(issued.token);
}

function seed(db, month, spent) {
  db.prepare('INSERT INTO months (month, spent) VALUES (?, ?) ON CONFLICT(month) DO UPDATE SET spent = excluded.spent')
    .run(month, spent);
}

function spentIn(db, month) {
  return db.prepare('SELECT spent FROM months WHERE month = ?').get(month)?.spent ?? 0;
}

function held(db) {
  return db.prepare('SELECT COALESCE(SUM(amount), 0) AS held FROM holds').get().held;
}

function reply(change = {}) {
  return {
    object: 'response',
    status: 'completed',
    model: MODEL,
    service_tier: 'default',
    usage: {
      input_tokens: 13, output_tokens: 9, total_tokens: 22,
      input_tokens_details: { cached_tokens: 0, cache_write_tokens: 0 },
      output_tokens_details: { reasoning_tokens: 3 },
    },
    output: [{
      type: 'message', role: 'assistant', status: 'completed',
      content: [{ type: 'output_text', text: 'hi' }],
    }],
    ...change,
  };
}

function invoke(storage, fetchImpl, now = () => november, extra = {}) {
  return runCompanion(storage, prompt, providerKey, { now, fetchImpl, deviceDigest: devices.get(storage), ...extra });
}

function outerEnv() {
  const prompts = [];
  const env = {
    OPENAI_API_KEY: providerKey,
    BUDGET: {
      getByName(name) {
        assert.equal(name, 'personal');
        return {
          async companion(body, digest) {
            if (digest !== await tokenDigest(token)) return { status: 401, body: { error: 'Unknown device.' } };
            prompts.push(body);
            return { status: 200, body: { text: 'ok' } };
          },
        };
      },
    },
  };
  return { env, prompts };
}

function request(body, authorization = 'Bearer ' + token, url = endpoint) {
  const headers = { 'Content-Type': 'application/json' };
  if (authorization) headers.Authorization = authorization;
  return new Request(url, { method: 'POST', headers, body: JSON.stringify(body) });
}

test('only an authenticated device can submit a note', async () => {
  const { env, prompts } = outerEnv();
  for (const auth of [null, 'Basic ' + token, 'Bearer wrong', 'Bearer ' + token + 'x']) {
    assert.equal((await handleRequest(request(note, auth), env)).status, 401);
  }
  assert.equal((await handleRequest(request(note, null, endpoint + '?token=' + token), env)).status, 404);
  assert.equal((await handleRequest(request(note, 'Bearer ' + token, endpoint + '/extra'), env)).status, 404);
  const get = new Request(endpoint, { headers: { Authorization: 'Bearer ' + token } });
  assert.equal((await handleRequest(get, env)).status, 405);
  assert.deepEqual(prompts, []);
  const result = await handleRequest(request(note), env);
  assert.equal(result.status, 200);
  assert.deepEqual(await result.json(), { text: 'ok' });
  assert.equal(prompts.length, 1);
  assert.equal(typeof prompts[0].instructions, 'string');
  assert.match(prompts[0].input, /hello/);
});

test('invalid prompts and oversized streamed JSON never reach the budget object', async () => {
  const { env, prompts } = outerEnv();
  for (const body of [null, [], {}, { prompt: 'arbitrary chat' }, { operation: 'read', note: 42 }, { operation: 'unknown' }]) {
    assert.equal((await handleRequest(request(body), env)).status, 400);
  }
  const body = new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode('{"operation":"read","note":"'));
      controller.enqueue(new Uint8Array(1024 * 1024).fill(97));
      controller.enqueue(new TextEncoder().encode('"}'));
      controller.close();
    },
  });
  const streamed = new Request(endpoint, {
    method: 'POST', headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' },
    body, duplex: 'half',
  });
  assert.equal(streamed.headers.get('content-length'), null);
  assert.equal((await handleRequest(streamed, env)).status, 413);
  assert.deepEqual(prompts, []);
  const extraFields = await handleRequest(request({
    ...note, model: 'another-model', max_output_tokens: 100000, tools: [{ type: 'web_search' }],
  }), env);
  assert.equal(extraFields.status, 400);
  assert.deepEqual(prompts, []);
});

test('integer prices cover caching, regional processing and the long-context threshold', () => {
  assert.equal(MODEL, 'gpt-6-luna');
  // Standard at maximum cache-write and regional rates: $0.275/M in and $0.55/M out.
  assert.equal(cost(272_000, 1), 74_800_550);
  // Above the threshold all input costs 2x and all output costs 1.5x.
  assert.equal(cost(272_001, 1), 149_601_375);
  assert.equal(RESERVATION_NANODOLLARS, 2 * cost(MAX_INPUT_TOKENS, MAX_OUTPUT_TOKENS));
  assert.ok(MAX_INPUT_TOKENS <= 272_000);
});

test('the reservation covers the largest prompt the relay accepts', () => {
  const bytes = prompt => Buffer.byteLength(prompt.instructions) + Buffer.byteLength(prompt.input);
  const largestRead = buildNotePrompt({ operation: 'read', note: '\u0001'.repeat(16_384) });
  const excerpt = '\u0001'.repeat(3_000);
  const largestAnswer = buildNotePrompt({
    operation: 'answer', question: '\u0001'.repeat(1_300),
    notes: Array.from({ length: 5 }, () => ({ text: excerpt, date: 'Nov 30' })),
  });
  assert.throws(() => buildNotePrompt({ operation: 'read', note: 'a'.repeat(16_385) }), RangeError);
  // A token spans at least one byte, so serialized bytes bound the token count.
  assert.ok(bytes(largestRead) + 1_000 <= MAX_INPUT_TOKENS);
  assert.ok(bytes(largestAnswer) + 1_000 <= MAX_INPUT_TOKENS);
});

test('one bounded standard request charges reported output including hidden reasoning', async (t) => {
  const { storage, db } = await openStorage(t);
  assert.equal(LIMIT_NANODOLLARS, 70_000_000_000);
  assert.ok(Number.isSafeInteger(RESERVATION_NANODOLLARS));
  assert.ok(RESERVATION_NANODOLLARS >= cost(MAX_INPUT_TOKENS, MAX_OUTPUT_TOKENS));
  let calls = 0;
  let synced = false;
  storage.sync = async () => { synced = true; };
  const result = await invoke(storage, async (url, init) => {
    calls++;
    assert.equal(String(url), 'https://api.openai.com/v1/responses');
    assert.equal(init.method, 'POST');
    assert.equal(new Headers(init.headers).get('authorization'), 'Bearer ' + providerKey);
    assert.equal(init.redirect, 'manual');
    assert.ok(synced, 'reserve must reach durable storage before sending');
    assert.equal(held(db), RESERVATION_NANODOLLARS);
    const body = JSON.parse(init.body);
    assert.equal(body.model, MODEL);
    assert.equal(body.reasoning.effort, 'none');
    assert.equal(body.service_tier, 'default');
    assert.equal(body.store, false);
    assert.equal(body.max_output_tokens, MAX_OUTPUT_TOKENS);
    assert.deepEqual(body.tools ?? [], []);
    assert.match(JSON.stringify(body.input), /a private note/);
    return Response.json(reply());
  });
  assert.equal(calls, 1);
  assert.deepEqual(result, { status: 200, body: { text: 'hi' } });
  assert.equal(held(db), 0);
  assert.equal(spentIn(db, '2026-11'), cost(13, 9));
  assert.ok(cost(13, 9) > cost(13, 6), 'hidden output tokens count');
});

test('no request is sent when the maximum exceeds the remaining allowance by one nanodollar', async (t) => {
  const { storage, db } = await openStorage(t);
  seed(db, '2026-11', LIMIT_NANODOLLARS - RESERVATION_NANODOLLARS + 1);
  let calls = 0;
  const result = await invoke(storage, async () => { calls++; return Response.json(reply()); });
  assert.equal(result.status, 429);
  assert.equal(result.body.code, 'monthly_budget');
  assert.equal(calls, 0);
  assert.equal(held(db), 0);
});

test('concurrent phones cannot both claim the final reservation', async (t) => {
  const { storage, db } = await openStorage(t);
  seed(db, '2026-11', LIMIT_NANODOLLARS - RESERVATION_NANODOLLARS);
  const otherAccounts = await Promise.all(Array.from({ length: 12 }, (_, i) => newDevice(storage, 'other-account-' + i)));
  const started = Promise.withResolvers();
  const upstream = Promise.withResolvers();
  let calls = 0;
  const send = async () => { calls++; started.resolve(); return upstream.promise; };
  const first = invoke(storage, send);
  await Promise.race([started.promise, first.then(() => assert.fail('first request did not reach OpenAI'))]);
  const others = await Promise.all(otherAccounts.map(deviceDigest => invoke(storage, send, () => november, { deviceDigest })));
  assert.ok(others.every(result => result.status === 429 && result.body.code === 'monthly_budget'));
  assert.equal(calls, 1);
  upstream.resolve(Response.json(reply()));
  assert.equal((await first).status, 200);
  assert.equal(held(db), 0);
});

test('a free account gets five paid attempts per UTC day, shared across its devices', async (t) => {
  const { storage } = await openStorage(t);
  const second = await newDevice(storage, 'verified-account');
  const anotherUser = await newDevice(storage, 'another-account');
  let calls = 0;
  const send = async () => {
    calls++;
    if (calls === 1) throw new Error('provider reply lost');
    return Response.json(reply());
  };
  for (let i = 0; i < 5; i++) {
    const deviceDigest = i % 2 === 0 ? second : devices.get(storage);
    assert.equal((await invoke(storage, send, () => november, { deviceDigest })).status, i === 0 ? 502 : 200);
  }
  for (const deviceDigest of [second, devices.get(storage)]) {
    const denied = await invoke(storage, send, () => november, { deviceDigest });
    assert.equal(denied.status, 429);
    assert.equal(denied.body.code, 'daily_quota');
  }
  assert.equal(calls, 5);
  assert.equal((await invoke(storage, send, () => november, { deviceDigest: anotherUser })).status, 200);
  assert.equal((await invoke(storage, send, () => december, { deviceDigest: second })).status, 200);
  assert.equal(calls, 7);
});

test('paid accounts can exceed five daily calls and still share the minute and monthly limits', async (t) => {
  const { db, storage } = await openStorage(t);
  db.prepare('INSERT INTO billing_accounts (account_id, paid_until) VALUES (?, ?)').run('verified-account', Date.UTC(2027, 0, 1) / 1000);
  const second = await newDevice(storage, 'verified-account');
  const minute = Date.UTC(2026, 10, 15, 10, 0, 5);
  let clock = minute;
  let calls = 0;
  const send = async () => { calls++; return Response.json(reply()); };
  for (let i = 0; i < 10; i++) {
    const deviceDigest = i % 2 === 0 ? second : devices.get(storage);
    assert.equal((await invoke(storage, send, () => clock, { deviceDigest })).status, 200);
  }
  const throttled = await invoke(storage, send, () => clock);
  assert.equal(throttled.status, 429);
  assert.equal(throttled.body.code, 'rate_limit');
  assert.equal(calls, 10);
  clock += 60_000;
  assert.equal((await invoke(storage, send, () => clock)).status, 200);
  seed(db, '2026-11', LIMIT_NANODOLLARS - RESERVATION_NANODOLLARS + 1);
  const exhausted = await invoke(storage, send, () => clock, { deviceDigest: second });
  assert.equal(exhausted.status, 429);
  assert.equal(exhausted.body.code, 'monthly_budget');
  assert.equal(calls, 11);
});

test('a paid account stops at its daily call limit until the next UTC day', async (t) => {
  const { db, storage } = await openStorage(t);
  db.prepare('INSERT INTO billing_accounts (account_id, paid_until) VALUES (?, ?)').run('verified-account', Date.UTC(2027, 0, 1) / 1000);
  const second = await newDevice(storage, 'verified-account');
  const start = Date.UTC(2026, 10, 15, 10, 0, 5);
  let calls = 0;
  const send = async () => { calls++; return Response.json(reply()); };
  for (let i = 0; i < PAID_DAILY_CALLS; i++) {
    const clock = start + Math.floor(i / 10) * 60_000;
    const deviceDigest = i % 2 === 0 ? second : devices.get(storage);
    assert.equal((await invoke(storage, send, () => clock, { deviceDigest })).status, 200);
  }
  const later = start + 3_600_000;
  for (const deviceDigest of [second, devices.get(storage)]) {
    const denied = await invoke(storage, send, () => later, { deviceDigest });
    assert.equal(denied.status, 429);
    assert.equal(denied.body.code, 'daily_quota');
  }
  assert.equal(calls, PAID_DAILY_CALLS);
  assert.equal(PAID_DAILY_CALLS, 100);
  assert.equal((await invoke(storage, send, () => start + 86_400_000)).status, 200);
});

test('two devices share their account’s concurrent request limit', async (t) => {
  const { storage } = await openStorage(t);
  const second = await newDevice(storage, 'verified-account');
  let calls = 0;
  const bothStarted = Promise.withResolvers();
  const release = Promise.withResolvers();
  const send = async () => {
    calls++;
    if (calls === 2) bothStarted.resolve();
    await release.promise;
    return Response.json(reply());
  };
  const pending = Promise.all([
    invoke(storage, send),
    invoke(storage, send, () => november, { deviceDigest: second }),
  ]);
  await Promise.race([bothStarted.promise, pending.then(() => assert.fail('both requests did not reach the provider'))]);
  const third = await invoke(storage, async () => { assert.fail('third request reached provider'); });
  assert.equal(third.status, 429);
  assert.equal(third.body.code, 'concurrency_limit');
  assert.equal(calls, 2);
  release.resolve();
  assert.ok((await pending).every(result => result.status === 200));
  assert.equal((await invoke(storage, send)).status, 200);
  assert.equal(calls, 3);
});

test('unknown and revoked device credentials never reserve funds', async (t) => {
  const { storage, db } = await openStorage(t);
  const deviceDigest = devices.get(storage);
  const deviceId = db.prepare('SELECT id FROM devices WHERE token_digest = ?').get(deviceDigest).id;
  assert.equal(await revokeAccountDevice(storage, 'verified-account', deviceId, () => november), true);
  let calls = 0;
  const send = async () => { calls++; return Response.json(reply()); };
  assert.equal((await invoke(storage, send)).status, 401);
  const unknown = await tokenDigest(Buffer.alloc(32, 73).toString('base64url'));
  assert.equal((await invoke(storage, send, () => november, { deviceDigest: unknown })).status, 401);
  assert.equal(calls, 0);
  assert.equal(held(db), 0);
});

test('UTC rollover grants a fresh allowance while a crossing call counts against both months', async (t) => {
  const { storage, db } = await openStorage(t);
  seed(db, '2026-11', LIMIT_NANODOLLARS - RESERVATION_NANODOLLARS);
  const started = Promise.withResolvers();
  const upstream = Promise.withResolvers();
  let clock = november;
  const pending = invoke(storage, async () => { started.resolve(); return upstream.promise; }, () => clock);
  await Promise.race([started.promise, pending.then(() => assert.fail('request did not start'))]);
  clock = december;
  seed(db, '2026-12', LIMIT_NANODOLLARS - RESERVATION_NANODOLLARS);
  let secondCalls = 0;
  const send = async () => { secondCalls++; return Response.json(reply()); };
  assert.equal((await invoke(storage, send, () => clock)).status, 429);
  assert.equal(secondCalls, 0);
  upstream.resolve(Response.json(reply()));
  assert.equal((await pending).status, 200);
  assert.equal(spentIn(db, '2026-11'), LIMIT_NANODOLLARS - RESERVATION_NANODOLLARS + cost(13, 9));
  assert.equal(spentIn(db, '2026-12'), LIMIT_NANODOLLARS - RESERVATION_NANODOLLARS + cost(13, 9));
  assert.equal(held(db), 0);
  assert.equal((await invoke(storage, send, () => Date.UTC(2027, 0, 1))).status, 200);
  assert.equal(spentIn(db, '2027-01'), cost(13, 9));
});

test('a restarted relay retains ambiguous upstream charges across month boundaries', async (t) => {
  const dir = mkdtempSync(join(tmpdir(), 'jot-budget-test-'));
  const path = join(dir, 'budget.sqlite');
  const initial = await openStorage(t, path);
  let calls = 0;
  const failure = await invoke(initial.storage, async () => { calls++; throw new Error('lost response'); });
  assert.equal(failure.status, 502);
  assert.equal(calls, 1);
  assert.equal(held(initial.db), RESERVATION_NANODOLLARS);
  initial.close();
  const reopened = await openStorage(t, path);
  try {
    seed(reopened.db, '2026-12', LIMIT_NANODOLLARS - RESERVATION_NANODOLLARS);
    const next = await invoke(reopened.storage, async () => { calls++; return Response.json(reply()); }, () => december);
    assert.equal(next.status, 429);
    assert.equal(calls, 1);
    assert.equal(held(reopened.db), RESERVATION_NANODOLLARS);
    // Two months on, the same hold can no longer have been charged.
    seed(reopened.db, '2027-01', LIMIT_NANODOLLARS - RESERVATION_NANODOLLARS);
    const later = await invoke(reopened.storage, async () => { calls++; return Response.json(reply()); }, () => Date.UTC(2027, 0, 1));
    assert.equal(later.status, 200);
    assert.equal(calls, 2);
  } finally {
    reopened.close();
    unlinkSync(path);
    rmdirSync(dir);
  }
});

test('unusable HTTP replies preserve the reservation and never trigger a resubmission', async (t) => {
  const responses = [
    () => new Response(null, { status: 302, headers: { Location: 'https://redirect.invalid/' } }),
    () => new Response('unavailable', { status: 503 }),
    () => new Response('{'),
    () => Response.json(reply({ status: 'in_progress' })),
    () => Response.json(reply({ usage: null })),
    () => Response.json(reply({ usage: { input_tokens: 1, output_tokens: 1, total_tokens: 99 } })),
    () => Response.json(reply({ usage: { input_tokens: 1, output_tokens: -1, total_tokens: 0 } })),
    () => Response.json(reply({ usage: { input_tokens: 1, output_tokens: MAX_OUTPUT_TOKENS + 1, total_tokens: MAX_OUTPUT_TOKENS + 2 } })),
    () => Response.json(reply({ model: 'unexpected-model' })),
    () => Response.json(reply({ service_tier: 'priority' })),
  ];
  for (const makeResponse of responses) {
    const { storage, db } = await openStorage(t);
    let calls = 0;
    const result = await invoke(storage, async () => { calls++; return makeResponse(); });
    assert.ok(result.status >= 400);
    assert.equal(calls, 1);
    assert.equal(held(db), RESERVATION_NANODOLLARS);
    assert.equal(spentIn(db, '2026-11'), 0);
  }
});

test('a request OpenAI rejects outright releases its reservation but keeps its quota debit', async (t) => {
  for (const status of [400, 401, 429]) {
    const { storage, db } = await openStorage(t);
    let calls = 0;
    const result = await invoke(storage, async () => { calls++; return new Response('{"error":{}}', { status }); });
    assert.equal(result.status, 502);
    assert.equal(calls, 1);
    assert.equal(held(db), 0);
    assert.equal(spentIn(db, '2026-11'), 0);
    assert.equal(db.prepare('SELECT calls FROM account_days').get().calls, 1);
    assert.equal(db.prepare('SELECT COUNT(*) AS n FROM account_leases').get().n, 0);
  }
});

test('a timed-out call keeps its complete reservation', async (t) => {
  const { storage, db } = await openStorage(t);
  let calls = 0;
  const keepAlive = setTimeout(() => {}, 3000);
  t.after(() => clearTimeout(keepAlive));
  const outcome = await invoke(storage, (_, { signal }) => {
    calls++;
    return new Promise((resolve, reject) => signal.addEventListener('abort', () => reject(signal.reason), { once: true }));
  }, () => november, { timeoutMs: 10 });
  assert.equal(outcome.status, 504);
  assert.equal(calls, 1);
  assert.equal(held(db), RESERVATION_NANODOLLARS);
});

test('a failed durable write prevents upstream contact', async (t) => {
  const { storage, db } = await openStorage(t);
  storage.sync = async () => { throw new Error('disk unavailable'); };
  let calls = 0;
  try {
    const response = await invoke(storage, async () => { calls++; return Response.json(reply()); });
    assert.ok(response.status >= 500);
  } catch (error) {
    assert.match(error.message, /disk unavailable/);
  }
  assert.equal(calls, 0);
  assert.equal(held(db), 0);
  assert.equal(db.prepare('SELECT COALESCE(SUM(calls), 0) AS calls FROM account_days').get().calls, 0);
});
