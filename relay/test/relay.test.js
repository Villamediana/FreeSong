// Ponta a ponta: o relay de verdade numa porta livre, HTTP + WebSocket, banco num diretório temporário.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import net from 'node:net';
import { DatabaseSync } from 'node:sqlite';
import WebSocket from 'ws';
import { createRelay, DELIVERY_TTL_MS, ipKey, Limiter, MAX_SOCKETS_PER_USER } from '../src/server.js';
import { cleanTrack, IDLE_END_MS } from '../src/jams.js';
import { MAX_TOKENS } from '../src/db.js';

let relay, base, dir;
let now = 1_800_000_000_000;
let ipCounter = 0;

before(async () => {
  dir = mkdtempSync(join(tmpdir(), 'freesong-relay-'));
  relay = createRelay({ dataDir: dir, clock: () => now, log: { error() {} } });
  await new Promise((r) => relay.server.listen(0, '127.0.0.1', r));
  base = `http://127.0.0.1:${relay.server.address().port}`;
});

after(async () => {
  await relay.close();
  rmSync(dir, { recursive: true, force: true });
});

async function api(method, path, { token, body, headers = {}, raw } = {}) {
  const h = { ...headers };
  if (token) h.authorization = `Bearer ${token}`;
  let payload;
  if (raw) payload = raw;
  else if (body !== undefined) {
    h['content-type'] = 'application/json';
    payload = JSON.stringify(body);
  }
  const res = await fetch(base + path, { method, headers: h, body: payload });
  const type = res.headers.get('content-type') ?? '';
  return { status: res.status, json: type.includes('json') ? await res.json() : null, res };
}

/** Uma pessoa nova com o app instalado (cada uma "de um IP" pra não bater no limite). */
async function person() {
  const r = await api('POST', '/v1/accounts', { body: {}, headers: { 'cf-connecting-ip': `10.0.0.${++ipCounter}` } });
  assert.equal(r.status, 201);
  return { token: r.json.token, code: r.json.recoveryCode, me: r.json.me, id: r.json.me.id };
}

async function friends(a, b) {
  const r = await api('POST', '/v1/friends/requests', { token: a.token, body: { userId: b.id } });
  assert.equal(r.json.status, 'pending');
  const ok = await api('POST', `/v1/friends/requests/${r.json.requestId}/accept`, { token: b.token });
  assert.equal(ok.json.status, 'friends');
}

/** O app aberto: um WebSocket que guarda tudo o que chega. */
async function online(p) {
  const ws = new WebSocket(base.replace('http', 'ws') + '/v1/ws', { headers: { authorization: `Bearer ${p.token}` } });
  const events = [];
  const waiters = [];
  ws.on('message', (data) => {
    const e = JSON.parse(data.toString());
    events.push(e);
    for (const w of [...waiters]) if (w.pred(e)) {
      waiters.splice(waiters.indexOf(w), 1);
      w.resolve(e);
    }
  });
  await new Promise((r, j) => { ws.once('open', r); ws.once('error', j); });
  const next = (pred, ms = 3000) => {
    const found = events.find(pred);
    if (found) {
      events.splice(events.indexOf(found), 1);
      return Promise.resolve(found);
    }
    return new Promise((resolve, reject) => {
      const w = { pred, resolve: (e) => { events.splice(events.indexOf(e), 1); clearTimeout(timer); resolve(e); } };
      const timer = setTimeout(() => { waiters.splice(waiters.indexOf(w), 1); reject(new Error('timeout waiting event')); }, ms);
      waiters.push(w);
    });
  };
  const send = (msg) => ws.send(JSON.stringify(msg));
  await next((e) => e.t === 'hello');
  return { ws, events, next, send, close: () => ws.close() };
}

const tick = () => new Promise((r) => setTimeout(r, 60));
/** JPEG de verdade, 8×8 cinza, sem nenhum metadado (é exatamente o que o relay devolve). */
const TINY_JPEG = Buffer.from(
  '/9j/2wBDABALDA4MChAODQ4SERATGCgaGBYWGDEjJR0oOjM9PDkzODdASFxOQERXRTc4UG1RV19iZ2hnPk1xeXBkeFxlZ2P/wAALCAAIAAgBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAAA//EABcQAAMBAAAAAAAAAAAAAAAAAAADBjP/2gAIAQEAAD8AGNVmf//Z',
  'base64',
);
const track = (n) => ({ videoId: `vid${String(n).padStart(8, '0')}`, title: `Música ${n}`, artist: 'Artista', durationMs: 200_000, cover: 'https://i.ytimg.com/vi/x/hq.jpg' });

// -------------------------------------------------------------------- contas

test('saúde', async () => {
  const r = await api('GET', '/v1/health');
  assert.equal(r.status, 200);
  assert.equal(r.json.ok, true);
});

test('conta nasce sozinha com @nome, nome, token e código de recuperação', async () => {
  const p = await person();
  assert.match(p.me.username, /^[a-z]+_\d{3,4}$/);
  assert.ok(p.me.name.length > 0);
  assert.match(p.code, /^[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}$/);
  assert.equal(p.me.avatar, null);
  assert.equal(p.me.listenedMs, 0);
  const me = await api('GET', '/v1/me', { token: p.token });
  assert.equal(me.json.me.id, p.id);
  assert.equal((await api('GET', '/v1/me')).status, 401);
  assert.equal((await api('GET', '/v1/me', { token: 'x' })).status, 401);
});

test('o código de recuperação devolve o mesmo perfil num celular novo', async () => {
  const p = await person();
  const typed = p.code.toLowerCase().replace(/-/g, ' ').replace(/0/g, 'o');
  const r = await api('POST', '/v1/accounts/recover', { body: { code: typed } });
  assert.equal(r.status, 200);
  assert.equal(r.json.me.id, p.id);
  assert.notEqual(r.json.token, p.token);
  assert.equal((await api('GET', '/v1/me', { token: r.json.token })).json.me.id, p.id);
  assert.equal((await api('POST', '/v1/accounts/recover', { body: { code: 'AAAA-BBBB-CCCC-DDDD' } })).status, 404);
  // Código novo: o antigo para de valer.
  const fresh = await api('POST', '/v1/me/recovery', { token: p.token });
  assert.equal((await api('POST', '/v1/accounts/recover', { body: { code: p.code } })).status, 404);
  assert.equal((await api('POST', '/v1/accounts/recover', { body: { code: fresh.json.recoveryCode } })).json.me.id, p.id);
});

test('o nome muda; o @nome é o identificador e não muda', async () => {
  const a = await person();
  const b = await person();
  let r = await api('PATCH', '/v1/me', { token: a.token, body: { name: '  Miguel   V.  ' } });
  assert.equal(r.status, 200);
  assert.equal(r.json.me.name, 'Miguel V.');
  assert.equal(r.json.me.username, a.me.username);
  // Trocar o @nome: recusado (qualquer um, livre ou não), e nada muda.
  for (const wanted of ['miguel.v', 'MIGUEL.V', 'ab', 'admin', b.me.username]) {
    r = await api('PATCH', '/v1/me', { token: a.token, body: { name: 'Outro', username: wanted } });
    assert.equal(r.status, 400, wanted);
    assert.equal(r.json.error, 'username_fixed', wanted);
  }
  assert.equal((await api('GET', '/v1/me', { token: a.token })).json.me.name, 'Miguel V.');
  // Mandar o mesmo @nome de volta (um app que sempre manda os dois campos) não é troca.
  r = await api('PATCH', '/v1/me', { token: a.token, body: { name: 'Miguel', username: '@' + a.me.username.toUpperCase() } });
  assert.equal(r.status, 200);
  assert.equal(r.json.me.username, a.me.username);
  r = await api('PATCH', '/v1/me', { token: b.token, body: { name: '   ' } });
  assert.equal(r.json.error, 'invalid_name');
  r = await api('PATCH', '/v1/me', { token: b.token, body: { name: 'x'.repeat(41) } });
  assert.equal(r.json.error, 'invalid_name');
});

