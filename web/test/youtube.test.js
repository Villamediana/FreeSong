// O player do YouTube por mensagens (src/core/youtube.js), sem navegador: um frame de mentira que anota o
// que recebe, e o relógio na mão. O que quebrava o primeiro play depois de recarregar a página:
// comandos velhos esperando na fila, o "estou ouvindo" que parava aos 10 s, um só pedido de "tocar".
import { test, mock, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';

let frames;
globalThis.window = { addEventListener() {}, removeEventListener() {} };
globalThis.location = { origin: 'https://app.test' };
globalThis.document = {
  documentElement: { lang: 'pt-BR' },
  createElement() {
    const f = {
      posted: [],
      removed: false,
      listeners: {},
      contentWindow: { postMessage: (msg) => f.posted.push(JSON.parse(msg)) },
      addEventListener(ev, fn) { f.listeners[ev] = fn; },
      setAttribute() {},
      remove() { f.removed = true; },
    };
    frames.push(f);
    return f;
  },
};
const { YouTubeFrame, STATE, ORIGIN } = await import('../src/core/youtube.js');

const host = { append() {} };
const say = (yt, data) => yt.message({ origin: ORIGIN, source: yt.frame.contentWindow, data: JSON.stringify(data) });
const commands = (f) => f.posted.filter((m) => m.event === 'command' && m.func !== 'addEventListener').map((m) => m.func);
const knocks = (f) => f.posted.filter((m) => m.event === 'listening').length;

beforeEach(() => {
  frames = [];
  mock.timers.enable({ apis: ['setTimeout', 'setInterval'] });
});
afterEach(() => mock.timers.reset());

test('esperando o frame, um vídeo novo tira os comandos velhos da fila', () => {
  const yt = new YouTubeFrame(host);
  yt.warm();
  yt.load('aaaaaaaaaaa', 30, false); // a fila que voltou ao recarregar, pausada
  yt.seek(40);
  yt.load('bbbbbbbbbbb', 0, true); // o clique numa música
  frames[0].listeners.load();
  say(yt, { event: 'onReady' });
  assert.deepEqual(commands(frames[0]), ['loadVideoById']);
  assert.equal(frames[0].posted.find((m) => m.func === 'loadVideoById').args[0].videoId, 'bbbbbbbbbbb');
});

test('o "estou ouvindo" continua depois de 10 s até o frame responder', () => {
  const yt = new YouTubeFrame(host);
  yt.warm();
  frames[0].listeners.load();
  mock.timers.tick(10_000);
  const at10 = knocks(frames[0]);
  mock.timers.tick(5_000);
  assert.ok(knocks(frames[0]) >= at10 + 4, 'segue batendo a cada segundo');
  say(yt, { event: 'onReady' });
  const ready = knocks(frames[0]);
  mock.timers.tick(5_000);
  assert.equal(knocks(frames[0]), ready, 'pronto: para');
});

test('um frame que não fica pronto é refeito, e o vídeo pedido vai pro novo', () => {
  const yt = new YouTubeFrame(host);
  yt.warm();
  yt.load('ccccccccccc', 0, true);
  mock.timers.tick(8_000);
  assert.equal(frames.length, 2);
  assert.ok(frames[0].removed);
  frames[1].listeners.load();
  say(yt, { event: 'onReady' });
  assert.deepEqual(commands(frames[1]), ['loadVideoById']);
});

test('mandou tocar e não começou: pede de novo, logo; tocando, não pede mais', () => {
  const yt = new YouTubeFrame(host);
  yt.warm();
  frames[0].listeners.load();
  say(yt, { event: 'onReady' });
  say(yt, { event: 'onStateChange', info: STATE.PLAYING }); // a música de antes tocava
  yt.load('ddddddddddd', 0, true);
  mock.timers.tick(1_000);
  assert.deepEqual(commands(frames[0]), ['loadVideoById', 'playVideo'], 'o "tocando" da música de antes não segura o pedido');
  say(yt, { event: 'onStateChange', info: STATE.PLAYING });
  mock.timers.tick(5_000);
  assert.deepEqual(commands(frames[0]), ['loadVideoById', 'playVideo']);
});

test('depois de um play barrado pelo navegador, o próximo vídeo vai com um "tocar" junto', () => {
  const yt = new YouTubeFrame(host);
  yt.warm();
  frames[0].listeners.load();
  say(yt, { event: 'onReady' });
  say(yt, { event: 'onAutoplayBlocked' });
  yt.load('eeeeeeeeeee', 0, true);
  assert.deepEqual(commands(frames[0]), ['loadVideoById', 'playVideo']);
});
