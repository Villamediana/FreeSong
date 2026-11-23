// Gera o que o teste do app (WebParityTest.kt) abre: um cofre fechado pela página, a chave dele
// embrulhada pelo código e um QR code de computador (com a privada dele, só pra teste). Rodar de novo
// só se o formato mudar: node web/tools/fixtures.mjs
import { writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import * as lock from '../src/core/crypto.js';
import { compact, normalize } from '../src/core/vault.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const subtle = globalThis.crypto.subtle;
const code = '7K2Q-M9XD-4TFA-H3RB';
const user = 'uparity01';
const raw = globalThis.crypto.getRandomValues(new Uint8Array(32));
const content = compact(normalize({
  likes: [{ s: { i: 'dQw4w9WgXcQ', t: 'Oceano', a: 'Djavan', al: 'Djavan', d: 240000, c: 'https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg' }, at: 1700000000000 }],
  plays: [{ i: 'dQw4w9WgXcQ', t: 'Oceano', a: 'Djavan', at: 1700000001000, l: 230000, d: 240000, c: true }],
  lists: [{ k: '1700000002000', n: 'Da web ção', at: 1700000002000, up: 1700000003000, s: [{ i: 'abcdefghijk', t: 'Flor de Lis', a: 'Djavan' }] }],
  gen: { c: true, ids: ['mpb'], at: 5 },
}));
const key = await subtle.importKey('raw', raw, 'AES-GCM', false, ['encrypt', 'decrypt']);
const data = await lock.sealVault(content, key);
const wrapped = (await lock.phoneVault({}, raw.slice(), code, user)).key;
const pair = await subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, ['deriveBits']);
const pub = lock.b64u.enc(new Uint8Array(await subtle.exportKey('raw', pair.publicKey)));
const secret = lock.b64u.enc(globalThis.crypto.getRandomValues(new Uint8Array(16)));
const linkId = lock.b64u.enc(globalThis.crypto.getRandomValues(new Uint8Array(16)));
const out = {
  code,
  user,
  rawKey: lock.b64.enc(raw),
  vault: data,
  wrappedKey: wrapped,
  content,
  link: { url: lock.linkUrl('https://freesong.simplyonegoal.com', linkId, pub, secret), id: linkId, pub, secret, privateJwk: await subtle.exportKey('jwk', pair.privateKey) },
};
writeFileSync(path.join(here, '../../app/src/test/resources/web-fixtures.json'), JSON.stringify(out, null, 2) + '\n');
console.log('app/src/test/resources/web-fixtures.json');
