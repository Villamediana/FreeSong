// Banco do relay (SQLite embutido no Node, node:sqlite). Guarda SÓ o básico pra funcionar
// (pedido do Miguel): perfil (@nome, nome, foto, horas e top 5), tokens e código de recuperação
// em hash, amizades e pedidos de amizade. Músicas compartilhadas NÃO ficam aqui: o histórico vive
// em cada celular; o servidor só segura a entrega (deliveries) até o celular do amigo confirmar
// que recebeu, e apaga. As jams vivem só na memória.
import { DatabaseSync } from 'node:sqlite';
import { createHash, randomBytes } from 'node:crypto';
import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

// WAL + synchronous NORMAL: seguro contra corrupção e bem mais rápido no armazenamento do celular
// (numa queda de energia perde no máximo a última escrita). busy_timeout: um backup rodando
// (sqlite3 .backup) faz a escrita esperar um instante em vez de falhar.
const SCHEMA = `
PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;
PRAGMA busy_timeout = 2000;
PRAGMA journal_size_limit = 16777216;
PRAGMA foreign_keys = ON;
CREATE TABLE IF NOT EXISTS users (
  id            TEXT PRIMARY KEY,
  username      TEXT NOT NULL UNIQUE,
  name          TEXT NOT NULL,
  avatar_rev    INTEGER NOT NULL DEFAULT 0,
  created_at    INTEGER NOT NULL,
  listened_ms   INTEGER NOT NULL DEFAULT 0,
  top_json      TEXT NOT NULL DEFAULT '[]',
  numbers_json  TEXT NOT NULL DEFAULT 'null',
  recovery_hash TEXT NOT NULL UNIQUE
);
CREATE TABLE IF NOT EXISTS tokens (
  hash       TEXT PRIMARY KEY,
  user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS tokens_user ON tokens(user_id);
CREATE TABLE IF NOT EXISTS friendships (
  a          TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  b          TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (a, b),
  CHECK (a < b)
);
CREATE INDEX IF NOT EXISTS friendships_b ON friendships(b);
CREATE TABLE IF NOT EXISTS friend_requests (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  from_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  to_id      TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL,
  UNIQUE (from_id, to_id)
);
CREATE INDEX IF NOT EXISTS friend_requests_to ON friend_requests(to_id);
-- Entregas pendentes: uma música mandada pra quem estava com o app fechado. Some quando o
-- celular dele confirma (ack) ou depois de DELIVERY_TTL_MS.
CREATE TABLE IF NOT EXISTS deliveries (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  from_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  to_id      TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind       TEXT NOT NULL,
  payload    TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS deliveries_to ON deliveries(to_id, id);
-- Lista global (mais novos primeiro) e a faxina das entregas vencidas sem varrer a tabela toda.
CREATE INDEX IF NOT EXISTS users_created ON users(created_at DESC, id);
CREATE INDEX IF NOT EXISTS deliveries_created ON deliveries(created_at);
-- Cofre: o que o celular sabe da pessoa (curtidas, histórico, playlists, músicas trocadas), cifrado
-- NO CELULAR com uma chave que só o código de recuperação abre. Aqui ficam bytes que o servidor não
-- consegue ler; servem pra pessoa trocar de celular (ou usar vários) sem sentir diferença.
CREATE TABLE IF NOT EXISTS vaults (
  user_id    TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  rev        INTEGER NOT NULL,
  key        TEXT NOT NULL,
  data       BLOB NOT NULL,
  updated_at INTEGER NOT NULL
);
-- Áudio de uma mensagem de voz, preso à entrega: some junto com ela (ack, 14 dias ou perfil apagado).
CREATE TABLE IF NOT EXISTS voice_notes (
  delivery_id INTEGER PRIMARY KEY REFERENCES deliveries(id) ON DELETE CASCADE,
  to_id       TEXT NOT NULL,
  data        BLOB NOT NULL
);
CREATE INDEX IF NOT EXISTS voice_notes_to ON voice_notes(to_id);
`;

export const sha256 = (text) => createHash('sha256').update(text).digest('hex');

/** Aparelhos (tokens) por pessoa; o mais antigo sai quando entra um novo além disso. */
export const MAX_TOKENS = 10;

/** Token de acesso: 32 bytes aleatórios; no banco só o hash. */
export const newToken = () => randomBytes(32).toString('base64url');

const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

