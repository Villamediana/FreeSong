// A ponte com o YouTube Music (InnerTube "WEB_REMIX", pela youtubei.js): busca, sugestões, artista,
// álbum, playlist, rádio de uma música, o que está em alta e vídeos alternativos quando um não toca
// fora do YouTube. Tudo sai normalizado nos mesmos formatos que a página usa (e o cofre guarda):
//
//   música    {videoId, title, artist, artists: [{id, name}], album, albumId, durationMs, cover, video}
//   álbum     {kind: 'album', id, title, artist, artistId, year, type ('Álbum', 'Single', 'EP'…), cover}
//   artista   {kind: 'artist', id, name, cover, subtitle}
//   playlist  {kind: 'playlist', id, title, author, cover, count}
//
// O som e a imagem não passam por aqui: a página toca pelo player do próprio YouTube.
import { Innertube, Log } from 'youtubei.js';

Log.setLevel(Log.Level.NONE);

export const VIDEO = /^[A-Za-z0-9_-]{11}$/;
export const CHANNEL = /^UC[A-Za-z0-9_-]{22}$/;
export const ALBUM = /^MPREb_[A-Za-z0-9_-]{4,74}$/;
export const PLAYLIST = /^(VL)?(PL|RD|OL|LL|FL|UU|RDCLAK|OLAK)[A-Za-z0-9_-]{8,80}$/;
const LANGS = { pt: 'pt', es: 'es', en: 'en' };

/** O texto de um nó da youtubei.js (string, {text}, Text, runs…). */
export function text(x) {
  if (x == null) return '';
  if (typeof x === 'string') return x.trim();
  if (typeof x.text === 'string') return x.text.trim();
  if (typeof x.toString === 'function' && x.toString !== Object.prototype.toString) return String(x).trim();
  return '';
}

const SIZE_WH = /=w\d+-h\d+/;
const SIZE_S = /=s\d+(?=-|$)/;
const YTIMG = /^https?:\/\/i\d?\.ytimg\.com\/(vi|vi_webp)\/([A-Za-z0-9_-]{11})\/([a-z0-9_]+)\.(jpg|webp)(\?.*)?$/;

/** A imagem no tamanho que a página usa (os servidores do Google redimensionam pela própria URL). */
export function upgrade(url, size = 544) {
  if (!url || typeof url !== 'string') return null;
  let u = url.startsWith('//') ? 'https:' + url : url;
  if (!u.startsWith('https://')) return null;
  if (/googleusercontent\.com\/|ggpht\.com\//.test(u)) {
    if (SIZE_WH.test(u)) return u.replace(SIZE_WH, `=w${size}-h${size}`);
    if (SIZE_S.test(u)) return u.replace(SIZE_S, `=s${size}`);
    return u;
  }
  const m = YTIMG.exec(u);
  if (m) {
    const name = m[3];
    if (name.startsWith('maxres')) return `https://i.ytimg.com/vi/${m[2]}/maxresdefault.jpg`;
    if (name.startsWith('hq720')) return `https://i.ytimg.com/vi/${m[2]}/hq720.jpg`;
    return `https://i.ytimg.com/vi/${m[2]}/hqdefault.jpg`;
  }
  return u;
}

/** A maior imagem de uma lista de miniaturas (de qualquer jeito que a youtubei.js entregue). */
export function cover(it, size = 544) {
  const list = Array.isArray(it?.thumbnail) ? it.thumbnail
    : Array.isArray(it?.thumbnail?.contents) ? it.thumbnail.contents
      : Array.isArray(it?.thumbnails) ? it.thumbnails
        : Array.isArray(it?.thumbnail?.thumbnails) ? it.thumbnail.thumbnails
          : Array.isArray(it) ? it : [];
  const best = [...list].filter((t) => t?.url).sort((a, b) => (b.width ?? 0) * (b.height ?? 0) - (a.width ?? 0) * (a.height ?? 0))[0];
  return best ? upgrade(best.url, size) : null;
}