test('foto: só JPEG, servida com versão, apagável', async () => {
  const a = await person();
  assert.equal((await api('PUT', '/v1/me/avatar', { token: a.token, raw: Buffer.from('não é imagem'.repeat(20)) })).status, 400);
  // Só o cabeçalho de JPEG com lixo atrás não passa mais: tem que ser um JPEG de verdade.
  const fake = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(500, 7), Buffer.from([0xff, 0xd9])]);
  assert.equal((await api('PUT', '/v1/me/avatar', { token: a.token, raw: fake })).status, 400);
  const jpeg = TINY_JPEG;
  let r = await api('PUT', '/v1/me/avatar', { token: a.token, raw: jpeg, headers: { 'content-type': 'image/jpeg' } });
  assert.equal(r.status, 200);
  assert.match(r.json.me.avatar, new RegExp(`^/avatars/${a.id}\\.jpg\\?v=1$`));
  const img = await fetch(base + r.json.me.avatar);
  assert.equal(img.status, 200);
  assert.equal(img.headers.get('content-type'), 'image/jpeg');
  assert.equal(img.headers.get('x-content-type-options'), 'nosniff');
  assert.equal(img.headers.get('cache-control'), 'public, max-age=3600');
  assert.deepEqual(Buffer.from(await img.arrayBuffer()), jpeg);
  r = await api('PUT', '/v1/me/avatar', { token: a.token, raw: jpeg });
  assert.match(r.json.me.avatar, /\?v=2$/);
  r = await api('DELETE', '/v1/me/avatar', { token: a.token });
  assert.equal(r.json.me.avatar, null);
  assert.equal((await fetch(`${base}/avatars/${a.id}.jpg`)).status, 404);
  assert.equal((await api('PUT', '/v1/me/avatar', { token: a.token, raw: Buffer.alloc(700 * 1024, 0xff) })).status, 413);
});

// -------------------------------------------------------------------- lista e amizades

test('lista global: foto e nome de todos; horas e top 5 só de amigos', async () => {
  const a = await person();
  const b = await person();
  await api('PATCH', '/v1/me', { token: b.token, body: { name: 'Beatriz Lista' } });
  await api('PUT', '/v1/me/stats', { token: b.token, body: { listenedMs: 7_200_000, top: [track(1), track(2), { title: 'sem vídeo' }] } });
  let r = await api('GET', '/v1/users?q=beatriz', { token: a.token });
  const seen = r.json.users.find((u) => u.id === b.id);
  assert.equal(seen.name, 'Beatriz Lista');
  assert.equal(seen.relation, 'none');
  assert.equal(seen.listenedMs, undefined);
  assert.equal(seen.top, undefined);
  await friends(a, b);
  r = await api('GET', `/v1/users/${b.id}`, { token: a.token });
  assert.equal(r.json.user.relation, 'friends');
  assert.equal(r.json.user.listenedMs, 7_200_000);
  assert.deepEqual(r.json.user.top.map((t) => t.videoId), [track(1).videoId, track(2).videoId]);
  // A própria pessoa não aparece na lista.
  r = await api('GET', '/v1/users?limit=50', { token: a.token });
  assert.ok(!r.json.users.some((u) => u.id === a.id));
});

test('a lista pagina', async () => {
  const a = await person();
  for (let i = 0; i < 4; i++) await person();
  const first = await api('GET', '/v1/users?limit=2', { token: a.token });
  assert.equal(first.json.users.length, 2);
  assert.equal(first.json.next, 2);
  const second = await api('GET', `/v1/users?limit=2&offset=${first.json.next}`, { token: a.token });
  assert.notDeepEqual(second.json.users.map((u) => u.id), first.json.users.map((u) => u.id));
});

test('pedido de amizade chega na hora, aceitar avisa quem pediu', async () => {
  const a = await person();
  const b = await person();
  const appA = await online(a);
  const appB = await online(b);
  const r = await api('POST', '/v1/friends/requests', { token: a.token, body: { username: b.me.username } });
  assert.equal(r.json.status, 'pending');
  const ev = await appB.next((e) => e.t === 'friend.request');
  assert.equal(ev.request.user.id, a.id);
  let list = await api('GET', '/v1/friends', { token: b.token });
  assert.equal(list.json.incoming[0].user.id, a.id);
  list = await api('GET', '/v1/users?q=' + b.me.username, { token: a.token });
  assert.equal(list.json.users[0].relation, 'outgoing');
  await api('POST', `/v1/friends/requests/${r.json.requestId}/accept`, { token: b.token });
  const accepted = await appA.next((e) => e.t === 'friend.accepted');
  assert.equal(accepted.user.id, b.id);
  assert.equal((await api('GET', '/v1/friends', { token: a.token })).json.friends[0].id, b.id);
  appA.close();
  appB.close();
});

test('os dois pedindo viram amigos; recusar, cancelar e desfazer', async () => {
  const a = await person();
  const b = await person();
  const c = await person();
  await api('POST', '/v1/friends/requests', { token: a.token, body: { userId: b.id } });
  const both = await api('POST', '/v1/friends/requests', { token: b.token, body: { userId: a.id } });
  assert.equal(both.json.status, 'friends');
  const r = await api('POST', '/v1/friends/requests', { token: a.token, body: { userId: c.id } });
  // Quem recebe recusa; quem pediu pode cancelar; ninguém mais mexe.
  assert.equal((await api('POST', `/v1/friends/requests/${r.json.requestId}/accept`, { token: a.token })).status, 404);
  assert.equal((await api('POST', `/v1/friends/requests/${r.json.requestId}/decline`, { token: a.token })).json.status, 'none');
  assert.equal((await api('GET', '/v1/friends', { token: c.token })).json.incoming.length, 0);
  await api('DELETE', `/v1/friends/${b.id}`, { token: a.token });
  assert.equal((await api('GET', '/v1/friends', { token: b.token })).json.friends.length, 0);
  assert.equal((await api('POST', '/v1/friends/requests', { token: a.token, body: { userId: a.id } })).status, 400);
  assert.equal((await api('POST', '/v1/friends/requests', { token: a.token, body: { userId: 'unada' } })).status, 404);
});

// -------------------------------------------------------------------- músicas compartilhadas

test('compartilhar só com amigo; quem está com o app aberto recebe na hora e o servidor esquece', async () => {
  const a = await person();
  const b = await person();
  assert.equal((await api('POST', '/v1/shares', { token: a.token, body: { to: b.id, track: track(3) } })).status, 403);
  await friends(a, b);
  assert.equal((await api('POST', '/v1/shares', { token: a.token, body: { to: b.id, track: { title: 'x' } } })).status, 400);
  const appB = await online(b);
  const sent = await api('POST', '/v1/shares', { token: a.token, body: { to: b.id, track: track(3) } });
  assert.equal(sent.status, 201);
  const ev = await appB.next((e) => e.t === 'delivery');
  assert.equal(ev.delivery.track.videoId, track(3).videoId);
  assert.equal(ev.delivery.from, a.id);
  assert.equal(ev.from.id, a.id);
  appB.send({ t: 'ack', ids: [ev.delivery.id] });
  await tick();
  assert.equal((await api('GET', '/v1/mailbox', { token: b.token })).json.deliveries.length, 0);
  appB.close();
});

test('quem estava com o app fechado busca na caixa; o ack apaga só as suas', async () => {
  const a = await person();
  const b = await person();
  const c = await person();
  await friends(a, b);
  await api('POST', '/v1/shares', { token: a.token, body: { to: b.id, track: track(4) } });
  await api('POST', '/v1/shares', { token: a.token, body: { to: b.id, track: track(5) } });
  const box = await api('GET', '/v1/mailbox', { token: b.token });
  assert.deepEqual(box.json.deliveries.map((d) => d.track.videoId), [track(4).videoId, track(5).videoId]);
  assert.equal(box.json.from[0].id, a.id);
  const ids = box.json.deliveries.map((d) => d.id);
  assert.equal((await api('POST', '/v1/mailbox/ack', { token: c.token, body: { ids } })).json.removed, 0);
  assert.equal((await api('POST', '/v1/mailbox/ack', { token: b.token, body: { ids } })).json.removed, 2);
  assert.equal((await api('GET', '/v1/mailbox', { token: b.token })).json.deliveries.length, 0);
});

