// A página falando com o servidor (o mesmo endereço): o relay do FreeSong (/v1/…: ligar o computador,
// o perfil, o cofre cifrado, os amigos) e a ponte com o YouTube Music (/api/…). Os pedidos ao relay
// vão com o token deste computador e assinados pela chave que só este navegador tem.
import { b64u } from './crypto.js';

export class ApiError extends Error {
  constructor(status, code, extra = {}) {
    super(code || `HTTP ${status}`);
    this.status = status;
    this.code = code;
    this.extra = extra;
  }
}

const te = new TextEncoder();
const hex = (buf) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');

export class Api {
  constructor({ base = '', token = null, proof = null, lang = 'pt', onUnlinked = () => {} } = {}) {
    Object.assign(this, { base, token, proof, lang, onUnlinked });
    this.skew = 0; // a hora do servidor menos a daqui (aprendida quando ele diz que o relógio está fora)
  }

  /** X-FreeSong-Sig: "<ms>.<nonce>.<ECDSA sobre método, caminho, ms, nonce e o SHA-256 do corpo>". */
  async sign(method, path, body) {
    const at = String(Date.now() + this.skew);
    const nonce = b64u.enc(globalThis.crypto.getRandomValues(new Uint8Array(16)));
    const digest = hex(await globalThis.crypto.subtle.digest('SHA-256', body ?? new Uint8Array(0)));
    const text = `freesong-web-v1\n${method}\n${path}\n${at}\n${nonce}\n${digest}`;
    const sig = await globalThis.crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, this.proof, te.encode(text));
    return `${at}.${nonce}.${b64u.enc(new Uint8Array(sig))}`;
  }

  async call(method, path, { json, headers = {}, signal, signed = true } = {}, again = true) {
    const body = json !== undefined ? te.encode(JSON.stringify(json)) : undefined;
    let res;
    try {
      res = await fetch(this.base + path, {
        method,
        headers: {
          ...(signed && this.token ? { Authorization: `Bearer ${this.token}` } : {}),
          ...(signed && this.token && this.proof ? { 'X-FreeSong-Sig': await this.sign(method, path, body) } : {}),
          ...(json !== undefined ? { 'Content-Type': 'application/json' } : {}),
          ...headers,
        },
        body,
        signal,
        cache: 'no-store',
        credentials: 'omit',
        referrerPolicy: 'no-referrer',
      });
    } catch (e) {
      if (e?.name === 'AbortError') throw e;
      throw new ApiError(0, 'offline');
    }
    const text = await res.text();
    let out = null;
    try {
      out = text ? JSON.parse(text) : null;
    } catch { /* não é JSON */ }
    if (!res.ok) {
      const code = typeof out?.error === 'string' ? out.error : 'http';
      if (res.status === 401 && code === 'unlinked') this.onUnlinked();
      // O relógio deste computador está fora: o servidor disse a hora dele; assina de novo com ela, uma vez.
      if (res.status === 401 && code === 'bad_proof' && again && Number.isSafeInteger(out?.now)) {
        this.skew = out.now - Date.now();
        return this.call(method, path, { json, headers, signal, signed }, false);
      }
      throw new ApiError(res.status, code, out ?? {});
    }
    return out;
  }

  // ------------------------------------------------------------------ ligar

  startLink(key, proof, name) {
    return this.call('POST', '/v1/link', { json: { key, proof, name }, signed: false });
  }

  pollLink(id, poll, wait = 25, signal) {
    // O segredo da espera num cabeçalho, nunca no endereço (endereços acabam em logs pelo caminho).
    return this.call('GET', `/v1/link/${encodeURIComponent(id)}?wait=${wait}`, { signal, signed: false, headers: { 'X-FreeSong-Poll': poll } });
  }

  recover(code, proof, name) {
    return this.call('POST', '/v1/link/recover', { json: { code, proof, name }, signed: false });
  }

  unlink() {
    return this.call('DELETE', '/v1/link');
  }

  markShared() {
    return this.call('POST', '/v1/link/shared');
  }

  // ------------------------------------------------------------------ o perfil e o cofre

  me() {
    return this.call('GET', '/v1/me');
  }

  rename(name) {
    return this.call('PATCH', '/v1/me', { json: { name } });
  }

  /** O cofre; com [after], só se mudou (esperando até [wait] s); null quando o perfil ainda não tem. */
  async vault(after = null, wait = 0, signal) {
    const qs = after == null ? '' : `?after=${after}${wait ? `&wait=${wait}` : ''}`;
    try {
      return await this.call('GET', `/v1/me/vault${qs}`, { signal });
    } catch (e) {
      if (e instanceof ApiError && e.status === 404 && e.code === 'no_vault') return null;
      throw e;
    }
  }

  putVault(baseRev, data, key) {
    return this.call('PUT', '/v1/me/vault', { json: key ? { baseRev, data, key } : { baseRev, data } });
  }

  /** O que toca agora num aparelho do perfil: {rev, data, at, now} (rev 0: nada); com [after], só se mudou (esperando até [wait] s). */
  now(after = null, wait = 0, signal) {
    const qs = after == null ? '' : `?after=${after}${wait ? `&wait=${wait}` : ''}`;
    return this.call('GET', `/v1/me/now${qs}`, { signal });
  }

  putNow(data) {
    return this.call('PUT', '/v1/me/now', { json: { data } });
  }

  friends() {
    return this.call('GET', '/v1/friends');
  }

  share(to, track) {
    return this.call('POST', '/v1/shares', { json: { to, track } });
  }

  // ------------------------------------------------------------------ o YouTube Music (pela ponte)

  /**
   * O passe da ponte: o relay dá pra este computador (pedido assinado), vale umas horas. A ponte só
   * atende com ele (ninguém de fora usa o servidor como API do YouTube).
   */
  async bridgePass(fresh = false) {
    const now = Date.now() + this.skew;
    if (!fresh && this.pass && this.pass.exp - now > 5 * 60_000) return this.pass.pass;
    if (!fresh && this.passing) return this.passing;
    this.passing = this.call('GET', '/v1/pass').then((r) => {
      this.pass = r;
      return r.pass;
    }).finally(() => { this.passing = null; });
    return this.passing;
  }

  async music(path, params = {}, signal) {
    const qs = new URLSearchParams({ ...params, lang: this.lang });
    for (const [k, v] of [...qs]) if (v === '' || v === 'undefined' || v === 'null') qs.delete(k);
    const go = async (fresh) => this.call('GET', `/api/${path}?${qs}`, { signed: false, signal, headers: { 'X-FreeSong-Pass': await this.bridgePass(fresh) } });
    try {
      return await go(false);
    } catch (e) {
      // O passe venceu ou o relay reiniciou (chave nova): pede outro, uma vez.
      if (e instanceof ApiError && e.status === 401 && e.code === 'pass') return go(true);
      throw e;
    }
  }

  search(q, type = '', signal) { return this.music('search', { q, type }, signal); }
  more(token) { return this.music('more', { token }); }
  suggest(q, signal) { return this.music('suggest', { q }, signal); }
  artist(id) { return this.music(`artist/${id}`); }
  album(id) { return this.music(`album/${id}`); }
  playlist(id) { return this.music(`playlist/${id}`); }
  radio(id) { return this.music(`radio/${id}`); }
  song(id) { return this.music(`song/${id}`); }
  explore() { return this.music('explore'); }
  alternatives(title, artist, not) { return this.music('alt', { title, artist, not }); }
  lyrics(s) { return this.music(`lyrics/${s.videoId}`, { title: s.title, artist: s.artist, album: s.album ?? '', duration: s.durationMs ?? '' }); }
}
