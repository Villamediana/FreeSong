// Ligar um computador (a web) a um perfil: o código do QR, a resposta do celular, os pedidos
// assinados, o cofre avisando na hora e desligar. O relay de verdade numa porta livre.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import WebSocket from 'ws';
import { createRelay } from '../src/server.js';
import { LINK_TTL, MAX_COMPUTERS, SHARED_IDLE_MS } from '../src/computers.js';
import { MAX_TOKENS } from '../src/db.js';

const subtle = globalThis.crypto.subtle;
let relay, base, dir;
let now = 1_800_000_000_000;
let ip = 0;

before(async () => {
  dir = mkdtempSync(join(tmpdir(), 'freesong-computers-'));
  relay = createRelay({ dataDir: dir, clock: () => now, log: { error() {} } });
  await new Promise((r) => relay.server.listen(0, '127.0.0.1', r));
  base = `http://127.0.0.1:${relay.server.address().port}`;
});

after(async () => {
  await relay.close();
  rmSync(dir, { recursive: true, force: true });
});

const b64u = (bytes) => Buffer.from(bytes).toString('base64url');

async function api(method, path, { token, body, headers = {} } = {}) {
  const h = { 'cf-connecting-ip': `10.9.${Math.floor(++ip / 200)}.${ip % 200}`, ...headers };
  if (token) h.authorization = `Bearer ${token}`;
  let payload;
  if (body !== undefined) {
    h['content-type'] = 'application/json';
    payload = typeof body === 'string' ? body : JSON.stringify(body);
  }
  const res = await fetch(base + path, { method, headers: h, body: payload });
  const type = res.headers.get('content-type') ?? '';
  return { status: res.status, json: type.includes('json') ? await res.json() : null };
}

async function person() {
  const r = await api('POST', '/v1/accounts', { body: {} });
  assert.equal(r.status, 201);
  return { token: r.json.token, code: r.json.recoveryCode, id: r.json.me.id };
}

/** O navegador: um par ECDH pro QR, a chave que assina, e pedidos assinados como a página faz. */
async function browser() {
  const ecdh = await subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, ['deriveBits']);
  const ecdsa = await subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign', 'verify']);
  return {
    key: b64u(await subtle.exportKey('raw', ecdh.publicKey)),
    proof: b64u(await subtle.exportKey('raw', ecdsa.publicKey)),
    signer: ecdsa.privateKey,
    token: null,
    skew: 0,
  };
}

async function sig(b, method, path, body, { at = now, nonce } = {}) {
  const n = nonce ?? randomBytes(16).toString('base64url');
  const digest = createHash('sha256').update(body ?? '').digest('hex');
  const text = `freesong-web-v1\n${method}\n${path}\n${at}\n${n}\n${digest}`;
  const s = await subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, b.signer, new TextEncoder().encode(text));
  return `${at}.${n}.${b64u(s)}`;
}

async function signed(b, method, path, body, opts = {}) {
  const payload = body === undefined ? undefined : JSON.stringify(body);
  return api(method, path, { token: b.token, body: payload, headers: { 'x-freesong-sig': await sig(b, method, path, payload, opts) } });
}

/** O que o celular manda: qualquer coisa no formato certo (quem abre é o navegador; aqui não importa). */
const WRAPPED = { pub: b64u(Buffer.alloc(65, 4)), iv: b64u(Buffer.alloc(12, 1)), ct: b64u(Buffer.alloc(60, 2)) };

async function linked(p) {
  const b = await browser();
  const start = await api('POST', '/v1/link', { body: { key: b.key, proof: b.proof, name: 'Chrome no Windows' } });
  assert.equal(start.status, 201);
  const ok = await api('POST', `/v1/link/${start.json.id}/approve`, { token: p.token, body: { wrapped: WRAPPED, owner: 'Pixel 8' } });
  assert.equal(ok.status, 200, JSON.stringify(ok.json));
  const got = await api('GET', `/v1/link/${start.json.id}`, { headers: { 'x-freesong-poll': start.json.poll } });
  assert.equal(got.json.status, 'linked');
  b.token = got.json.token;
  b.id = start.json.id;
  return b;
}

