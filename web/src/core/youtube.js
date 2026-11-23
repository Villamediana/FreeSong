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
    this.listen = (e) => this.message(e);
    window.addEventListener('message', this.listen);
  }

  src(videoId, start = 0, autoplay = true) {
    const p = new URLSearchParams({
      enablejsapi: '1',
      origin: location.origin,
      widget_referrer: location.origin,
      autoplay: autoplay ? '1' : '0',
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
    if (start > 0) p.set('start', String(Math.floor(start)));
    return `${ORIGIN}/embed/${videoId}?${p}`;
  }

  /** Toca [videoId] (de [start] segundos): o primeiro cria o frame, os outros trocam o vídeo nele. */
  load(videoId, start = 0, autoplay = true) {
    this.videoId = videoId;
    this.time = start;
    this.duration = 0;
    if (!this.frame) {
      const f = document.createElement('iframe');
      f.title = 'YouTube';
      f.allow = 'autoplay; encrypted-media; fullscreen; picture-in-picture';
      f.allowFullscreen = true;
      // A página não manda Referer pra ninguém; o player do YouTube precisa saber de onde é (erro 153).
      f.referrerPolicy = 'strict-origin-when-cross-origin';
      f.setAttribute('frameborder', '0');
      f.src = this.src(videoId, start, autoplay);
      f.addEventListener('load', () => this.hello());
      this.frame = f;
      this.host.append(f);
      return;
    }
    if (!this.ready) {
      this.frame.src = this.src(videoId, start, autoplay);
      return;
    }
    this.command(autoplay ? 'loadVideoById' : 'cueVideoById', [{ videoId, startSeconds: start }]);
  }

  /** Avisa o frame que estamos ouvindo (até ele responder). */
  hello() {
    this.ready = false;
    let n = 0;
    clearInterval(this.knock);
    this.knock = setInterval(() => {
      if (this.ready || ++n > 40) return clearInterval(this.knock);
      this.post({ event: 'listening', id: this.id, channel: 'widget' });
    }, 250);
    this.post({ event: 'listening', id: this.id, channel: 'widget' });
  }

  post(msg) {
    this.frame?.contentWindow?.postMessage(JSON.stringify(msg), ORIGIN);
  }

  command(func, args = []) {
    if (!this.ready) {
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
      clearInterval(this.knock);
      for (const ev of ['onReady', 'onStateChange', 'onError', 'onPlaybackRateChange', 'onAutoplayBlocked']) {
        this.post({ event: 'command', func: 'addEventListener', args: [ev], id: this.id, channel: 'widget' });
      }
      const waiting = this.queue.splice(0);
      for (const [func, args] of waiting) this.command(func, args);
      this.onReady();
    }
    if (m.event === 'onStateChange' && Number.isInteger(m.info)) this.setState(m.info);
    else if (m.event === 'onError') this.onError(Number(m.info));
    else if (m.event === 'onAutoplayBlocked') this.onBlocked();
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
    if (s === this.state) return;
    this.state = s;
    this.onState(s);
  }

  play() { this.command('playVideo'); }
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
    window.removeEventListener('message', this.listen);
    this.frame?.remove();
    this.frame = null;
  }
}
