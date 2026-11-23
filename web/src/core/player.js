// O que toca: a fila (de onde veio, qual é a atual), embaralhar e repetir, o tempo de cada música, e
// o que foi ouvido indo pro cofre (como no celular: concluída ≥ 90 %, pulada < 30 s ou < 1/3). Quando a
// fila acaba, continua sozinha com o rádio da última música (como o app). Uma música que o dono não
// deixa tocar fora do YouTube tenta outro vídeo da mesma música; sem nenhum, passa pra próxima.
import { STATE, YouTubeFrame } from './youtube.js';

const RESTART_AFTER_S = 4;
// Pediu pra tocar e nada em tanto tempo: aparece "Clique para tocar" (em vez de girar pra sempre).
const STUCK_AFTER_MS = 12_000;

export class Player {
  constructor({ host, api, onListen = () => {}, onToast = () => {}, prefs = {}, savePrefs = () => {} }) {
    Object.assign(this, { api, onListen, onToast, savePrefs });
    this.items = []; // a fila: músicas da página
    this.index = -1;
    this.source = null; // {kind, id, title}: de onde a fila veio
    this.order = null; // embaralhado: a ordem dos índices
    this.shuffle = !!prefs.shuffle;
    this.repeat = prefs.repeat === 'all' || prefs.repeat === 'one' ? prefs.repeat : 'off';
    this.vol = Number.isFinite(prefs.volume) ? prefs.volume : 80;
    this.muted = !!prefs.muted;
    this.state = STATE.UNSTARTED;
    this.time = 0;
    this.duration = 0;
    this.version = 0;
    this.listeners = new Set();
    this.listen = null; // {song, startedAt, ms, lastTick}
    this.tried = new Map(); // videoId pedido → vídeos já tentados no lugar
    this.playingId = null; // o vídeo no frame (pode ser um alternativo)
    this.extending = false;
    this.frame = new YouTubeFrame(host, {
      onState: (s) => this.onState(s),
      onTime: (t, d) => this.onTime(t, d),
      onError: (code) => this.onError(code),
      onReady: () => {
        this.frame.volume(this.vol);
        this.frame.mute(this.muted);
      },
      onBlocked: () => this.set({ blocked: true }),
    });
    this.blocked = false;
    this.wanted = false; // a pessoa pediu pra tocar (até pausar ou acabar)
    this.failures = 0; // músicas seguidas que não tocaram (o YouTube recusando tudo: para de pular)
    this.lastActive = 0; // a última vez que a pessoa tocou, pausou, pulou ou trocou de música aqui (ms)
    this.seeks = 0; // quantas vezes pulou pra outro ponto (o celular fica sabendo)
    // O frame do YouTube já pronto: o primeiro play é um comando, como os outros.
    this.frame.warm();
  }

