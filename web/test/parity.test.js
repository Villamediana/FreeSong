// O que o celular faz (WebParityTest.kt grava em test/fixtures/phone.json) abre aqui do mesmo jeito:
// o cofre fechado em Kotlin, a chave embrulhada pelo código e a resposta ao QR code do computador.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import * as lock from '../src/core/crypto.js';
import { normalize } from '../src/core/vault.js';

const web = JSON.parse(readFileSync(new URL('../../app/src/test/resources/web-fixtures.json', import.meta.url)));
const phone = JSON.parse(readFileSync(new URL('./fixtures/phone.json', import.meta.url)));

test('o cofre fechado no celular abre na página', async () => {
  const key = await lock.unwrapWithCode(phone.wrappedKey, web.code, web.user);
  assert.ok(key, 'o código abre a chave que o celular embrulhou');
  const v = normalize(await lock.openVault(phone.vault, key));
  assert.equal(v.likes[0].s.i, 'kotlin12345');
  assert.equal(v.likes[0].s.t, 'Do celular');
});

test('o que toca no celular abre na página, e não passa por cofre', async () => {
  const key = await globalThis.crypto.subtle.importKey('raw', lock.b64.dec(phone.rawKey), 'AES-GCM', false, ['encrypt', 'decrypt']);
  const n = await lock.openNow(phone.now, key);
  assert.deepEqual([n.dev, n.i, n.t, n.a, n.d, n.p, n.s, n.at], ['phonefixture', 'kotlin12345', 'Do celular', 'FreeSong', 200000, 42000, false, 1700000010000]);
  assert.equal(n.c, undefined);
  await assert.rejects(lock.openVault(phone.now, key));
  await assert.rejects(lock.openNow(phone.vault, key));
});

test('a resposta do celular ao QR code abre com a privada do computador', async () => {
  const priv = await globalThis.crypto.subtle.importKey('jwk', web.link.privateJwk, { name: 'ECDH', namedCurve: 'P-256' }, false, ['deriveBits']);
  const opened = await lock.unwrapLink(priv, web.link.id, web.link.secret, phone.answer);
  assert.equal(opened.user, web.user);
  assert.equal(opened.owner, 'Pixel 8');
  const v = normalize(await lock.openVault(phone.vault, opened.key));
  assert.equal(v.likes[0].s.i, 'kotlin12345');
  // Outro segredo (um servidor que não viu a tela): não abre.
  await assert.rejects(lock.unwrapLink(priv, web.link.id, lock.b64u.enc(new Uint8Array(16)), phone.answer));
});