test('o código do QR: espera o celular, a resposta sai uma vez, com o perfil', async () => {
  const p = await person();
  const b = await browser();
  const start = await api('POST', '/v1/link', { body: { key: b.key, proof: b.proof, name: 'Firefox‮ no Linux\n' } });
  assert.equal(start.status, 201);
  assert.match(start.json.id, /^[A-Za-z0-9_-]{22}$/);
  assert.equal(start.json.expires_in, LINK_TTL / 1000);
  // Sem o poll (ou com outro) o código não existe pra quem pergunta.
  assert.equal((await api('GET', `/v1/link/${start.json.id}`)).status, 404);
  assert.equal((await api('GET', `/v1/link/${start.json.id}`, { headers: { 'x-freesong-poll': 'x' } })).status, 404);
  const pending = await api('GET', `/v1/link/${start.json.id}`, { headers: { 'x-freesong-poll': start.json.poll } });
  assert.deepEqual(pending.json, { status: 'pending' });

  // Esperando (wait): o celular responde no meio e a espera acaba na hora.
  const waiting = api('GET', `/v1/link/${start.json.id}?wait=20`, { headers: { 'x-freesong-poll': start.json.poll } });
  await new Promise((r) => setTimeout(r, 100));
  const t0 = Date.now();
  const ok = await api('POST', `/v1/link/${start.json.id}/approve`, { token: p.token, body: { wrapped: WRAPPED, owner: 'Pixel​ 8' } });
  assert.deepEqual(ok.json, { id: start.json.id, name: 'Firefox no Linux' });
  const got = await waiting;
  assert.ok(Date.now() - t0 < 5000);
  assert.equal(got.json.status, 'linked');
  assert.match(got.json.token, /^[A-Za-z0-9_-]{43}$/);
  assert.deepEqual(got.json.wrapped, WRAPPED);
  assert.equal(got.json.owner, 'Pixel 8');
  assert.equal(got.json.me.id, p.id);
  // Uma vez só.
  const again = await api('GET', `/v1/link/${start.json.id}`, { headers: { 'x-freesong-poll': start.json.poll } });
  assert.equal(again.status, 404);
  // E o mesmo código não liga duas vezes.
  assert.equal((await api('POST', `/v1/link/${start.json.id}/approve`, { token: p.token, body: { wrapped: WRAPPED } })).status, 410);
});

test('código errado, torto ou vencido', async () => {
  const p = await person();
  const b = await browser();
  assert.equal((await api('POST', '/v1/link', { body: { key: 'abc', proof: b.proof } })).json.error, 'bad_key');
  assert.equal((await api('POST', '/v1/link', { body: { key: b.key, proof: b.key.slice(1) } })).json.error, 'bad_proof_key');
  const start = await api('POST', '/v1/link', { body: { key: b.key, proof: b.proof, name: 'x' } });
  const bad = await api('POST', `/v1/link/${start.json.id}/approve`, { token: p.token, body: { wrapped: { pub: 'a', iv: 'b' } } });
  assert.equal(bad.json.error, 'bad_wrapped');
  now += LINK_TTL + 1;
  try {
    const late = await api('POST', `/v1/link/${start.json.id}/approve`, { token: p.token, body: { wrapped: WRAPPED } });
    assert.equal(late.status, 410);
    const poll = await api('GET', `/v1/link/${start.json.id}`, { headers: { 'x-freesong-poll': start.json.poll } });
    assert.equal(poll.json.error, 'expired');
    relay.computers.sweep();
    assert.equal((await api('GET', `/v1/link/${start.json.id}`, { headers: { 'x-freesong-poll': start.json.poll } })).status, 404);
  } finally {
    now += 1;
  }
  // Só um celular responde (sem token, nada).
  const s2 = await api('POST', '/v1/link', { body: { key: b.key, proof: b.proof } });
  assert.equal((await api('POST', `/v1/link/${s2.json.id}/approve`, { body: { wrapped: WRAPPED } })).status, 401);
});

