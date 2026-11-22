import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, readFileSync, rmdirSync, unlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import test from 'node:test';
import {
  accountForDevice, initializeAccess, issueDevice, issuePreparedDevice, prepareDevice,
  revokeAccountDevice, revokeDevice, tokenDigest,
} from './access.mjs';

const now = () => 1_800_000_000_000;

function openStore(path = ':memory:') {
  const db = new DatabaseSync(path);
  const storage = {
    sql: {
      exec(statement, ...params) {
        const rows = db.prepare(statement).all(...params);
        return { toArray: () => rows, one: () => rows[0], [Symbol.iterator]: () => rows[Symbol.iterator]() };
      },
    },
    transactionSync(work) {
      db.exec('BEGIN');
      try {
        const result = work();
        db.exec('COMMIT');
        return result;
      } catch (error) {
        db.exec('ROLLBACK');
        throw error;
      }
    },
    async sync() {},
  };
  initializeAccess(storage);
  return { db, storage };
}

test('concurrent verified sign-ins share one account and each device has an independent credential', async t => {
  const { db, storage } = openStore();
  t.after(() => db.close());
  const devices = await Promise.all(Array.from({ length: 10 }, () => issueDevice(storage, 'account-a', now)));
  assert.equal(new Set(devices.map(g => g.token)).size, 10);
  assert.equal(new Set(devices.map(g => g.id)).size, 10);
  assert.equal(db.prepare('SELECT count(*) AS n FROM accounts').get().n, 1);
  assert.equal(db.prepare('SELECT count(*) AS n FROM devices').get().n, 10);
  for (const { id, token } of devices) {
    assert.match(token, /^[A-Za-z0-9_-]{43}$/);
    assert.equal(Buffer.from(token, 'base64url').length, 32);
    const digest = createHash('sha256').update(token).digest('hex');
    assert.equal(await tokenDigest(token), digest);
    const row = db.prepare('SELECT account_id, token_digest, revoked_at FROM devices WHERE id = ?').get(id);
    assert.equal(row.account_id, 'account-a');
    assert.equal(row.token_digest, digest);
    assert.equal(row.revoked_at, null);
  }
});

test('invalid external identity cannot create an account or a credential', async t => {
  const { db, storage } = openStore();
  t.after(() => db.close());
  for (const bad of [null, undefined, '', '   ', 'prefix\x00suffix', 'account\nnext', 'a'.repeat(257), 123, {}]) {
    await assert.rejects(async () => issueDevice(storage, bad, now));
  }
  assert.equal(db.prepare('SELECT count(*) AS n FROM accounts').get().n, 0);
  assert.equal(db.prepare('SELECT count(*) AS n FROM devices').get().n, 0);
});

test('self-service revocation is scoped to the account and an owner can revoke either account', async t => {
  const { db, storage } = openStore();
  t.after(() => db.close());
  const a = await issueDevice(storage, 'account-a', now);
  const b = await issueDevice(storage, 'account-b', now);
  const secondA = await issueDevice(storage, 'account-a', now);
  const active = ({ id }) => db.prepare('SELECT revoked_at IS NULL AS active FROM devices WHERE id = ?').get(id).active;
  assert.equal(await revokeAccountDevice(storage, 'account-b', a.id, now), false);
  assert.equal(active(a), 1);
  assert.equal(await revokeAccountDevice(storage, 'account-a', a.id, now), true);
  assert.equal(active(a), 0);
  assert.equal(active(secondA), 1);
  assert.equal(active(b), 1);
  assert.equal(await revokeDevice(storage, b.id, now), true);
  assert.equal(active(b), 0);
  assert.equal(active(secondA), 1);
  assert.equal(await revokeDevice(storage, 'no-such-device', now), false);
});

test('restart preserves only the bearer digest and the revocation state', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'jot-device-'));
  const path = join(dir, 'devices.db');
  let ledger;
  try {
    ledger = openStore(path);
    const credential = await issueDevice(ledger.storage, 'account-a', now);
    await revokeDevice(ledger.storage, credential.id, now);
    ledger.db.close();
    ledger = null;
    assert.equal(readFileSync(path).includes(credential.token), false);
    ledger = openStore(path);
    const row = ledger.db.prepare('SELECT token_digest, revoked_at FROM devices WHERE id = ?').get(credential.id);
    assert.equal(row.token_digest, await tokenDigest(credential.token));
    assert.notEqual(row.revoked_at, null);
  } finally {
    ledger?.db.close();
    unlinkSync(path);
    rmdirSync(dir);
  }
});

test('no device bearer is returned when durable confirmation fails', async t => {
  const { db, storage } = openStore();
  t.after(() => db.close());
  storage.sync = async () => { throw new Error('disk unavailable'); };
  await assert.rejects(issueDevice(storage, 'account-a', now), /disk unavailable/);
});

test('device issuance rolls back with its caller and only active credentials resolve an account', async t => {
  const { db, storage } = openStore();
  t.after(() => db.close());
  const credential = await prepareDevice();
  assert.throws(() => storage.transactionSync(() => {
    issuePreparedDevice(storage, 'account-a', credential, now);
    throw new Error('challenge could not be consumed');
  }), /challenge/);
  assert.equal(accountForDevice(storage, credential.digest), null);
  assert.equal(db.prepare('SELECT count(*) AS count FROM accounts').get().count, 0);

  storage.transactionSync(() => issuePreparedDevice(storage, 'account-a', credential, now));
  assert.equal(accountForDevice(storage, credential.digest), 'account-a');
  assert.equal(accountForDevice(storage, credential.token), null);
  await revokeAccountDevice(storage, 'account-a', credential.id, now);
  assert.equal(accountForDevice(storage, credential.digest), null);
});
