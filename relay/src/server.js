// Relay do FreeSong: perfis criados sozinhos, lista global, amizades, músicas compartilhadas
// entre amigos e jam (todos ouvindo a mesma coisa). HTTP JSON + um WebSocket por app aberto.
//
// Guarda só o básico (perfil e amizades). Música compartilhada não vira histórico aqui: fica em
// cada celular; o servidor só segura a entrega até o celular do amigo confirmar, depois apaga.
//
// Variáveis: PORT (8787), HOST (127.0.0.1), DATA_DIR (./data), NEW_ACCOUNTS_PER_HOUR (300, no
// servidor todo). Ver relay/DEPLOY.md.
import http from 'node:http';
import { isIP } from 'node:net';
import { mkdirSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';
import { Store, sha256 } from './db.js';
import { Jams, cleanState, cleanTrack, MAX_MEMBERS } from './jams.js';
import { cleanJpeg } from './jpeg.js';
import { cleanDisplayName, normalizeUsername, randomIdentity } from './names.js';

export const VERSION = '1.2.0';
const JSON_LIMIT = 64 * 1024;
const AVATAR_LIMIT = 600 * 1024;
/** Entrega que o celular do amigo não buscou nesse tempo é apagada. */
export const DELIVERY_TTL_MS = 14 * 24 * 3600_000;
/** Teto de entregas esperando por pessoa (e de pedidos de amizade em aberto por pessoa). */
const MAX_PENDING = 500;
const MAX_OUTGOING_REQUESTS = 100;
/** WebSockets abertos por pessoa (o mais antigo cai) e no servidor todo. */
export const MAX_SOCKETS_PER_USER = 5;
const MAX_SOCKETS = 2000;
/** Celular que não lê o que mandamos: passou disso esperando no buffer, a conexão cai. */
const SEND_BUFFER_LIMIT = 1024 * 1024;
const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

// Em toda resposta: nada de o navegador "adivinhar" o tipo, abrir em frame ou mandar Referer.
const SECURITY_HEADERS = {
  'x-content-type-options': 'nosniff',
  'content-security-policy': "default-src 'none'; frame-ancestors 'none'",
  'referrer-policy': 'no-referrer',
  'strict-transport-security': 'max-age=31536000',
};
const JSON_HEADERS = { ...SECURITY_HEADERS, 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' };

class HttpError extends Error {
  constructor(status, code, extra) {
    super(code);
    this.status = status;
    this.code = code;
    this.extra = extra;
  }
}

/** Janela fixa por chave: no máximo [max] em [windowMs]. Guarda até [cap] chaves. */
export class Limiter {
  constructor(max, windowMs, clock = Date.now, cap = 50_000) {
    Object.assign(this, { max, windowMs, clock, cap, hits: new Map() });
  }
  take(key) {
    const now = this.clock();
    const h = this.hits.get(key);
    if (h && now - h.start < this.windowMs) return ++h.n <= this.max;
    if (!h && this.hits.size >= this.cap) {
      this.sweep(now);
      // Cheio de chaves ainda valendo (alguém girando IPs): recusa a chave nova. Zerar tudo
      // devolveria o limite de todo mundo, inclusive de quem estava sendo barrado.
      if (this.hits.size >= this.cap) return false;
    }
    this.hits.set(key, { start: now, n: 1 });
    return this.max >= 1;
  }
  /** Esquece as janelas que já venceram. */
  sweep(now = this.clock()) {
    for (const [key, h] of this.hits) if (now - h.start >= this.windowMs) this.hits.delete(key);
  }
}

/**
 * Chave dos limites por IP: o IPv4 inteiro; o IPv6 pelo /64 (a operadora entrega um /64 por
 * aparelho, e dentro dele dá pra trocar de endereço à vontade).
 */
export function ipKey(raw) {
  let ip = String(raw ?? '').trim().replace(/%.*$/, '');
  if (/^::ffff:/i.test(ip) && isIP(ip.slice(7)) === 4) ip = ip.slice(7);
  const version = isIP(ip);
  if (version === 4) return ip;
  if (version !== 6) return '?';
  const [head, tail] = ip.toLowerCase().split('::');
  const groups = (s) => (s ? s.split(':') : []);
  const h = groups(head);
  const t = tail === undefined ? [] : groups(tail);
  // IPv4 no fim ("64:ff9b::1.2.3.4") ocupa dois grupos.
  const last = tail === undefined ? h : t;
  if (last.length && last[last.length - 1].includes('.')) last.splice(-1, 1, '0', '0');
  const all = tail === undefined ? h : [...h, ...Array(Math.max(0, 8 - h.length - t.length)).fill('0'), ...t];
  return all.slice(0, 4).map((g) => parseInt(g, 16).toString(16)).join(':') + '::/64';
}

const LOOPBACK = /^(127\.|::1$|::ffff:127\.)/;

/** Caminho e query do pedido; null se a URL não serve (URL torta não pode derrubar o servidor). */
function parseUrl(raw) {
  if (typeof raw !== 'string' || !raw.startsWith('/')) return null;
  try {
    return new URL('http://relay' + raw);
  } catch {
    return null;
  }
}

export function createRelay({ dataDir = resolve('data'), clock = Date.now, log = console, newAccountsPerHour = 300 } = {}) {
  mkdirSync(join(dataDir, 'avatars'), { recursive: true });
  const store = new Store(join(dataDir, 'freesong.db'));
  const jams = new Jams({ clock });
  const limits = {
    accounts: new Limiter(20, HOUR, clock), // contas novas por IP (/64 no IPv6)
    accountsAll: new Limiter(newAccountsPerHour, HOUR, clock), // contas novas no servidor todo
    recover: new Limiter(20, HOUR, clock), // tentativas de recuperação por IP
    badAuth: new Limiter(120, MINUTE, clock), // token errado por IP
    requests: new Limiter(600, MINUTE, clock), // por pessoa: pedidos HTTP, mensagens e conexões do WebSocket
    friendRequests: new Limiter(60, HOUR, clock), // pedidos de amizade novos por pessoa
    friendPair: new Limiter(3, DAY, clock), // o mesmo pedido de novo depois de recusado ou cancelado
    shares: new Limiter(120, HOUR, clock), // músicas mandadas por pessoa
    jamChanges: new Limiter(120, MINUTE, clock), // por jam: cada mudança vai pra até 30 pessoas
    jamInvites: new Limiter(20, HOUR, clock), // convites de jam de uma pessoa pra outra
    jamRejects: new Limiter(10, MINUTE, clock), // por pessoa: jam devolvida pelo WebSocket quando passou do teto
  };
  /** userId → sockets abertos (um por celular com o app aberto) */
  const sockets = new Map();
  let socketCount = 0;

  // ---------------------------------------------------------------- views

  const avatarPath = (id) => join(dataDir, 'avatars', `${id}.jpg`);

  const publicProfile = (u) => ({
    id: u.id,
    username: u.username,
    name: u.name,
    avatar: u.avatar_rev > 0 ? `/avatars/${u.id}.jpg?v=${u.avatar_rev}` : null,
    since: u.created_at,
  });

  /** O que só amigos (e a própria pessoa) veem: horas escutadas e top 5. */
  const fullProfile = (u) => ({
    ...publicProfile(u),
    listenedMs: u.listened_ms,
    top: JSON.parse(u.top_json || '[]'),
  });

  const profileFor = (viewer, u, relation) =>
    viewer === u.id || relation?.relation === 'friends' ? fullProfile(u) : publicProfile(u);

  /** Uma entrega como o celular recebe: quem mandou, a música e quando. */
  const deliveryView = (d) => ({
    id: d.id,
    kind: d.kind,
    from: d.from_id,
    at: d.created_at,
    ...JSON.parse(d.payload),
  });

  const jamView = (jam) => ({
    id: jam.id,
    hostId: jam.hostId,
    members: [...jam.members].map((id) => store.user(id)).filter(Boolean).map(publicProfile),
    invited: [...jam.invited.keys()].map((id) => store.user(id)).filter(Boolean).map(publicProfile),
    state: jam.state,
    serverTime: clock(),
  });

  // ---------------------------------------------------------------- eventos em tempo real

  const isOnline = (userId) => (sockets.get(userId)?.size ?? 0) > 0;

  /** Manda pra um celular; o que não lê (buffer cheio) cai e reconecta, em vez de acumular memória aqui. */
  function send(ws, text) {
    if (ws.readyState !== 1) return;
    if (ws.bufferedAmount > SEND_BUFFER_LIMIT) ws.terminate();
    else ws.send(text);
  }

  function emit(userId, event) {
    const set = sockets.get(userId);
    if (!set) return;
    const text = JSON.stringify(event);
    for (const ws of set) send(ws, text);
  }

  /** Fecha os WebSockets cujo token não vale mais (saiu dos outros aparelhos, ou passou do teto de aparelhos). */
  function dropRevokedSockets(userId) {
    for (const ws of sockets.get(userId) ?? []) if (!store.tokenAlive(ws.tokenHash)) ws.close(4003, 'signed_out');
  }

  function broadcastJam(jam) {
    const event = { t: 'jam', jam: jamView(jam) };
    for (const m of jam.members) emit(m, event);
  }

  function jamEnded(jam, extra = []) {
    const event = { t: 'jam.ended', jamId: jam.id };
    for (const id of new Set([...jam.members, ...jam.invited.keys(), ...extra])) emit(id, event);
  }

  /** Depois de alguém sair: a jam segue (atualiza quem ficou) ou acabou (avisa todos). */
  function afterLeave({ jam, ended }, leaver) {
    if (ended) jamEnded(jam, [leaver]);
    else broadcastJam(jam);
  }

  // ---------------------------------------------------------------- HTTP

  async function body(req, limit) {
    const chunks = [];
    let size = 0;
    for await (const chunk of req) {
      size += chunk.length;
      if (size > limit) throw new HttpError(413, 'too_large');
      chunks.push(chunk);
    }
    return Buffer.concat(chunks);
  }

  async function json(req) {
    const raw = await body(req, JSON_LIMIT);
    if (!raw.length) return {};
    try {
      const v = JSON.parse(raw.toString('utf8'));
      if (!v || typeof v !== 'object' || Array.isArray(v)) throw new Error();
      return v;
    } catch {
      throw new HttpError(400, 'bad_json');
    }
  }

  // O IP de verdade vem da Cloudflare (cf-connecting-ip). Só se acredita nele quando a conexão vem
  // do próprio aparelho (o cloudflared): se um dia o relay escutar na rede, ninguém forja o cabeçalho.
  const ipOf = (req) => {
    const peer = req.socket.remoteAddress ?? '';
    const cf = req.headers['cf-connecting-ip'];
    return ipKey(LOOPBACK.test(peer) && typeof cf === 'string' && cf ? cf : peer);
  };
  // Chegou na Cloudflare por http:// (sem TLS): o token atravessou a internet aberto. Recusa sem
  // processar, pro erro aparecer no app na hora em vez de vazar em silêncio pra sempre.
  const plainHttp = (req) => {
    if (!LOOPBACK.test(req.socket.remoteAddress ?? '')) return false;
    const visitor = req.headers['cf-visitor'];
    return req.headers['x-forwarded-proto'] === 'http' || (typeof visitor === 'string' && /"scheme"\s*:\s*"http"/.test(visitor));
  };
  // Token só no cabeçalho (nunca na URL, que pode parar em log).
  const tokenOf = (req) => {
    const h = req.headers.authorization;
    if (typeof h === 'string' && h.startsWith('Bearer ')) return h.slice(7).trim();
    return null;
  };

  function auth(req) {
    const user = store.userByToken(tokenOf(req));
    if (!user) {
      // Chutar tokens também tem limite (por IP).
      if (!limits.badAuth.take(ipOf(req))) throw new HttpError(429, 'slow_down');
      throw new HttpError(401, 'unauthorized');
    }
    if (!limits.requests.take(user.id)) throw new HttpError(429, 'slow_down');
    return user;
  }

  const routes = [];
  const route = (method, pattern, handler) => routes.push({ method, pattern, handler });

  route('GET', /^\/v1\/health$/, () => ({ ok: true, version: VERSION, time: clock() }));

  // Conta nova (o app chama sozinho na primeira vez): @nome e nome sorteados, token e código de recuperação.
  route('POST', /^\/v1\/accounts$/, async (req) => {
    if (!limits.accounts.take(ipOf(req)) || !limits.accountsAll.take('all')) throw new HttpError(429, 'slow_down');
    await json(req);
    for (let attempt = 0; attempt < 20; attempt++) {
      const { username, name } = randomIdentity();
      if (store.userByName(username)) continue;
      const created = store.createUser({ username, name, now: clock() });
      return { status: 201, body: { token: created.token, recoveryCode: created.recoveryCode, me: fullProfile(store.user(created.id)) } };
    }
    throw new HttpError(503, 'try_again');
  });

  // Celular novo: o código de recuperação devolve o mesmo perfil.
  route('POST', /^\/v1\/accounts\/recover$/, async (req) => {
    if (!limits.recover.take(ipOf(req))) throw new HttpError(429, 'slow_down');
    const b = await json(req);
    const user = store.userByRecovery(b.code);
    if (!user) throw new HttpError(404, 'unknown_code');
    const token = store.addToken(user.id, clock());
    dropRevokedSockets(user.id);
    return { token, me: fullProfile(user) };
  });

  // "Sair dos outros aparelhos": só o celular que pediu continua entrando (os outros voltam pelo
  // código de recuperação). Junto com um código novo, tira de vez quem tinha o antigo.
  route('DELETE', /^\/v1\/me\/sessions$/, (req) => {
    const me = auth(req);
    const revoked = store.revokeOtherTokens(me.id, sha256(tokenOf(req)));
    dropRevokedSockets(me.id);
    return { revoked };
  });

  route('GET', /^\/v1\/me$/, (req) => ({ me: fullProfile(auth(req)) }));

  route('PATCH', /^\/v1\/me$/, async (req) => {
    const me = auth(req);
    const b = await json(req);
    const change = {};
    if (b.name !== undefined) {
      change.name = cleanDisplayName(b.name);
      if (!change.name) throw new HttpError(400, 'invalid_name');
    }
    // O @nome é o identificador da pessoa: nasce com a conta e não muda (só o nome de exibição muda).
    if (b.username !== undefined && normalizeUsername(b.username) !== me.username) throw new HttpError(400, 'username_fixed');
    try {
      store.rename(me.id, change);
    } catch (e) {
      if (String(e.message).includes('UNIQUE')) throw new HttpError(409, 'username_taken');
      throw e;
    }
    return { me: fullProfile(store.user(me.id)) };
  });

  // Foto: JPEG já reduzido pelo app (512 px). Sai daqui só a imagem: sem EXIF/GPS nem nada colado.
  route('PUT', /^\/v1\/me\/avatar$/, async (req) => {
    const me = auth(req);
    const img = cleanJpeg(await body(req, AVATAR_LIMIT));
    if (!img) throw new HttpError(400, 'not_jpeg');
    const tmp = avatarPath(me.id) + '.tmp';
    writeFileSync(tmp, img);
    renameSync(tmp, avatarPath(me.id));
    store.bumpAvatar(me.id, true);
    return { me: fullProfile(store.user(me.id)) };
  });

  route('DELETE', /^\/v1\/me\/avatar$/, (req) => {
    const me = auth(req);
    rmSync(avatarPath(me.id), { force: true });
    store.bumpAvatar(me.id, false);
    return { me: fullProfile(store.user(me.id)) };
  });

  // Horas escutadas e as 5 mais ouvidas (o app manda de tempos em tempos).
  route('PUT', /^\/v1\/me\/stats$/, async (req) => {
    const me = auth(req);
    const b = await json(req);
    const listenedMs = Number.isFinite(b.listenedMs) && b.listenedMs >= 0 ? Math.min(Math.round(b.listenedMs), 1e13) : null;
    if (listenedMs == null || !Array.isArray(b.top)) throw new HttpError(400, 'invalid_stats');
    const top = b.top.slice(0, 5).map(cleanTrack).filter(Boolean);
    store.setStats(me.id, { listenedMs, top });
    return { me: fullProfile(store.user(me.id)) };
  });

  route('POST', /^\/v1\/me\/recovery$/, (req) => {
    const me = auth(req);
    return { recoveryCode: store.newRecovery(me.id) };
  });

  // Apagar o perfil: some da lista, das amizades e das conversas.
  route('DELETE', /^\/v1\/me$/, (req) => {
    const me = auth(req);
    const jam = jams.of(me.id);
    if (jam) afterLeave(jams.leave(jam, me.id), me.id);
    for (const j of jams.invitesFor(me.id)) {
      jams.decline(j, me.id);
      broadcastJam(j);
    }
    const friends = store.friendIds(me.id);
    store.deleteUser(me.id);
    rmSync(avatarPath(me.id), { force: true });
    for (const f of friends) emit(f, { t: 'friend.removed', userId: me.id });
    for (const ws of sockets.get(me.id) ?? []) ws.close(4001, 'deleted');
    return { ok: true };
  });

  // Lista global: foto, @nome e nome de todos; horas e top 5 só de amigos.
  route('GET', /^\/v1\/users$/, (req, url) => {
    const me = auth(req);
    const query = (url.searchParams.get('q') ?? '').trim().replace(/^@/, '').slice(0, 40);
    const offset = Math.max(0, parseInt(url.searchParams.get('offset') ?? '0', 10) || 0);
    const limit = Math.min(50, Math.max(1, parseInt(url.searchParams.get('limit') ?? '30', 10) || 30));
    const rows = store.listUsers({ exclude: me.id, query, offset, limit: limit + 1 });
    const page = rows.slice(0, limit);
    const rel = store.relations(me.id, page.map((u) => u.id));
    return {
      users: page.map((u) => ({ ...profileFor(me.id, u, rel.get(u.id)), ...(rel.get(u.id) ?? { relation: 'none' }) })),
      next: rows.length > limit ? offset + limit : null,
    };
  });

  route('GET', /^\/v1\/users\/([a-z0-9]+)$/, (req, url, [id]) => {
    const me = auth(req);
    const u = store.user(id);
    if (!u) throw new HttpError(404, 'unknown_user');
    const rel = store.relations(me.id, [u.id]).get(u.id);
    return { user: { ...profileFor(me.id, u, rel), ...(id === me.id ? { relation: 'self' } : rel ?? { relation: 'none' }) } };
  });

  route('GET', /^\/v1\/friends$/, (req) => {
    const me = auth(req);
    return {
      friends: store.friends(me.id).map((u) => ({ ...fullProfile(u), friendsSince: u.friends_since })),
      incoming: store.incoming(me.id).map((u) => ({ requestId: u.request_id, at: u.requested_at, user: publicProfile(u) })),
      outgoing: store.outgoing(me.id).map((u) => ({ requestId: u.request_id, at: u.requested_at, user: publicProfile(u) })),
    };
  });

  route('POST', /^\/v1\/friends\/requests$/, async (req) => {
    const me = auth(req);
    const b = await json(req);
    const other = b.userId ? store.user(String(b.userId)) : b.username ? store.userByName(normalizeUsername(b.username)) : null;
    if (!other) throw new HttpError(404, 'unknown_user');
    if (other.id === me.id) throw new HttpError(400, 'self');
    if (store.areFriends(me.id, other.id)) return { status: 'friends', user: fullProfile(other) };
    // Os dois pediram: já são amigos.
    if (store.requestBetween(other.id, me.id)) {
      store.befriend(me.id, other.id, clock());
      emit(other.id, { t: 'friend.accepted', user: fullProfile(store.user(me.id)) });
      return { status: 'friends', user: fullProfile(other) };
    }
    // Pedido repetido não toca de novo no celular do outro.
    const pending = store.requestBetween(me.id, other.id);
    if (pending) return { status: 'pending', requestId: pending.id };
    if (store.outgoingCount(me.id) >= MAX_OUTGOING_REQUESTS) throw new HttpError(429, 'too_many_requests');
    // Pedir, cancelar e pedir de novo (ou insistir depois de recusado) não vira enxurrada de avisos.
    if (!limits.friendRequests.take(me.id) || !limits.friendPair.take(`${me.id}>${other.id}`)) throw new HttpError(429, 'slow_down');
    const r = store.addRequest(me.id, other.id, clock());
    emit(other.id, { t: 'friend.request', request: { requestId: r.id, at: r.created_at, user: publicProfile(me) } });
    return { status: 'pending', requestId: r.id };
  });

  route('POST', /^\/v1\/friends\/requests\/(\d+)\/(accept|decline)$/, (req, url, [rid, action]) => {
    const me = auth(req);
    const r = store.request(Number(rid));
    if (!r || (r.to_id !== me.id && !(action === 'decline' && r.from_id === me.id))) throw new HttpError(404, 'unknown_request');
    if (action === 'accept') {
      store.befriend(r.from_id, r.to_id, clock());
      emit(r.from_id, { t: 'friend.accepted', user: fullProfile(store.user(me.id)) });
      return { status: 'friends', user: fullProfile(store.user(r.from_id)) };
    }
    store.deleteRequest(r.id);
    return { status: 'none' };
  });

  route('DELETE', /^\/v1\/friends\/([a-z0-9]+)$/, (req, url, [id]) => {
    const me = auth(req);
    // Só avisa quem era amigo de verdade (senão qualquer um mandava "friend.removed" pra qualquer um).
    if (store.unfriend(me.id, id)) emit(id, { t: 'friend.removed', userId: me.id });
    return { status: 'none' };
  });

  // Músicas compartilhadas: só entre amigos, sem texto. O servidor entrega e esquece: quem
  // estava com o app aberto recebe na hora (e confirma); quem não estava busca na caixa depois.
  route('POST', /^\/v1\/shares$/, async (req) => {
    const me = auth(req);
    const b = await json(req);
    const to = String(b.to ?? '');
    if (!store.user(to)) throw new HttpError(404, 'unknown_user');
    if (!store.areFriends(me.id, to)) throw new HttpError(403, 'not_friends');
    const track = cleanTrack(b.track);
    if (!track) throw new HttpError(400, 'invalid_track');
    if (store.pendingCount(to) >= MAX_PENDING) throw new HttpError(429, 'inbox_full');
    if (!limits.shares.take(me.id)) throw new HttpError(429, 'slow_down');
    const d = deliveryView(store.addDelivery(me.id, to, 'share', { track }, clock()));
    emit(to, { t: 'delivery', delivery: d, from: publicProfile(me) });
    return { status: 201, body: { share: { id: d.id, at: d.at } } };
  });

  // O que chegou enquanto o app estava fechado (some depois do ack).
  route('GET', /^\/v1\/mailbox$/, (req) => {
    const me = auth(req);
    const items = store.deliveries(me.id, 200);
    const senders = new Map();
    for (const d of items) if (!senders.has(d.from_id)) senders.set(d.from_id, store.user(d.from_id));
    return {
      deliveries: items.map(deliveryView),
      from: [...senders.values()].filter(Boolean).map(publicProfile),
      requests: store.incomingCount(me.id),
    };
  });

  route('POST', /^\/v1\/mailbox\/ack$/, async (req) => {
    const me = auth(req);
    const b = await json(req);
    const ids = Array.isArray(b.ids) ? b.ids.filter(Number.isInteger).slice(0, 500) : [];
    return { removed: store.ack(me.id, ids) };
  });

  // ---------------------------------------------------------------- jam

  function jamOrThrow(id) {
    const jam = jams.get(id);
    if (!jam) throw new HttpError(404, 'unknown_jam');
    return jam;
  }

  function inviteFriends(jam, me, ids) {
    const list = Array.isArray(ids) ? [...new Set(ids.map(String))] : [];
    if (list.length > MAX_MEMBERS) throw new HttpError(400, 'jam_full');
    for (const id of list) {
      if (id === me.id) continue;
      if (!store.areFriends(me.id, id)) throw new HttpError(403, 'not_friends', { userId: id });
    }
    // Conta só quem é novo (quem já está ou já foi convidado não ocupa vaga de novo).
    const fresh = list.filter((id) => id !== me.id && !jam.members.has(id) && !jam.invited.has(id));
    if (jam.members.size + jam.invited.size + fresh.length > MAX_MEMBERS) throw new HttpError(400, 'jam_full');
    const sent = [];
    for (const id of fresh) {
      // Criar jam atrás de jam só pra mandar convite não vira enxurrada no celular do amigo.
      if (!limits.jamInvites.take(`${me.id}>${id}`)) continue;
      if (jams.invite(jam, me.id, id)) sent.push(id);
    }
    const from = publicProfile(me);
    const view = jamView(jam);
    for (const id of sent) emit(id, { t: 'jam.invite', jam: view, from });
    return sent;
  }

  function invitesView(userId) {
    return jams.invitesFor(userId).map((j) => ({ jam: jamView(j), from: publicProfile(store.user(j.invited.get(userId)) ?? { id: '?', username: '?', name: '?', avatar_rev: 0, created_at: 0 }) }));
  }

  route('GET', /^\/v1\/jams\/current$/, (req) => {
    const me = auth(req);
    const jam = jams.of(me.id);
    return { jam: jam ? jamView(jam) : null, invites: invitesView(me.id), serverTime: clock() };
  });

  route('POST', /^\/v1\/jams$/, async (req) => {
    const me = auth(req);
    const b = await json(req);
    // Confere os convites antes de criar (ninguém começa jam com quem não é amigo; nada fica pela metade).
    const invite = Array.isArray(b.invite) ? [...new Set(b.invite.map(String))].filter((id) => id !== me.id) : [];
    if (invite.length + 1 > MAX_MEMBERS) throw new HttpError(400, 'jam_full');
    for (const id of invite) {
      if (!store.areFriends(me.id, id)) throw new HttpError(403, 'not_friends', { userId: id });
    }
    // O que vai tocar também é conferido antes: estado inválido não tira a pessoa da jam em que estava.
    if (b.state != null && !cleanState(b.state)) throw new HttpError(400, 'invalid_state');
    const previous = jams.of(me.id);
    if (previous) afterLeave(jams.leave(previous, me.id), me.id);
    const jam = jams.create(me.id, b.state ?? null);
    inviteFriends(jam, me, invite);
    return { status: 201, body: { jam: jamView(jam) } };
  });

  route('POST', /^\/v1\/jams\/([a-z0-9]+)\/invite$/, async (req, url, [id]) => {
    const me = auth(req);
    const jam = jamOrThrow(id);
    if (!jam.members.has(me.id)) throw new HttpError(403, 'not_member');
    const b = await json(req);
    inviteFriends(jam, me, b.userIds);
    broadcastJam(jam);
    return { jam: jamView(jam) };
  });

  route('POST', /^\/v1\/jams\/([a-z0-9]+)\/join$/, (req, url, [id]) => {
    const me = auth(req);
    const jam = jamOrThrow(id);
    if (!jam.members.has(me.id)) {
      if (!jam.invited.has(me.id)) throw new HttpError(403, 'not_invited');
      const left = jams.join(jam, me.id);
      if (left) afterLeave(left, me.id);
      broadcastJam(jam);
    }
    return { jam: jamView(jam) };
  });

  route('POST', /^\/v1\/jams\/([a-z0-9]+)\/decline$/, (req, url, [id]) => {
    const me = auth(req);
    const jam = jamOrThrow(id);
    // Quem não foi convidado não faz a jam inteira receber atualização à toa.
    if (jam.invited.has(me.id)) {
      jams.decline(jam, me.id);
      broadcastJam(jam);
    }
    return { ok: true };
  });

  route('POST', /^\/v1\/jams\/([a-z0-9]+)\/leave$/, (req, url, [id]) => {
    const me = auth(req);
    const jam = jamOrThrow(id);
    if (jam.members.has(me.id)) afterLeave(jams.leave(jam, me.id), me.id);
    return { ok: true };
  });

  route('DELETE', /^\/v1\/jams\/([a-z0-9]+)$/, (req, url, [id]) => {
    const me = auth(req);
    const jam = jamOrThrow(id);
    if (jam.hostId !== me.id) throw new HttpError(403, 'not_host');
    jams.end(jam);
    jamEnded(jam);
    return { ok: true };
  });

  /** O que toca agora: qualquer participante muda pra todos (também chega pelo WebSocket). */
  function jamState(me, msg) {
    const jam = jamOrThrow(String(msg.jamId ?? ''));
    if (!jam.members.has(me.id)) return { result: { ok: false, reason: 'not_member' }, jam };
    // Cada mudança vai pra todo mundo (até 30 pessoas × fila de 200 músicas): teto por jam, senão
    // um participante sozinho transforma o servidor num canhão de banda.
    if (!limits.jamChanges.take(jam.id)) return { result: { ok: false, reason: 'slow_down' }, jam };
    const result = jams.apply(jam, me.id, msg);
    if (result.ok) broadcastJam(jam);
    return { result, jam };
  }

  const JAM_STATUS = { not_member: 403, slow_down: 429 };

  route('POST', /^\/v1\/jams\/([a-z0-9]+)\/state$/, async (req, url, [id]) => {
    const me = auth(req);
    const b = await json(req);
    const { result, jam } = jamState(me, { ...b, jamId: id });
    if (!result.ok && result.reason !== 'stale') throw new HttpError(JAM_STATUS[result.reason] ?? 400, result.reason);
    return { accepted: result.ok, jam: jamView(jam) };
  });

  // ---------------------------------------------------------------- servidor

  async function handle(req, res) {
    const url = parseUrl(req.url);
    try {
      if (!url) throw new HttpError(400, 'bad_url');
      if (plainHttp(req)) throw new HttpError(403, 'https_only');
      const avatar = req.method === 'GET' && url.pathname.match(/^\/avatars\/([a-z0-9]+)\.jpg$/);
      if (avatar) {
        let img;
        try {
          img = await readFile(avatarPath(avatar[1]));
        } catch (e) {
          if (e.code === 'ENOENT') throw new HttpError(404, 'no_avatar');
          throw e;
        }
        // Cache curto: foto trocada ou apagada (ou perfil apagado) não fica um ano no cache da Cloudflare.
        res.writeHead(200, { ...SECURITY_HEADERS, 'content-type': 'image/jpeg', 'content-length': img.length, 'cache-control': 'public, max-age=3600' });
        res.end(img);
        return;
      }
      let methodMismatch = false;
      for (const r of routes) {
        const m = url.pathname.match(r.pattern);
        if (!m) continue;
        if (r.method !== req.method) {
          methodMismatch = true;
          continue;
        }
        const out = await r.handler(req, url, m.slice(1));
        const status = out?.status && out.body ? out.status : 200;
        const payload = JSON.stringify(out?.status && out.body ? out.body : out);
        res.writeHead(status, JSON_HEADERS);
        res.end(payload);
        return;
      }
      throw new HttpError(methodMismatch ? 405 : 404, methodMismatch ? 'method' : 'not_found');
    } catch (e) {
      const status = e instanceof HttpError ? e.status : 500;
      if (status === 500) log.error?.(`[relay] ${req.method} ${url?.pathname ?? '?'}`, e);
      if (!res.headersSent) {
        // Corpo grande demais ficou pela metade no socket: fecha a conexão em vez de reaproveitar.
        const headers = status === 413 ? { ...JSON_HEADERS, connection: 'close' } : JSON_HEADERS;
        res.writeHead(status, headers);
        res.end(JSON.stringify({ error: e instanceof HttpError ? e.code : 'server_error', ...(e.extra ?? {}) }));
      } else res.destroy();
    }
  }

  const server = http.createServer((req, res) => {
    // Nada que escape do handle pode virar promessa rejeitada solta (o Node derruba o processo).
    handle(req, res).catch((e) => {
      log.error?.('[relay] http', e);
      res.destroy();
    });
  });
  server.headersTimeout = 20_000;
  server.requestTimeout = 60_000;
  // O cloudflared reaproveita a conexão com a origem por até 90 s. Se o Node fechar antes (o padrão
  // é 5 s), às vezes o cloudflared manda um pedido numa conexão que acabou de fechar: 502 do nada.
  server.keepAliveTimeout = 100_000;

  // WebSocket /v1/ws (Authorization: Bearer <token>): eventos e o controle da jam. Sem compressão
  // (bomba de descompressão e memória por conexão).
  const wss = new WebSocketServer({ noServer: true, maxPayload: JSON_LIMIT, perMessageDeflate: false });
  const refuse = (socket, status) => socket.end(`HTTP/1.1 ${status}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
  server.on('upgrade', (req, socket, head) => {
    socket.on('error', () => {});
    try {
      const url = parseUrl(req.url);
      if (url?.pathname !== '/v1/ws') return socket.destroy();
      if (plainHttp(req)) return refuse(socket, '403 Forbidden');
      const token = tokenOf(req);
      const user = store.userByToken(token);
      if (!user) return refuse(socket, limits.badAuth.take(ipOf(req)) ? '401 Unauthorized' : '429 Too Many Requests');
      // Abrir e fechar conexão sem parar também gasta o limite da pessoa.
      if (!limits.requests.take(user.id)) return refuse(socket, '429 Too Many Requests');
      if (socketCount >= MAX_SOCKETS) return refuse(socket, '503 Service Unavailable');
      wss.handleUpgrade(req, socket, head, (ws) => connected(ws, user.id, sha256(token)));
    } catch (e) {
      log.error?.('[relay] upgrade', e);
      socket.destroy();
    }
  });

  function onMessage(ws, userId, data) {
    // Toda mensagem gasta o mesmo limite dos pedidos HTTP (ping e ack também: ack apaga no banco).
    if (!limits.requests.take(userId)) return;
    let msg;
    try {
      msg = JSON.parse(data.toString('utf8'));
    } catch {
      return;
    }
    if (msg?.t === 'ping') {
      const c = Number.isFinite(msg.c) ? msg.c : typeof msg.c === 'string' ? msg.c.slice(0, 64) : null;
      send(ws, JSON.stringify({ t: 'pong', c, s: clock() }));
    } else if (msg?.t === 'ack' && Array.isArray(msg.ids)) {
      store.ack(userId, msg.ids.filter(Number.isInteger).slice(0, 500));
    } else if (msg?.t === 'jam.state') {
      const me = store.user(userId);
      if (!me) return ws.close(4001, 'deleted');
      try {
        const { result, jam } = jamState(me, msg);
        // Recusada (mudou junto com outra pessoa, inválida, passou do teto): devolve a versão atual,
        // senão o celular fica tocando a mudança dele, que ninguém mais ouve. A resposta leva a fila
        // inteira, então no teto ela também tem limite (quem insiste depois disso não ganha banda).
        // Quem não está na jam não vê nada dela.
        if (result.reason === 'not_member') send(ws, JSON.stringify({ t: 'jam.ended', jamId: jam.id }));
        else if (!result.ok && (result.reason !== 'slow_down' || limits.jamRejects.take(userId))) {
          send(ws, JSON.stringify({ t: 'jam', jam: jamView(jam), rejected: result.reason }));
        }
      } catch (e) {
        if (!(e instanceof HttpError)) throw e;
        send(ws, JSON.stringify({ t: 'jam.ended', jamId: String(msg.jamId ?? '').slice(0, 40) }));
      }
    }
  }

  function connected(ws, userId, tokenHash) {
    if (!sockets.has(userId)) sockets.set(userId, new Set());
    const set = sockets.get(userId);
    // Teto por pessoa: o mais antigo cai (celular que trocou de rede e deixou a conexão velha pendurada).
    // Sai do conjunto já, sem esperar o fechamento terminar: senão uma rajada passa do teto.
    while (set.size >= MAX_SOCKETS_PER_USER) {
      const oldest = set.values().next().value;
      set.delete(oldest);
      oldest.close(4002, 'too_many_connections');
    }
    set.add(ws);
    socketCount++;
    ws.tokenHash = tokenHash;
    ws.alive = true;
    // Sem ouvinte de 'error', um frame inválido ou grande demais vira exceção solta e derruba o relay.
    ws.on('error', () => {});
    ws.on('pong', () => (ws.alive = true));
    send(ws, JSON.stringify({ t: 'hello', serverTime: clock(), pending: store.pendingCount(userId) }));
    ws.on('message', (data) => {
      try {
        onMessage(ws, userId, data);
      } catch (e) {
        log.error?.('[relay] ws', e);
      }
    });
    ws.on('close', () => {
      socketCount--;
      const set = sockets.get(userId);
      set?.delete(ws);
      if (set && set.size === 0) sockets.delete(userId);
    });
  }

  // Cloudflare corta conexão parada em ~100 s: ping a cada 25 s (e derruba quem não responde).
  const heartbeat = setInterval(() => {
    try {
      for (const set of sockets.values()) {
        for (const ws of set) {
          if (!ws.alive) {
            ws.terminate();
            continue;
          }
          ws.alive = false;
          ws.ping();
        }
      }
      for (const jam of jams.idle(isOnline)) {
        jams.end(jam);
        jamEnded(jam);
      }
      store.expireDeliveries(clock() - DELIVERY_TTL_MS);
      for (const limiter of Object.values(limits)) limiter.sweep();
    } catch (e) {
      // Banco ocupado num instante não pode derrubar o servidor: tenta de novo na próxima volta.
      log.error?.('[relay] heartbeat', e);
    }
  }, 25_000);
  heartbeat.unref();

  function close() {
    clearInterval(heartbeat);
    for (const set of sockets.values()) for (const ws of set) ws.terminate();
    wss.close();
    return new Promise((done) => {
      server.close(() => {
        store.close();
        done();
      });
      // Conexões keep-alive paradas não seguram o desligamento.
      server.closeIdleConnections?.();
    });
  }

  return { server, store, jams, close };
}

// node src/server.js
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const port = parseInt(process.env.PORT ?? '8787', 10);
  const host = process.env.HOST ?? '127.0.0.1';
  const dataDir = resolve(process.env.DATA_DIR ?? 'data');
  // Banco e fotos legíveis só pelo usuário que roda o relay.
  process.umask(0o077);
  const perHour = parseInt(process.env.NEW_ACCOUNTS_PER_HOUR ?? '', 10);
  const relay = createRelay({ dataDir, newAccountsPerHour: perHour > 0 ? perHour : 300 });
  relay.server.listen(port, host, () => console.log(`[relay] FreeSong relay ${VERSION} em http://${host}:${port} (dados em ${dataDir})`));
  // Desliga em até 5 s mesmo com conexão pendurada (o run.sh/pm2 sobe de novo).
  const stop = () => {
    setTimeout(() => process.exit(0), 5000).unref();
    relay.close().then(() => process.exit(0));
  };
  process.on('SIGTERM', stop);
  process.on('SIGINT', stop);
}
