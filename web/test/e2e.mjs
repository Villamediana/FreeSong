// Ponta a ponta no Chromium (não entra no npm test: usa a rede de verdade e leva uns minutos):
//   node test/e2e.mjs <pasta das capturas>
// Sobe o relay (relay/) e a web num endereço só (como o túnel: /web → a web, o resto → o relay), faz
// um "celular" (conta + cofre com curtidas, escutas e playlists), liga o computador pelo QR code e
// confere: a Início com as coisas do celular, busca, artista, álbum, tocar, curtir aqui → chega no
// celular, o celular muda → aparece aqui na hora, tema claro, desligar.
import http from 'node:http';
import { mkdtempSync, mkdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright-core';
import { createRelay } from '../../relay/src/server.js';
import { createWeb } from '../server/app.js';
import * as lock from '../src/core/crypto.js';
import { normalize } from '../src/core/vault.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const shots = path.resolve(process.argv[2] ?? path.join(tmpdir(), 'freesong-web-shots'));
mkdirSync(shots, { recursive: true });
const CHROME = process.env.CHROME ?? `${process.env.HOME}/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome`;

const dataDir = mkdtempSync(path.join(tmpdir(), 'freesong-e2e-'));
const relay = createRelay({ dataDir, log: { error() {} } });
const web = createWeb({ publicDir: path.join(here, '..', 'public') });
const listen = (server) => new Promise((r) => server.listen(0, '127.0.0.1', () => r(server.address().port)));
const relayPort = await listen(relay.server);
const webPort = await listen(web.server);
// O "túnel": /web vai pra web, o resto pro relay.
const front = http.createServer((req, res) => {
  const port = req.url === '/web' || req.url.startsWith('/web/') ? webPort : relayPort;
  const up = http.request({ host: '127.0.0.1', port, path: req.url, method: req.method, headers: req.headers }, (r) => {
    res.writeHead(r.statusCode, r.headers);
    r.pipe(res);
  });
  up.on('error', () => res.destroy());
  req.pipe(up);
});
const port = await listen(front);
const base = `http://127.0.0.1:${port}`;

async function api(method, p, { token, body } = {}) {
  const r = await fetch(base + p, { method, headers: { ...(token ? { authorization: `Bearer ${token}` } : {}), ...(body ? { 'content-type': 'application/json' } : {}) }, body: body ? JSON.stringify(body) : undefined });
  return { status: r.status, json: await r.json().catch(() => null) };
}

const steps = [];
const check = (ok, what) => {
  steps.push(`${ok ? 'ok ' : 'FALHOU'} ${what}`);
  console.log(`${ok ? 'ok ' : 'FALHOU'} ${what}`);
  if (!ok) process.exitCode = 1;
};

// ------------------------------------------------------------------ o celular
const acct = (await api('POST', '/v1/accounts', { body: {} })).json;
const phone = { token: acct.token, code: acct.recoveryCode, id: acct.me.id };
await api('PATCH', '/v1/me', { token: phone.token, body: { name: 'Miguel' } });
const raw = globalThis.crypto.getRandomValues(new Uint8Array(32));
const phoneKey = await globalThis.crypto.subtle.importKey('raw', raw, 'AES-GCM', false, ['encrypt', 'decrypt']);
const now = Date.now();
// Músicas de verdade (as capas carregam), tiradas da própria ponte.
const coldplay = (await (await fetch(`${base}/web/api/search?q=coldplay&type=songs`)).json()).items;
const djavan = (await (await fetch(`${base}/web/api/search?q=djavan&type=songs`)).json()).items;
const v = (s) => ({ i: s.videoId, t: s.title, a: s.artist, al: s.album, d: s.durationMs, c: s.cover, u: `https://music.youtube.com/watch?v=${s.videoId}` });
const content = {
  likes: [...coldplay.slice(0, 4), ...djavan.slice(0, 3)].map((s, i) => ({ s: v(s), at: now - i * 3600_000 })),
  plays: [...coldplay.slice(0, 8), ...djavan.slice(0, 6)].flatMap((s, i) => [0, 1, 2].map((k) => ({ i: s.videoId, t: s.title, a: s.artist, at: now - (i * 3 + k) * 3_000_000, l: 200_000, d: 210_000, c: true }))),
  lists: [
    { k: String(now - 9e8), n: 'Pra estrada', at: now - 9e8, up: now - 1e6, s: [...coldplay.slice(2, 7), ...djavan.slice(1, 4)].map(v) },
    { k: String(now - 8e8), n: 'Domingo calmo', at: now - 8e8, up: now - 2e6, p: true, s: djavan.slice(0, 5).map(v) },
  ],
  gen: { c: true, ids: ['mpb', 'rock'], at: now - 5e8 },
};
const sealed = await lock.phoneVault(content, raw, phone.code, phone.id);
check((await api('PUT', '/v1/me/vault', { token: phone.token, body: { baseRev: 0, data: sealed.data, key: sealed.key } })).json?.rev === 1, 'o celular grava o cofre');

// ------------------------------------------------------------------ o computador
const browser = await chromium.launch({ executablePath: CHROME, args: ['--autoplay-policy=no-user-gesture-required'] });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'pt-BR', colorScheme: 'dark' });
const page = await ctx.newPage();
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
page.on('console', (m) => { if (m.type() === 'error' && !/youtube|ytimg|googlevideo|doubleclick|google/.test(m.text())) errors.push(m.text()); });
const shot = async (name) => page.screenshot({ path: path.join(shots, `${name}.png`) });