const seconds = (t) => {
  const m = /^(?:(\d+):)?(\d{1,2}):(\d{2})$/.exec(String(t ?? '').trim());
  return m ? (Number(m[1] ?? 0) * 3600 + Number(m[2]) * 60 + Number(m[3])) : 0;
};

function people(it) {
  const list = it?.artists?.length ? it.artists : it?.authors?.length ? it.authors : [];
  return list.map((a) => ({ id: CHANNEL.test(a?.channel_id ?? '') ? a.channel_id : null, name: text(a?.name) })).filter((a) => a.name);
}

/** Uma música (ou vídeo de música) de qualquer lista; null se não é uma. */
export function song(it, ctx = {}) {
  if (!it) return null;
  const videoId = it.video_id ?? it.id ?? it.endpoint?.payload?.videoId;
  if (!VIDEO.test(videoId ?? '')) return null;
  const title = text(it.title) || text(it.name);
  if (!title) return null;
  const artists = people(it);
  const artist = artists.map((a) => a.name).join(', ') || text(it.author?.name ?? it.author) || ctx.artist || '';
  const albumId = ALBUM.test(it.album?.id ?? '') ? it.album.id : ctx.albumId ?? null;
  const secs = it.duration?.seconds ?? seconds(text(it.duration));
  // A youtubei.js chama de "video" até faixa de álbum: só a busca sabe de verdade o que é vídeo.
  const isVideo = ctx.video === true;
  const flex = (it.flex_columns ?? []).map((c) => text(c?.title));
  return {
    videoId,
    title: title.slice(0, 200),
    artist: artist.slice(0, 200),
    artists: artists.length ? artists : ctx.artistId ? [{ id: ctx.artistId, name: ctx.artist }] : [],
    album: text(it.album?.name) || ctx.album || (ctx.flexAlbum ? flex[3] || null : null),
    albumId,
    durationMs: secs > 0 ? secs * 1000 : null,
    cover: cover(it) ?? ctx.cover ?? `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`,
    video: isVideo,
  };
}

const yearOf = (s) => /\b(19|20)\d\d\b/.exec(s ?? '')?.[0] ?? null;

export function album(it) {
  const id = it?.id ?? it?.endpoint?.payload?.browseId;
  if (!ALBUM.test(id ?? '')) return null;
  const sub = text(it.subtitle);
  const artists = people(it);
  return {
    kind: 'album',
    id,
    title: text(it.title) || text(it.name),
    artist: text(it.author?.name) || artists.map((a) => a.name).join(', ') || sub.split('•').slice(1, -1).join('•').trim() || '',
    artistId: (CHANNEL.test(it.author?.channel_id ?? '') ? it.author.channel_id : null) ?? artists.find((a) => a.id)?.id ?? null,
    year: it.year ? String(it.year) : yearOf(sub),
    type: sub.split('•')[0]?.trim() || null,
    cover: cover(it),
  };
}

export function artist(it) {
  const id = it?.id ?? it?.endpoint?.payload?.browseId;
  if (!CHANNEL.test(id ?? '')) return null;
  return { kind: 'artist', id, name: text(it.name) || text(it.title), cover: cover(it), subtitle: text(it.subtitle) || text(it.subscribers) || null };
}

export function playlist(it) {
  let id = it?.id ?? it?.endpoint?.payload?.browseId;
  if (!PLAYLIST.test(id ?? '')) return null;
  if (id.startsWith('VL')) id = id.slice(2);
  const sub = text(it.subtitle);
  return {
    kind: 'playlist',
    id,
    title: text(it.title) || text(it.name),
    author: text(it.author?.name) || people(it).map((a) => a.name).join(', ') || sub.split('•').slice(1, 2).join('').trim() || null,
    cover: cover(it),
    count: Number.parseInt(String(it.item_count ?? it.song_count ?? '').replace(/\D/g, ''), 10) || null,
  };
}

