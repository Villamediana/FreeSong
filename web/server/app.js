// O servidor do FreeSong no computador: as páginas (a raiz, /link e os arquivos) e a ponte com o
// YouTube Music (/api/…). Fica atrás do túnel da Cloudflare junto com o relay (freesong.simplyonegoal.com):
// o túnel manda pro relay só o que é dele (/v1, /avatars, /.well-known…) e o resto pra cá.
// Não guarda nada de ninguém: o que a pessoa busca não
// vai pra log nenhum, e as respostas do YouTube ficam um tempo na memória pra servir a todos.
import http from 'node:http';
import { isIP } from 'node:net';
import { Cache } from './cache.js';
import { Lyrics } from './lyrics.js';
import { ALBUM, CHANNEL, Music, PLAYLIST, VIDEO } from './music.js';
import { HEADERS, Pages } from './pages.js';
import { PassCheck } from './pass.js';

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const LOOPBACK = /^(127\.|::1$|::ffff:127\.)/;

class HttpError extends Error {
  constructor(status, code) {
    super(code);
    this.status = status;
    this.code = code;
  }
}

/** No máximo [max] por [windowMs] por chave (IP). */
class Limiter {
  constructor(max, windowMs, clock) {
    Object.assign(this, { max, windowMs, clock, hits: new Map() });
  }
  take(key, cost = 1) {
    const now = this.clock();
    const h = this.hits.get(key);
    if (h && now - h.start < this.windowMs) return (h.n += cost) <= this.max;
    if (this.hits.size > 50_000) for (const [k, v] of this.hits) if (now - v.start >= this.windowMs) this.hits.delete(k);
    if (this.hits.size > 50_000) return false;
    this.hits.set(key, { start: now, n: cost });
    return cost <= this.max;
  }
}

/** IPv4 inteiro; IPv6 pelo /64. */
function ipKey(raw) {
  let ip = String(raw ?? '').trim();
  if (/^::ffff:/i.test(ip) && isIP(ip.slice(7)) === 4) ip = ip.slice(7);
  if (isIP(ip) === 4) return ip;
  if (isIP(ip) !== 6) return '?';
  const [head, tail] = ip.toLowerCase().split('::');
  const h = head ? head.split(':') : [];
  const t = tail === undefined ? [] : tail ? tail.split(':') : [];
  const all = tail === undefined ? h : [...h, ...Array(Math.max(0, 8 - h.length - t.length)).fill('0'), ...t];
  return all.slice(0, 4).join(':') + '::/64';
}

/** Pedidos ao YouTube ao mesmo tempo: no máximo [max]; os outros esperam na fila (até [queue]). */
class Gate {
  constructor(max, queue) {
    Object.assign(this, { max, queue, busy: 0, waiting: [] });
  }
  async run(fn) {
    if (this.busy >= this.max) {
      if (this.waiting.length >= this.queue) throw new HttpError(503, 'busy');
      await new Promise((r) => this.waiting.push(r));
    }
    this.busy++;
    try {
      return await fn();
    } finally {
      this.busy--;
      this.waiting.shift()?.();
    }
  }
}

const LANG = (v) => (v === 'es' || v === 'en' ? v : 'pt');
const q = (url, name, max = 200) => (url.searchParams.get(name) ?? '').replace(/[\u0000-\u001f\u007f]/g, ' ').trim().slice(0, max);

// Por computador ligado: pedidos por minuto, pedidos que vão mesmo ao YouTube (fora do cache) por minuto,
// e pedidos ao mesmo tempo. Um só computador não enche a fila de todos.
const PER_COMPUTER = 300;
const UPSTREAM_PER_COMPUTER = 120;
const IN_FLIGHT_PER_COMPUTER = 6;

