// O cofre do perfil (o mesmo do celular, social/Vault.kt): curtidas, o que foi ouvido (um ano),
// playlists, playlists trazidas do Spotify, a conversa com os amigos e os gêneros da Início. Os nomes
// curtos são os do app (o cofre viaja inteiro a cada sincronização). Aqui: ler (com os valores
// padrão que o celular omite), juntar três versões (a mesma regra do VaultMerge) e mudar.

export const PLAYS_KEPT_MS = 400 * 24 * 3_600_000;
export const MAX_PLAYS = 25_000;

const cmp = (a, b) => (a < b ? -1 : a > b ? 1 : 0);
const str = (v, d = '') => (typeof v === 'string' ? v : d);
const strOrNull = (v) => (typeof v === 'string' ? v : null);
const num = (v, d = 0) => (Number.isFinite(v) ? Math.trunc(v) : d);
const numOrNull = (v) => (Number.isFinite(v) ? Math.trunc(v) : null);
const bool = (v) => v === true;
const list = (v) => (Array.isArray(v) ? v : []);

/** Uma música do cofre {i, t, a, al, d, c, u}, com os padrões (o celular omite os vazios). */
export function vsong(s) {
  if (!s || typeof s.i !== 'string' || typeof s.t !== 'string') return null;
  return { i: s.i, t: s.t, a: str(s.a), al: strOrNull(s.al), d: numOrNull(s.d), c: strOrNull(s.c), u: strOrNull(s.u) };
}

const vlike = (l) => {
  const s = vsong(l?.s);
  return s ? { s, at: num(l.at) } : null;
};

const vplay = (p) => (p && typeof p.i === 'string' && typeof p.t === 'string'
  ? { i: p.i, t: p.t, a: str(p.a), g: strOrNull(p.g), at: num(p.at), l: num(p.l), d: num(p.d), c: bool(p.c), k: bool(p.k), pp: strOrNull(p.pp) }
  : null);

const vlist = (p) => (p && typeof p.k === 'string' && typeof p.n === 'string'
  ? { k: p.k, n: p.n, ds: strOrNull(p.ds), src: strOrNull(p.src), p: bool(p.p), at: num(p.at), up: num(p.up), s: list(p.s).map(vsong).filter(Boolean) }
  : null);

const vimp = (p) => (p && typeof p.u === 'string' && typeof p.n === 'string' && typeof p.j === 'string'
  ? { u: p.u, n: p.n, o: strOrNull(p.o), c: strOrNull(p.c), j: p.j, nn: num(p.nn), at: num(p.at) }
  : null);

const vshared = (m) => {
  const s = vsong(m?.s);
  if (!s || typeof m.f !== 'string' || typeof m.dir !== 'string') return null;
  return {
    f: m.f, dir: m.dir, del: num(m.del), s, at: num(m.at), sn: bool(m.sn), k: str(m.k, 'song'), t: strOrNull(m.t),
    mr: strOrNull(m.mr), mra: num(m.mra), tr: strOrNull(m.tr), tra: num(m.tra), rp: numOrNull(m.rp), ed: num(m.ed),
  };
};

const vgen = (g) => (g && Array.isArray(g.ids) ? { c: bool(g.c), ids: g.ids.filter((x) => typeof x === 'string'), at: num(g.at) } : null);

/** O cofre lido de qualquer jeito (do relay, deste navegador): tudo com os padrões, nada estranho. */
export function normalize(raw) {
  const v = raw && typeof raw === 'object' ? raw : {};
  return {
    v: num(v.v, 1) || 1,
    likes: list(v.likes).map(vlike).filter(Boolean),
    plays: list(v.plays).map(vplay).filter(Boolean),
    lists: list(v.lists).map(vlist).filter(Boolean),
    imp: list(v.imp).map(vimp).filter(Boolean),
    sh: list(v.sh).map(vshared).filter(Boolean),
    gen: vgen(v.gen),
  };
}

export const empty = () => normalize({});

/** Igual de verdade (as duas já normalizadas): o mesmo que o "==" das data classes do Kotlin. */
export const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

