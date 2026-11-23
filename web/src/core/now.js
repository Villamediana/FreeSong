// O mini player junto com o do celular: o que toca aqui vai pro relay (fechado com a chave do cofre,
// o relay não lê) e o que toca no celular chega aqui. Quem está parado mostra o que o outro tocou por
// último, pausado no ponto em que está — é só dar play pra continuar. Quem está tocando não muda nada.
//
// Vai pro relay: começou uma música, deu play ou pausa, pulou pra outro ponto. Não vai: uma música só
// deixada pronta (a do celular, ou a desta aba ao recarregar) — senão um ficaria mandando de volta pro
// outro o que acabou de receber.
import { ApiError } from './api.js';
import { openNow, sealNow } from './crypto.js';

/** {v, dev, i, t, a, al, d, c, p, s, at}: o que toca, de que aparelho, em que ponto, se tocando. */
export function nowOf(song, positionMs, playing, dev, at) {
  return {
    v: 1,
    dev,
    i: song.videoId,
    t: song.title ?? '',
    a: song.artist ?? '',
    al: song.album ?? null,
    d: Math.max(0, Math.round(song.durationMs ?? 0)),
    c: typeof song.cover === 'string' && /^https:\/\//.test(song.cover) ? song.cover : null,
    p: Math.max(0, Math.round(positionMs)),
    s: !!playing,
    at,
  };
}

/** Uma música do jeito que o player usa, a partir do que chegou. */
export function songOf(n) {
  return {
    videoId: n.i,
    title: n.t || '…',
    artist: n.a ?? '',
    artists: [],
    album: n.al ?? null,
    durationMs: n.d || null,
    cover: n.c ?? `https://i.ytimg.com/vi/${n.i}/hqdefault.jpg`,
  };
}

/** Em que ponto está agora o que chegou (tocando: o ponto andou desde que foi mandado). */
export function positionNow(n, elapsedMs) {
  if (!n.s) return n.p;
  const p = n.p + Math.max(0, elapsedMs);
  return n.d > 0 ? Math.min(p, Math.max(0, n.d - 3000)) : p;
}

export class NowShare {
  constructor({ api, key, player, dev, onRemote = () => {}, clock = () => Date.now() }) {
    Object.assign(this, { api, key, player, dev, onRemote, clock });
    this.rev = null;
    this.running = false;
    this.lastKey = '';
    this.lastPlaying = false;
    this.seekSeen = 0;
  }

  /** Começa: escuta o player daqui e espera o que vier do celular. */
  start() {
    this.unsub = this.player.subscribe(() => this.look());
    this.run();
  }

  look() {
    const s = this.player.current;
    const playing = this.player.playing;
    const key = s ? `${s.videoId}|${playing}` : '';
    const seeked = this.player.seeks !== this.seekSeen;
    this.seekSeen = this.player.seeks;
    if (key === this.lastKey && !(seeked && playing)) return;
    const was = this.lastPlaying;
    this.lastKey = key;
    this.lastPlaying = playing;
    if (!s || !(playing || was)) return;
    clearTimeout(this.soon);
    this.soon = setTimeout(() => this.publish(), 400);
  }

  async publish() {
    const s = this.player.current;
    if (!s) return;
    const n = nowOf(s, this.player.time * 1000, this.player.playing, this.dev, this.clock());
    try {
      const r = await this.api.putNow(await sealNow(n, this.key));
      this.rev = r.rev;
    } catch { /* sem rede: a próxima mudança manda */ }
  }

  async run() {
    if (this.running) return;
    this.running = true;
    let pause = 1000;
    while (this.running) {
      try {
        this.abort = new AbortController();
        const r = await this.api.now(this.rev, this.rev == null ? 0 : 25, this.abort.signal);
        pause = 1000;
        if (r.same) continue;
        const first = this.rev == null;
        this.rev = r.rev;
        if (!r.data) {
          if (first) continue;
          await wait(1000);
          continue;
        }
        let n;
        try {
          n = await openNow(r.data, this.key);
        } catch {
          continue;
        }
        if (!n || typeof n.i !== 'string' || !/^[A-Za-z0-9_-]{11}$/.test(n.i) || n.dev === this.dev) continue;
        this.onRemote(n, positionNow(n, (r.now ?? 0) - (r.at ?? 0)));
      } catch (e) {
        if (!this.running) return;
        if (e instanceof ApiError && e.status === 401) return;
        await wait(pause);
        pause = Math.min(pause * 2, 30_000);
      }
    }
  }

  stop() {
    this.running = false;
    this.abort?.abort();
    this.unsub?.();
    clearTimeout(this.soon);
  }
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms));