/** Código de recuperação "7K2Q-M9XD-4TFA-H3RB" (80 bits); no banco só o hash da forma normalizada. */
export function newRecoveryCode() {
  const bytes = randomBytes(10);
  let bits = 0n;
  for (const b of bytes) bits = (bits << 8n) | BigInt(b);
  let out = '';
  for (let i = 0; i < 16; i++) {
    out = CROCKFORD[Number(bits & 31n)] + out;
    bits >>= 5n;
  }
  return out.match(/.{4}/g).join('-');
}

/** "7k2q m9xd-4tfa h3rb" → "7K2QM9XD4TFAH3RB" (O vira 0, I/L viram 1, como no Crockford). */
export function normalizeRecoveryCode(raw) {
  return String(raw ?? '').toUpperCase().replace(/[^0-9A-Z]/g, '').replace(/O/g, '0').replace(/[IL]/g, '1');
}

const newId = () => 'u' + randomBytes(9).toString('base64url').replace(/[-_]/g, 'x').toLowerCase();

export class Store {
  constructor(file) {
    if (file !== ':memory:') mkdirSync(dirname(file), { recursive: true });
    this.db = new DatabaseSync(file);
    this.db.exec(SCHEMA);
    // Bancos de antes da 1.4.0: a coluna dos números (o que os amigos veem no perfil) entra vazia.
    const cols = this.db.prepare('PRAGMA table_info(users)').all().map((c) => c.name);
    if (!cols.includes('numbers_json')) this.db.exec("ALTER TABLE users ADD COLUMN numbers_json TEXT NOT NULL DEFAULT 'null'");
    this.q = {};
  }

  /** Statement preparado uma vez e guardado. */
  st(sql) {
    return (this.q[sql] ??= this.db.prepare(sql));
  }

  tx(fn) {
    this.db.exec('BEGIN IMMEDIATE');
    try {
      const out = fn();
      this.db.exec('COMMIT');
      return out;
    } catch (e) {
      this.db.exec('ROLLBACK');
      throw e;
    }
  }

  close() {
    this.db.close();
  }

  // ---------------------------------------------------------------- contas

  createUser({ username, name, now }) {
    const id = newId();
    const token = newToken();
    const recoveryCode = newRecoveryCode();
    this.tx(() => {
      this.st(`INSERT INTO users (id, username, name, created_at, recovery_hash)
               VALUES (?, ?, ?, ?, ?)`).run(id, username, name, now, sha256(normalizeRecoveryCode(recoveryCode)));
      this.st('INSERT INTO tokens (hash, user_id, created_at) VALUES (?, ?, ?)').run(sha256(token), id, now);
    });
    return { id, token, recoveryCode };
  }

  userByToken(token) {
    if (!token) return null;
    return this.st('SELECT u.* FROM tokens t JOIN users u ON u.id = t.user_id WHERE t.hash = ?').get(sha256(token)) ?? null;
  }

  userByRecovery(code) {
    const norm = normalizeRecoveryCode(code);
    if (norm.length !== 16) return null;
    return this.st('SELECT * FROM users WHERE recovery_hash = ?').get(sha256(norm)) ?? null;
  }

  /** Celular novo (recuperação). Guarda só os [MAX_TOKENS] mais novos: aparelho antigo esquecido cai sozinho. */
  addToken(userId, now) {
    const token = newToken();
    this.tx(() => {
      this.st('INSERT INTO tokens (hash, user_id, created_at) VALUES (?, ?, ?)').run(sha256(token), userId, now);
      this.st(`DELETE FROM tokens WHERE user_id = ? AND hash NOT IN
               (SELECT hash FROM tokens WHERE user_id = ? ORDER BY created_at DESC, rowid DESC LIMIT ?)`).run(userId, userId, MAX_TOKENS);
    });
    return token;
  }

  tokenAlive(hash) {
    return !!hash && !!this.st('SELECT 1 FROM tokens WHERE hash = ?').get(hash);
  }

  /** "Sair dos outros aparelhos": apaga todos os tokens da pessoa menos o [keepHash]. */
  revokeOtherTokens(userId, keepHash) {
    return Number(this.st('DELETE FROM tokens WHERE user_id = ? AND hash != ?').run(userId, keepHash).changes);
  }

  newRecovery(userId) {
    const code = newRecoveryCode();
    this.st('UPDATE users SET recovery_hash = ? WHERE id = ?').run(sha256(normalizeRecoveryCode(code)), userId);
    return code;
  }

  user(id) {
    return this.st('SELECT * FROM users WHERE id = ?').get(id) ?? null;
  }

  userByName(username) {
    return this.st('SELECT * FROM users WHERE username = ?').get(username) ?? null;
  }

