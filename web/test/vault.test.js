// O cofre no navegador: as mesmas regras do app (os casos do VaultTest.kt), a cifra que o celular abre
// e a sincronização com um relay de mentira (dois "aparelhos" mexendo ao mesmo tempo).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as lock from '../src/core/crypto.js';
import { compact, edit, merge, normalize, PLAYS_KEPT_MS, same } from '../src/core/vault.js';
import { VaultSync } from '../src/core/sync.js';
import { ApiError } from '../src/core/api.js';

const song = (id) => ({ i: id, t: `Song ${id}`, a: 'Artist' });
const like = (id, at = 1) => ({ s: song(id), at });
const play = (id, at) => ({ i: id, t: `Song ${id}`, a: 'Artist', at, l: 200_000, d: 200_000, c: true });
const list = (k, up, ...ids) => ({ k, n: `List ${k}`, at: Number(k), up, s: ids.map(song) });
const V = (x) => normalize(x);

test('curtidas dadas ou tiradas em qualquer aparelho chegam aos outros', () => {
  const base = V({ likes: [like('a'), like('b')] });
  const here = V({ likes: [like('a'), like('b'), like('c')] });
  const there = V({ likes: [like('a'), like('d')] });
  assert.deepEqual(merge(base, here, there, 10).likes.map((l) => l.s.i), ['a', 'c', 'd']);
  const m2 = merge(base, V({ likes: [like('b')] }), V({ likes: [like('a'), like('b')] }), 10);
  assert.deepEqual(m2.likes.map((l) => l.s.i), ['b']);
});

test('um aparelho recém-ligado não perde nada', () => {
  const m = merge(null, V({ likes: [like('new')] }), V({ likes: [like('a'), like('b')] }), 10);
  assert.deepEqual(m.likes.map((l) => l.s.i), ['a', 'b', 'new']);
});

test('o que foi ouvido soma, playlists ficam com a mudança mais nova, conversas nunca somem', () => {
  const now = 1_000_000_000;
  const base = V({ lists: [list('100', 5, 'x')] });
  const here = V({
    plays: [play('x', now - 10), play('y', now - 5)],
    lists: [list('100', 9, 'x', 'y'), list('200', 7, 'z')],
    sh: [{ f: 'uf', dir: 'in', del: 1, s: song('s1'), at: 3, sn: true }],
  });
  const there = V({
    plays: [play('x', now - 10), play('w', now - 1), play('old', now - PLAYS_KEPT_MS - 1)],
    lists: [list('100', 8, 'x', 'q')],
    sh: [{ f: 'uf', dir: 'in', del: 1, s: song('s1'), at: 3, sn: false }, { f: 'uf', dir: 'out', del: 2, s: song('s2'), at: 4, sn: true }],
  });
  const m = merge(base, here, there, now);
  assert.deepEqual(m.plays.map((p) => p.i), ['w', 'y', 'x']);
  assert.deepEqual(m.lists.find((p) => p.k === '100').s.map((s) => s.i), ['x', 'y']);
  assert.ok(m.lists.some((p) => p.k === '200'));
  assert.equal(m.sh.length, 2);
  assert.equal(m.sh.find((x) => x.del === 1).sn, true);
  const gone = merge(base, V({}), V({ lists: [list('100', 5, 'x')] }), now);
  assert.equal(gone.lists.length, 0);
});

test('reações: cada uma fica com a mais nova; a correção mais nova de um texto ganha', () => {
  const text = { f: 'uf', dir: 'in', del: 9, s: { i: '', t: '' }, at: 5, sn: false, k: 'text', t: 'oi' };
  const a = V({ sh: [{ ...text, mr: '❤️', mra: 10 }] });
  const b = V({ sh: [{ ...text, sn: true, mr: '😂', mra: 20, tr: '🔥', tra: 15 }] });
  const m = merge(null, a, b, 100).sh[0];
  assert.deepEqual([m.k, m.t, m.sn, m.mr, m.mra, m.tr, m.tra], ['text', 'oi', true, '😂', 20, '🔥', 15]);
  const base = { f: 'uf', dir: 'out', del: 9, s: { i: '', t: '' }, at: 5, sn: true, k: 'text', t: 'bora?', rp: 4 };
  const r = merge(null, V({ sh: [{ ...base, t: 'bora ouvir?', ed: 100 }] }), V({ sh: [{ ...base, t: 'bora ouvir agora?', ed: 200, rp: null }] }), 1000).sh[0];
  assert.deepEqual([r.t, r.ed, r.rp], ['bora ouvir agora?', 200, 4]);
});

