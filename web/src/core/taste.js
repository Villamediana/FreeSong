// O gosto da pessoa, tirado do cofre (o que ouviu, curtiu e escolheu), pra Início: tocadas
// recentemente, os artistas de agora, as mais ouvidas, os mixes "Porque você curte…", as descobertas
// e as prateleiras dos gêneros. Tudo aqui, no navegador (o relay não sabe o que a pessoa ouve).
import { fromV, playSong } from './vault.js';

const DAY = 86_400_000;
const decay = (age, halfDays) => 0.5 ** (Math.max(0, age) / (halfDays * DAY));

/** O artista principal ("Anitta, Maluma" → "Anitta"), pra juntar as escutas dele. */
export const artistKey = (a) => String(a ?? '').split(/\s*(?:,|&| e | y | x | feat\.?| ft\.?)\s*/i)[0].trim().toLowerCase();
const mainArtist = (a) => String(a ?? '').split(/\s*(?:,|&| e | y | x | feat\.?| ft\.?)\s*/i)[0].trim();

/** As tocadas por último (uma vez cada), mais recente primeiro. */
export function recent(v, n = 16) {
  const seen = new Set();
  const out = [];
  for (const p of v.plays) {
    if (p.k || seen.has(p.i)) continue;
    seen.add(p.i);
    out.push({ ...playSong(p), at: p.at });
    if (out.length >= n) break;
  }
  // A capa da curtida (o cofre não guarda a das escutas).
  const covers = new Map(v.likes.map((l) => [l.s.i, l.s.c]));
  for (const l of v.lists) for (const s of l.s) if (s.c && !covers.has(s.i)) covers.set(s.i, s.c);
  return out.map((s) => (covers.get(s.videoId) ? { ...s, cover: covers.get(s.videoId) } : s));
}

/** Quanto cada música conta pro gosto (concluída vale mais, pulada conta contra), com o tempo apagando. */
function weights(v, now, halfDays) {
  const songs = new Map(); // videoId → {score, song}
  for (const p of v.plays) {
    const w = (p.c ? 1 : p.k ? -0.4 : 0.5) * decay(now - p.at, halfDays);
    const s = songs.get(p.i) ?? { score: 0, p };
    s.score += w;
    songs.set(p.i, s);
  }
  for (const l of v.likes) {
    const s = songs.get(l.s.i) ?? { score: 0, like: l };
    s.score += 1.5 * decay(now - l.at, halfDays * 3);
    s.like = l;
    songs.set(l.s.i, s);
  }
  return songs;
}

/** As mais ouvidas agora (as que a pessoa volta a ouvir). */
export function topSongs(v, now, n = 30) {
  return [...weights(v, now, 30).values()]
    .filter((s) => s.score > 0.3)
    .sort((a, b) => b.score - a.score)
    .slice(0, n)
    .map((s) => (s.like ? fromV(s.like.s) : playSong(s.p)));
}

/** Os artistas de agora: nome, quanto e a música dele mais ouvida (a semente do mix). */
export function topArtists(v, now, n = 8) {
  const by = new Map();
  for (const [, s] of weights(v, now, 21)) {
    const a = s.like ? s.like.s.a : s.p.a;
    const key = artistKey(a);
    if (!key) continue;
    const e = by.get(key) ?? { name: mainArtist(a), score: 0, best: null, bestScore: -Infinity, covers: [] };
    e.score += s.score;
    if (s.score > e.bestScore) {
      e.best = s.like ? fromV(s.like.s) : playSong(s.p);
      e.bestScore = s.score;
    }
    if (s.like?.s.c) e.covers.push(s.like.s.c);
    by.set(key, e);
  }
  return [...by.values()].filter((a) => a.score > 0.8).sort((a, b) => b.score - a.score).slice(0, n);
}

/** O que a pessoa já conhece (curtiu ou ouviu): as descobertas ficam de fora disso. */
export function known(v) {
  return new Set([...v.likes.map((l) => l.s.i), ...v.plays.map((p) => p.i)]);
}