test('cada pedido do computador vem assinado por ele', async () => {
  const p = await person();
  const b = await linked(p);
  const me = await signed(b, 'GET', '/v1/me');
  assert.equal(me.status, 200);
  assert.equal(me.json.me.id, p.id);
  // O token sozinho não entra (nem como celular).
  assert.equal((await api('GET', '/v1/me', { token: b.token })).status, 401);
  // Assinatura de outro pedido, de outra chave, repetida ou fora da hora.
  const other = await sig(b, 'GET', '/v1/friends');
  const wrong = await api('GET', '/v1/me', { token: b.token, headers: { 'x-freesong-sig': other } });
  assert.equal(wrong.json.error, 'bad_proof');
  const stranger = await browser();
  const forged = await api('GET', '/v1/me', { token: b.token, headers: { 'x-freesong-sig': await sig(stranger, 'GET', '/v1/me') } });
  assert.equal(forged.json.error, 'bad_proof');
  const once = await sig(b, 'GET', '/v1/me');
  assert.equal((await api('GET', '/v1/me', { token: b.token, headers: { 'x-freesong-sig': once } })).status, 200);
  const replay = await api('GET', '/v1/me', { token: b.token, headers: { 'x-freesong-sig': once } });
  assert.equal(replay.json.why, 'replay');
  const late = await signed(b, 'GET', '/v1/me', undefined, { at: now - 5 * 60_000 });
  assert.equal(late.json.error, 'bad_proof');
  assert.equal(late.json.why, 'clock');
  assert.equal(late.json.now, now);
  // Corpo trocado no caminho.
  const s = await sig(b, 'PATCH', '/v1/me', JSON.stringify({ name: 'Ana' }));
  const swapped = await api('PATCH', '/v1/me', { token: b.token, body: JSON.stringify({ name: 'Eve' }), headers: { 'x-freesong-sig': s } });
  assert.equal(swapped.json.why, 'signature');
  const renamed = await signed(b, 'PATCH', '/v1/me', { name: 'Ana' });
  assert.equal(renamed.json.me.name, 'Ana');
  // Token que não é de computador nenhum.
  const ghost = await api('GET', '/v1/me', { token: 'A'.repeat(43), headers: { 'x-freesong-sig': await sig(b, 'GET', '/v1/me') } });
  assert.equal(ghost.status, 401);
  assert.equal(ghost.json.error, 'unlinked');
});

test('o computador só faz o que é dele: o resto é do celular', async () => {
  const p = await person();
  const b = await linked(p);
  for (const [m, path] of [['DELETE', '/v1/me'], ['POST', '/v1/me/recovery'], ['DELETE', '/v1/me/sessions'], ['GET', '/v1/mailbox'], ['GET', '/v1/jams/current'], ['GET', '/v1/me/computers'], ['PUT', '/v1/me/stats']]) {
    const r = await signed(b, m, path, m === 'PUT' ? { listenedMs: 1, top: [] } : undefined);
    assert.equal(r.status, 403, `${m} ${path}`);
    assert.equal(r.json.error, 'phone_only');
  }
  assert.equal((await api('GET', '/v1/me', { token: p.token })).status, 200);
  // E o celular não usa as rotas do computador.
  assert.equal((await api('DELETE', '/v1/link', { token: p.token })).json.error, 'computer_only');
});

test('o cofre: a web grava, o celular aberto fica sabendo na hora; a web espera o celular', async () => {
  const p = await person();
  const b = await linked(p);
  const sealed = Buffer.alloc(64, 7).toString('base64');
  // O primeiro cofre (o celular faz, com a chave embrulhada pelo código).
  const first = await api('PUT', '/v1/me/vault', { token: p.token, body: { baseRev: 0, data: sealed, key: Buffer.alloc(61, 3).toString('base64') } });
  assert.equal(first.json.rev, 1);

  const ws = new WebSocket(base.replace('http', 'ws') + '/v1/ws', { headers: { authorization: `Bearer ${p.token}` } });
  const got = [];
  ws.on('message', (d) => got.push(JSON.parse(d.toString())));
  await new Promise((r) => ws.once('open', r));

  const read = await signed(b, 'GET', '/v1/me/vault');
  assert.equal(read.json.rev, 1);
  assert.equal(read.json.data, sealed);
  const same = await signed(b, 'GET', '/v1/me/vault?after=1');
  assert.deepEqual({ rev: same.json.rev, same: same.json.same }, { rev: 1, same: true });

  const put = await signed(b, 'PUT', '/v1/me/vault', { baseRev: 1, data: Buffer.alloc(64, 8).toString('base64') });
  assert.equal(put.json.rev, 2);
  await new Promise((r) => setTimeout(r, 100));
  assert.ok(got.some((e) => e.t === 'vault' && e.rev === 2), JSON.stringify(got));

  // A web esperando: o celular grava e ela recebe na hora.
  const waiting = signed(b, 'GET', '/v1/me/vault?after=2&wait=20');
  await new Promise((r) => setTimeout(r, 100));
  const t0 = Date.now();
  const n = got.length;
  const phone = await api('PUT', '/v1/me/vault', { token: p.token, body: { baseRev: 2, data: Buffer.alloc(64, 9).toString('base64') } });
  assert.equal(phone.json.rev, 3);
  const woke = await waiting;
  assert.ok(Date.now() - t0 < 5000);
  assert.equal(woke.json.rev, 3);
  assert.equal(woke.json.data, Buffer.alloc(64, 9).toString('base64'));
  await new Promise((r) => setTimeout(r, 100));
  // O celular que gravou não recebe o próprio aviso.
  assert.equal(got.slice(n).filter((e) => e.t === 'vault').length, 0);
  // Versão velha: 409 com a atual, como no celular.
  const stale = await signed(b, 'PUT', '/v1/me/vault', { baseRev: 2, data: sealed });
  assert.equal(stale.status, 409);
  assert.equal(stale.json.rev, 3);
  ws.close();
});