await page.goto(`${base}/web/`);
await page.waitForSelector('.qr[data-code]', { timeout: 20_000 });
await page.waitForTimeout(400);
await shot('01-ligar');
const url = await page.getAttribute('.qr', 'data-code');
const m = /#([A-Za-z0-9_-]{22})\.([A-Za-z0-9_-]+)\.([A-Za-z0-9_-]{22})$/.exec(url);
check(!!m && url.startsWith(`${base}/web/link#`), 'o QR code tem o endereço, a chave e o segredo');
const wrapped = await lock.wrapLink(m[2], m[1], m[3], { user: phone.id, key: raw, owner: 'Pixel 8' });
check((await api('POST', `/v1/link/${m[1]}/approve`, { token: phone.token, body: { wrapped, owner: 'Pixel 8' } })).status === 200, 'o celular aprova');
await page.waitForSelector('.hello h1', { timeout: 30_000 });
await page.waitForSelector('.tile', { timeout: 15_000 });
await page.waitForTimeout(3500);
await shot('02-inicio');
check(await page.locator('.side-item', { hasText: 'Pra estrada' }).count() === 1, 'as playlists do celular aparecem');
check(await page.locator('.tile', { hasText: 'Músicas curtidas' }).count() === 1, 'as curtidas aparecem');
const computers = (await api('GET', '/v1/me/computers', { token: phone.token })).json.computers;
check(computers.length === 1 && /Chrome/.test(computers[0].name), `o celular vê o computador (${computers[0]?.name})`);

// Mixes
await page.locator('.shelf', { hasText: 'Feitos pra você' }).locator('.card').first().click();
await page.waitForSelector('.hero h1', { timeout: 20_000 });
await page.waitForTimeout(1500);
await shot('03-mix');

// Buscar
await page.click('.search-box input');
await page.keyboard.type('coldplay', { delay: 40 });
await page.waitForSelector('.top-result', { timeout: 20_000 });
await page.waitForTimeout(1800);
await shot('04-busca');
await page.click('.top-result');
await page.waitForSelector('.artist-hero h1', { timeout: 20_000 });
await page.waitForTimeout(2000);
await shot('05-artista');
await page.locator('.shelf', { hasText: 'Álbuns' }).locator('.card').first().click();
await page.waitForSelector('.hero h1', { timeout: 20_000 });
await page.waitForTimeout(1500);
await shot('06-album');

// Tocar
await page.locator('.songs .row').first().hover();
await page.locator('.songs .row .row-play').first().click();
await page.waitForTimeout(9000);
await shot('07-tocando');
const state = await page.evaluate(() => ({ dock: document.querySelector('#yt-dock iframe')?.src ?? null }));
check(!!state.dock && state.dock.includes('youtube-nocookie.com/embed/'), 'o player do YouTube abriu no painel');

