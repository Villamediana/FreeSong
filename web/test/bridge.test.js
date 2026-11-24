// A ponte (/api): sem passe de computador ligado não atende; com passe, cada computador tem seus
// limites (pedidos, idas ao YouTube, ao mesmo tempo) e um não enche a fila dos outros. YouTube de mentira.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createWeb } from '../server/app.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const A = 'A'.repeat(22);
const B = 'B'.repeat(22);

function setup() {
  let calls = 0;
  let hold = null;
  const music = {
    reset() {},
    async search(text) {
      calls++;
      if (hold) await hold;
      return { items: [{ title: text }] };
    },
  };
  const passes = { check: async (h) => (h === 'pass-a' ? A : h === 'pass-b' ? B : null) };
  const web = createWeb({ publicDir: path.join(here, '..', 'public'), music, lyrics: { find: async () => null }, passes, log: { error() {} } });
  return { web, music, calls: () => calls, holdOn: () => { let open; hold = new Promise((r) => { open = r; }); return () => { hold = null; open(); }; } };
}

async function serve(web) {
  await new Promise((r) => web.server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${web.server.address().port}`;
  let n = 0;
  const get = (p, pass, ip) => fetch(base + p, { headers: { ...(pass ? { 'x-freesong-pass': pass } : {}), 'cf-connecting-ip': ip ?? `10.1.${Math.floor(++n / 200)}.${n % 200}` } });
  return { get, close: () => new Promise((r) => { web.server.closeAllConnections?.(); web.server.close(r); }) };
}

test('sem passe a ponte não atende; as páginas, sim', async () => {
  const s = setup();
  const { get, close } = await serve(s.web);
  try {
    const no = await get('/api/search?q=oceano');
    assert.equal(no.status, 401);
    assert.equal((await no.json()).error, 'pass');
    assert.equal((await get('/api/search?q=oceano', 'inventado')).status, 401);
    assert.equal(s.calls(), 0, 'nada foi ao YouTube');
    assert.equal((await get('/')).status, 200);
    assert.equal((await get('/api/health')).status, 200);
    const ok = await get('/api/search?q=oceano', 'pass-a');
    assert.equal(ok.status, 200);
    assert.equal((await ok.json()).items[0].title, 'oceano');
  } finally {
    await close();
  }
});

test('cada computador: no máximo 6 ao mesmo tempo, e o outro segue sendo atendido', async () => {
  const s = setup();
  const { get, close } = await serve(s.web);
  const open = s.holdOn();
  try {
    const slow = Array.from({ length: 6 }, (_, i) => get(`/api/search?q=lento${i}`, 'pass-a'));
    await new Promise((r) => setTimeout(r, 100));
    assert.equal((await get('/api/search?q=mais', 'pass-a')).status, 429);
    open();
    assert.equal((await get('/api/search?q=outro', 'pass-b')).status, 200);
    for (const r of await Promise.all(slow)) assert.equal(r.status, 200);
  } finally {
    open();
    await close();
  }
});

test('idas ao YouTube por computador têm teto; o que está no cache não conta', async () => {
  const s = setup();
  const { get, close } = await serve(s.web);
  try {
    let refused = 0;
    for (let i = 0; i < 125; i++) if ((await get(`/api/search?q=busca${i}`, 'pass-a')).status === 429) refused++;
    assert.ok(refused >= 5, `recusou ${refused}`);
    assert.equal((await get('/api/search?q=busca1', 'pass-a')).status, 200, 'do cache: atende');
    assert.equal((await get('/api/search?q=nova', 'pass-b')).status, 200, 'o outro computador não sente');
  } finally {
    await close();
  }
});
