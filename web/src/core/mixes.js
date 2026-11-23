// Os mixes da Início, feitos do gosto da pessoa (como os do app): "Porque você curte <artista>" (o rádio
// da música dela mais ouvida desse artista), "Descobertas pra você" (dos rádios das favoritas, só o que
// ela ainda não conhece), um mix por gênero escolhido e "Suas mais ouvidas". O id de um mix diz como
// refazê-lo (a página do mix abre direto, mesmo depois de recarregar). Guardados enquanto a aba vive.
import { artistKey, chosenGenres, daily, GENRES, known, topArtists, topSongs, unwanted } from './taste.js';

export class Mixes {
  constructor({ api, sync, clock = () => Date.now() }) {
    Object.assign(this, { api, sync, clock });
    this.cache = new Map(); // id → Promise<{id, kind, title, artist?, tracks, covers}>
  }

  /** Os mixes que a Início mostra agora (sem as músicas: cada um carrega quando aparece). */
  plan() {
    const v = this.sync.data;
    const now = this.clock();
    const out = [];
    for (const a of topArtists(v, now, 4)) {
      if (a.best) out.push({ id: `because:${a.best.videoId}:${a.name}`, kind: 'because', artist: a.name });
    }
    if (v.likes.length + v.plays.length >= 5) out.splice(1, 0, { id: 'discover', kind: 'discover' });
    for (const g of chosenGenres(v).slice(0, 6)) out.push({ id: `genre:${g.id}`, kind: 'genre', genre: g });
    if (topSongs(v, now, 30).length >= 8) out.push({ id: 'top', kind: 'top' });
    return out;
  }

  get(id) {
    let p = this.cache.get(id);
    if (!p) {
      p = this.make(id);
      this.cache.set(id, p);
      p.catch(() => this.cache.delete(id));
    }
    return p;
  }

  async make(id) {
    const v = this.sync.data;
    const now = this.clock();
    const skip = unwanted(v);
    const clean = (list) => dedupe(list.filter((s) => !skip.has(s.videoId)));
    if (id.startsWith('because:')) {
      const [, videoId, ...name] = id.split(':');
      const artist = name.join(':');
      const r = await this.api.radio(videoId);
      // O artista do mix na frente, os parecidos depois, alternando (não vira uma lista de um só).
      const tracks = clean(r.tracks);
      const his = tracks.filter((s) => artistKey(s.artist) === artistKey(artist));
      const others = tracks.filter((s) => artistKey(s.artist) !== artistKey(artist));
      return finish({ id, kind: 'because', artist, tracks: interleave(his, others) });
    }
    if (id === 'discover') {
      const seeds = daily(topSongs(v, now, 20), now, 3, 7);
      const radios = await Promise.all(seeds.map((s) => this.api.radio(s.videoId).catch(() => ({ tracks: [] }))));
      const mine = known(v);
      const fresh = clean(radios.flatMap((r) => r.tracks)).filter((s) => !mine.has(s.videoId) && !s.video);
      return finish({ id, kind: 'discover', tracks: daily(fresh, now, 40, 3) });
    }
    if (id.startsWith('genre:')) {
      const g = GENRES.find((x) => x.id === id.slice(6));
      if (!g) throw new Error('gênero');
      const flavours = ['', 'new', 'top', 'best', 'popular'];
      const slot = Math.floor(now / (6 * 3_600_000));
      const q = [g.query, flavours[slot % flavours.length]].filter(Boolean).join(' ');
      const r = await this.api.search(q, 'songs');
      return finish({ id, kind: 'genre', genre: g, tracks: clean(r.items ?? []) });
    }
    if (id === 'top') return finish({ id, kind: 'top', tracks: topSongs(v, now, 50) });
    throw new Error('mix');
  }
}

function dedupe(list) {
  const seen = new Set();
  return list.filter((s) => {
    const k = `${String(s.title).toLowerCase()}|${artistKey(s.artist)}`;
    if (seen.has(s.videoId) || seen.has(k)) return false;
    seen.add(s.videoId);
    seen.add(k);
    return true;
  });
}

function interleave(a, b) {
  const out = [];
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    if (a[i]) out.push(a[i]);
    if (b[i]) out.push(b[i]);
  }
  return dedupe(out);
}

/** As capas do cartão: quatro artistas diferentes. */
function finish(mix) {
  const covers = [];
  const seen = new Set();
  for (const s of mix.tracks) {
    const k = artistKey(s.artist);
    if (!s.cover || seen.has(k)) continue;
    seen.add(k);
    covers.push(s.cover);
    if (covers.length === 4) break;
  }
  if (covers.length < 4) for (const s of mix.tracks) if (s.cover && !covers.includes(s.cover) && covers.length < 4) covers.push(s.cover);
  const artists = [];
  for (const s of mix.tracks) {
    const name = s.artist.split(',')[0].trim();
    if (name && !artists.some((x) => artistKey(x) === artistKey(name)) && artistKey(name) !== artistKey(mix.artist ?? '')) artists.push(name);
    if (artists.length === 3) break;
  }
  return { ...mix, covers, artists };
}
