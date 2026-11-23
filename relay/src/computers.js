// Computadores ligados a um perfil (o FreeSong na web, freesong.simplyonegoal.com/web).
//
// Um computador não é um celular: não ganha um token da tabela `tokens` (lá o 11º aparelho derruba o
// mais antigo, e um celular que recebe 401 esquece o perfil). Ele fica aqui, com teto próprio, e cada
// pedido dele vem assinado por uma chave que só o navegador dele tem (ECDSA P-256, não extraível):
// um token vazado sozinho não serve pra nada.
//
// Ligar (o mesmo jeito do Novelo Web):
// 1. O computador cria um par ECDH (a privada não sai do navegador), um segredo de 16 bytes que só vai
//    no QR code (nunca pra cá) e a chave que assina os pedidos; manda as públicas → {id, poll}.
// 2. O celular lê o QR code, confirma e manda a chave do cofre cifrada pra pública do computador
//    misturada com o segredo (HKDF, "freesong-link-v1"). Este servidor só carrega bytes que não abre.
// 3. O computador busca a resposta uma vez (com o `poll`, num cabeçalho) e ganha um token daqui.
//
// Ou, sem o celular por perto: o código de recuperação (o mesmo que traz o perfil num celular novo).
import { createHash, createPublicKey, randomBytes, verify } from 'node:crypto';

const MINUTE = 60_000;
const DAY = 24 * 60 * MINUTE;
const ID = /^[A-Za-z0-9_-]{22}$/;
const B64URL = /^[A-Za-z0-9_-]+$/;
const TOKEN = /^[A-Za-z0-9_-]{43}$/;
/** Até quanto o relógio do computador pode estar longe do daqui (e quanto tempo um nonce fica lembrado). */
export const PROOF_WINDOW = 2 * MINUTE;
/** Código no QR que ninguém leu nesse tempo some (e a resposta que o computador não buscou também). */
export const LINK_TTL = 10 * MINUTE;
/** Computadores por perfil. */
export const MAX_COMPUTERS = 10;
/** Computador sem aparecer esse tempo é desligado sozinho (precisa ler um código novo). */
export const IDLE_MS = 90 * DAY;
/** Computador de outra pessoa ("Manter ligado" desmarcado): sem sinal nesse tempo, desligado. */
export const SHARED_IDLE_MS = 30 * MINUTE;
/** Códigos abertos no servidor todo. */
const MAX_PENDING = 5000;

const SCHEMA = `
CREATE TABLE IF NOT EXISTS computers (
  id          TEXT PRIMARY KEY,
  user_id     TEXT REFERENCES users(id) ON DELETE CASCADE,
  web_key     TEXT NOT NULL,
  proof_key   TEXT NOT NULL,
  poll_hash   TEXT,
  name        TEXT NOT NULL,
  token_hash  TEXT UNIQUE,
  wrapped     TEXT,
  via         TEXT NOT NULL DEFAULT 'qr',
  shared      INTEGER NOT NULL DEFAULT 0,
  created_at  INTEGER NOT NULL,
  approved_at INTEGER NOT NULL DEFAULT 0,
  last_seen   INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS computers_user ON computers(user_id);
CREATE INDEX IF NOT EXISTS computers_created ON computers(created_at);
`;

const sha = (s) => createHash('sha256').update(s).digest('hex');
const token = (n) => randomBytes(n).toString('base64url');
/** Um nome que alguém vai ler: sem controle nem caractere que vira o texto ao contrário. */
export const shown = (v, max) =>
  String(v ?? '').replace(/[\u0000-\u001f\u007f-\u009f؜​-‏‪-‮⁠-⁯﻿]/g, '').replace(/\s+/g, ' ').trim().slice(0, max);

/** Uma pública P-256 (crua, 65 bytes, base64url) como chave pra conferir assinatura; null se não é. */
export function p256(raw) {
  try {
    if (typeof raw !== 'string' || !B64URL.test(raw)) return null;
    const b = Buffer.from(raw, 'base64url');
    if (b.length !== 65 || b[0] !== 4) return null;
    return createPublicKey({ key: { kty: 'EC', crv: 'P-256', x: b.subarray(1, 33).toString('base64url'), y: b.subarray(33).toString('base64url') }, format: 'jwk' });
  } catch {
    return null;
  }
}

export class Computers {
  constructor(db, clock = Date.now) {
    this.db = db;
    this.clock = clock;
    db.exec(SCHEMA);
    this.keys = new Map(); // computador → a pública que assina (KeyObject)
    this.nonces = new Map(); // computador → Map(nonce → quando): cada pedido assinado vale uma vez
    this.polls = new Map(); // código → Set<() => void> (quem espera o celular ler)
    this.waiting = 0; // esperas abertas no servidor todo
    this.q = {};
  }

  st(sql) {
    return (this.q[sql] ??= this.db.prepare(sql));
  }

  // ---------------------------------------------------------------- ligar

