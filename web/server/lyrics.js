// Letras do lrclib.net (com o tempo de cada linha, quando tem), como o app faz: primeiro a busca exata
// (artista, título, álbum, duração), depois a busca aberta escolhendo a duração mais perto (±5 s).
// O que volta: {synced: [{t: ms, l: linha}] | null, plain: texto | null, instrumental, source}.

const BASE = 'https://lrclib.net';
const AGENT = 'FreeSong Web/1.0 (https://freesong.simplyonegoal.com/web)';
const TOLERANCE_S = 5;

/** O título sem "(Ao Vivo)", "- Remastered 2011", "[Official Video]", "feat. …". */
export function baseTitle(t) {
  return String(t ?? '')
    .replace(/\s*[([][^)\]]*(official|video|lyric|letra|audio|ao vivo|live|remaster|feat\.?|ft\.?|part\.?|with|versão|version|edit|mix)[^)\]]*[)\]]/gi, '')
    .replace(/\s+-\s+.*(remaster|version|versão|live|ao vivo|edit|mix).*$/i, '')
    .replace(/\s+(feat\.?|ft\.?|part\.?)\s.+$/i, '')
    .replace(/\s+/g, ' ')
    .trim();
}

export const primaryArtist = (a) => String(a ?? '').split(/\s*(?:,|&| e | y | x | feat\.?| ft\.?)\s*/i)[0].trim();

const fold = (s) => String(s ?? '').normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim();

/** Parecido (0..1) por palavras em comum. */
export function similar(a, b) {
  const x = new Set(fold(a).split(' ').filter(Boolean));
  const y = new Set(fold(b).split(' ').filter(Boolean));
  if (!x.size || !y.size) return 0;
  let common = 0;
  for (const w of x) if (y.has(w)) common++;
  return (2 * common) / (x.size + y.size);
}

/** "[01:02.34] linha" → [{t: 62340, l: "linha"}], em ordem. */
export function parseLrc(lrc) {
  const out = [];
  for (const raw of String(lrc ?? '').split(/\r?\n/)) {
    const stamps = [...raw.matchAll(/\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]/g)];
    if (!stamps.length) continue;
    const line = raw.replace(/\[[^\]]*\]/g, '').trim();
    for (const m of stamps) {
      const frac = m[3] ? Number(m[3].padEnd(3, '0')) : 0;
      out.push({ t: Number(m[1]) * 60_000 + Number(m[2]) * 1000 + frac, l: line });
    }
  }
  return out.sort((a, b) => a.t - b.t);
}

function shape(r) {
  if (!r) return null;
  if (r.instrumental) return { synced: null, plain: null, instrumental: true, source: 'LRCLIB' };
  const synced = r.syncedLyrics?.trim() ? parseLrc(r.syncedLyrics) : null;
  const plain = r.plainLyrics?.trim() || null;
  if (!synced?.length && !plain) return null;
  return { synced: synced?.length ? synced : null, plain, instrumental: false, source: 'LRCLIB' };
}

export class Lyrics {
  constructor({ fetch: f = globalThis.fetch } = {}) {
    this.fetch = f;
  }

  async ask(path, params) {
    const url = new URL(BASE + path);
    for (const [k, v] of Object.entries(params)) if (v != null && v !== '') url.searchParams.set(k, String(v));
    const r = await this.fetch(url, { headers: { 'User-Agent': AGENT, 'Lrclib-Client': AGENT, Accept: 'application/json' }, signal: AbortSignal.timeout(8000) });
    if (r.status === 404) return null;
    if (!r.ok) throw new Error(`lrclib ${r.status}`);
    return r.json();
  }

  /** As letras de uma música; null quando o LRCLIB não tem. */
  async find({ title, artist, album, durationMs }) {
    if (!title || !artist) return null;
    const secs = durationMs > 0 ? Math.round(durationMs / 1000) : null;
    const exact = await this.ask('/api/get', { artist_name: artist, track_name: title, album_name: album, duration: secs });
    const got = shape(exact);
    if (got) return got;
    const t = baseTitle(title);
    const a = primaryArtist(artist);
    const list = await this.ask('/api/search', { track_name: t, artist_name: a });
    if (!Array.isArray(list)) return null;
    const best = list
      .filter((r) => !r.instrumental && (r.syncedLyrics || r.plainLyrics))
      .filter((r) => similar(baseTitle(r.trackName), t) >= 0.8)
      .filter((r) => similar(r.artistName, a) >= 0.5 || fold(r.artistName).includes(fold(a)))
      .filter((r) => secs == null || r.duration == null || Math.abs(r.duration - secs) <= TOLERANCE_S)
      .sort((x, y) => (secs == null ? 0 : Math.abs((x.duration ?? secs) - secs) - Math.abs((y.duration ?? secs) - secs)) || (y.syncedLyrics ? 1 : 0) - (x.syncedLyrics ? 1 : 0))[0];
    return shape(best);
  }
}
