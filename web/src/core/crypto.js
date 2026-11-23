// As travas do FreeSong no computador, as mesmas do celular (social/Vault.kt): o cofre chega cifrado
// e só é aberto aqui. Só WebCrypto: roda no navegador e no Node (os testes).
//
// - Cofre: a "chave do cofre" (32 bytes aleatórios, feita no celular) cifra o conteúdo (gzip antes,
//   AES-256-GCM, 1 byte de versão + IV de 12 + cifra, AAD = a versão). O relay guarda junto essa chave
//   embrulhada pela chave que o código de recuperação gera (PBKDF2-SHA256, 60 000 voltas, sal
//   "freesong-vault:<id do perfil>").
// - Ligar pelo QR code: o par ECDH do computador e um segredo de 16 bytes vão no QR code (o segredo só
//   lá); o celular responde com a pública dele, feita só pra isso, e a chave do cofre cifrada com
//   HKDF-SHA256(ECDH ‖ segredo, sal = o id do código, "freesong-link-v1") — só este computador abre, e
//   só um celular que leu a tela dele consegue fazer (nem o relay que carrega a resposta).
// - No navegador a chave do cofre vira uma CryptoKey que não pode ser lida de volta.
const subtle = globalThis.crypto.subtle;
const te = new TextEncoder();
const td = new TextDecoder();

export const b64u = {
  enc(bytes) {
    let s = '';
    const b = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode(...b.subarray(i, i + 0x8000));
    return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  },
  dec(text) {
    const s = atob(String(text).replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((String(text).length + 3) % 4));
    const out = new Uint8Array(s.length);
    for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
    return out;
  },
};

export const b64 = {
  enc(bytes) {
    let s = '';
    const b = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode(...b.subarray(i, i + 0x8000));
    return btoa(s);
  },
  dec(text) {
    const s = atob(text);
    const out = new Uint8Array(s.length);
    for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
    return out;
  },
};

const VERSION = 1;
const IV = 12;
const ITERATIONS = 60_000;
const LINK_INFO = te.encode('freesong-link-v1');

function concat(...parts) {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const p of parts) {
    out.set(p, at);
    at += p.length;
  }
  return out;
}

async function pipe(bytes, stream) {
  const out = new Response(new Blob([bytes]).stream().pipeThrough(stream));
  return new Uint8Array(await out.arrayBuffer());
}

export const gzip = (bytes) => pipe(bytes, new CompressionStream('gzip'));
export const gunzip = (bytes) => pipe(bytes, new DecompressionStream('gzip'));

/** O código como o relay lê: "7k2q m9xd…", "7K2Q-M9XD-…" e O/0, I/L/1 dão no mesmo. */
export function normalizeCode(code) {
  return String(code ?? '').toUpperCase().replace(/[^0-9A-Z]/g, '').replace(/O/g, '0').replace(/[IL]/g, '1');
}

/** A chave que o código de recuperação faz pra este perfil. */
async function codeKey(code, userId) {
  const base = await subtle.importKey('raw', te.encode(normalizeCode(code)), 'PBKDF2', false, ['deriveKey']);
  return subtle.deriveKey(
    { name: 'PBKDF2', hash: 'SHA-256', salt: te.encode(`freesong-vault:${userId}`), iterations: ITERATIONS },
    base,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt'],
  );
}

async function seal(plain, key) {
  const iv = globalThis.crypto.getRandomValues(new Uint8Array(IV));
  const ct = new Uint8Array(await subtle.encrypt({ name: 'AES-GCM', iv, additionalData: new Uint8Array([VERSION]) }, key, plain));
  return concat(new Uint8Array([VERSION]), iv, ct);
}

async function open(sealed, key) {
  if (sealed.length <= 1 + IV || sealed[0] !== VERSION) throw new Error('cofre de outra versão');
  return new Uint8Array(await subtle.decrypt({ name: 'AES-GCM', iv: sealed.subarray(1, 1 + IV), additionalData: new Uint8Array([VERSION]) }, key, sealed.subarray(1 + IV)));
}

/** A chave do cofre (32 bytes) como CryptoKey que não sai do navegador; os bytes são apagados. */
export async function dataKey(raw) {
  if (!(raw instanceof Uint8Array) || raw.length !== 32) throw new Error('chave do cofre: 32 bytes');
  const key = await subtle.importKey('raw', raw, { name: 'AES-GCM' }, false, ['encrypt', 'decrypt']);
  raw.fill(0);
  return key;
}

/** A chave do cofre aberta com o código de recuperação; null se o código não abre. */
export async function unwrapWithCode(wrapped, code, userId) {
  try {
    return await dataKey(await open(b64.dec(wrapped), await codeKey(code, userId)));
  } catch {
    return null;
  }
}

/** Uma chave do cofre nova (o perfil ainda não tinha cofre) e ela embrulhada pelo código. */
export async function newDataKey(code, userId) {
  const raw = globalThis.crypto.getRandomValues(new Uint8Array(32));
  const wrapped = b64.enc(await seal(raw, await codeKey(code, userId)));
  return { key: await dataKey(raw), wrapped };
}