  /** O computador pede um código: a pública ECDH do QR, a pública que vai assinar, o nome dele. */
  start(webKey, proof, name) {
    if (!p256(webKey)) return { error: 'bad_key' };
    if (!p256(proof)) return { error: 'bad_proof_key' };
    if (this.st('SELECT count(*) AS n FROM computers WHERE approved_at = 0').get().n >= MAX_PENDING) return { error: 'busy' };
    const id = token(16);
    const poll = token(32);
    this.st('INSERT INTO computers (id, web_key, proof_key, poll_hash, name, created_at) VALUES (?, ?, ?, ?, ?, ?)')
      .run(id, webKey, proof, sha(poll), shown(name, 80) || 'Computador', this.clock());
    return { id, poll, expires_in: Math.round(LINK_TTL / 1000) };
  }

  /** O que o celular precisa saber de um código antes de responder (null = não existe ou venceu). */
  pending(id) {
    const c = typeof id === 'string' && ID.test(id) ? this.st('SELECT * FROM computers WHERE id = ?').get(id) : null;
    if (!c || c.approved_at || this.clock() - c.created_at > LINK_TTL) return null;
    return c;
  }

  /**
   * O celular de [userId] respondeu ao código [id]: [wrapped] = a chave do cofre cifrada pro
   * computador ({pub, iv, ct}, base64url). O token do computador nasce aqui e espera ele buscar.
   */
  approve(userId, id, wrapped, owner) {
    const c = this.pending(id);
    if (!c) return { error: 'expired' };
    if (!wrapped || typeof wrapped !== 'object') return { error: 'bad_wrapped' };
    const w = {};
    for (const f of ['pub', 'iv', 'ct']) {
      if (typeof wrapped[f] !== 'string' || !B64URL.test(wrapped[f]) || wrapped[f].length > 4096) return { error: 'bad_wrapped' };
      w[f] = wrapped[f];
    }
    if (this.count(userId) >= MAX_COMPUTERS) return { error: 'too_many' };
    const t = token(32);
    const now = this.clock();
    const payload = JSON.stringify({ token: t, wrapped: w, owner: shown(owner, 80) });
    const r = this.st('UPDATE computers SET user_id = ?, token_hash = ?, wrapped = ?, approved_at = ?, last_seen = ? WHERE id = ? AND approved_at = 0')
      .run(userId, sha(t), payload, now, now, id);
    if (Number(r.changes) !== 1) return { error: 'expired' };
    for (const wake of [...(this.polls.get(id) ?? [])]) wake();
    return { id, name: c.name };
  }

  /**
   * O computador pergunta se o celular já leu (esperando até [ms]). A resposta sai uma vez só:
   * {status: 'pending'} | {status: 'linked', userId, token, wrapped, owner} | {error}.
   */
  async poll(id, poll, ms, maxWaiters = 2, maxAll = 2000) {
    const read = () => {
      const c = typeof id === 'string' && ID.test(id) ? this.st('SELECT * FROM computers WHERE id = ?').get(id) : null;
      if (!c || !c.poll_hash || typeof poll !== 'string' || c.poll_hash !== sha(poll)) return { error: 'no_link' };
      if (!c.approved_at) return this.clock() - c.created_at > LINK_TTL ? { error: 'expired' } : null;
      if (!c.wrapped) return { error: 'delivered' };
      return c;
    };
    let c = read();
    if (!c && ms > 0 && (this.polls.get(id)?.size ?? 0) < maxWaiters && this.waiting < maxAll) {
      this.waiting++;
      await new Promise((resolve) => {
        let set = this.polls.get(id);
        if (!set) this.polls.set(id, (set = new Set()));
        const done = () => {
          clearTimeout(timer);
          set.delete(done);
          if (!set.size) this.polls.delete(id);
          this.waiting--;
          resolve();
        };
        const timer = setTimeout(done, ms);
        set.add(done);
      });
      c = read();
    }
    if (!c) return { status: 'pending' };
    if (c.error) return c;
    const out = JSON.parse(c.wrapped);
    this.st('UPDATE computers SET wrapped = NULL, poll_hash = NULL WHERE id = ?').run(id);
    return { status: 'linked', userId: c.user_id, ...out };
  }

  /** Sem o celular: o código de recuperação de [userId] liga este computador direto. */
  recover(userId, proof, name) {
    if (!p256(proof)) return { error: 'bad_proof_key' };
    if (this.count(userId) >= MAX_COMPUTERS) return { error: 'too_many' };
    const id = token(16);
    const t = token(32);
    const now = this.clock();
    this.st(`INSERT INTO computers (id, user_id, web_key, proof_key, name, token_hash, via, created_at, approved_at, last_seen)
             VALUES (?, ?, '', ?, ?, ?, 'code', ?, ?, ?)`).run(id, userId, proof, shown(name, 80) || 'Computador', sha(t), now, now, now);
    return { id, token: t };
  }

