// Jams: todo mundo ouvindo a mesma fila ao mesmo tempo. Vivem só na memória do servidor.
//
// Regras (pedido do Miguel):
// - Só se convida AMIGO. Quem está na jam pode convidar os próprios amigos (amigo de amigo entra
//   pelo convite do amigo); ninguém começa uma jam direto com quem não é amigo.
// - Qualquer participante toca, pausa, pula, adianta ou mexe na fila: muda pra todo mundo.
// - Quem criou encerra; qualquer um sai. Se quem criou sai, a jam acaba.
// - Uma jam por pessoa: entrar numa nova sai da anterior.
import { randomBytes } from 'node:crypto';
import { cleanText } from './names.js';

export const MAX_QUEUE = 200;
export const MAX_MEMBERS = 30;
/** Jam sem ninguém conectado por esse tempo acaba sozinha. */
export const IDLE_END_MS = 15 * 60 * 1000;
const MAX_TRACK_MS = 6 * 3600_000;

const newJamId = () => 'j' + randomBytes(8).toString('base64url').replace(/[-_]/g, 'x').toLowerCase();

// Capa e link vão parar no celular dos amigos (e de quem está na jam). Só os servidores que o app
// usa: uma capa em servidor qualquer faria o celular de todo mundo bater lá e entregar o IP.
const COVER_HOST = /^(i\d?\.ytimg\.com|[a-z0-9-]+\.googleusercontent\.com|[a-z0-9-]+\.ggpht\.com)$/;
const LINK_HOST = /^((www|m|music)\.)?youtube\.com$|^youtu\.be$/;

function httpsUrl(v, hostPattern) {
  if (typeof v !== 'string' || v.length > 500) return undefined;
  let u;
  try {
    u = new URL(v);
  } catch {
    return undefined;
  }
  if (u.protocol !== 'https:' || u.username || u.password || u.port || !hostPattern.test(u.hostname)) return undefined;
  return u.href;
}

/** Uma música da fila como o app manda: o vídeo e o que o player mostra. Null se não serve. */
/** Uma capa só dos servidores que o app usa (ou nada). */
export const cleanCover = (v) => httpsUrl(v, COVER_HOST);

export function cleanTrack(raw) {
  if (!raw || typeof raw !== 'object') return null;
  const videoId = typeof raw.videoId === 'string' && /^[A-Za-z0-9_-]{6,20}$/.test(raw.videoId) ? raw.videoId : null;
  const title = cleanText(raw.title, 200);
  if (!videoId || !title) return null;
  const str = (v, max) => cleanText(v, max) || undefined;
  const durationMs = Number.isFinite(raw.durationMs) && raw.durationMs > 0 && raw.durationMs < MAX_TRACK_MS ? Math.round(raw.durationMs) : undefined;
  return {
    videoId,
    title,
    artist: str(raw.artist, 200) ?? '',
    album: str(raw.album, 200),
    durationMs,
    cover: httpsUrl(raw.cover, COVER_HOST),
    url: httpsUrl(raw.url, LINK_HOST),
  };
}

/**
 * Confere uma mudança do que toca contra o estado [current]: a fila (se veio), o índice, a posição
 * e o play. Devolve o novo {queue, index, positionMs, playing} ou null se não serve.
 */
export function cleanState(msg, current = { queue: [], index: 0 }) {
  if (!msg || typeof msg !== 'object' || Array.isArray(msg)) return null;
  let queue = current.queue;
  if (msg.queue !== undefined) {
    // Uma música inválida no meio mudaria o que o índice aponta: a fila inteira é recusada.
    if (!Array.isArray(msg.queue) || msg.queue.length > MAX_QUEUE) return null;
    queue = msg.queue.map(cleanTrack);
    if (queue.some((t) => !t)) return null;
  }
  const index = Number.isInteger(msg.index) ? msg.index : current.index;
  if (queue.length === 0 ? index !== 0 : index < 0 || index >= queue.length) return null;
  const positionMs = Number.isFinite(msg.positionMs) && msg.positionMs >= 0 ? Math.min(Math.round(msg.positionMs), MAX_TRACK_MS) : 0;
  const playing = queue.length > 0 && msg.playing === true;
  return { queue, index, positionMs, playing };
}