// Curtir aqui → chega no celular
const title = await page.locator('.bar .row-title').innerText();
await page.click('.bar .like');
await page.waitForTimeout(3500);
const after = (await api('GET', '/v1/me/vault', { token: phone.token })).json;
const opened = normalize(await lock.openVault(after.data, phoneKey));
check(opened.likes.some((l) => l.s.t === title) || content.likes.some((l) => l.s.t === title), `curtir aqui chega no celular ("${title}")`);

// O celular cria uma playlist → aparece aqui na hora
const next = { ...opened, lists: [...opened.lists, { k: String(Date.now()), n: 'Feita no celular', at: Date.now(), up: Date.now(), p: false, s: coldplay.slice(0, 2).map(v) }] };
const put = await api('PUT', '/v1/me/vault', { token: phone.token, body: { baseRev: after.rev, data: await lock.sealVault(next, phoneKey) } });
check(put.status === 200, 'o celular grava de novo');
const t0 = Date.now();
await page.waitForSelector('.side-item:has-text("Feita no celular")', { timeout: 15_000 }).catch(() => {});
check(await page.locator('.side-item', { hasText: 'Feita no celular' }).count() === 1, `a playlist do celular aparece aqui (${Date.now() - t0} ms)`);

// Letra e fila
await page.click('.np-tabs button:has-text("Fila")');
await page.waitForTimeout(800);
await shot('08-fila');
await page.click('.np-tabs button:has-text("Letra")');

// Biblioteca, curtidas, perfil, histórico
for (const [hash, name] of [['/liked', '09-curtidas'], ['/library', '10-biblioteca'], ['/profile', '11-perfil'], ['/history', '12-historico'], ['/search', '13-generos']]) {
  await page.evaluate((h) => { location.hash = h; }, hash);
  await page.waitForTimeout(1500);
  await shot(name);
}
// Menu de uma música
await page.evaluate(() => { location.hash = '/liked'; });
await page.waitForSelector('.songs .row');
await page.locator('.songs .row').first().click({ button: 'right' });
await page.waitForSelector('.menu.ready');
await shot('14-menu');
await page.keyboard.press('Escape');

// Tema claro
await page.emulateMedia({ colorScheme: 'light' });
await page.evaluate(() => { location.hash = '/'; });
await page.waitForTimeout(2500);
await shot('15-inicio-claro');
await page.setViewportSize({ width: 1000, height: 760 });
await page.waitForTimeout(800);
await shot('16-janela-menor');

// Desligar daqui
await page.setViewportSize({ width: 1440, height: 900 });
await page.evaluate(() => { location.hash = '/profile'; });
await page.click('button:has-text("Desligar este computador")');
await page.click('.dialog button:has-text("Desligar")');
await page.waitForSelector('text=Este computador foi desligado', { timeout: 10_000 });
check((await api('GET', '/v1/me/computers', { token: phone.token })).json.computers.length === 0, 'desligado: some da lista do celular');
const left = await page.evaluate(async () => (await indexedDB.databases()).filter((d) => d.name === 'freesong-web').length);
check(left === 0, 'e nada fica no navegador');

// Pelo código de recuperação
const p2 = await ctx.newPage();
await p2.goto(`${base}/web/`);
await p2.waitForTimeout(1500);
await p2.screenshot({ path: path.join(shots, '17-codigo.png') });
await p2.click('.text-btn');
await p2.fill('.code-form input', phone.code.toLowerCase());
await p2.click('.code-form button[type=submit]');
await p2.waitForSelector('.hello h1', { timeout: 30_000 });
check(await p2.locator('.side-item', { hasText: 'Pra estrada' }).count() === 1, 'pelo código de recuperação o cofre abre');

check(errors.length === 0, `sem erros na página${errors.length ? `: ${errors.slice(0, 3).join(' | ')}` : ''}`);
await browser.close();
await new Promise((r) => front.close(r));
await relay.close();
web.server.close();
rmSync(dataDir, { recursive: true, force: true });
console.log(`\n${steps.filter((s) => s.startsWith('ok')).length}/${steps.length} ok · capturas em ${shots}`);
process.exit(process.exitCode ?? 0);