export function createWeb({
  publicDir,
  music = new Music(),
  lyrics = new Lyrics(),
  clock = Date.now,
  log = console,
  relay = process.env.RELAY_URL || 'http://127.0.0.1:3980',
  passes = new PassCheck({ keyUrl: `${relay}/v1/pass-key`, clock }),
} = {}) {
  const pages = new Pages({ dir: publicDir });
  const cache = new Cache({ max: 3000, clock });
  const gate = new Gate(8, 200);
  const perIp = new Limiter(300, MINUTE, clock);
  const perComputer = new Limiter(PER_COMPUTER, MINUTE, clock);
  const upstream = new Limiter(UPSTREAM_PER_COMPUTER, MINUTE, clock);
  const inFlight = new Map();

  /** Vai ao YouTube (ou ao LRCLIB) por [who]: conta no limite dele. */
  const spend = (who, cost = 1) => {
    if (!upstream.take(who, cost)) throw new HttpError(429, 'slow_down');
  };

  /** Uma resposta do YouTube (do cache, ou pedida agora; um erro tenta de novo uma vez com uma sessão nova). */
  const yt = (key, ttl, lang, fn, who) => cache.get(`${lang}|${key}`, ttl, () => {
    spend(who);
    return gate.run(async () => {
      try {
        return await fn();
      } catch (e) {
        if (e instanceof HttpError) throw e;
        music.reset(lang);
        return fn();
      }
    });
  });

  const api = {
    search: (url, lang, who) => {
      const text = q(url, 'q', 150);
      if (!text) throw new HttpError(400, 'no_query');
      const type = q(url, 'type', 20);
      return yt(`search|${type}|${text.toLowerCase()}`, 30 * MINUTE, lang, () => music.search(text, type, lang), who);
    },
    more: async (url, lang, who) => {
      const token = q(url, 'token', 80);
      spend(who);
      const out = await gate.run(() => music.more(token));
      if (!out) throw new HttpError(404, 'no_page');
      return out;
    },
    suggest: (url, lang, who) => {
      const text = q(url, 'q', 100);
      if (!text) return { queries: [], items: [] };
      return yt(`suggest|${text.toLowerCase()}`, HOUR, lang, () => music.suggest(text, lang), who);
    },
    explore: (url, lang, who) => yt('explore', HOUR, lang, () => music.explore(lang), who),
    alt: (url, lang, who) => {
      const title = q(url, 'title', 200);
      const artist = q(url, 'artist', 200);
      const not = q(url, 'not', 11);
      if (!title) throw new HttpError(400, 'no_query');
      return yt(`alt|${title}|${artist}|${not}`, 24 * HOUR, lang, () => music.alternatives(title, artist, not, lang), who);
    },
  };

  const byId = {
    artist: [CHANNEL, 6 * HOUR, (id, lang) => music.artist(id, lang)],
    album: [ALBUM, 24 * HOUR, (id, lang) => music.album(id, lang)],
    playlist: [PLAYLIST, HOUR, (id, lang) => music.playlist(id.replace(/^VL/, ''), lang)],
    radio: [VIDEO, HOUR, (id, lang) => music.radio(id, lang)],
    song: [VIDEO, 24 * HOUR, (id, lang) => music.song(id, lang)],
  };

  async function lyricsOf(id, url, lang, who) {
    const want = { title: q(url, 'title', 200), artist: q(url, 'artist', 200), album: q(url, 'album', 200), durationMs: Number(q(url, 'duration', 9)) || null };
    return cache.get(`lyrics|${id}|${want.title}|${want.artist}`, 7 * 24 * HOUR, async () => {
      spend(who);
      let found = null;
      try {
        found = await lyrics.find(want);
      } catch {
        found = null;
      }
      if (found) return found;
      // O LRCLIB não tem (ou não respondeu): as do YouTube Music, sem tempo.
      try {
        return (await gate.run(() => music.lyrics(id, lang))) ?? { synced: null, plain: null, instrumental: false, source: null };
      } catch {
        return { synced: null, plain: null, instrumental: false, source: null };
      }
    });
  }

  function send(res, status, body, extra = {}) {
    const bytes = Buffer.from(JSON.stringify(body));
    res.writeHead(status, {
      ...HEADERS,
      'Content-Type': 'application/json; charset=utf-8',
      'Content-Length': bytes.length,
      'Cache-Control': status === 200 ? 'private, max-age=300' : 'no-store',
      ...extra,
    });
    res.end(bytes);
  }

  // O IP de verdade vem da Cloudflare (cf-connecting-ip), e só se acredita nele vindo do próprio aparelho (o cloudflared).
  const ipOf = (req) => {
    const peer = req.socket.remoteAddress ?? '';
    const cf = req.headers['cf-connecting-ip'];
    return ipKey(LOOPBACK.test(peer) && typeof cf === 'string' && cf ? cf : peer);
  };

  async function handle(req, res) {
    let url;
    try {
      url = new URL('http://web' + (req.url?.startsWith('/') ? req.url : '/'));
    } catch {
      return send(res, 400, { error: 'bad_url' });
    }
    const p = url.pathname;
    if (req.method !== 'GET' && req.method !== 'HEAD') return send(res, 405, { error: 'method' });
    if (p === '/api/health') return send(res, 200, { ok: true });
    if (!p.startsWith('/api/')) return pages.serve(req, res, p);
    let who = null;
    try {
      if (!perIp.take(ipOf(req))) throw new HttpError(429, 'slow_down');
      // Só computador ligado (o passe que o relay dá a ele): de fora, a ponte não atende.
      const sub = await passes.check(req.headers['x-freesong-pass']);
      if (!sub) throw new HttpError(401, 'pass');
      if (!perComputer.take(sub)) throw new HttpError(429, 'slow_down');
      if ((inFlight.get(sub) ?? 0) >= IN_FLIGHT_PER_COMPUTER) throw new HttpError(429, 'slow_down');
      who = sub;
      inFlight.set(who, (inFlight.get(who) ?? 0) + 1);
      const lang = LANG(url.searchParams.get('lang'));
      const [, , what, id] = p.split('/');
      let out;
      if (Object.hasOwn(api, what) && id === undefined) out = await api[what](url, lang, who);
      else if (what === 'lyrics' && VIDEO.test(id ?? '')) out = await lyricsOf(id, url, lang, who);
      else if (Object.hasOwn(byId, what) && id !== undefined) {
        const [pattern, ttl, fn] = byId[what];
        if (!pattern.test(id)) throw new HttpError(400, 'bad_id');
        out = await yt(`${what}|${id}`, ttl, lang, () => fn(id, lang), who);
      } else throw new HttpError(404, 'not_found');
      return send(res, 200, out);
    } catch (e) {
      if (e instanceof HttpError) return send(res, e.status, { error: e.code }, e.status === 429 ? { 'Retry-After': '30' } : {});
      // Nunca a busca da pessoa no log: só o tipo de pedido.
      log.error?.(`[web] ${p.split('/').slice(0, 3).join('/')}: ${e?.message ?? e}`);
      return send(res, 502, { error: 'upstream' });
    } finally {
      if (who) {
        const n = (inFlight.get(who) ?? 1) - 1;
        if (n > 0) inFlight.set(who, n);
        else inFlight.delete(who);
      }
    }
  }

  const server = http.createServer((req, res) => {
    handle(req, res).catch(() => res.destroy());
  });
  server.headersTimeout = 20_000;
  server.requestTimeout = 60_000;
  server.keepAliveTimeout = 100_000;
  return { server, music, cache };
}