/** Músicas que a pessoa pulou várias vezes: não voltam nos mixes. */
export function unwanted(v) {
  const skips = new Map();
  for (const p of v.plays) if (p.k) skips.set(p.i, (skips.get(p.i) ?? 0) + 1);
  const liked = new Set(v.likes.map((l) => l.s.i));
  return new Set([...skips].filter(([id, n]) => n >= 2 && !liked.has(id)).map(([id]) => id));
}

/** Um sorteio que é o mesmo o dia todo (a Início não muda a cada visita). */
export function daily(list, now, n, salt = 0) {
  const day = Math.floor(now / DAY) + salt;
  let seed = (day * 2654435761) >>> 0;
  const rnd = () => ((seed = (seed * 1103515245 + 12345) >>> 0) / 4294967296);
  return [...list].map((x) => [rnd(), x]).sort((a, b) => a[0] - b[0]).slice(0, n).map((x) => x[1]);
}

/** Os gêneros da Início (os do app: GenreCatalog), com a busca que representa cada um. */
export const GENRES = [
  ['pop', { pt: 'Pop', es: 'Pop', en: 'Pop' }, 'pop hits', '#4C6FFF'],
  ['reggaeton', { pt: 'Reggaeton', es: 'Reguetón', en: 'Reggaeton' }, 'reggaeton hits', '#E0567A'],
  ['rock', { pt: 'Rock', es: 'Rock', en: 'Rock' }, 'rock classics', '#3D3F4A'],
  ['sertanejo', { pt: 'Sertanejo', es: 'Sertanejo', en: 'Sertanejo' }, 'sertanejo sucessos', '#C98A2B'],
  ['funk', { pt: 'Funk', es: 'Funk brasileño', en: 'Brazilian funk' }, 'funk brasileiro', '#8E44D9'],
  ['mpb', { pt: 'MPB', es: 'MPB', en: 'MPB' }, 'mpb clássicos', '#1E9E6E'],
  ['hiphop', { pt: 'Hip-hop', es: 'Hip-hop', en: 'Hip-hop' }, 'hip hop hits', '#2B2D36'],
  ['electronic', { pt: 'Eletrônica', es: 'Electrónica', en: 'Electronic' }, 'electronic dance hits', '#14A3C7'],
  ['latin', { pt: 'Latino', es: 'Latino', en: 'Latin' }, 'latin pop hits', '#E36A3A'],
  ['salsa', { pt: 'Salsa', es: 'Salsa', en: 'Salsa' }, 'salsa clásica', '#D9453B'],
  ['pagode', { pt: 'Pagode', es: 'Pagode', en: 'Pagode' }, 'pagode', '#B5651D'],
  ['indie', { pt: 'Indie', es: 'Indie', en: 'Indie' }, 'indie hits', '#5E8C61'],
  ['rnb', { pt: 'R&B', es: 'R&B', en: 'R&B' }, 'r&b hits', '#7A4BC2'],
  ['kpop', { pt: 'K-pop', es: 'K-pop', en: 'K-pop' }, 'k-pop hits', '#E85FB0'],
  ['gospel', { pt: 'Gospel', es: 'Góspel', en: 'Gospel' }, 'louvor gospel', '#3A7BD5'],
  ['jazz', { pt: 'Jazz', es: 'Jazz', en: 'Jazz' }, 'jazz classics', '#2F6F8F'],
  ['classical', { pt: 'Clássica', es: 'Clásica', en: 'Classical' }, 'classical music', '#8A7556'],
  ['eighties', { pt: 'Anos 80', es: 'Años 80', en: '80s' }, '80s hits', '#D94E8F'],
].map(([id, label, query, tint]) => ({ id, label, query, tint }));

/** Os gêneros que a pessoa escolheu no app (na ordem do catálogo). */
export function chosenGenres(v) {
  const ids = new Set(v.gen?.c ? v.gen.ids : []);
  return GENRES.filter((g) => ids.has(g.id));
}
