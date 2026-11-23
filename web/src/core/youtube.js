// O player do YouTube num frame (youtube-nocookie.com), comandado por mensagens — o mesmo protocolo
// que a iframe_api.js do YouTube usa, sem carregar script de fora na página (a CSP não deixa, e nenhum
// script de terceiro roda com acesso ao cofre). O som e a imagem tocam dentro do frame; daqui saem só
// os comandos (tocar, pausar, pular pra, volume) e chegam o tempo, o estado e os erros.
//
// Estados (os do YouTube): -1 não começou, 0 acabou, 1 tocando, 2 pausado, 3 carregando, 5 pronto.
// Erros: 2 id inválido, 5 erro do HTML5, 100 não existe, 101/150 o dono não deixa tocar fora do YouTube,
// 153 o YouTube não recebeu o endereço da página (o frame manda a origem: referrerpolicy).

export const ORIGIN = 'https://www.youtube-nocookie.com';
export const STATE = { UNSTARTED: -1, ENDED: 0, PLAYING: 1, PAUSED: 2, BUFFERING: 3, CUED: 5 };

// Comandos que um vídeo novo torna velhos (esperando o frame ficar pronto, só o último vale).
const LOADS = new Set(['loadVideoById', 'cueVideoById']);
const SUPERSEDED = new Set(['loadVideoById', 'cueVideoById', 'playVideo', 'pauseVideo', 'stopVideo', 'seekTo']);
// Mandou tocar e não começou: pede de novo nestes momentos (ms). O frame que não responde é refeito.
const NUDGES_MS = [900, 2200, 4500];
const REBUILD_AFTER_MS = 8000;

export class YouTubeFrame {
  /**
   * [host]: o elemento onde o frame mora (nunca muda de lugar no DOM: mover um iframe recarrega ele).
   * [controls]: os controles do próprio YouTube (o FreeVideo usa; o FreeSong tem os seus).
   */
  constructor(host, { controls = false, onState = () => {}, onTime = () => {}, onError = () => {}, onReady = () => {}, onBlocked = () => {} } = {}) {
    Object.assign(this, { host, controls, onState, onTime, onError, onReady, onBlocked });
    this.frame = null;
    this.ready = false;
    this.queue = [];
    this.state = STATE.UNSTARTED;
    this.time = 0;
    this.duration = 0;
    this.videoId = null;
    this.id = 1;
    this.nudges = [];
    this.rebuilds = 0;
    this.wasBlocked = false; // o navegador já barrou um play sozinho aqui (o próximo vai com um "tocar" junto)
    this.listen = (e) => this.message(e);
    window.addEventListener('message', this.listen);
  }

  /** O endereço do frame, sem vídeo: os vídeos chegam depois, por comando. */
  src() {
    const p = new URLSearchParams({
      enablejsapi: '1',
      origin: location.origin,
      widget_referrer: location.origin,
      playsinline: '1',
      rel: '0',
      iv_load_policy: '3',
      modestbranding: '1',
      controls: this.controls ? '1' : '0',
      disablekb: this.controls ? '0' : '1',
      fs: this.controls ? '1' : '0',
      cc_load_policy: '0',
      hl: document.documentElement.lang || 'pt-BR',
    });
    return `${ORIGIN}/embed/?${p}`;
  }

  /**
   * Cria o frame vazio, já pronto pra tocar quando a pessoa pedir. Tocar por comando num frame pronto
   * funciona sempre; um frame que nasce com autoplay (ainda invisível, no clique) o YouTube às vezes
   * deixa parado — era a música que só tocava no segundo clique.
   */
  warm() {
    if (this.frame) return;
    const f = document.createElement('iframe');
    f.title = 'YouTube';
    f.allow = 'autoplay; encrypted-media; fullscreen; picture-in-picture';
    f.allowFullscreen = true;
    // A página não manda Referer pra ninguém; o player do YouTube precisa saber de onde é (erro 153).
    f.referrerPolicy = 'strict-origin-when-cross-origin';
    f.setAttribute('frameborder', '0');
    f.src = this.src();
    f.addEventListener('load', () => this.hello());
    this.frame = f;
    this.host.append(f);
  }

  /** Toca [videoId] de [start] segundos ([autoplay] false: deixa pronto, pausado ali). */
  load(videoId, start = 0, autoplay = true) {
    this.videoId = videoId;
    this.time = start;
    this.duration = 0;
    this.failed = null;
    // O estado do vídeo de antes não diz nada deste (um "tocando" velho seguraria os pedidos de novo).
    this.state = STATE.UNSTARTED;
    this.warm();
    this.command(autoplay ? 'loadVideoById' : 'cueVideoById', [{ videoId, startSeconds: start }]);
    // Depois de um play barrado, o YouTube só deixa o vídeo pronto: vai um "tocar" junto (agora houve clique).
    if (autoplay && this.wasBlocked) this.command('playVideo');
    // Mandou tocar e não começou nem deu erro: pede de novo, logo e mais algumas vezes.
    this.armNudges(autoplay ? videoId : null);
    this.watchdog();
  }