/** Qualquer item: o que ele é, normalizado; null quando não interessa (podcast, episódio…). */
export function anything(it) {
  switch (it?.item_type) {
    case 'song':
      return song(it);
    case 'video':
    case 'non_music_track':
      return song(it, { video: true });
    case 'album':
      return album(it);
    case 'artist':
      return artist(it);
    case 'playlist':
      return playlist(it);
    default:
      if (it?.type === 'PlaylistPanelVideo') return song(it);
      return null;
  }
}

const clean = (list, fn = anything) => {
  const seen = new Set();
  const out = [];
  for (const it of list ?? []) {
    const v = fn(it);
    const key = v && (v.videoId ?? `${v.kind}:${v.id}`);
    if (!v || seen.has(key)) continue;
    seen.add(key);
    out.push(v);
  }
  return out;
};

const TYPES = { songs: 'song', albums: 'album', artists: 'artist', playlists: 'playlist', videos: 'video' };

export class Music {
  constructor({ create, clock = Date.now } = {}) {
    // Um cliente por idioma (os títulos de seções e "há 3 dias" vêm no idioma de quem pede).
    this.create = create ?? ((lang) => Innertube.create({ lang, location: 'BR', retrieve_player: false, generate_session_locally: true }));
    this.clock = clock;
    this.clients = new Map(); // lang → {at, p}
    this.pages = new Map(); // token → {at, page} (a próxima página de uma busca ou playlist)
  }

  async yt(lang) {
    const l = LANGS[lang] ?? 'pt';
    const hit = this.clients.get(l);
    // Um cliente vale 6 h (a sessão de visitante do YouTube envelhece).
    if (hit && this.clock() - hit.at < 6 * 3600_000) return hit.p;
    const p = this.create(l);
    this.clients.set(l, { at: this.clock(), p });
    p.catch(() => this.clients.delete(l));
    return p;
  }

  /** Um erro do YouTube num cliente: o próximo pedido cria outro (sessão nova). */
  reset(lang) {
    this.clients.delete(LANGS[lang] ?? 'pt');
  }

  keep(page) {
    const now = this.clock();
    for (const [k, v] of this.pages) if (now - v.at > 10 * 60_000) this.pages.delete(k);
    if (this.pages.size > 3000) this.pages.delete(this.pages.keys().next().value);
    const token = globalThis.crypto.randomUUID();
    this.pages.set(token, { at: now, page });
    return token;
  }

  /** "Tudo": as músicas (busca de músicas) e os destaques da busca geral (artista, álbuns, playlists). */
  async searchAll(q, lang) {
    const yt = await this.yt(lang);
    const [songs, all] = await Promise.all([
      yt.music.search(q, { type: 'song' }).catch(() => null),
      yt.music.search(q).catch(() => null),
    ]);
    const out = { top: null, songs: clean(songs?.contents?.[0]?.contents, song), albums: [], artists: [], playlists: [], videos: [] };
    for (const sec of all?.contents ?? []) {
      if (sec.type === 'MusicCardShelf') {
        const id = sec.on_tap?.payload?.browseId ?? sec.endpoint?.payload?.browseId;
        const sub = text(sec.subtitle);
        if (CHANNEL.test(id ?? '')) out.top = { kind: 'artist', id, name: text(sec.title), cover: cover(sec.thumbnail?.contents ?? sec.thumbnail), subtitle: sub };
        else if (ALBUM.test(id ?? '')) out.top = { kind: 'album', id, title: text(sec.title), artist: sub.split('•').slice(1).join('•').trim(), cover: cover(sec.thumbnail?.contents ?? sec.thumbnail), type: sub.split('•')[0]?.trim() };
        else {
          const vid = sec.on_tap?.payload?.videoId;
          if (VIDEO.test(vid ?? '')) out.top = { kind: 'song', ...song({ id: vid, title: text(sec.title), author: sub.split('•').slice(1, 2).join('').trim(), thumbnail: sec.thumbnail }) };
        }
      }
      for (const it of sec.contents ?? []) {
        const v = anything(it);
        if (!v) continue;
        if (v.videoId) {
          if (v.video && !out.videos.some((x) => x.videoId === v.videoId)) out.videos.push(v);
        } else if (!out[`${v.kind}s`].some((x) => x.id === v.id)) out[`${v.kind}s`].push(v);
      }
    }
    if (!out.songs.length && !out.top && !out.albums.length && !out.artists.length && !out.playlists.length) throw new Error('empty search');
    return out;
  }