test('conversa: texto (com quebras, limpo) e reação só entre amigos, pela mesma caixa', async () => {
  const a = await person();
  const b = await person();
  assert.equal((await api('POST', '/v1/messages', { token: a.token, body: { to: b.id, text: 'oi' } })).status, 403);
  await friends(a, b);
  assert.equal((await api('POST', '/v1/messages', { token: a.token, body: { to: b.id, text: '  \u202E ' } })).status, 400);
  assert.equal((await api('POST', '/v1/messages', { token: a.token, body: { to: b.id } })).status, 400);
  const appB = await online(b);
  const sent = await api('POST', '/v1/messages', { token: a.token, body: { to: b.id, text: 'oi!\r\n\n\n\ntudo bem?\u202E' } });
  assert.equal(sent.status, 201);
  const ev = await appB.next((e) => e.t === 'delivery');
  assert.equal(ev.delivery.kind, 'text');
  assert.equal(ev.delivery.text, 'oi!\n\ntudo bem?');
  assert.equal(ev.delivery.id, sent.json.message.id);
  // b reage à mensagem de a (o id da entrega é o mesmo nos dois celulares); "" tira a reação.
  const bad = [{ ref: sent.json.message.id, emoji: '💩' }, { ref: 0, emoji: '❤️' }, { ref: '3', emoji: '❤️' }, { ref: 3 }];
  for (const body of bad) assert.equal((await api('POST', '/v1/reactions', { token: b.token, body: { to: a.id, ...body } })).status, 400);
  const appA = await online(a);
  assert.equal((await api('POST', '/v1/reactions', { token: b.token, body: { to: a.id, ref: sent.json.message.id, emoji: '❤️' } })).status, 201);
  const r = await appA.next((e) => e.t === 'delivery');
  assert.deepEqual([r.delivery.kind, r.delivery.ref, r.delivery.emoji, r.from.id], ['reaction', sent.json.message.id, '❤️', b.id]);
  assert.equal((await api('POST', '/v1/reactions', { token: b.token, body: { to: a.id, ref: sent.json.message.id, emoji: '' } })).status, 201);
  assert.equal((await appA.next((e) => e.t === 'delivery' && e.delivery.emoji === '')).delivery.ref, sent.json.message.id);
  appA.close();
  appB.close();
});

test('conversa 1.6: resposta, edição e "escrevendo…" (só entre amigos, sem guardar nada)', async () => {
  const a = await person();
  const b = await person();
  const c = await person();
  await friends(a, b);
  const appB = await online(b);
  const first = await api('POST', '/v1/messages', { token: a.token, body: { to: b.id, text: 'bora ouvir?' } });
  await appB.next((e) => e.t === 'delivery' && e.delivery.kind === 'text');
  for (const replyTo of [0, -1, '3', 1.5]) {
    assert.equal((await api('POST', '/v1/messages', { token: b.token, body: { to: a.id, text: 'x', replyTo } })).json.error, 'invalid_reply');
  }
  const appA = await online(a);
  assert.equal((await api('POST', '/v1/messages', { token: b.token, body: { to: a.id, text: 'bora!', replyTo: first.json.message.id } })).status, 201);
  const reply = await appA.next((e) => e.t === 'delivery' && e.delivery.kind === 'text');
  assert.deepEqual([reply.delivery.text, reply.delivery.replyTo], ['bora!', first.json.message.id]);
  assert.equal((await api('POST', '/v1/shares', { token: b.token, body: { to: a.id, track: track(9), replyTo: first.json.message.id } })).status, 201);
  assert.equal((await appA.next((e) => e.t === 'delivery' && e.delivery.kind === 'share')).delivery.replyTo, first.json.message.id);
  // Edição: texto limpo como uma mensagem nova; ref inválido ou texto vazio é recusado.
  assert.equal((await api('POST', '/v1/messages/edit', { token: a.token, body: { to: b.id, ref: 0, text: 'x' } })).json.error, 'invalid_edit');
  assert.equal((await api('POST', '/v1/messages/edit', { token: a.token, body: { to: b.id, ref: first.json.message.id, text: ' ‮ ' } })).json.error, 'invalid_text');
  assert.equal((await api('POST', '/v1/messages/edit', { token: c.token, body: { to: b.id, ref: first.json.message.id, text: 'oi' } })).status, 403);
  const edit = await api('POST', '/v1/messages/edit', { token: a.token, body: { to: b.id, ref: first.json.message.id, text: 'bora ouvir agora?' } });
  assert.equal(edit.status, 201);
  const ev = await appB.next((e) => e.t === 'delivery' && e.delivery.kind === 'edit');
  assert.deepEqual([ev.delivery.ref, ev.delivery.text, ev.delivery.id], [first.json.message.id, 'bora ouvir agora?', edit.json.edit.id]);
  // "Escrevendo…" vai na hora pra amigo com o app aberto; pra quem não é amigo, nada.
  appA.send({ t: 'typing', to: b.id });
  assert.deepEqual(await appB.next((e) => e.t === 'typing'), { t: 'typing', from: a.id, on: true });
  appA.send({ t: 'typing', to: b.id, on: false });
  assert.equal((await appB.next((e) => e.t === 'typing')).on, false);
  const appC = await online(c);
  appC.send({ t: 'typing', to: b.id });
  appA.send({ t: 'typing', to: c.id });
  await tick();
  assert.equal(appB.events.filter((e) => e.t === 'typing').length, 0);
  assert.equal(appC.events.filter((e) => e.t === 'typing').length, 0);
  appA.close();
  appB.close();
  appC.close();
});

/** Um "MP4" mínimo: o relay só confere a caixa ftyp. */
const voiceBytes = (n = 2048) => {
  const buf = Buffer.alloc(n, 7);
  buf.writeUInt32BE(24, 0);
  buf.write('ftypM4A ', 4, 'latin1');
  return buf;
};