test('a lista no celular, desligar de lá ou daqui', async () => {
  const p = await person();
  const b1 = await linked(p);
  const b2 = await linked(p);
  const list = await api('GET', '/v1/me/computers', { token: p.token });
  assert.equal(list.json.computers.length, 2);
  assert.equal(list.json.computers[0].name, 'Chrome no Windows');
  assert.equal(list.json.computers[0].via, 'qr');
  assert.equal((await api('DELETE', `/v1/me/computers/${b1.id}`, { token: p.token })).json.ok, true);
  const gone = await signed(b1, 'GET', '/v1/me');
  assert.equal(gone.status, 401);
  assert.equal(gone.json.error, 'unlinked');
  // O de outra pessoa não se desliga daqui.
  const q = await person();
  assert.equal((await api('DELETE', `/v1/me/computers/${b2.id}`, { token: q.token })).json.ok, false);
  assert.equal((await signed(b2, 'DELETE', '/v1/link')).json.ok, true);
  assert.equal((await signed(b2, 'GET', '/v1/me')).json.error, 'unlinked');
  assert.equal((await api('GET', '/v1/me/computers', { token: p.token })).json.computers.length, 0);
});

test('pelo código de recuperação; computadores não derrubam celulares', async () => {
  const p = await person();
  const b = await browser();
  const wrong = await api('POST', '/v1/link/recover', { body: { code: 'AAAA-AAAA-AAAA-AAAA', proof: b.proof } });
  assert.equal(wrong.status, 404);
  const r = await api('POST', '/v1/link/recover', { body: { code: p.code.toLowerCase().replace(/-/g, ' '), proof: b.proof, name: 'Safari no Mac' } });
  assert.equal(r.status, 200, JSON.stringify(r.json));
  assert.equal(r.json.me.id, p.id);
  b.token = r.json.token;
  assert.equal((await signed(b, 'GET', '/v1/me')).status, 200);
  assert.equal((await api('GET', '/v1/me/computers', { token: p.token })).json.computers[0].via, 'code');
  // Até o teto de computadores, e o celular segue entrando (os tokens dos celulares são outra coisa).
  for (let i = 1; i < MAX_COMPUTERS; i++) await linked(p);
  assert.ok(MAX_COMPUTERS >= MAX_TOKENS);
  const extra = await browser();
  const s = await api('POST', '/v1/link', { body: { key: extra.key, proof: extra.proof } });
  assert.equal((await api('POST', `/v1/link/${s.json.id}/approve`, { token: p.token, body: { wrapped: WRAPPED } })).json.error, 'too_many');
  assert.equal((await api('POST', '/v1/link/recover', { body: { code: p.code, proof: extra.proof } })).json.error, 'too_many');
  assert.equal((await api('GET', '/v1/me', { token: p.token })).status, 200);
  // "Sair dos outros aparelhos" desliga os computadores também.
  const out = await api('DELETE', '/v1/me/sessions', { token: p.token });
  assert.equal(out.json.revoked, MAX_COMPUTERS);
  assert.equal((await signed(b, 'GET', '/v1/me')).json.error, 'unlinked');
});

test('computador emprestado: sem sinal por meia hora, desligado; perfil apagado leva os computadores', async () => {
  const p = await person();
  const b = await linked(p);
  const kept = await linked(p);
  assert.equal((await signed(b, 'POST', '/v1/link/shared')).json.ok, true);
  now += SHARED_IDLE_MS + 61_000;
  relay.computers.sweep();
  assert.equal((await signed(b, 'GET', '/v1/me')).json.error, 'unlinked');
  assert.equal((await signed(kept, 'GET', '/v1/me')).status, 200);
  assert.equal((await api('DELETE', '/v1/me', { token: p.token })).json.ok, true);
  assert.equal((await signed(kept, 'GET', '/v1/me')).json.error, 'unlinked');
});