  /** Só um tipo (com a próxima página em `next`). */
  async search(q, type, lang) {
    if (!TYPES[type]) return this.searchAll(q, lang);
    const yt = await this.yt(lang);
    const r = await yt.music.search(q, { type: TYPES[type] });
    const shelf = r.contents?.find((s) => s.contents?.length) ?? r.contents?.[0];
    return { items: clean(shelf?.contents), next: r.has_continuation ? this.keep(r) : null };
  }

  async more(token) {
    const hit = this.pages.get(token);
    if (!hit) return null;
    this.pages.delete(token);
    const r = await hit.page.getContinuation();
    const items = r.contents?.contents ?? r.contents?.flatMap?.((s) => s.contents ?? []) ?? r.items ?? [];
    return { items: clean(items), next: r.has_continuation ? this.keep(r) : null };
  }

  async suggest(q, lang) {
    const yt = await this.yt(lang);
    const sections = await yt.music.getSearchSuggestions(q);
    const queries = [];
    const items = [];
    for (const sec of sections ?? []) {
      for (const c of sec.contents ?? []) {
        const s = text(c.suggestion) || text(c.query);
        if (s && c.type === 'SearchSuggestion') queries.push(s);
        else {
          const v = anything(c);
          if (v) items.push(v);
        }
      }
    }
    return { queries: queries.slice(0, 8), items: items.slice(0, 6) };
  }

  async artist(id, lang) {
    const yt = await this.yt(lang);
    const a = await yt.music.getArtist(id);
    const h = a.header ?? {};
    const name = text(h.title);
    const out = {
      kind: 'artist',
      id,
      name,
      cover: cover(h.thumbnail?.contents ?? h.thumbnail, 1080) ?? cover(h.foreground_thumbnail, 1080),
      description: text(h.description).slice(0, 1200) || null,
      subtitle: text(h.subscription_button?.subscriber_count) || text(h.monthly_listener_count) || null,
      top: [],
      songsId: null,
      albums: [],
      singles: [],
      videos: [],
      playlists: [],
      similar: [],
    };
    for (const sec of a.sections ?? []) {
      const contents = sec.contents ?? [];
      if (sec.type === 'MusicShelf') {
        out.top = clean(contents, (it) => song(it, { artist: name, artistId: id, flexAlbum: true }));
        const more = sec.bottom_endpoint?.payload?.browseId ?? sec.endpoint?.payload?.browseId;
        if (PLAYLIST.test(more ?? '')) out.songsId = more.replace(/^VL/, '');
        continue;
      }
      const first = contents[0]?.item_type;
      const title = text(sec.header?.title ?? sec.title).toLowerCase();
      if (first === 'album') {
        const list = clean(contents, album).map((x) => ({ ...x, artist: x.artist || name, artistId: x.artistId ?? id }));
        if (/single|ep\b/.test(title)) out.singles.push(...list);
        else out.albums.push(...list);
      } else if (first === 'video' || first === 'song') out.videos.push(...clean(contents, (it) => song(it, { artist: name, artistId: id, video: true })));
      else if (first === 'playlist') out.playlists.push(...clean(contents, playlist));
      else if (first === 'artist') out.similar.push(...clean(contents, artist));
    }
    return out;
  }

  async album(id, lang) {
    const yt = await this.yt(lang);
    const a = await yt.music.getAlbum(id);
    const h = a.header ?? {};
    const sub = text(h.subtitle);
    const artistName = text(h.strapline_text_one) || text(h.author?.name) || '';
    const artistId = [h.strapline_text_one?.endpoint?.payload?.browseId, h.author?.channel_id, h.strapline_text_one?.runs?.[0]?.endpoint?.payload?.browseId].find((x) => CHANNEL.test(x ?? '')) ?? null;
    const art = cover(h.thumbnail?.contents ?? h.thumbnail, 1080);
    const title = text(h.title);
    return {
      kind: 'album',
      id,
      title,
      artist: artistName,
      artistId,
      year: yearOf(sub),
      type: sub.split('•')[0]?.trim() || null,
      info: text(h.second_subtitle) || null,
      description: text(h.description).slice(0, 1200) || null,
      cover: art,
      tracks: clean(a.contents, (it) => song(it, { artist: artistName, artistId, album: title, albumId: id, cover: art ? upgrade(art, 544) : null })),
    };
  }