  subscribe(fn) {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  set(change = {}) {
    Object.assign(this, change);
    this.version++;
    for (const f of this.listeners) f();
  }

  get current() {
    return this.items[this.index] ?? null;
  }

  get playing() {
    return this.state === STATE.PLAYING || this.state === STATE.BUFFERING;
  }

  /** Pediu pra tocar e ainda não começou (o botão gira em vez de parecer parado). */
  get loading() {
    return !!this.current && !this.blocked && (this.state === STATE.BUFFERING || (this.state === STATE.UNSTARTED && this.wanted));
  }

  /** As próximas, na ordem em que vão tocar. */
  get upcoming() {
    if (this.index < 0) return [];
    const order = this.orderList();
    const at = order.indexOf(this.index);
    return order.slice(at + 1).map((i) => ({ song: this.items[i], index: i }));
  }

  orderList() {
    return this.shuffle && this.order?.length === this.items.length ? this.order : this.items.map((_, i) => i);
  }

  // ------------------------------------------------------------------ comandos

  /** Toca [list] a partir de [start] (índice), vindo de [source]. */
  playList(list, start = 0, source = null) {
    const songs = list.filter((s) => s?.videoId);
    if (!songs.length) return;
    this.finishListen();
    this.items = songs.map((s) => ({ ...s }));
    this.source = source;
    this.index = Math.max(0, Math.min(start, songs.length - 1));
    this.order = this.shuffle ? shuffled(this.items.length, this.index) : null;
    this.tried.clear();
    this.start();
  }

  /** Uma música só: ela e o rádio dela depois. */
  async playRadio(song, source = null) {
    this.playList([song], 0, source ?? { kind: 'radio', id: song.videoId, title: song.title });
    try {
      const r = await this.api.radio(song.videoId);
      if (this.current?.videoId !== song.videoId) return;
      const more = r.tracks.filter((t) => t.videoId !== song.videoId);
      this.append(more);
    } catch { /* sem rádio: toca só ela */ }
  }

  playNext(songs) {
    const list = (Array.isArray(songs) ? songs : [songs]).filter(Boolean);
    if (!list.length) return;
    if (this.index < 0) return this.playList(list, 0, null);
    this.items.splice(this.index + 1, 0, ...list);
    if (this.order) {
      const at = this.order.indexOf(this.index);
      const shiftedOrder = this.order.map((i) => (i > this.index ? i + list.length : i));
      shiftedOrder.splice(at + 1, 0, ...list.map((_, k) => this.index + 1 + k));
      this.order = shiftedOrder;
    }
    this.set();
  }

  append(songs) {
    const list = (Array.isArray(songs) ? songs : [songs]).filter(Boolean);
    if (!list.length) return;
    if (this.index < 0) return this.playList(list, 0, null);
    const from = this.items.length;
    this.items.push(...list);
    if (this.order) this.order.push(...list.map((_, k) => from + k));
    this.set();
  }

  removeAt(i) {
    if (i === this.index || i < 0 || i >= this.items.length) return;
    this.items.splice(i, 1);
    if (this.order) this.order = this.order.filter((x) => x !== i).map((x) => (x > i ? x - 1 : x));
    if (i < this.index) this.index--;
    this.set();
  }

  jump(i) {
    if (i < 0 || i >= this.items.length) return;
    this.finishListen();
    this.index = i;
    this.start();
  }

  toggle() {
    if (this.index < 0) return;
    this.lastActive = Date.now();
    if (this.playing) this.frame.pause();
    else {
      this.set({ blocked: false, wanted: true });
      if (this.state === STATE.ENDED) this.frame.seek(0);
      this.frame.play();
      this.watchStart(this.current);
    }
  }

  play() { if (!this.playing) this.toggle(); }
  pause() { if (this.playing) this.toggle(); }

  next(auto = false) {
    if (this.index < 0) return;
    if (auto && this.repeat === 'one') {
      this.finishListen();
      this.start();
      return;
    }
    const order = this.orderList();
    const at = order.indexOf(this.index);
    if (at + 1 < order.length) {
      this.finishListen();
      this.index = order[at + 1];
      this.start();
      return;
    }
    if (this.repeat === 'all' && this.items.length) {
      this.finishListen();
      if (this.shuffle) this.order = shuffled(this.items.length, -1);
      this.index = this.orderList()[0];
      this.start();
      return;
    }
    // O fim da fila: continua com o rádio da última (como o app); se não vier nada, para.
    this.extend().then((added) => {
      if (added) this.next(auto);
      else if (!auto) this.frame.pause();
    });
  }

  prev() {
    if (this.index < 0) return;
    if (this.time > RESTART_AFTER_S) {
      this.seek(0);
      return;
    }
    const order = this.orderList();
    const at = order.indexOf(this.index);
    if (at > 0) {
      this.finishListen();
      this.index = order[at - 1];
      this.start();
    } else this.seek(0);
  }

  seek(seconds) {
    if (this.index < 0) return;
    const s = Math.max(0, Math.min(seconds, this.duration || seconds));
    this.lastActive = Date.now();
    this.seeks++;
    this.frame.seek(s);
    this.set({ time: s });
  }

  setVolume(v) {
    const vol = Math.round(Math.max(0, Math.min(100, v)));
    this.frame.volume(vol);
    if (this.muted && vol > 0) this.frame.mute(false);
    this.set({ vol, muted: vol === 0 ? this.muted : false });
    this.keep();
  }

  toggleMute() {
    this.frame.mute(!this.muted);
    this.set({ muted: !this.muted });
    this.keep();
  }

  toggleShuffle() {
    const on = !this.shuffle;
    this.set({ shuffle: on, order: on && this.items.length ? shuffled(this.items.length, this.index) : null });
    this.keep();
  }

  cycleRepeat() {
    this.set({ repeat: this.repeat === 'off' ? 'all' : this.repeat === 'all' ? 'one' : 'off' });
    this.keep();
  }

  keep() {
    this.savePrefs({ shuffle: this.shuffle, repeat: this.repeat, volume: this.vol, muted: this.muted });
  }

  // ------------------------------------------------------------------ por dentro

  start() {
    const s = this.current;
    if (!s) return;
    const tried = this.tried.get(s.videoId);
    const id = tried?.at(-1) ?? s.videoId;
    this.playingId = id;
    this.lastActive = Date.now();
    this.set({ time: 0, duration: (s.durationMs ?? 0) / 1000, state: STATE.BUFFERING, blocked: false, wanted: true });
    this.frame.load(id, 0, true);
    this.listen = { song: s, ms: 0, lastTick: null, at: Date.now() };
    this.watchStart(s);
    this.prefetch();
  }

  /** Se [s] não começar a tocar a tempo, a pessoa vê "Clique para tocar" (um play dela sempre vale). */
  watchStart(s) {
    clearTimeout(this.stuckTimer);
    this.stuckTimer = setTimeout(() => {
      if (this.current === s && this.state !== STATE.PLAYING && this.state !== STATE.PAUSED && this.state !== STATE.ENDED) this.set({ blocked: true });
    }, STUCK_AFTER_MS);
  }

  /**
   * Deixa [list] na fila com a [index] pronta em [startS] segundos, pausada ([play]: tenta já tocar):
   * o que tocava no celular, ou o que tocava nesta aba antes de recarregar. [at]: quando aquilo tocou.
   */
  cue(list, index = 0, startS = 0, source = null, { at = Date.now(), play = false, order = null } = {}) {
    const songs = list.filter((s) => s?.videoId);
    if (!songs.length) return;
    this.finishListen();
    this.items = songs.map((s) => ({ ...s }));
    this.source = source;
    this.index = Math.max(0, Math.min(index, songs.length - 1));
    const valid = Array.isArray(order) && order.length === songs.length && new Set(order).size === songs.length && order.every((i) => Number.isInteger(i) && i >= 0 && i < songs.length);
    this.order = this.shuffle ? (valid ? order : shuffled(songs.length, this.index)) : null;
    this.tried.clear();
    const s = this.current;
    const start = Math.max(0, startS);
    this.playingId = s.videoId;
    this.lastActive = at;
    this.set({ time: start, duration: (s.durationMs ?? 0) / 1000, state: play ? STATE.BUFFERING : STATE.CUED, blocked: false, wanted: play });
    this.frame.load(s.videoId, start, play);
    if (play) this.watchStart(s);
    this.listen = { song: s, ms: 0, lastTick: null, at: Date.now() };
    this.prefetch();
  }

  /** A fila como está (pra voltar igual depois de recarregar a página). */
  snapshot() {
    if (this.index < 0 || !this.current) return null;
    // Uma fila enorme fica com as 300 em volta da que toca.
    const from = Math.max(0, Math.min(this.index - 100, this.items.length - 300));
    const items = this.items.slice(from, from + 300);
    const keep = items.length === this.items.length;
    return {
      v: 1,
      items,
      index: this.index - from,
      source: this.source,
      order: keep ? this.order : null,
      time: this.time,
      playing: this.playing,
      at: this.lastActive || Date.now(),
      saved: Date.now(),
    };
  }

  /** Quando faltam poucas na fila, já busca o rádio (a próxima não espera a rede). */
  prefetch() {
    if (this.repeat !== 'off') return;
    if (this.upcoming.length <= 1 && this.items.length) this.extend();
  }

  async extend() {
    if (this.extending) return false;
    const last = this.items.at(-1);
    if (!last) return false;
    this.extending = true;
    try {
      const r = await this.api.radio(last.videoId);
      const known = new Set(this.items.map((s) => s.videoId));
      const fresh = r.tracks.filter((t) => !known.has(t.videoId)).slice(0, 25);
      if (fresh.length) this.append(fresh);
      return fresh.length > 0;
    } catch {
      return false;
    } finally {
      this.extending = false;
    }
  }

  onState(s) {
    this.set({ state: s });
    if (s === STATE.PLAYING) {
      this.blocked = false;
      this.failures = 0;
      clearTimeout(this.stuckTimer);
    }
    if (s === STATE.PAUSED || s === STATE.ENDED) this.wanted = false;
    if (s !== STATE.PLAYING && this.listen) this.listen.lastTick = null;
    if (s === STATE.ENDED) this.next(true);
  }

  onTime(t, d) {
    if (this.listen && this.state === STATE.PLAYING) {
      const now = performance.now();
      if (this.listen.lastTick != null) {
        const step = now - this.listen.lastTick;
        // Só o tempo tocando de verdade (uma aba parada, um pulo no tempo, não contam).
        if (step > 0 && step < 5000) this.listen.ms += step;
      }
      this.listen.lastTick = now;
    }
    this.set({ time: t, duration: d || this.duration });
  }

  async onError(code) {
    const s = this.current;
    if (!s) return;
    // O dono não deixa tocar fora do YouTube (101/150) ou o vídeo sumiu: tenta outro da mesma música.
    if (code === 101 || code === 150 || code === 100 || code === 5) {
      const tried = this.tried.get(s.videoId) ?? [s.videoId];
      if (tried.length < 4) {
        try {
          const alt = await this.api.alternatives(s.title, s.artist, s.videoId);
          if (this.current !== s) return;
          const pick = alt.videos.find((v) => !tried.includes(v.videoId) && (!s.durationMs || !v.durationMs || Math.abs(v.durationMs - s.durationMs) < 30_000));
          if (pick) {
            this.tried.set(s.videoId, [...tried, pick.videoId]);
            this.start();
            return;
          }
        } catch { /* sem alternativa */ }
      }
    }
    this.listen = null;
    // Três seguidas sem tocar: o YouTube não está deixando tocar aqui agora — para em vez de pular a fila toda.
    if (++this.failures >= 3) {
      this.failures = 0;
      this.onToast('youtubeRefuses', s);
      this.set({ state: STATE.PAUSED });
      return;
    }
    this.onToast('cantPlay', s);
    setTimeout(() => { if (this.current === s) this.next(true); }, 800);
  }

  /** A música atual deixou de tocar (outra começou, a fila mudou): o que foi ouvido vai pro cofre. */
  finishListen() {
    const l = this.listen;
    this.listen = null;
    if (!l || l.ms < 1000) return;
    const durationMs = (this.duration || (l.song.durationMs ?? 0) / 1000) * 1000;
    this.onListen(l.song, l.ms, durationMs);
  }
}

/** Uma ordem embaralhada de [n] índices, com [first] na frente (a que está tocando segue). */
function shuffled(n, first) {
  const rest = [...Array(n).keys()].filter((i) => i !== first);
  for (let i = rest.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [rest[i], rest[j]] = [rest[j], rest[i]];
  }
  return first >= 0 ? [first, ...rest] : rest;
}