/** Sem os padrões (como o celular escreve): o cofre menor pra viajar. */
export function compact(v) {
  const drop = (o, defaults) => {
    const out = {};
    for (const [k, val] of Object.entries(o)) {
      if (val === null || val === undefined) continue;
      if (k in defaults && JSON.stringify(defaults[k]) === JSON.stringify(val)) continue;
      out[k] = val;
    }
    return out;
  };
  const song = (s) => drop(s, { a: '' });
  const out = {};
  if (v.likes.length) out.likes = v.likes.map((l) => ({ s: song(l.s), at: l.at }));
  if (v.plays.length) out.plays = v.plays.map((p) => drop(p, { a: '', d: 0, c: false, k: false }));
  if (v.lists.length) out.lists = v.lists.map((p) => ({ ...drop({ ...p, s: undefined }, { p: false }), ...(p.s.length ? { s: p.s.map(song) } : {}) }));
  if (v.imp.length) out.imp = v.imp.map((p) => drop(p, {}));
  if (v.sh.length) out.sh = v.sh.map((m) => ({ ...drop({ ...m, s: undefined }, { k: 'song', mra: 0, tra: 0, ed: 0 }), s: song(m.s) }));
  if (v.gen) out.gen = v.gen;
  return out;
}

/**
 * Guarda o que os dois lados têm ([pick] escolhe entre duas versões), o que um lado pôs desde [base]
 * e tira o que um lado tirou desde [base]. Sem [base] (primeira vez), nada sai: tudo se junta.
 */
export function threeWay(base, local, remote, key, pick) {
  const b = base ? new Set(base.map(key)) : null;
  const l = new Map(local.map((x) => [key(x), x]));
  const r = new Map(remote.map((x) => [key(x), x]));
  const out = new Map();
  for (const [k, lv] of l) {
    const rv = r.get(k);
    if (rv !== undefined) out.set(k, pick(lv, rv));
    else if (!b || !b.has(k)) out.set(k, lv); // posto aqui
    // senão: tirado em outro aparelho
  }
  for (const [k, rv] of r) if (!l.has(k) && (!b || !b.has(k))) out.set(k, rv); // posto em outro aparelho
  return [...out.values()];
}

const distinctBy = (items, key) => {
  const seen = new Set();
  return items.filter((x) => {
    const k = key(x);
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  });
};

const maxBy = (items, f) => items.reduce((best, x) => (best === undefined || f(x) > f(best) ? x : best), undefined);

/** Dois aparelhos do mesmo perfil juntos — a mesma regra do VaultMerge.merge do app. */
export function merge(base, local, remote, now) {
  const playKey = (p) => `${p.i}@${p.at}`;
  return {
    v: 1,
    likes: threeWay(base?.likes, local.likes, remote.likes, (x) => x.s.i, (l, r) => (r.at > l.at ? r : l)).sort((a, b) => cmp(a.s.i, b.s.i)),
    plays: distinctBy([...local.plays, ...remote.plays], playKey)
      .filter((p) => now - p.at <= PLAYS_KEPT_MS)
      .sort((a, b) => b.at - a.at || cmp(a.i, b.i))
      .slice(0, MAX_PLAYS),
    lists: threeWay(base?.lists, local.lists, remote.lists, (x) => x.k, (l, r) => (r.up > l.up ? r : l)).sort((a, b) => cmp(a.k, b.k)),
    imp: threeWay(base?.imp, local.imp, remote.imp, (x) => x.u, (l, r) => (r.at > l.at ? r : l)).sort((a, b) => cmp(a.u, b.u)),
    sh: [...groupBy([...local.sh, ...remote.sh], (m) => `${m.dir}#${m.del}`).values()]
      .map((all) => {
        const mine = maxBy(all, (m) => m.mra);
        const theirs = maxBy(all, (m) => m.tra);
        const edited = maxBy(all, (m) => m.ed);
        return {
          ...all[0],
          sn: all.some((m) => m.sn),
          mr: mine.mr, mra: mine.mra,
          tr: theirs.tr, tra: theirs.tra,
          t: edited.t, ed: edited.ed,
          rp: all.find((m) => m.rp != null)?.rp ?? null,
        };
      })
      .sort((a, b) => a.at - b.at || cmp(`${a.dir}#${a.del}`, `${b.dir}#${b.del}`)),
    gen: maxBy([local.gen, remote.gen].filter(Boolean), (g) => g.at) ?? null,
  };
}