  rename(id, { name, username }) {
    if (name != null) this.st('UPDATE users SET name = ? WHERE id = ?').run(name, id);
    if (username != null) this.st('UPDATE users SET username = ? WHERE id = ?').run(username, id);
  }

  bumpAvatar(id, present) {
    if (present) this.st('UPDATE users SET avatar_rev = abs(avatar_rev) + 1 WHERE id = ?').run(id);
    else this.st('UPDATE users SET avatar_rev = -(abs(avatar_rev) + 1) WHERE id = ?').run(id);
  }

  setStats(id, { listenedMs, top, numbers }) {
    this.st('UPDATE users SET listened_ms = ?, top_json = ? WHERE id = ?').run(listenedMs, JSON.stringify(top), id);
    // Apps de antes da 1.4.0 não mandam números: os que estavam ficam.
    if (numbers !== undefined) this.st('UPDATE users SET numbers_json = ? WHERE id = ?').run(JSON.stringify(numbers), id);
  }

  /** O cofre da pessoa ({rev, key, data, updated_at}) ou null. */
  vault(userId) {
    return this.st('SELECT rev, key, data, updated_at FROM vaults WHERE user_id = ?').get(userId) ?? null;
  }

  /**
   * Grava o cofre se [baseRev] é a versão que está aqui (0 = ainda não há): dois celulares da mesma
   * pessoa nunca apagam o que o outro acabou de mandar. [key] (a chave do cofre, embrulhada pelo
   * código) é obrigatória na criação e só muda quando vem. {ok, rev} | {stale, rev} | {nokey}.
   */
  putVault(userId, baseRev, key, data, now) {
    return this.tx(() => {
      const cur = this.st('SELECT rev, key FROM vaults WHERE user_id = ?').get(userId);
      const rev = cur ? cur.rev : 0;
      if (baseRev !== rev) return { stale: true, rev };
      if (!cur) {
        if (!key) return { nokey: true };
        this.st('INSERT INTO vaults (user_id, rev, key, data, updated_at) VALUES (?, 1, ?, ?, ?)').run(userId, key, data, now);
        return { ok: true, rev: 1 };
      }
      this.st('UPDATE vaults SET rev = ?, key = ?, data = ?, updated_at = ? WHERE user_id = ?').run(rev + 1, key ?? cur.key, data, now, userId);
      return { ok: true, rev: rev + 1 };
    });
  }

  deleteUser(id) {
    this.st('DELETE FROM users WHERE id = ?').run(id);
  }

  /** Lista global: mais novos primeiro, filtrando por @nome ou nome. */
  listUsers({ exclude, query, offset, limit }) {
    if (query) {
      const like = '%' + query.replace(/[\\%_]/g, (c) => '\\' + c) + '%';
      return this.st(`SELECT * FROM users WHERE id != ? AND (username LIKE ? ESCAPE '\\' OR name LIKE ? ESCAPE '\\')
                      ORDER BY created_at DESC, id LIMIT ? OFFSET ?`).all(exclude, like, like, limit, offset);
    }
    return this.st('SELECT * FROM users WHERE id != ? ORDER BY created_at DESC, id LIMIT ? OFFSET ?').all(exclude, limit, offset);
  }

  // ---------------------------------------------------------------- amizades

  areFriends(x, y) {
    const [a, b] = x < y ? [x, y] : [y, x];
    return !!this.st('SELECT 1 FROM friendships WHERE a = ? AND b = ?').get(a, b);
  }

  friendIds(id) {
    return this.st('SELECT b AS id FROM friendships WHERE a = ? UNION SELECT a AS id FROM friendships WHERE b = ?').all(id, id).map((r) => r.id);
  }

  friends(id) {
    return this.st(`SELECT u.*, f.created_at AS friends_since FROM friendships f
                    JOIN users u ON u.id = CASE WHEN f.a = ? THEN f.b ELSE f.a END
                    WHERE f.a = ? OR f.b = ? ORDER BY u.name COLLATE NOCASE`).all(id, id, id);
  }

  befriend(x, y, now) {
    const [a, b] = x < y ? [x, y] : [y, x];
    this.tx(() => {
      this.st('INSERT OR IGNORE INTO friendships (a, b, created_at) VALUES (?, ?, ?)').run(a, b, now);
      this.st('DELETE FROM friend_requests WHERE (from_id = ? AND to_id = ?) OR (from_id = ? AND to_id = ?)').run(x, y, y, x);
    });
  }