test('mensagem de voz: o áudio fica só até quem recebe baixar e confirmar', async () => {
  const a = await person();
  const b = await person();
  const c = await person();
  const path = (q) => '/v1/voice?' + new URLSearchParams(q).toString();
  const headers = { 'content-type': 'audio/mp4' };
  assert.equal((await api('POST', path({ to: b.id, ms: 4000, wave: '0369cf' }), { token: a.token, raw: voiceBytes(), headers })).status, 403);
  await friends(a, b);
  const bad = [
    { to: b.id, ms: 100, wave: '0369' },
    { to: b.id, ms: 400_000, wave: '0369' },
    { to: b.id, ms: 4000, wave: 'xyz' },
    { to: b.id, ms: 4000, wave: '' },
    { to: b.id, ms: 4000, wave: '0'.repeat(65) },
  ];
  for (const q of bad) assert.equal((await api('POST', path(q), { token: a.token, raw: voiceBytes(), headers })).json.error, 'invalid_voice');
  assert.equal((await api('POST', path({ to: b.id, ms: 4000, wave: '01' }), { token: a.token, raw: Buffer.alloc(2048, 1), headers })).json.error, 'invalid_voice');
  assert.equal((await api('POST', path({ to: b.id, ms: 4000, wave: '01', replyTo: 'x' }), { token: a.token, raw: voiceBytes(), headers })).json.error, 'invalid_reply');
  const big = await api('POST', path({ to: b.id, ms: 4000, wave: '01' }), { token: a.token, raw: voiceBytes(1536 * 1024 + 1), headers });
  assert.equal(big.status, 413);
  const appB = await online(b);
  const audio = voiceBytes(4096);
  const sent = await api('POST', path({ to: b.id, ms: 4200, wave: '0369cf', replyTo: 12 }), { token: a.token, raw: audio, headers });
  assert.equal(sent.status, 201);
  const ev = await appB.next((e) => e.t === 'delivery' && e.delivery.kind === 'voice');
  assert.deepEqual(ev.delivery.voice, { ms: 4200, wave: '0369cf', bytes: 4096 });
  assert.equal(ev.delivery.replyTo, 12);
  // Só quem recebe baixa.
  assert.equal((await api('GET', `/v1/voice/${ev.delivery.id}`, { token: a.token })).status, 404);
  assert.equal((await api('GET', `/v1/voice/${ev.delivery.id}`, { token: c.token })).status, 404);
  const got = await api('GET', `/v1/voice/${ev.delivery.id}`, { token: b.token });
  assert.equal(got.status, 200);
  assert.equal(got.res.headers.get('content-type'), 'audio/mp4');
  assert.deepEqual(Buffer.from(await got.res.arrayBuffer()), audio);
  // Confirmado: some a entrega e o áudio.
  assert.equal((await api('POST', '/v1/mailbox/ack', { token: b.token, body: { ids: [ev.delivery.id] } })).json.removed, 1);
  assert.equal((await api('GET', `/v1/voice/${ev.delivery.id}`, { token: b.token })).status, 404);
  assert.equal(relay.store.pendingVoiceBytes(b.id), 0);
  // Vencido (14 dias) também leva o áudio junto.
  await api('POST', path({ to: b.id, ms: 4200, wave: '03' }), { token: a.token, raw: audio, headers });
  assert.equal(relay.store.pendingVoiceBytes(b.id), 4096);
  now += DELIVERY_TTL_MS + 1;
  relay.store.expireDeliveries(now - DELIVERY_TTL_MS);
  assert.equal(relay.store.pendingVoiceBytes(b.id), 0);
  appB.close();
});