  armNudges(videoId) {
    for (const t of this.nudges) clearTimeout(t);
    this.nudges = videoId ? NUDGES_MS.map((ms) => setTimeout(() => this.nudge(videoId), ms)) : [];
  }

  /** O frame que não ficou pronto a tempo (carregou pela metade, a rede piscou) é feito de novo. */
  watchdog() {
    if (this.ready) return;
    clearTimeout(this.rebuildTimer);
    this.rebuildTimer = setTimeout(() => {
      if (this.ready || !this.frame || this.rebuilds >= 2) return;
      this.rebuilds++;
      clearInterval(this.knock);
      this.frame.remove();
      this.frame = null;
      this.warm(); // os comandos esperando continuam na fila
      this.watchdog();
    }, REBUILD_AFTER_MS);
  }

  nudge(videoId) {
    // Sem frame pronto o vídeo ainda está na fila (os pedidos recomeçam quando ele ficar pronto).
    if (!this.ready || this.videoId !== videoId || this.failed === videoId) return;
    if (this.state === STATE.PLAYING || this.state === STATE.BUFFERING || this.state === STATE.ENDED) return;
    this.command('playVideo');
  }

  /** Avisa o frame que estamos ouvindo (até ele responder). */
  hello() {
    this.ready = false;
    let n = 0;
    clearInterval(this.knock);
    // A cada 250 ms nos primeiros 10 s; depois a cada segundo, até responder (antes parava aos 10 s e o
    // frame lento nunca mais tocava nada).
    this.knock = setInterval(() => {
      if (this.ready || !this.frame) return clearInterval(this.knock);
      if (++n > 40 && n % 4) return;
      this.post({ event: 'listening', id: this.id, channel: 'widget' });
    }, 250);
    this.post({ event: 'listening', id: this.id, channel: 'widget' });
  }

  post(msg) {
    this.frame?.contentWindow?.postMessage(JSON.stringify(msg), ORIGIN);
  }

  command(func, args = []) {
    if (!this.ready) {
      if (LOADS.has(func)) this.queue = this.queue.filter(([f]) => !SUPERSEDED.has(f));
      this.queue.push([func, args]);
      return;
    }
    this.post({ event: 'command', func, args, id: this.id, channel: 'widget' });
  }

  message(e) {
    if (e.origin !== ORIGIN || e.source !== this.frame?.contentWindow) return;
    let m;
    try {
      m = typeof e.data === 'string' ? JSON.parse(e.data) : e.data;
    } catch {
      return;
    }
    if (!m || typeof m !== 'object') return;
    if (!this.ready && (m.event === 'onReady' || m.event === 'initialDelivery' || m.event === 'infoDelivery')) {
      this.ready = true;
      this.rebuilds = 0;
      clearInterval(this.knock);
      clearTimeout(this.rebuildTimer);
      for (const ev of ['onReady', 'onStateChange', 'onError', 'onPlaybackRateChange', 'onAutoplayBlocked']) {
        this.post({ event: 'command', func: 'addEventListener', args: [ev], id: this.id, channel: 'widget' });
      }
      const waiting = this.queue.splice(0);
      for (const [func, args] of waiting) this.command(func, args);
      if (waiting.some(([func]) => func === 'loadVideoById')) this.armNudges(this.videoId);
      this.onReady();
    }
    if (m.event === 'onStateChange' && Number.isInteger(m.info)) this.setState(m.info);
    else if (m.event === 'onError') {
      this.failed = this.videoId;
      this.onError(Number(m.info));
    }
    else if (m.event === 'onAutoplayBlocked') {
      this.wasBlocked = true;
      this.onBlocked();
    }
    else if ((m.event === 'infoDelivery' || m.event === 'initialDelivery') && m.info && typeof m.info === 'object') {
      const i = m.info;
      if (Number.isFinite(i.duration) && i.duration > 0) this.duration = i.duration;
      if (Number.isFinite(i.currentTime)) {
        this.time = i.currentTime;
        this.onTime(this.time, this.duration);
      }
      if (Number.isInteger(i.playerState)) this.setState(i.playerState);
      const vid = i.videoData?.video_id;
      if (typeof vid === 'string' && vid && vid !== this.videoId && /^[A-Za-z0-9_-]{11}$/.test(vid)) this.playing = vid;
    }
  }

  setState(s) {
    if (s === STATE.PLAYING) this.wasBlocked = false;
    if (s === this.state) return;
    this.state = s;
    this.onState(s);
  }

  play() {
    this.failed = null;
    this.command('playVideo');
  }
  pause() { this.command('pauseVideo'); }
  stop() { this.command('stopVideo'); }
  seek(seconds) {
    this.time = seconds;
    this.command('seekTo', [seconds, true]);
    this.onTime(this.time, this.duration);
  }
  volume(v) { this.command('setVolume', [Math.round(Math.max(0, Math.min(100, v)))]); }
  mute(on) { this.command(on ? 'mute' : 'unMute'); }
  rate(r) { this.command('setPlaybackRate', [r]); }

  destroy() {
    clearInterval(this.knock);
    clearTimeout(this.rebuildTimer);
    for (const t of this.nudges) clearTimeout(t);
    window.removeEventListener('message', this.listen);
    this.frame?.remove();
    this.frame = null;
  }
}