  /** Desfaz a amizade; true se eram amigos. */
  unfriend(x, y) {
    const [a, b] = x < y ? [x, y] : [y, x];
    return Number(this.st('DELETE FROM friendships WHERE a = ? AND b = ?').run(a, b).changes) > 0;
  }

  request(id) {
    return this.st('SELECT * FROM friend_requests WHERE id = ?').get(id) ?? null;
  }

  requestBetween(fromId, toId) {
    return this.st('SELECT * FROM friend_requests WHERE from_id = ? AND to_id = ?').get(fromId, toId) ?? null;
  }

  addRequest(fromId, toId, now) {
    this.st('INSERT OR IGNORE INTO friend_requests (from_id, to_id, created_at) VALUES (?, ?, ?)').run(fromId, toId, now);
    return this.requestBetween(fromId, toId);
  }

  deleteRequest(id) {
    this.st('DELETE FROM friend_requests WHERE id = ?').run(id);
  }

  incoming(id) {
    return this.st(`SELECT r.id AS request_id, r.created_at AS requested_at, u.* FROM friend_requests r
                    JOIN users u ON u.id = r.from_id WHERE r.to_id = ? ORDER BY r.created_at DESC`).all(id);
  }

  outgoing(id) {
    return this.st(`SELECT r.id AS request_id, r.created_at AS requested_at, u.* FROM friend_requests r
                    JOIN users u ON u.id = r.to_id WHERE r.from_id = ? ORDER BY r.created_at DESC`).all(id);
  }

  /** Relação de [me] com cada id: friends · incoming (pedido recebido) · outgoing (pedido enviado). */
  relations(me, ids) {
    const out = new Map();
    if (!ids.length) return out;
    const friends = new Set(this.friendIds(me));
    const pending = this.st('SELECT id, from_id, to_id FROM friend_requests WHERE from_id = ? OR to_id = ?').all(me, me);
    for (const r of pending) {
      if (r.from_id === me) out.set(r.to_id, { relation: 'outgoing', requestId: r.id });
      else out.set(r.from_id, { relation: 'incoming', requestId: r.id });
    }
    for (const id of ids) if (friends.has(id)) out.set(id, { relation: 'friends' });
    return out;
  }

  incomingCount(id) {
    return this.st('SELECT count(*) AS n FROM friend_requests WHERE to_id = ?').get(id).n;
  }

  outgoingCount(id) {
    return this.st('SELECT count(*) AS n FROM friend_requests WHERE from_id = ?').get(id).n;
  }

  // ---------------------------------------------------------------- entregas (não é histórico)

  /** Uma entrega; com [audio], o áudio da mensagem de voz entra junto (as duas coisas ou nenhuma). */
  addDelivery(fromId, toId, kind, payload, now, audio = null) {
    return this.tx(() => {
      const r = this.st('INSERT INTO deliveries (from_id, to_id, kind, payload, created_at) VALUES (?, ?, ?, ?, ?)').run(fromId, toId, kind, JSON.stringify(payload), now);
      if (audio) this.st('INSERT INTO voice_notes (delivery_id, to_id, data) VALUES (?, ?, ?)').run(r.lastInsertRowid, toId, audio);
      return this.st('SELECT * FROM deliveries WHERE id = ?').get(r.lastInsertRowid);
    });
  }

  /** O áudio da entrega [id], só pra quem vai receber; null se não há (ou já foi confirmado). */
  voice(id, toId) {
    return this.st('SELECT data FROM voice_notes WHERE delivery_id = ? AND to_id = ?').get(id, toId)?.data ?? null;
  }

  /** Bytes de áudio esperando [toId] confirmar. */
  pendingVoiceBytes(toId) {
    return Number(this.st('SELECT coalesce(sum(length(data)), 0) AS n FROM voice_notes WHERE to_id = ?').get(toId).n);
  }

  pendingCount(toId) {
    return this.st('SELECT count(*) AS n FROM deliveries WHERE to_id = ?').get(toId).n;
  }

  deliveries(toId, limit) {
    return this.st('SELECT * FROM deliveries WHERE to_id = ? ORDER BY id LIMIT ?').all(toId, limit);
  }

  /** O celular confirmou: apaga (só as dele). */
  ack(toId, ids) {
    const del = this.st('DELETE FROM deliveries WHERE id = ? AND to_id = ?');
    let n = 0;
    this.tx(() => {
      for (const id of ids) n += Number(del.run(id, toId).changes);
    });
    return n;
  }

  expireDeliveries(before) {
    return Number(this.st('DELETE FROM deliveries WHERE created_at < ?').run(before).changes);
  }
}
