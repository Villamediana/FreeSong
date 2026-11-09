// Ponta a ponta: o relay de verdade numa porta livre, HTTP + WebSocket, banco num diretório temporário.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import WebSocket from 'ws';
import { createRelay, DELIVERY_TTL_MS } from '../src/server.js';
import { IDLE_END_MS } from '../src/jams.js';

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

test('nome e @nome editáveis; @nome único e com regras', async () => {
  const a = await person();
  const b = await person();
  let r = await api('PATCH', '/v1/me', { token: a.token, body: { name: '  Miguel   V.  ', username: '@Miguel.V' } });
  assert.equal(r.status, 200);
  assert.equal(r.json.me.name, 'Miguel V.');
  assert.equal(r.json.me.username, 'miguel.v');
  r = await api('PATCH', '/v1/me', { token: b.token, body: { username: 'MIGUEL.V' } });
  assert.equal(r.status, 409);
  assert.equal(r.json.error, 'username_taken');
  for (const [bad, code] of [['ab', 'username_invalid'], ['1abc', 'username_invalid'], ['a..b', 'username_invalid'], ['abc.', 'username_invalid'], ['tem espaço', 'username_invalid'], ['admin', 'username_reserved']]) {
    r = await api('PATCH', '/v1/me', { token: b.token, body: { username: bad } });
    assert.equal(r.status, 400, bad);
    assert.equal(r.json.error, code, bad);
  }
  r = await api('PATCH', '/v1/me', { token: b.token, body: { name: '   ' } });
  assert.equal(r.json.error, 'invalid_name');
  r = await api('PATCH', '/v1/me', { token: b.token, body: { name: 'x'.repeat(41) } });
  assert.equal(r.json.error, 'invalid_name');
});

test('foto: só JPEG, servida com versão, apagável', async () => {
  const a = await person();
  assert.equal((await api('PUT', '/v1/me/avatar', { token: a.token, raw: Buffer.from('não é imagem'.repeat(20)) })).status, 400);
  const jpeg = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(500, 7), Buffer.from([0xff, 0xd9])]);
  let r = await api('PUT', '/v1/me/avatar', { token: a.token, raw: jpeg, headers: { 'content-type': 'image/jpeg' } });
  assert.equal(r.status, 200);
  assert.match(r.json.me.avatar, new RegExp(`^/avatars/${a.id}\\.jpg\\?v=1$`));
  const img = await fetch(base + r.json.me.avatar);
  assert.equal(img.status, 200);
  assert.equal(img.headers.get('content-type'), 'image/jpeg');
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

test('o banco guarda só o básico: nada de histórico de músicas, IP, último acesso ou versão do app', async () => {
  const db = new DatabaseSync(join(dir, 'freesong.db'), { readOnly: true });
  const tables = db.prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").all().map((t) => t.name).sort();
  assert.deepEqual(tables, ['deliveries', 'friend_requests', 'friendships', 'tokens', 'users']);
  const cols = db.prepare('PRAGMA table_info(users)').all().map((c) => c.name).sort();
  assert.deepEqual(cols, ['avatar_rev', 'created_at', 'id', 'listened_ms', 'name', 'recovery_hash', 'top_json', 'username']);
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