  count(userId) {
    return this.st('SELECT count(*) AS n FROM computers WHERE user_id = ? AND approved_at > 0').get(userId).n;
  }

  // ---------------------------------------------------------------- pedidos de um computador

  /** O computador dono de [t] (Authorization: Bearer), já buscado; null se não há. */
  byToken(t) {
    if (typeof t !== 'string' || !TOKEN.test(t)) return null;
    return this.st('SELECT * FROM computers WHERE token_hash = ? AND wrapped IS NULL').get(sha(t)) ?? null;
  }

  /**
   * O pedido é mesmo deste computador: `X-FreeSong-Sig: <ms>.<nonce>.<assinatura>` sobre
   * "freesong-web-v1\n<método>\n<caminho com ?…>\n<ms>\n<nonce>\n<SHA-256 do corpo em hex>". Fora de
   * ±2 min daqui, outra chave, outro caminho, outro corpo ou o mesmo nonce de novo → um erro (com a
   * hora daqui, pra página acertar o relógio). null = confere.
   */
  check(c, method, path, header, raw) {
    const now = this.clock();
    const m = /^(\d{12,14})\.([A-Za-z0-9_-]{16,32})\.([A-Za-z0-9_-]{86})$/.exec(String(header ?? ''));
    let key = this.keys.get(c.id);
    if (!key) {
      key = p256(c.proof_key);
      if (this.keys.size > 2000) this.keys.clear();
      if (key) this.keys.set(c.id, key);
    }
    if (!key || !m) return 'missing';
    if (Math.abs(now - Number(m[1])) > PROOF_WINDOW) return 'clock';
    const text = `freesong-web-v1\n${method}\n${path}\n${m[1]}\n${m[2]}\n${createHash('sha256').update(raw ?? Buffer.alloc(0)).digest('hex')}`;
    let ok = false;
    try {
      ok = verify('sha256', Buffer.from(text), { key, dsaEncoding: 'ieee-p1363' }, Buffer.from(m[3], 'base64url'));
    } catch {
      ok = false;
    }
    if (!ok) return 'signature';
    let seen = this.nonces.get(c.id);
    if (!seen) this.nonces.set(c.id, (seen = new Map()));
    if (seen.has(m[2])) return 'replay';
    if (seen.size >= 20_000) return 'busy';
    seen.set(m[2], now);
    // Visto agora (no primeiro uso depois de ligado, e daí uma vez por minuto).
    if (c.last_seen === c.approved_at || now - c.last_seen > MINUTE) this.st('UPDATE computers SET last_seen = ? WHERE id = ?').run(now, c.id);
    return null;
  }

  /** Não é o computador da pessoa: some quando a aba fechar (ou sozinho, sem sinal). */
  markShared(id) {
    this.st('UPDATE computers SET shared = 1 WHERE id = ?').run(id);
  }

  // ---------------------------------------------------------------- a lista (no celular)

  list(userId) {
    return this.st('SELECT id, name, via, shared, approved_at, last_seen FROM computers WHERE user_id = ? AND approved_at > 0 ORDER BY approved_at')
      .all(userId)
      .map((c) => ({ id: c.id, name: c.name, via: c.via, shared: c.shared === 1, linkedAt: c.approved_at, lastSeen: c.last_seen }));
  }

  /** Desliga um computador (o celular, ou o próprio computador). true se existia. */
  unlink(userId, id) {
    this.forget(id);
    return Number(this.st('DELETE FROM computers WHERE id = ? AND user_id = ?').run(id, userId).changes) > 0;
  }

  /** "Sair dos outros aparelhos": os computadores também. */
  unlinkAll(userId) {
    for (const c of this.st('SELECT id FROM computers WHERE user_id = ?').all(userId)) this.forget(c.id);
    return Number(this.st('DELETE FROM computers WHERE user_id = ?').run(userId).changes);
  }

  forget(id) {
    this.keys.delete(id);
    this.nonces.delete(id);
  }

  /** A faxina (de minuto em minuto): códigos vencidos, respostas não buscadas, computadores esquecidos, nonces velhos. */
  sweep() {
    const now = this.clock();
    const gone = this.st(`SELECT id FROM computers WHERE
        (approved_at = 0 AND created_at < ?)
        OR (approved_at > 0 AND wrapped IS NOT NULL AND approved_at < ?)
        OR (approved_at > 0 AND last_seen < ?)
        OR (shared = 1 AND approved_at > 0 AND last_seen < ?)`).all(now - LINK_TTL, now - LINK_TTL, now - IDLE_MS, now - SHARED_IDLE_MS);
    for (const c of gone) {
      this.forget(c.id);
      this.st('DELETE FROM computers WHERE id = ?').run(c.id);
    }
    for (const [id, seen] of this.nonces) {
      for (const [n, at] of seen) if (now - at > 2 * PROOF_WINDOW) seen.delete(n);
      if (!seen.size) this.nonces.delete(id);
    }
    return gone.length;
  }
}