  async playlist(id, lang) {
    const yt = await this.yt(lang);
    let p = await yt.music.getPlaylist(id);
    const h = p.header ?? {};
    const tracks = clean(p.items ?? p.contents, song);
    // Playlists grandes vêm em páginas: até 300 músicas.
    for (let i = 0; i < 4 && p.has_continuation && tracks.length < 300; i++) {
      try {
        p = await p.getContinuation();
      } catch {
        break;
      }
      for (const t of clean(p.items ?? p.contents, song)) if (!tracks.some((x) => x.videoId === t.videoId)) tracks.push(t);
    }
    return {
      kind: 'playlist',
      id,
      title: text(h.title),
      author: text(h.strapline_text_one) || text(h.author?.name) || null,
      info: [text(h.subtitle), text(h.second_subtitle)].filter(Boolean).join(' · ') || null,
      description: text(h.description).slice(0, 1200) || null,
      cover: cover(h.thumbnail?.contents ?? h.thumbnail, 1080),
      tracks,
    };
  }

  /** O rádio de uma música (o "a seguir" infinito do YouTube Music): a própria primeiro. */
  async radio(videoId, lang) {
    const yt = await this.yt(lang);
    const up = await yt.music.getUpNext(videoId, true);
    return { tracks: clean(up.contents, song) };
  }

  /** Uma música sozinha (um link compartilhado): o primeiro do "a seguir" dela é ela mesma. */
  async song(videoId, lang) {
    const yt = await this.yt(lang);
    const up = await yt.music.getUpNext(videoId, false);
    const first = clean(up.contents, song).find((s) => s.videoId === videoId);
    if (!first) throw new Error('no song');
    return first;
  }

  /** O que está em alta: lançamentos, hits do dia, as músicas em alta e os gêneros/momentos. */
  async explore(lang) {
    const yt = await this.yt(lang);
    const [home, explore] = await Promise.all([yt.music.getHomeFeed().catch(() => null), yt.music.getExplore().catch(() => null)]);
    const shelves = [];
    for (const sec of [...(home?.sections ?? []), ...(explore?.sections ?? [])]) {
      const title = text(sec.header?.title ?? sec.title);
      const items = clean(sec.contents);
      if (!title || items.length < 3) continue;
      if (shelves.some((s) => s.title === title)) continue;
      shelves.push({ title, items: items.slice(0, 20) });
    }
    if (!shelves.length) throw new Error('empty explore');
    return { shelves };
  }

  /** Outros vídeos da mesma música (quando o dono não deixa tocar fora do YouTube). */
  async alternatives(title, artist, not, lang) {
    const yt = await this.yt(lang);
    const r = await yt.search(`${artist} ${title}`.trim(), { type: 'video' });
    const out = [];
    for (const v of r.videos ?? []) {
      const id = v.video_id ?? v.id;
      if (!VIDEO.test(id ?? '') || id === not) continue;
      const secs = v.duration?.seconds ?? seconds(text(v.length_text));
      out.push({ videoId: id, title: text(v.title), channel: text(v.author?.name), durationMs: secs ? secs * 1000 : null });
      if (out.length >= 6) break;
    }
    return { videos: out };
  }

  /** As letras do YouTube Music (sem tempo): o último recurso, quando o LRCLIB não tem. */
  async lyrics(videoId, lang) {
    const yt = await this.yt(lang);
    const l = await yt.music.getLyrics(videoId);
    const plain = text(l?.description);
    return plain ? { plain, source: text(l?.footer) || 'YouTube Music' } : null;
  }
}