test('nome ou foto mudou: amigos, quem está na jam e os outros celulares veem na hora', async () => {
  const a = await person();
  const b = await person();
  const c = await person();
  const d = await person();
  await friends(a, b);
  await friends(b, c);
  const appB = await online(b);
  const appC = await online(c);
  const appD = await online(d);
  const appA2 = await online(a);
  // a e c (amigo de amigo) na mesma jam, pelo b.
  const jam = await api('POST', '/v1/jams', { token: b.token, body: { invite: [a.id, c.id] } });
  await api('POST', `/v1/jams/${jam.json.jam.id}/join`, { token: a.token });
  await api('POST', `/v1/jams/${jam.json.jam.id}/join`, { token: c.token });
  await api('PATCH', '/v1/me', { token: a.token, body: { name: 'Osorio' } });
  const forFriend = await appB.next((e) => e.t === 'profile');
  assert.deepEqual([forFriend.user.id, forFriend.user.name], [a.id, 'Osorio']);
  assert.ok('numbers' in forFriend.user);
  const forJam = await appC.next((e) => e.t === 'profile');
  assert.deepEqual([forJam.user.id, forJam.user.name], [a.id, 'Osorio']);
  assert.ok(!('numbers' in forJam.user));
  assert.equal((await appA2.next((e) => e.t === 'profile')).user.name, 'Osorio');
  // O mesmo nome de novo não avisa ninguém.
  await api('PATCH', '/v1/me', { token: a.token, body: { name: 'Osorio' } });
  await api('PUT', '/v1/me/avatar', { token: a.token, raw: TINY_JPEG, headers: { 'content-type': 'image/jpeg' } });
  assert.match((await appB.next((e) => e.t === 'profile')).user.avatar, /\/avatars\//);
  await tick();
  assert.equal(appB.events.filter((e) => e.t === 'profile').length, 0);
  assert.equal(appD.events.filter((e) => e.t === 'profile').length, 0);
  for (const app of [appB, appC, appD, appA2]) app.close();
});

test('números novos: amigos com o app aberto veem na hora, os mesmos números não avisam', async () => {
  const a = await person();
  const b = await person();
  const d = await person();
  await friends(a, b);
  const appB = await online(b);
  const appD = await online(d);
  const numbers = { month: { minutes: 42, songs: 3, artists: 2, weekdays: [0, 0, 0, 0, 0, 0, 42], top: [] } };
  await api('PUT', '/v1/me/stats', { token: a.token, body: { listenedMs: 2_520_000, top: [track(1)], numbers } });
  const seen = await appB.next((e) => e.t === 'profile');
  assert.equal(seen.user.id, a.id);
  assert.equal(seen.user.listenedMs, 2_520_000);
  assert.equal(seen.user.top.length, 1);
  await api('PUT', '/v1/me/stats', { token: a.token, body: { listenedMs: 2_520_000, top: [track(1)], numbers } });
  await tick();
  assert.equal(appB.events.filter((e) => e.t === 'profile').length, 0);
  assert.equal(appD.events.filter((e) => e.t === 'profile').length, 0);
  for (const app of [appB, appD]) app.close();
});

test('entrega não buscada some depois de 14 dias', async () => {
  const a = await person();
  const b = await person();
  await friends(a, b);
  await api('POST', '/v1/shares', { token: a.token, body: { to: b.id, track: track(6) } });
  relay.store.expireDeliveries(now - DELIVERY_TTL_MS);
  assert.equal((await api('GET', '/v1/mailbox', { token: b.token })).json.deliveries.length, 1);
  now += DELIVERY_TTL_MS + 1;
  relay.store.expireDeliveries(now - DELIVERY_TTL_MS);
  assert.equal((await api('GET', '/v1/mailbox', { token: b.token })).json.deliveries.length, 0);
});

// -------------------------------------------------------------------- jam

test('jam: só com amigo; amigo de amigo entra pelo convite do amigo', async () => {
  const a = await person();
  const b = await person();
  const c = await person();
  await friends(a, b);
  await friends(b, c);
  // A não é amigo de C: não começa jam com ele, nem convida.
  assert.equal((await api('POST', '/v1/jams', { token: a.token, body: { invite: [c.id] } })).status, 403);
  const appB = await online(b);
  const appC = await online(c);
  const created = await api('POST', '/v1/jams', { token: a.token, body: { invite: [b.id], state: { queue: [track(1), track(2)], index: 0, positionMs: 0, playing: true } } });
  assert.equal(created.status, 201);
  const jam = created.json.jam;
  assert.equal(jam.hostId, a.id);
  assert.equal(jam.state.queue.length, 2);
  assert.equal(jam.state.playing, true);
  const invite = await appB.next((e) => e.t === 'jam.invite');
  assert.equal(invite.jam.id, jam.id);
  assert.equal(invite.from.id, a.id);
  assert.equal((await api('POST', `/v1/jams/${jam.id}/invite`, { token: a.token, body: { userIds: [c.id] } })).status, 403);
  // C não foi convidado: não entra.
  assert.equal((await api('POST', `/v1/jams/${jam.id}/join`, { token: c.token })).status, 403);
  const joined = await api('POST', `/v1/jams/${jam.id}/join`, { token: b.token });
  assert.deepEqual(joined.json.jam.members.map((m) => m.id).sort(), [a.id, b.id].sort());
  // B convida o amigo dele.
  assert.equal((await api('POST', `/v1/jams/${jam.id}/invite`, { token: b.token, body: { userIds: [c.id] } })).status, 200);
  const inviteC = await appC.next((e) => e.t === 'jam.invite');
  assert.equal(inviteC.from.id, b.id);
  assert.equal((await api('GET', '/v1/jams/current', { token: c.token })).json.invites[0].jam.id, jam.id);
  await api('POST', `/v1/jams/${jam.id}/join`, { token: c.token });
  const current = await api('GET', '/v1/jams/current', { token: c.token });
  assert.equal(current.json.jam.members.length, 3);
  appB.close();
  appC.close();
});

test('jam: qualquer um muda o que toca pra todos; dois ao mesmo tempo, vale o primeiro', async () => {
  const a = await person();
  const b = await person();
  await friends(a, b);
  const appA = await online(a);
  const appB = await online(b);
  const jam = (await api('POST', '/v1/jams', { token: a.token, body: { invite: [b.id], state: { queue: [track(1), track(2), track(3)], index: 0, positionMs: 0, playing: true } } })).json.jam;
  await api('POST', `/v1/jams/${jam.id}/join`, { token: b.token });
  await appA.next((e) => e.t === 'jam' && e.jam.members.length === 2);
  const rev = jam.state.rev;
  // B pula pra próxima: chega pros dois.
  appB.send({ t: 'jam.state', jamId: jam.id, baseRev: rev, index: 1, positionMs: 0, playing: true });
  const seenA = await appA.next((e) => e.t === 'jam' && e.jam.state.rev === rev + 1);
  assert.equal(seenA.jam.state.index, 1);
  assert.equal(seenA.jam.state.by, b.id);
  assert.equal(seenA.jam.state.queue.length, 3);
  await appB.next((e) => e.t === 'jam' && e.jam.state.rev === rev + 1);
  // A, ainda na versão antiga, pausa: recusado, recebe a atual.
  appA.send({ t: 'jam.state', jamId: jam.id, baseRev: rev, index: 0, positionMs: 5000, playing: false });
  const rejected = await appA.next((e) => e.t === 'jam' && e.rejected);
  assert.equal(rejected.rejected, 'stale');
  assert.equal(rejected.jam.state.index, 1);
  // Fila nova inteira (tocou outra música / adicionou à fila).
  appA.send({ t: 'jam.state', jamId: jam.id, baseRev: rev + 1, queue: [track(9), track(1)], index: 0, positionMs: 12_000, playing: false });
  const q = await appB.next((e) => e.t === 'jam' && e.jam.state.rev === rev + 2);
  assert.deepEqual(q.jam.state.queue.map((t) => t.videoId), [track(9).videoId, track(1).videoId]);
  assert.equal(q.jam.state.positionMs, 12_000);
  assert.equal(q.jam.state.playing, false);
  assert.equal(typeof q.jam.serverTime, 'number');
  // Índice fora da fila: recusado.
  const bad = await api('POST', `/v1/jams/${jam.id}/state`, { token: b.token, body: { baseRev: rev + 2, index: 5, positionMs: 0, playing: true } });
  assert.equal(bad.status, 400);
  appA.close();
  appB.close();
});

test('jam: quem não está nela não mexe; relógio do servidor no ping', async () => {
  const a = await person();
  const b = await person();
  const outsider = await person();
  await friends(a, b);
  const jam = (await api('POST', '/v1/jams', { token: a.token, body: { invite: [b.id] } })).json.jam;
  const r = await api('POST', `/v1/jams/${jam.id}/state`, { token: outsider.token, body: { baseRev: 0, queue: [track(1)], index: 0, positionMs: 0, playing: true } });
  assert.equal(r.status, 403);
  const app = await online(outsider);
  app.send({ t: 'ping', c: 123 });
  const pong = await app.next((e) => e.t === 'pong');
  assert.equal(pong.c, 123);
  assert.equal(pong.s, now);
  app.close();
});

test('jam: alguém sai e segue; quem criou sai ou encerra e acaba pra todos', async () => {
  const a = await person();
  const b = await person();
  const c = await person();
  await friends(a, b);
  await friends(a, c);
  const appB = await online(b);
  const appC = await online(c);
  let jam = (await api('POST', '/v1/jams', { token: a.token, body: { invite: [b.id, c.id] } })).json.jam;
  await api('POST', `/v1/jams/${jam.id}/join`, { token: b.token });
  await api('POST', `/v1/jams/${jam.id}/join`, { token: c.token });
  await api('POST', `/v1/jams/${jam.id}/leave`, { token: c.token });
  const left = await appB.next((e) => e.t === 'jam' && e.jam.members.length === 2);
  assert.ok(!left.jam.members.some((m) => m.id === c.id));
  assert.equal((await api('DELETE', `/v1/jams/${jam.id}`, { token: b.token })).status, 403);
  await api('POST', `/v1/jams/${jam.id}/leave`, { token: a.token });
  await appB.next((e) => e.t === 'jam.ended' && e.jamId === jam.id);
  assert.equal((await api('GET', '/v1/jams/current', { token: b.token })).json.jam, null);
  // Encerrar: só quem criou.
  jam = (await api('POST', '/v1/jams', { token: a.token, body: { invite: [b.id] } })).json.jam;
  await api('POST', `/v1/jams/${jam.id}/join`, { token: b.token });
  await api('DELETE', `/v1/jams/${jam.id}`, { token: a.token });
  await appB.next((e) => e.t === 'jam.ended' && e.jamId === jam.id);
  appB.close();
  appC.close();
});

test('jam: entrar numa nova sai da anterior; jam sem ninguém conectado acaba sozinha', async () => {
  const a = await person();
  const b = await person();
  const c = await person();
  await friends(a, c);
  await friends(b, c);
  const j1 = (await api('POST', '/v1/jams', { token: a.token, body: { invite: [c.id] } })).json.jam;
  const j2 = (await api('POST', '/v1/jams', { token: b.token, body: { invite: [c.id] } })).json.jam;
  await api('POST', `/v1/jams/${j1.id}/join`, { token: c.token });
  await api('POST', `/v1/jams/${j2.id}/join`, { token: c.token });
  assert.equal(relay.jams.get(j1.id).members.has(c.id), false);
  assert.equal(relay.jams.get(j2.id).members.has(c.id), true);
  // Ninguém com o app aberto por 15 min: acaba.
  const idleBefore = relay.jams.idle(() => false).map((j) => j.id);
  assert.ok(!idleBefore.includes(j1.id));
  now += IDLE_END_MS;
  assert.ok(relay.jams.idle(() => false).some((j) => j.id === j1.id));
});

// -------------------------------------------------------------------- segurança e dados

test('apagar o perfil some com tudo e avisa os amigos', async () => {
  const a = await person();
  const b = await person();
  await friends(a, b);
  const appB = await online(b);
  await api('POST', '/v1/shares', { token: b.token, body: { to: a.id, track: track(7) } });
  assert.equal((await api('DELETE', '/v1/me', { token: a.token })).status, 200);
  const gone = await appB.next((e) => e.t === 'friend.removed');
  assert.equal(gone.userId, a.id);
  assert.equal((await api('GET', '/v1/me', { token: a.token })).status, 401);
  assert.equal((await api('GET', `/v1/users/${a.id}`, { token: b.token })).status, 404);
  assert.equal((await api('POST', '/v1/accounts/recover', { body: { code: a.code } })).status, 404);
  appB.close();
});

test('WebSocket sem token, ou com token na URL, é recusado', async () => {
  const p = await person();
  for (const url of [`${base.replace('http', 'ws')}/v1/ws`, `${base.replace('http', 'ws')}/v1/ws?token=${p.token}`]) {
    const ws = new WebSocket(url);
    const code = await new Promise((r) => {
      ws.on('unexpected-response', (_req, res) => r(res.statusCode));
      ws.on('open', () => r('open'));
      ws.on('error', () => r('error'));
    });
    assert.equal(code, 401);
  }
});

test('números do perfil: só totais, conferidos, e só a pessoa e os amigos veem', async () => {
  const a = await person();
  const b = await person();
  const c = await person();
  await friends(a, b);
  const period = (over = {}) => ({
    minutes: 2412, songs: 212, artists: 74, hour: 22, weekdays: [5, 4, 6, 5, 9, 7, 3],
    top: [{ name: 'Maná', minutes: 500, cover: 'https://i.ytimg.com/vi/x/hq.jpg' }],
    ...over,
  });
  const r = await api('PUT', '/v1/me/stats', {
    token: a.token,
    body: {
      listenedMs: 3_600_000,
      top: [],
      numbers: {
        month: period({ hour: 99, weekdays: [1, 2], top: [...Array(9)].map((_, i) => ({ name: `Artista ${i}`, minutes: 10, cover: 'https://evil.example/x.jpg' })) }),
        year: period({ minutes: -5, songs: 1e12 }),
        history: ['nada disso fica'],
      },
    },
  });
  assert.equal(r.status, 200);
  const n = r.json.me.numbers;
  // Hora fora do relógio vira vazia, semana torta vira zeros, no máximo 5 artistas, capa de servidor estranho some.
  assert.equal(n.month.hour, null);
  assert.deepEqual(n.month.weekdays, [0, 0, 0, 0, 0, 0, 0]);
  assert.equal(n.month.top.length, 5);
  assert.ok(n.month.top.every((t) => t.cover === undefined));
  assert.equal(n.year.minutes, 0);
  assert.equal(n.year.songs, 1_000_000);
  assert.equal(n.year.top[0].cover, 'https://i.ytimg.com/vi/x/hq.jpg');
  assert.equal(n.history, undefined);
  // As músicas mais ouvidas seguem no "top" de sempre: os números não levam lista de músicas.
  assert.equal(n.year.topSongs, undefined);
  // O amigo vê; quem não é amigo, não.
  const seen = (await api('GET', `/v1/users/${a.id}`, { token: b.token })).json.user;
  assert.equal(seen.numbers.year.top[0].name, 'Maná');
  const stranger = (await api('GET', `/v1/users/${a.id}`, { token: c.token })).json.user;
  assert.equal(stranger.numbers, undefined);
  // Um app antigo (sem números) não apaga os que estavam.
  await api('PUT', '/v1/me/stats', { token: a.token, body: { listenedMs: 4_000_000, top: [] } });
  assert.equal((await api('GET', '/v1/me', { token: a.token })).json.me.numbers.year.top[0].name, 'Maná');
});

test('o banco guarda só o básico: nada de histórico legível, IP, último acesso ou versão do app', async () => {
  const db = new DatabaseSync(join(dir, 'freesong.db'), { readOnly: true });
  const tables = db.prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").all().map((t) => t.name).sort();
  assert.deepEqual(tables, ['deliveries', 'friend_requests', 'friendships', 'tokens', 'users', 'vaults', 'voice_notes']);
  // Áudio de voz: só o que está esperando entrega, preso a ela (sem quem mandou nem quando).
  const voiceCols = db.prepare('PRAGMA table_info(voice_notes)').all().map((c) => c.name).sort();
  assert.deepEqual(voiceCols, ['data', 'delivery_id', 'to_id']);
  // O cofre é um blob opaco (cifrado no celular) e a chave dele embrulhada pelo código: nada legível.
  const vaultCols = db.prepare('PRAGMA table_info(vaults)').all().map((c) => c.name).sort();
  assert.deepEqual(vaultCols, ['data', 'key', 'rev', 'updated_at', 'user_id']);
  const cols = db.prepare('PRAGMA table_info(users)').all().map((c) => c.name).sort();
  assert.deepEqual(cols, ['avatar_rev', 'created_at', 'id', 'listened_ms', 'name', 'numbers_json', 'recovery_hash', 'top_json', 'username']);
  // Tokens e códigos só em hash.
  const p = await person();
  const row = db.prepare('SELECT hash FROM tokens').all().map((r) => r.hash);
  assert.ok(!row.includes(p.token));
  assert.ok(row.every((h) => /^[0-9a-f]{64}$/.test(h)));
  const user = db.prepare('SELECT recovery_hash FROM users WHERE id = ?').get(p.id);
  assert.notEqual(user.recovery_hash, p.code);
  db.close();
});

test('criar conta tem limite por IP', async () => {
  const ip = { 'cf-connecting-ip': '203.0.113.9' };
  let last;
  for (let i = 0; i < 21; i++) last = await api('POST', '/v1/accounts', { body: {}, headers: ip });
  assert.equal(last.status, 429);
});

test('rota desconhecida, método errado e JSON quebrado', async () => {
  const p = await person();
  assert.equal((await api('GET', '/v1/nada')).status, 404);
  assert.equal((await api('DELETE', '/v1/health')).status, 405);
  const r = await api('PATCH', '/v1/me', { token: p.token, raw: '{quebrado', headers: { 'content-type': 'application/json' } });
  assert.equal(r.status, 400);
  assert.equal(r.json.error, 'bad_json');
});

test('jam: fila com música inválida é recusada inteira (o índice não aponta pra outra)', async () => {
  const a = await person();
  const jam = (await api('POST', '/v1/jams', { token: a.token, body: {} })).json.jam;
  const bad = await api('POST', `/v1/jams/${jam.id}/state`, { token: a.token, body: { baseRev: jam.state.rev, queue: [track(1), { title: 'sem vídeo' }, track(3)], index: 2, positionMs: 0, playing: true } });
  assert.equal(bad.status, 400);
  assert.equal(relay.jams.get(jam.id).state.queue.length, 0);
});

test('jam: quem não está nela não recebe nada dela pelo WebSocket', async () => {
  const a = await person();
  const outsider = await person();
  const jam = (await api('POST', '/v1/jams', { token: a.token, body: { state: { queue: [track(1)], index: 0, positionMs: 0, playing: true } } })).json.jam;
  const app = await online(outsider);
  app.send({ t: 'jam.state', jamId: jam.id, baseRev: jam.state.rev, index: 0, positionMs: 0, playing: false });
  const ev = await app.next((e) => e.t === 'jam' || e.t === 'jam.ended');
  assert.equal(ev.t, 'jam.ended');
  assert.equal(ev.jam, undefined);
  assert.equal(relay.jams.get(jam.id).state.playing, true);
  app.close();
});

test('jam: convites demais não deixam jam criada pela metade', async () => {
  const a = await person();
  const ids = Array.from({ length: 31 }, (_, i) => `ufake${i}`);
  const r = await api('POST', '/v1/jams', { token: a.token, body: { invite: ids } });
  assert.equal(r.status, 400);
  assert.equal(r.json.error, 'jam_full');
  assert.equal((await api('GET', '/v1/jams/current', { token: a.token })).json.jam, null);
});

// -------------------------------------------------------------------- segurança (rodada 2)

const RLO = String.fromCodePoint(0x202e);
const ZWSP = String.fromCodePoint(0x200b);
const LSEP = String.fromCodePoint(0x2028);

/** Segmento JPEG [marker] com o texto dentro. */
function segment(marker, text) {
  const data = Buffer.from(text, 'latin1');
  const head = Buffer.from([0xff, marker, 0, 0]);
  head.writeUInt16BE(data.length + 2, 2);
  return Buffer.concat([head, data]);
}

const closeCode = (ws) => new Promise((r) => ws.once('close', (code) => r(code)));

test('foto: sai sem EXIF/GPS, comentário nem arquivo colado; gigante ou cortada é recusada', async () => {
  const a = await person();
  const dirty = Buffer.concat([
    TINY_JPEG.subarray(0, 2),
    segment(0xe0, 'JFIF\0miniatura SEGREDO'),
    segment(0xe1, 'Exif\0\0GPS -23.55 -46.63 SEGREDO'),
    segment(0xed, 'Photoshop 3.0\0IPTC SEGREDO'),
    segment(0xfe, 'comentario SEGREDO'),
    TINY_JPEG.subarray(2),
    Buffer.from('PK\x03\x04 arquivo colado SEGREDO', 'latin1'),
  ]);
  const r = await api('PUT', '/v1/me/avatar', { token: a.token, raw: dirty });
  assert.equal(r.status, 200);
  const served = Buffer.from(await (await fetch(base + r.json.me.avatar)).arrayBuffer());
  assert.deepEqual(served, TINY_JPEG);
  // Dimensão absurda (bomba de memória no celular de quem abrir a lista) e JPEG cortado no meio.
  const huge = Buffer.from(TINY_JPEG);
  huge.writeUInt16BE(30000, huge.indexOf(Buffer.from([0xff, 0xc0])) + 5);
  assert.equal((await api('PUT', '/v1/me/avatar', { token: a.token, raw: huge })).status, 400);
  assert.equal((await api('PUT', '/v1/me/avatar', { token: a.token, raw: TINY_JPEG.subarray(0, TINY_JPEG.length - 20) })).status, 400);
});

test('mensagem grande ou lixo pelo WebSocket não derruba o relay', async () => {
  const a = await person();
  const b = await person();
  const wa = await online(a);
  const wb = await online(b);
  wa.ws.on('error', () => {});
  const closed = closeCode(wa.ws);
  wa.ws.send('x'.repeat(70 * 1024));
  assert.equal(await closed, 1009);
  wb.ws.send(Buffer.from([0, 1, 2, 255]));
  wb.send({ t: 'jam.state', jamId: { nao: 'texto' } });
  wb.send({ t: 'ack', ids: ['x', 1.5, null] });
  wb.send({ t: 'ping', c: 7 });
  assert.equal((await wb.next((e) => e.t === 'pong')).c, 7);
  assert.equal((await api('GET', '/v1/health')).status, 200);
  wb.close();
});

test('URL torta não derruba o relay; respostas com cabeçalhos de segurança', async () => {
  const { port } = relay.server.address();
  for (const line of ['GET //[x HTTP/1.1', 'GET http://[x HTTP/1.1']) {
    const reply = await new Promise((resolve) => {
      let data = '';
      const s = net.connect(port, '127.0.0.1', () => s.write(`${line}\r\nHost: relay\r\nConnection: close\r\n\r\n`));
      s.on('data', (d) => (data += d));
      s.on('close', () => resolve(data));
      s.on('error', () => resolve(data));
    });
    assert.match(reply, /^HTTP\/1\.1 4\d\d /); // respondeu (400/404) em vez de cair
  }
  const h = await api('GET', '/v1/health');
  assert.equal(h.status, 200);
  assert.equal(h.res.headers.get('x-content-type-options'), 'nosniff');
  assert.match(h.res.headers.get('content-security-policy'), /default-src 'none'/);
  assert.equal(h.res.headers.get('referrer-policy'), 'no-referrer');
  // Chegou na Cloudflare sem TLS: recusado (o app descobre o erro na hora).
  const p = await person();
  for (const headers of [{ 'x-forwarded-proto': 'http' }, { 'cf-visitor': '{"scheme":"http"}' }]) {
    const r = await api('GET', '/v1/me', { token: p.token, headers });
    assert.equal(r.status, 403);
    assert.equal(r.json.error, 'https_only');
  }
  assert.equal((await api('GET', '/v1/me', { token: p.token, headers: { 'x-forwarded-proto': 'https', 'cf-visitor': '{"scheme":"https"}' } })).status, 200);
});

test('jam: convite repetido não toca de novo no celular do amigo', async () => {
  const a = await person();
  const b = await person();
  await friends(a, b);
  const wb = await online(b);
  const jam = (await api('POST', '/v1/jams', { token: a.token, body: { invite: [b.id] } })).json.jam;
  await wb.next((e) => e.t === 'jam.invite');
  assert.equal((await api('POST', `/v1/jams/${jam.id}/invite`, { token: a.token, body: { userIds: [b.id] } })).status, 200);
  await tick();
  assert.equal(wb.events.filter((e) => e.t === 'jam.invite').length, 0);
  wb.close();
});

test('teto de WebSockets por pessoa: o mais antigo cai', async () => {
  const a = await person();
  const first = await online(a);
  const closed = closeCode(first.ws);
  const rest = [];
  for (let i = 0; i < MAX_SOCKETS_PER_USER; i++) rest.push(await online(a));
  assert.equal(await closed, 4002);
  rest.at(-1).send({ t: 'ping', c: 1 });
  assert.equal((await rest.at(-1).next((e) => e.t === 'pong')).c, 1);
  for (const s of rest) s.close();
});

test('sair dos outros aparelhos: só o celular que pediu continua', async () => {
  const a = await person();
  const old = await online(a);
  const closed = closeCode(old.ws);
  const rec = await api('POST', '/v1/accounts/recover', { body: { code: a.code }, headers: { 'cf-connecting-ip': '198.51.100.7' } });
  assert.equal(rec.status, 200);
  const r = await api('DELETE', '/v1/me/sessions', { token: rec.json.token });
  assert.equal(r.json.revoked, 1);
  assert.equal(await closed, 4003);
  assert.equal((await api('GET', '/v1/me', { token: a.token })).status, 401);
  assert.equal((await api('GET', '/v1/me', { token: rec.json.token })).status, 200);
});

test('teto de aparelhos: o mais antigo cai quando entra um além do teto', async () => {
  const a = await person();
  const ip = { 'cf-connecting-ip': '198.51.100.8' };
  let last;
  for (let i = 0; i < MAX_TOKENS; i++) last = await api('POST', '/v1/accounts/recover', { body: { code: a.code }, headers: ip });
  assert.equal(last.status, 200);
  assert.equal((await api('GET', '/v1/me', { token: a.token })).status, 401);
  assert.equal((await api('GET', '/v1/me', { token: last.json.token })).status, 200);
});

test('limites por IP: IPv6 conta pelo /64; limitador cheio não devolve o limite de ninguém', async () => {
  assert.equal(ipKey('203.0.113.9'), '203.0.113.9');
  assert.equal(ipKey('::ffff:203.0.113.9'), '203.0.113.9');
  assert.equal(ipKey('2001:db8:1:2:3:4:5:6'), '2001:db8:1:2::/64');
  assert.equal(ipKey('2001:0db8:0001:0002::abcd'), '2001:db8:1:2::/64');
  assert.equal(ipKey('2001:db8::1'), '2001:db8:0:0::/64');
  assert.equal(ipKey('64:ff9b::192.0.2.1'), '64:ff9b:0:0::/64');
  assert.equal(ipKey('lixo'), '?');
  let last;
  for (let i = 1; i <= 21; i++) last = await api('POST', '/v1/accounts', { body: {}, headers: { 'cf-connecting-ip': `2001:db8:77:1::${i.toString(16)}` } });
  assert.equal(last.status, 429);
  let t = 0;
  const lim = new Limiter(1, 1000, () => t, 3);
  assert.equal(lim.take('a'), true);
  assert.equal(lim.take('a'), false);
  assert.equal(lim.take('b'), true);
  assert.equal(lim.take('c'), true);
  assert.equal(lim.take('d'), false); // cheio de chaves valendo: recusa a nova...
  assert.equal(lim.take('a'), false); // ...e quem estava barrado continua barrado
  t = 1000;
  assert.equal(lim.take('d'), true);
  assert.equal(lim.take('a'), true);
});

test('teto de contas novas no servidor todo (muitos IPs)', async () => {
  const d2 = mkdtempSync(join(tmpdir(), 'freesong-relay-'));
  const r2 = createRelay({ dataDir: d2, clock: () => now, log: { error() {} }, newAccountsPerHour: 2 });
  await new Promise((r) => r2.server.listen(0, '127.0.0.1', r));
  const url = `http://127.0.0.1:${r2.server.address().port}/v1/accounts`;
  const codes = [];
  for (let i = 1; i <= 3; i++) codes.push((await fetch(url, { method: 'POST', headers: { 'cf-connecting-ip': `192.0.2.${i}` }, body: '{}' })).status);
  assert.deepEqual(codes, [201, 201, 429]);
  await r2.close();
  rmSync(d2, { recursive: true, force: true });
});

test('nomes e músicas sem invisíveis nem inversão de texto; capa e link só do YouTube', async () => {
  const a = await person();
  const b = await person();
  await friends(a, b);
  let r = await api('PATCH', '/v1/me', { token: a.token, body: { name: `Ana${RLO}gnp.exe${ZWSP}${LSEP}` } });
  assert.equal(r.status, 200);
  assert.equal(r.json.me.name, 'Anagnp.exe');
  r = await api('PATCH', '/v1/me', { token: a.token, body: { name: `${ZWSP}${String.fromCodePoint(0x3164)}` } });
  assert.equal(r.json.error, 'invalid_name');
  const wb = await online(b);
  const sent = { videoId: 'dQw4w9WgXcQ', title: `Oi${RLO}${LSEP}tchau`, artist: 'X', cover: 'https://rastreador.example/pixel.jpg', url: 'https://music.youtube.com/watch?v=dQw4w9WgXcQ' };
  assert.equal((await api('POST', '/v1/shares', { token: a.token, body: { to: b.id, track: sent } })).status, 201);
  const d = (await wb.next((e) => e.t === 'delivery')).delivery;
  assert.equal(d.track.title, 'Oi tchau');
  assert.equal(d.track.cover, undefined);
  assert.equal(d.track.url, 'https://music.youtube.com/watch?v=dQw4w9WgXcQ');
  const cover = (c) => cleanTrack({ videoId: 'dQw4w9WgXcQ', title: 't', cover: c }).cover;
  assert.equal(cover('https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg'), 'https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg');
  assert.equal(cover('https://lh3.googleusercontent.com/abc=w120-h120'), 'https://lh3.googleusercontent.com/abc=w120-h120');
  assert.equal(cover('https://i.ytimg.com.rastreador.example/x.jpg'), undefined);
  assert.equal(cover('https://user:pw@i.ytimg.com/x.jpg'), undefined);
  assert.equal(cover('https://i.ytimg.com:8443/x.jpg'), undefined);
  assert.equal(cover('http://i.ytimg.com/x.jpg'), undefined);
  wb.close();
});

test('pedido de amizade repetido não toca de novo; pedir e cancelar sem parar tem limite', async () => {
  const a = await person();
  const b = await person();
  const wb = await online(b);
  const first = await api('POST', '/v1/friends/requests', { token: a.token, body: { userId: b.id } });
  const again = await api('POST', '/v1/friends/requests', { token: a.token, body: { userId: b.id } });
  assert.equal(again.json.requestId, first.json.requestId);
  await wb.next((e) => e.t === 'friend.request');
  await tick();
  assert.equal(wb.events.filter((e) => e.t === 'friend.request').length, 0);
  let r = first;
  for (let i = 0; i < 3; i++) {
    await api('POST', `/v1/friends/requests/${r.json.requestId}/decline`, { token: a.token });
    r = await api('POST', '/v1/friends/requests', { token: a.token, body: { userId: b.id } });
  }
  assert.equal(r.status, 429);
  wb.close();
});

test('desfazer amizade que não existe não avisa ninguém', async () => {
  const a = await person();
  const b = await person();
  const wb = await online(b);
  assert.equal((await api('DELETE', `/v1/friends/${b.id}`, { token: a.token })).json.status, 'none');
  await tick();
  assert.equal(wb.events.filter((e) => e.t === 'friend.removed').length, 0);
  wb.close();
});

test('jam: estado inválido não tira ninguém da jam; quem não foi convidado não mexe; teto de mudanças', async () => {
  const a = await person();
  const b = await person();
  const c = await person();
  await friends(a, b);
  const jam = (await api('POST', '/v1/jams', { token: a.token, body: { invite: [b.id] } })).json.jam;
  let r = await api('POST', '/v1/jams', { token: a.token, body: { state: { queue: [{ videoId: '!' }], index: 0 } } });
  assert.equal(r.status, 400);
  assert.equal(r.json.error, 'invalid_state');
  assert.equal((await api('GET', '/v1/jams/current', { token: a.token })).json.jam.id, jam.id);
  const wa = await online(a);
  await api('POST', `/v1/jams/${jam.id}/decline`, { token: c.token });
  await tick();
  assert.equal(wa.events.filter((e) => e.t === 'jam').length, 0);
  r = await api('POST', `/v1/jams/${jam.id}/state`, { token: a.token, body: { baseRev: 0, queue: [track(1)], index: 0, positionMs: 1e300, playing: true } });
  assert.equal(r.json.accepted, true);
  assert.ok(r.json.jam.state.positionMs <= 6 * 3600_000);
  let rev = r.json.jam.state.rev;
  let status = 200;
  for (let i = 0; i < 130 && status === 200; i++) {
    r = await api('POST', `/v1/jams/${jam.id}/state`, { token: a.token, body: { baseRev: rev, index: 0, positionMs: i, playing: true } });
    status = r.status;
    if (status === 200) rev = r.json.jam.state.rev;
  }
  assert.equal(status, 429);
  assert.equal(r.json.error, 'slow_down');
  // Pelo WebSocket, no teto: volta a jam como está (o celular desfaz a mudança que ninguém ouviu).
  wa.send({ t: 'jam.state', jamId: jam.id, baseRev: rev, index: 0, positionMs: 99, playing: false });
  const back = await wa.next((e) => e.t === 'jam' && e.rejected === 'slow_down');
  assert.equal(back.jam.state.rev, rev);
  assert.equal(back.jam.state.playing, true);
  wa.close();
});

test('cofre: guardado cifrado, só o dono lê, versão evita atropelo, some com a conta', async () => {
  const a = await person();
  const b = await person();
  const blob = (n) => Buffer.from(Array.from({ length: n }, (_, i) => (i * 31 + n) % 256)).toString('base64');
  assert.equal((await api('GET', '/v1/me/vault', { token: a.token })).json.error, 'no_vault');
  // Criar exige a chave embrulhada (sem ela ninguém abriria o cofre num celular novo).
  let r = await api('PUT', '/v1/me/vault', { token: a.token, body: { baseRev: 0, data: blob(100) } });
  assert.equal(r.json.error, 'bad_vault');
  r = await api('PUT', '/v1/me/vault', { token: a.token, body: { baseRev: 0, key: blob(60), data: blob(100) } });
  assert.equal(r.json.rev, 1);
  let v = (await api('GET', '/v1/me/vault', { token: a.token })).json;
  assert.deepEqual([v.rev, v.key, v.data], [1, blob(60), blob(100)]);
  // Outro celular gravou antes: a versão velha é recusada com a atual (ele junta e tenta de novo).
  r = await api('PUT', '/v1/me/vault', { token: a.token, body: { baseRev: 0, data: blob(120) } });
  assert.equal(r.status, 409);
  assert.equal(r.json.rev, 1);
  // Sem chave nova, a chave fica; os dados mudam.
  r = await api('PUT', '/v1/me/vault', { token: a.token, body: { baseRev: 1, data: blob(120) } });
  assert.equal(r.json.rev, 2);
  v = (await api('GET', '/v1/me/vault', { token: a.token })).json;
  assert.deepEqual([v.rev, v.key, v.data], [2, blob(60), blob(120)]);
  // Código novo: a chave é embrulhada de novo.
  r = await api('PUT', '/v1/me/vault', { token: a.token, body: { baseRev: 2, key: blob(61), data: blob(120) } });
  assert.equal((await api('GET', '/v1/me/vault', { token: a.token })).json.key, blob(61));
  // Ninguém mais vê o cofre de A.
  assert.equal((await api('GET', '/v1/me/vault', { token: b.token })).json.error, 'no_vault');
  // Lixo e tamanho.
  assert.equal((await api('PUT', '/v1/me/vault', { token: b.token, body: { baseRev: 0, key: blob(60), data: 'não é base64!' } })).json.error, 'bad_vault');
  assert.equal((await api('PUT', '/v1/me/vault', { token: b.token, body: { baseRev: 0, key: blob(60), data: blob(10) } })).json.error, 'bad_vault');
  assert.equal((await api('PUT', '/v1/me/vault', { token: b.token, body: { baseRev: 0, key: blob(60), data: blob(4 * 1024 * 1024 + 10) } })).status, 413);
  // Apagou o perfil: o cofre vai junto.
  await api('DELETE', '/v1/me', { token: a.token });
  assert.equal(relay.store.vault(a.id), null);
});