test('nada de novo compara igual (nada é gravado), e o formato compacto volta igual', () => {
  const d = V({ likes: [like('b'), like('a')], plays: [play('x', 5), play('y', 9)] });
  const once = merge(null, d, V({}), 10);
  assert.ok(same(once, merge(once, once, once, 10)));
  // O que vai pro relay (sem os padrões, como o celular escreve) é lido de volta igual.
  const full = V({
    likes: [{ s: { i: 'a', t: 'A', a: '', al: null, d: 1000, c: 'https://i.ytimg.com/vi/a/hqdefault.jpg' }, at: 3 }],
    plays: [{ i: 'a', t: 'A', a: 'X', at: 4, l: 10, d: 0, c: false, k: true, pp: 'ana' }],
    lists: [{ k: '7', n: 'L', at: 7, up: 8, p: true, s: [] }],
    sh: [{ f: 'u', dir: 'out', del: 3, s: { i: 'a', t: 'A' }, at: 1, sn: false }],
    gen: { c: true, ids: ['pop'], at: 2 },
  });
  const wire = compact(full);
  assert.equal(wire.likes[0].s.a, undefined);
  assert.equal(wire.plays[0].d, undefined);
  assert.equal(wire.sh[0].k, undefined);
  assert.equal(wire.sh[0].sn, false);
  assert.ok(same(normalize(JSON.parse(JSON.stringify(wire))), full));
});

test('mudanças da página: curtir, ouvir, playlists', () => {
  let v = V({});
  const s = { videoId: 'abcdefghijk', title: 'Oceano', artist: 'Djavan', album: 'Djavan', durationMs: 240_000, cover: 'https://i.ytimg.com/vi/x/hqdefault.jpg' };
  v = edit.like(v, s, 5);
  assert.equal(v.likes[0].s.u, 'https://music.youtube.com/watch?v=abcdefghijk');
  v = edit.unlike(v, s.videoId);
  assert.equal(v.likes.length, 0);
  v = edit.play(v, s, 230_000, 240_000, 9);
  assert.deepEqual([v.plays[0].c, v.plays[0].k], [true, false]);
  v = edit.play(v, s, 12_000, 240_000, 10);
  assert.deepEqual([v.plays[0].c, v.plays[0].k], [false, true]);
  let key;
  [v, key] = edit.newList(v, '  Estrada  ', [s], 100);
  assert.equal(key, '100');
  [v] = edit.newList(v, 'Outra', [], 100);
  assert.deepEqual(v.lists.map((p) => p.k), ['100', '101']);
  v = edit.list(v, '100', (p) => ({ n: 'Estrada 2' }), 50);
  assert.equal(v.lists[0].n, 'Estrada 2');
  assert.ok(v.lists[0].up > 100);
});

test('a cifra: o que o celular fecha a página abre, e o contrário; o código abre a chave', async () => {
  const raw = globalThis.crypto.getRandomValues(new Uint8Array(32));
  const content = { likes: [like('Oceano')] };
  const phone = await lock.phoneVault(content, raw, '7K2Q-M9XD-4TFA-H3RB', 'uabc');
  const key = await lock.unwrapWithCode(phone.key, '7k2q m9xd 4tfa h3rb', 'uabc');
  assert.ok(key);
  assert.deepEqual(await lock.openVault(phone.data, key), content);
  assert.equal(await lock.unwrapWithCode(phone.key, 'AAAA-BBBB-CCCC-DDDD', 'uabc'), null);
  assert.equal(await lock.unwrapWithCode(phone.key, '7K2Q-M9XD-4TFA-H3RB', 'uother'), null);
  const again = await lock.sealVault({ likes: [like('x')] }, key);
  assert.equal((await lock.openVault(again, key)).likes[0].s.i, 'x');
  const bytes = lock.b64.dec(again);
  bytes[bytes.length - 1] ^= 1;
  await assert.rejects(lock.openVault(lock.b64.enc(bytes), key));
  // Ligar pelo QR code: só o computador do código abre, e só com o segredo do código.
  const keys = await lock.linkKeys();
  const wrapped = await lock.wrapLink(keys.pub, 'L1', keys.secret, { user: 'uabc', key: raw, owner: 'Pixel 8' });
  const opened = await lock.unwrapLink(keys.privateKey, 'L1', keys.secret, wrapped);
  assert.equal(opened.user, 'uabc');
  assert.equal(opened.owner, 'Pixel 8');
  assert.deepEqual(await lock.openVault(phone.data, opened.key), content);
  const other = await lock.linkKeys();
  await assert.rejects(lock.unwrapLink(keys.privateKey, 'L1', other.secret, wrapped));
  await assert.rejects(lock.unwrapLink(keys.privateKey, 'L2', keys.secret, wrapped));
  assert.equal(lock.normalizeCode('O0IL-1'), '00111');
});