function groupBy(items, key) {
  const out = new Map();
  for (const x of items) {
    const k = key(x);
    if (!out.has(k)) out.set(k, []);
    out.get(k).push(x);
  }
  return out;
}

// ------------------------------------------------------------------ a página ↔ o cofre

/** Uma música da página {videoId, title, artist, album, durationMs, cover} como o cofre guarda. */
export function toV(s) {
  return {
    i: s.videoId,
    t: String(s.title ?? '').slice(0, 200),
    a: String(s.artist ?? '').slice(0, 200),
    al: s.album ?? null,
    d: Number.isFinite(s.durationMs) ? s.durationMs : null,
    c: s.cover ?? null,
    u: `https://music.youtube.com/watch?v=${s.videoId}`,
  };
}

/** Uma música do cofre como a página mostra e toca. */
export function fromV(v) {
  return {
    videoId: v.i,
    title: v.t,
    artist: v.a,
    artists: [],
    album: v.al,
    albumId: null,
    durationMs: v.d,
    cover: v.c ?? `https://i.ytimg.com/vi/${v.i}/hqdefault.jpg`,
  };
}

/** Uma escuta (do cofre) como música da página: o cofre não guarda a capa dela. */
export function playSong(p) {
  return { videoId: p.i, title: p.t, artist: p.a, artists: [], album: null, albumId: null, durationMs: p.d || null, cover: `https://i.ytimg.com/vi/${p.i}/hqdefault.jpg` };
}

/** Terminou de ouvir: concluída (≥ 90 %) ou pulada (< 30 s ou < 1/3) — a mesma regra do app. */
export function outcome(listenedMs, durationMs) {
  const c = durationMs > 0 && listenedMs >= durationMs * 0.9;
  const k = !c && (listenedMs < 30_000 || (durationMs > 0 && listenedMs < durationMs / 3));
  return { c, k };
}

/** As mudanças que a página faz (cada uma devolve um cofre novo; o velho não muda). */
export const edit = {
  like(v, song, now) {
    return { ...v, likes: [...v.likes.filter((l) => l.s.i !== song.videoId), { s: toV(song), at: now }] };
  },
  unlike(v, videoId) {
    return { ...v, likes: v.likes.filter((l) => l.s.i !== videoId) };
  },
  play(v, song, listenedMs, durationMs, at) {
    const { c, k } = outcome(listenedMs, durationMs);
    const p = { i: song.videoId, t: song.title, a: song.artist ?? '', g: null, at, l: Math.round(listenedMs), d: Math.round(durationMs || 0), c, k, pp: null };
    return { ...v, plays: [p, ...v.plays.filter((x) => !(x.i === p.i && x.at === p.at))] };
  },
  newList(v, name, songs, now) {
    // A chave é a hora em que nasceu (a mesma em todos os aparelhos): nunca repete.
    let at = now;
    while (v.lists.some((p) => p.k === String(at))) at++;
    const p = { k: String(at), n: name.trim().slice(0, 100) || '…', ds: null, src: null, p: false, at, up: now, s: songs.map(toV) };
    return [{ ...v, lists: [...v.lists, p] }, p.k];
  },
  list(v, key, change, now) {
    return { ...v, lists: v.lists.map((p) => (p.k === key ? { ...p, ...change(p), up: Math.max(now, p.up + 1) } : p)) };
  },
  dropList(v, key) {
    return { ...v, lists: v.lists.filter((p) => p.k !== key) };
  },
  /** Uma música mandada daqui pra um amigo: entra na conversa (o celular mostra). */
  sent(v, friendId, deliveryId, song, at) {
    const m = { f: friendId, dir: 'out', del: deliveryId, s: toV(song), at, sn: true, k: 'song', t: null, mr: null, mra: 0, tr: null, tra: 0, rp: null, ed: 0 };
    return { ...v, sh: [...v.sh, m] };
  },
};
