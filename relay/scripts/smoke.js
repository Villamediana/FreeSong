// Confere um relay no ar de ponta a ponta, como dois celulares fariam, e apaga o que criou.
//   node scripts/smoke.js https://freesong.simplyonegoal.com
import WebSocket from 'ws';

const base = (process.argv[2] ?? 'http://127.0.0.1:8787').replace(/\/$/, '');
const step = (msg) => console.log(`✓ ${msg}`);
const fail = (msg) => {
  console.error(`✗ ${msg}`);
  process.exit(1);
};

async function api(method, path, token, body) {
  const headers = {};
  if (token) headers.authorization = `Bearer ${token}`;
  if (body !== undefined) headers['content-type'] = 'application/json';
  const res = await fetch(base + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const json = await res.json().catch(() => null);
  if (!res.ok) fail(`${method} ${path} → HTTP ${res.status} ${JSON.stringify(json)}`);
  return json;
}

function socket(token) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(base.replace(/^http/, 'ws') + '/v1/ws', { headers: { authorization: `Bearer ${token}` } });
    const events = [];
    const waiters = [];
    ws.on('message', (d) => {
      const e = JSON.parse(d.toString());
      const w = waiters.find((x) => x.pred(e));
      if (w) {
        waiters.splice(waiters.indexOf(w), 1);
        w.resolve(e);
      } else events.push(e);
    });
    ws.on('error', reject);
    ws.on('unexpected-response', (_q, r) => reject(new Error(`WebSocket HTTP ${r.statusCode}`)));
    ws.on('open', () => resolve({
      ws,
      next: (pred) => {
        const i = events.findIndex(pred);
        if (i >= 0) return Promise.resolve(events.splice(i, 1)[0]);
        return new Promise((res, rej) => {
          waiters.push({ pred, resolve: res });
          setTimeout(() => rej(new Error('evento não chegou em 10 s')), 10_000);
        });
      },
    }));
  });
}

const health = await api('GET', '/v1/health');
step(`relay ${health.version} respondendo em ${base}`);

const a = await api('POST', '/v1/accounts', undefined, {});
const b = await api('POST', '/v1/accounts', undefined, {});
step(`dois perfis criados sozinhos: @${a.me.username} e @${b.me.username}`);

try {
  const wsB = await socket(b.token).catch((e) => fail(`WebSocket não abriu (Cloudflare com WebSocket ligado?): ${e.message}`));
  await wsB.next((e) => e.t === 'hello');
  step('WebSocket aberto pelo túnel');

  const req = await api('POST', '/v1/friends/requests', a.token, { userId: b.me.id });
  await wsB.next((e) => e.t === 'friend.request');
  await api('POST', `/v1/friends/requests/${req.requestId}/accept`, b.token);
  step('pedido de amizade chegou na hora e foi aceito');

  const track = { videoId: 'dQw4w9WgXcQ', title: 'Teste do relay', artist: 'FreeSong' };
  await api('POST', '/v1/shares', a.token, { to: b.me.id, track });
  const d = await wsB.next((e) => e.t === 'delivery');
  wsB.ws.send(JSON.stringify({ t: 'ack', ids: [d.delivery.id] }));
  await new Promise((r) => setTimeout(r, 500));
  const box = await api('GET', '/v1/mailbox', b.token);
  if (box.deliveries.length) fail('a entrega continuou no servidor depois do ack');
  step('música compartilhada entregue e apagada do servidor');

  const jam = (await api('POST', '/v1/jams', a.token, { invite: [b.me.id], state: { queue: [track], index: 0, positionMs: 0, playing: true } })).jam;
  await wsB.next((e) => e.t === 'jam.invite');
  await api('POST', `/v1/jams/${jam.id}/join`, b.token);
  const wsA = await socket(a.token);
  wsB.ws.send(JSON.stringify({ t: 'jam.state', jamId: jam.id, baseRev: jam.state.rev, index: 0, positionMs: 30_000, playing: false }));
  const seen = await wsA.next((e) => e.t === 'jam' && e.jam.state.rev === jam.state.rev + 1);
  if (seen.jam.state.playing !== false || seen.jam.state.positionMs !== 30_000) fail('a jam não repassou a mudança');
  step('jam: convite, entrada e controle em tempo real');
  await api('DELETE', `/v1/jams/${jam.id}`, a.token);
  wsA.ws.close();
  wsB.ws.close();
} finally {
  await api('DELETE', '/v1/me', a.token);
  await api('DELETE', '/v1/me', b.token);
  step('perfis de teste apagados');
}
console.log('\nTudo certo.');
process.exit(0);