/** Um relay de mentira: guarda o cofre, devolve 409 quando a versão é velha. */
function fakeRelay() {
  const r = { rev: 0, data: null, key: null, puts: 0 };
  return {
    r,
    api: {
      async vault() { return r.data ? { rev: r.rev, key: r.key, data: r.data } : null; },
      async putVault(baseRev, data, key) {
        if (baseRev !== r.rev) throw new ApiError(409, 'stale', { rev: r.rev });
        r.rev++;
        r.data = data;
        if (key) r.key = key;
        r.puts++;
        return { rev: r.rev };
      },
    },
  };
}

const memStore = () => {
  const m = new Map();
  return { get: async (k) => m.get(k), set: async (k, v) => { m.set(k, v); } };
};

test('sincronizar: a web junta com o celular, grava uma vez, e o que mudou no meio fica', async () => {
  const raw = globalThis.crypto.getRandomValues(new Uint8Array(32));
  const phoneKey = await globalThis.crypto.subtle.importKey('raw', raw, 'AES-GCM', false, ['encrypt', 'decrypt']);
  const relay = fakeRelay();
  // O celular já tinha um cofre.
  relay.r.data = await lock.sealVault({ likes: [like('a')], lists: [list('100', 5, 'x')] }, phoneKey);
  relay.r.rev = 1;
  const store = memStore();
  const sync = new VaultSync({ api: relay.api, key: phoneKey, store, clock: () => 1000 });
  sync.local = edit.like(sync.local, { videoId: 'web', title: 'Da web', artist: 'X' }, 50);
  await sync.sync();
  assert.deepEqual(sync.data.likes.map((l) => l.s.i), ['a', 'web']);
  assert.equal(relay.r.rev, 2);
  const onRelay = normalize(await lock.openVault(relay.r.data, phoneKey));
  assert.deepEqual(onRelay.likes.map((l) => l.s.i), ['a', 'web']);
  // Nada novo: não grava de novo.
  await sync.sync();
  assert.equal(relay.r.puts, 1);
  // O celular tira a curtida "a" e apaga a playlist; a web descurte "web" ao mesmo tempo.
  relay.r.data = await lock.sealVault({ likes: [like('web', 50)] }, phoneKey);
  relay.r.rev = 3;
  sync.change((v) => edit.unlike(v, 'web'));
  sync.stop();
  await sync.sync();
  assert.deepEqual(sync.data.likes.map((l) => l.s.i), []);
  assert.equal(sync.data.lists.length, 0);
  // E foi guardado neste navegador (fechado com a chave).
  const saved = await store.get('vault');
  assert.ok(saved instanceof Uint8Array);
  const back = new VaultSync({ api: relay.api, key: phoneKey, store });
  await back.load();
  assert.equal(back.rev, sync.rev);
});

test('sincronizar: outro aparelho gravou no meio (409) — lê de novo e junta', async () => {
  const key = await globalThis.crypto.subtle.importKey('raw', globalThis.crypto.getRandomValues(new Uint8Array(32)), 'AES-GCM', false, ['encrypt', 'decrypt']);
  const relay = fakeRelay();
  relay.r.data = await lock.sealVault({ likes: [like('a')] }, key);
  relay.r.rev = 1;
  let first = true;
  const api = {
    vault: relay.api.vault,
    async putVault(baseRev, data, k) {
      if (first) {
        first = false;
        // O celular grava "b" antes desta escrita chegar.
        relay.r.data = await lock.sealVault({ likes: [like('a'), like('b')] }, key);
        relay.r.rev = 2;
      }
      return relay.api.putVault(baseRev, data, k);
    },
  };
  const sync = new VaultSync({ api, key, store: memStore(), clock: () => 10 });
  sync.local = edit.like(sync.local, { videoId: 'c', title: 'C', artist: '' }, 5);
  await sync.sync();
  sync.stop();
  assert.deepEqual(sync.data.likes.map((l) => l.s.i), ['a', 'b', 'c']);
  assert.deepEqual(normalize(await lock.openVault(relay.r.data, key)).likes.map((l) => l.s.i), ['a', 'b', 'c']);
});