export class Jams {
  constructor({ clock = Date.now } = {}) {
    this.clock = clock;
    /** id → jam */
    this.jams = new Map();
    /** userId → jamId (onde a pessoa está) */
    this.memberOf = new Map();
  }

  get(id) {
    return this.jams.get(id) ?? null;
  }

  of(userId) {
    const id = this.memberOf.get(userId);
    return id ? this.jams.get(id) ?? null : null;
  }

  /** Convites pendentes pra [userId]. */
  invitesFor(userId) {
    return [...this.jams.values()].filter((j) => j.invited.has(userId));
  }

  create(hostId, state) {
    const now = this.clock();
    const jam = {
      id: newJamId(),
      hostId,
      members: new Set(),
      invited: new Map(), // userId → quem convidou
      createdAt: now,
      lastActive: now,
      state: { queue: [], index: 0, positionMs: 0, playing: false, updatedAt: now, rev: 0, by: hostId },
    };
    this.jams.set(jam.id, jam);
    this.join(jam, hostId);
    if (state) this.apply(jam, hostId, { ...state, baseRev: 0 });
    return jam;
  }

  /** Convida; false se já está na jam ou já foi convidado (convite repetido não toca de novo no celular). */
  invite(jam, fromId, toId) {
    if (jam.members.has(toId) || jam.invited.has(toId)) return false;
    jam.invited.set(toId, fromId);
    return true;
  }

  /** Entra (saindo da jam em que estava). Devolve a jam anterior que a pessoa deixou, se houve. */
  join(jam, userId) {
    const previous = this.of(userId);
    let left = null;
    if (previous && previous !== jam) left = this.leave(previous, userId);
    jam.members.add(userId);
    jam.invited.delete(userId);
    this.memberOf.set(userId, jam.id);
    jam.lastActive = this.clock();
    return left;
  }

  decline(jam, userId) {
    jam.invited.delete(userId);
  }

  /** Sai. Se era quem criou (ou o último), a jam acaba. Devolve {jam, ended}. */
  leave(jam, userId) {
    jam.members.delete(userId);
    jam.invited.delete(userId);
    if (this.memberOf.get(userId) === jam.id) this.memberOf.delete(userId);
    const ended = userId === jam.hostId || jam.members.size === 0;
    if (ended) this.end(jam);
    return { jam, ended };
  }

  end(jam) {
    for (const m of jam.members) if (this.memberOf.get(m) === jam.id) this.memberOf.delete(m);
    this.jams.delete(jam.id);
    jam.ended = true;
  }

  /**
   * Um participante mudou o que toca. Aceita só se ele estava vendo a versão atual ([baseRev] =
   * rev): dois toques ao mesmo tempo, vale o primeiro; o outro recebe a fila nova. Devolve
   * {ok, reason}.
   */
  apply(jam, userId, msg) {
    if (!jam.members.has(userId)) return { ok: false, reason: 'not_member' };
    if (msg.baseRev !== jam.state.rev) return { ok: false, reason: 'stale' };
    const next = cleanState(msg, jam.state);
    if (!next) return { ok: false, reason: 'invalid' };
    const now = this.clock();
    jam.state = { ...next, updatedAt: now, rev: jam.state.rev + 1, by: userId };
    jam.lastActive = now;
    return { ok: true };
  }

  /** Jams em que ninguém está conectado há [IDLE_END_MS]. */
  idle(isOnline) {
    const now = this.clock();
    const out = [];
    for (const jam of this.jams.values()) {
      if ([...jam.members].some(isOnline)) jam.lastActive = now;
      else if (now - jam.lastActive >= IDLE_END_MS) out.push(jam);
    }
    return out;
  }
}