/** O conteúdo do cofre aberto (o JSON); lança se a chave não abre. */
export async function openVault(sealedB64, key) {
  return JSON.parse(td.decode(await gunzip(await open(b64.dec(sealedB64), key))));
}

/** O conteúdo do cofre fechado (base64), pronto pro relay. */
export async function sealVault(value, key) {
  return b64.enc(await seal(await gzip(te.encode(JSON.stringify(value))), key));
}

/** O que fica guardado neste navegador (o cofre como estava, o que mudou aqui): fechado com a mesma chave. */
export async function sealLocal(value, key) {
  return seal(te.encode(JSON.stringify(value)), key);
}

export async function openLocal(bytes, key) {
  return JSON.parse(td.decode(await open(bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes), key)));
}

// ------------------------------------------------------------------ ligar pelo QR code

/** O par ECDH do QR code (a privada não pode ser lida) e o segredo do código (16 bytes, só no QR). */
export async function linkKeys() {
  const pair = await subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, false, ['deriveBits']);
  return {
    privateKey: pair.privateKey,
    pub: b64u.enc(new Uint8Array(await subtle.exportKey('raw', pair.publicKey))),
    secret: b64u.enc(globalThis.crypto.getRandomValues(new Uint8Array(16))),
  };
}

/** A chave que assina cada pedido deste computador (ECDSA P-256; a privada não sai do navegador). */
export async function proofKeys() {
  const pair = await subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign', 'verify']);
  return { privateKey: pair.privateKey, pub: b64u.enc(new Uint8Array(await subtle.exportKey('raw', pair.publicKey))) };
}

async function linkLock(privateKey, peerPubRaw, linkId, secret) {
  const extra = b64u.dec(secret);
  if (extra.length !== 16) throw new Error('segredo do código inválido');
  const peer = await subtle.importKey('raw', peerPubRaw, { name: 'ECDH', namedCurve: 'P-256' }, false, []);
  const shared = new Uint8Array(await subtle.deriveBits({ name: 'ECDH', public: peer }, privateKey, 256));
  const base = await subtle.importKey('raw', concat(shared, extra), 'HKDF', false, ['deriveKey']);
  return subtle.deriveKey({ name: 'HKDF', hash: 'SHA-256', salt: te.encode(linkId), info: LINK_INFO }, base, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
}

/** A resposta do celular aberta: {user, key (CryptoKey do cofre), owner}. */
export async function unwrapLink(privateKey, linkId, secret, wrapped) {
  const lock = await linkLock(privateKey, b64u.dec(wrapped.pub), linkId, secret);
  const pt = await subtle.decrypt({ name: 'AES-GCM', iv: b64u.dec(wrapped.iv), additionalData: te.encode(linkId) }, lock, b64u.dec(wrapped.ct));
  const v = JSON.parse(td.decode(pt));
  const raw = typeof v.key === 'string' ? b64u.dec(v.key) : null;
  if (typeof v.user !== 'string' || !raw || raw.length !== 32) throw new Error('resposta do celular inválida');
  return { user: v.user, key: await dataKey(raw), owner: typeof v.owner === 'string' ? v.owner.slice(0, 80) : '' };
}

/** O que o celular faz com um QR code (aqui pros testes; o do celular é em Kotlin). */
export async function wrapLink(webPub, linkId, secret, { user, key, owner = '' }) {
  const mine = await subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, false, ['deriveBits']);
  const lock = await linkLock(mine.privateKey, b64u.dec(webPub), linkId, secret);
  const iv = globalThis.crypto.getRandomValues(new Uint8Array(IV));
  const ct = new Uint8Array(await subtle.encrypt({ name: 'AES-GCM', iv, additionalData: te.encode(linkId) }, lock, te.encode(JSON.stringify({ user, key: b64u.enc(key), owner }))));
  return { pub: b64u.enc(new Uint8Array(await subtle.exportKey('raw', mine.publicKey))), iv: b64u.enc(iv), ct: b64u.enc(ct) };
}

/** O endereço no QR code: abre o FreeSong no celular, com o código, a pública e o segredo. */
export function linkUrl(origin, linkId, pub, secret) {
  return `${origin}/link#${linkId}.${pub}.${secret}`;
}

// ------------------------------------------------------------------ pros testes (o celular, em Kotlin)

/** O cofre como o celular faz (testes): conteúdo fechado e a chave embrulhada pelo código. */
export async function phoneVault(value, rawKey, code, userId) {
  const key = await subtle.importKey('raw', rawKey, { name: 'AES-GCM' }, false, ['encrypt', 'decrypt']);
  return { data: b64.enc(await seal(await gzip(te.encode(JSON.stringify(value))), key)), key: b64.enc(await seal(rawKey, await codeKey(code, userId))) };
}
