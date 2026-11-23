// O FreeSong no computador: num celular, a página de baixar o app; num computador ainda não ligado, o
// QR code; ligado, o cofre do perfil aberto aqui (a chave nunca sai deste navegador), junto com o
// celular nos dois sentidos, o player e a página.
import { createRoot } from 'react-dom/client';
import * as lock from './core/crypto.js';
import { Api, ApiError } from './core/api.js';
import { Store, MemoryStore } from './core/store.js';
import { VaultSync } from './core/sync.js';
import { Player } from './core/player.js';
import { Mixes } from './core/mixes.js';
import { edit, toV } from './core/vault.js';
import { strings, pickLang } from './ui/i18n.js';
import { Ctx, Dialog, Logo, useApp } from './ui/kit.jsx';
import { App, parse } from './ui/app.jsx';
import { LinkPage, PhonePage, isPhone } from './ui/link.jsx';
import { ACCENTS } from './ui/profile.jsx';

const root = createRoot(document.getElementById('root'));
const PREFS = 'freesong-prefs';
let prefs = {};

let installOffer = null;
addEventListener('beforeinstallprompt', (e) => {
  e.preventDefault();
  installOffer = e;
});

function savedPrefs() {
  try {
    return JSON.parse(localStorage.getItem(PREFS) ?? '{}') ?? {};
  } catch {
    return {};
  }
}

function look(p) {
  const dark = p.mode === 'dark' || (p.mode !== 'light' && matchMedia('(prefers-color-scheme: dark)').matches);
  const html = document.documentElement;
  html.dataset.mode = dark ? 'dark' : 'light';
  const [, light, darkC] = ACCENTS.find(([id]) => id === p.accent) ?? ACCENTS[0];
  const c = dark ? darkC : light;
  html.style.setProperty('--accent', c);
  // Texto em cima da cor: escuro nas cores claras.
  const n = parseInt(c.slice(1), 16);
  const lum = (0.299 * (n >> 16) + 0.587 * ((n >> 8) & 255) + 0.114 * (n & 255)) / 255;
  html.style.setProperty('--on-accent', lum > 0.6 ? '#0E0F14' : '#FFFFFF');
  html.lang = p.lang === 'en' ? 'en' : p.lang === 'es' ? 'es' : 'pt-BR';
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', dark ? '#0E0F14' : '#F7F8FB');
}

/** Aberto em outra aba: o FreeSong trabalha numa aba de cada vez (o mesmo cofre, o mesmo player). */
function OtherTab({ t, onUse }) {
  return (
    <div className="center"><div className="phone-page"><Logo size={72} /><h1>{t('otherTab')}</h1><p className="lead">{t('otherTabHint')}</p><button type="button" className="pill on big" onClick={onUse}>{t('useHere')}</button></div></div>
  );
}

function Unlinked({ t }) {
  return (
    <div className="center"><div className="phone-page"><Logo size={72} /><h1>{t('unlinked')}</h1><p className="lead">{t('unlinkedHint')}</p><button type="button" className="pill on big" onClick={() => location.reload()}>{t('linkNew')}</button></div></div>
  );
}

const ACTIVE = 'freesong-web-active';
/** Solta a vez desta aba (o computador foi desligado: outra aba pode ligar de novo). */
let releaseTab = () => {};

/** [run] só nesta aba enquanto nenhuma outra roda (Web Locks); a outra mostra "Usar aqui". */
function exclusive(run, t) {
  if (!navigator.locks?.request) return run();
  const hold = (opts) => navigator.locks.request(ACTIVE, opts, async (held) => {
    if (!held) return 'busy';
    await run();
    return new Promise((r) => { releaseTab = r; });
  });
  const watch = (p) => p.then(
    (r) => { if (r === 'busy') root.render(<OtherTab t={t} onUse={() => watch(hold({ steal: true }))} />); },
    () => location.reload(),
  );
  watch(hold({ ifAvailable: true }));
  return undefined;
}

async function start() {
  prefs = savedPrefs();
  look(prefs);
  matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => look(prefs));
  const lang = pickLang(prefs.lang);
  const t = strings(lang);
  if (isPhone()) {
    root.render(<PhonePage t={t} />);
    return;
  }
  const store = await new Store().open();
  const [key, token, proof, me] = [await store.get('key'), await store.get('token'), await store.get('proof'), await store.get('me')];
  if (!key || !token || !proof || !me) {
    if (key || token || proof || me) await store.wipe().then(() => store.open());
    const api = new Api({ lang });
    root.render(<LinkPage api={api} t={t} onLinked={async (got) => {
      const where = got.shared ? new MemoryStore() : store;
      let k = got.key;
      // Pelo código de recuperação: a chave do cofre sai do próprio código (ou nasce agora, sem cofre ainda).
      if (!k) {
        const linkedApi = new Api({ token: got.token, proof: got.proof, lang });
        const v = await linkedApi.vault();
        if (v) {
          k = await lock.unwrapWithCode(v.key, got.code, got.me.id);
          if (!k) {
            await linkedApi.unlink().catch(() => {});
            throw new Error('locked');
          }
        } else {
          const fresh = await lock.newDataKey(got.code, got.me.id);
          k = fresh.key;
          await linkedApi.putVault(0, await lock.sealVault({}, k), fresh.wrapped);
        }
      }
      await where.set('key', k);
      await where.set('proof', got.proof);
      await where.set('token', got.token);
      await where.set('me', got.me);
      await where.set('owner', got.owner ?? '');
      if (got.shared) return linked({ store: where, shared: true, t, lang });
      location.reload();
      return undefined;
    }} />);
    return;
  }
  store.close();
  exclusive(async () => {
    const s2 = await new Store().open();
    if (!(await s2.get('key')) || !(await s2.get('token'))) return location.reload();
    return linked({ store: s2, shared: false, t, lang });
  }, t);
}

async function linked({ store, shared, t, lang }) {
  const [key, token, proof] = [await store.get('key'), await store.get('token'), await store.get('proof')];
  let me = await store.get('me');
  const owner = await store.get('owner');
  let unlinked = false;
  let tabs = null;
  const unlink = async (tellServer) => {
    if (unlinked) return;
    unlinked = true;
    sync.stop();
    player.pause();
    if (tellServer) await api.unlink().catch(() => {});
    await store.wipe();
    try { tabs?.postMessage('unlinked'); } catch { /* fechado */ }
    document.getElementById('yt-dock')?.replaceChildren();
    root.render(<Unlinked t={t} />);
    releaseTab();
  };
  if (!shared) {
    try {
      tabs = new BroadcastChannel('freesong-web');
      tabs.onmessage = (e) => { if (e.data === 'unlinked') unlink(false); };
    } catch { /* navegador antigo: cada aba descobre sozinha (o relay diz 401) */ }
  }
  const api = new Api({ token, proof, lang, onUnlinked: () => unlink(false) });
  const sync = new VaultSync({ api, key, store });
  await sync.load();
  const mixes = new Mixes({ api, sync });

  // As escolhas deste computador (tema, cor, idioma, o painel, volume): só neste navegador.
  const setPrefs = (p, { quiet = false } = {}) => {
    const langChanged = p.lang && p.lang !== lang;
    prefs = p;
    try { localStorage.setItem(PREFS, JSON.stringify(p)); } catch { /* janela anônima */ }
    if (quiet) return;
    look(p);
    if (langChanged) location.reload();
    else rerender();
  };

  const ui = { menu: () => {}, toast: () => {}, dialog: () => {} };
  const toast = (text, action) => ui.toast(text, action);

  const player = new Player({
    host: document.getElementById('yt-dock'),
    api,
    prefs: prefs.player ?? {},
    savePrefs: (pp) => setPrefs({ ...prefs, player: pp }, { quiet: true }),
    onListen: (song, ms, durationMs) => sync.change((v) => edit.play(v, song, ms, durationMs, Date.now()), { quiet: true }),
    onToast: (what, s) => toast(t(what, s.title)),
  });

  let friendsCache = null;
  const loadFriends = async (fresh = false) => {
    if (friendsCache && !fresh) return friendsCache;
    friendsCache = await api.friends();
    return friendsCache;
  };

  const isLiked = (id) => sync.data.likes.some((l) => l.s.i === id);

  const app = {
    t, lang, api, sync, player, mixes, shared, owner,
    dockRef: { current: document.getElementById('yt-dock') },
    me: () => me,
    prefs: () => prefs,
    setPrefs,
    bindUi: (u) => Object.assign(ui, u),
    toast,
    openMenu: (e, items) => { e?.stopPropagation?.(); ui.menu(e, items); },
    isLiked,
    toggleLike: (song) => {
      if (isLiked(song.videoId)) {
        sync.change((v) => edit.unlike(v, song.videoId));
        toast(t('unliked'));
      } else {
        sync.change((v) => edit.like(v, song, Date.now()));
        toast(t('liked'));
      }
    },
    playFrom: (list, index = 0, source = null) => {
      if (index === -1) {
        if (!player.shuffle) player.toggleShuffle();
        player.playList(list, Math.floor(Math.random() * list.length), source);
      } else player.playList(list, index, source);
    },
    playRadio: (song, source) => player.playRadio(song, source),
    playArtist: async (id) => {
      try {
        const a = await api.artist(id);
        if (a.top.length) player.playList(a.top, 0, { kind: 'artist', id, title: a.name });
      } catch { toast(t('loadFailed')); }
    },
    playAlbum: async (id) => {
      try {
        const a = await api.album(id);
        if (a.tracks.length) player.playList(a.tracks, 0, { kind: 'album', id, title: a.title });
      } catch { toast(t('loadFailed')); }
    },
    /** Uma música de uma playlist do Spotify: a busca acha a mesma no YouTube Music e toca. */
    findAndPlay: async (track) => {
      try {
        const r = await api.search(`${track.title} ${track.artist}`, 'songs');
        const s = r.items?.find((x) => x.videoId);
        if (s) player.playRadio(s);
        else toast(t('noResults'));
      } catch { toast(t('loadFailed')); }
    },
    mixTitle: (m) => (m.kind === 'because' ? t('becauseYouLike', m.artist)
      : m.kind === 'discover' ? t('discoverForYou')
        : m.kind === 'genre' ? t('genreMix', m.genre.label[lang] ?? m.genre.label.pt)
          : m.kind === 'top' ? t('yourTopSongs') : t('mix')),
    copy: async (text) => {
      try {
        await navigator.clipboard.writeText(text);
        toast(t('linkCopied'));
      } catch { toast(text); }
    },
    editList: (k, change) => sync.change((v) => edit.list(v, k, change, Date.now())),
    deleteList: (k) => {
      const before = sync.data.lists.find((p) => p.k === k);
      sync.change((v) => edit.dropList(v, k));
      location.hash = '/library';
      toast(t('playlistDeleted'), before ? { label: t('undo'), run: () => sync.change((v) => ({ ...v, lists: [...v.lists, { ...before, up: Date.now() }] })) } : undefined);
    },
    saveAsList: (name, songs) => {
      let key = null;
      sync.change((v) => {
        const [next, k] = edit.newList(v, name, songs, Date.now());
        key = k;
        return next;
      });
      toast(t('playlistCreated'), key ? { label: t('open'), run: () => { location.hash = `/list/${key}`; } } : undefined);
    },
    newListDialog: (songs = []) => ui.dialog(<NewList songs={songs} onClose={() => ui.dialog(null)} />),
    /** O submenu "Adicionar à playlist": as playlists da pessoa (e uma nova). */
    playlistTargets: (songs) => [
      { label: t('newPlaylist'), icon: 'plus', onClick: () => app.newListDialog(songs) },
      '-',
      ...[...sync.data.lists].sort((a, b) => (b.p - a.p) || b.up - a.up).map((p) => ({
        label: p.n,
        icon: 'queue',
        onClick: () => {
          const have = new Set(p.s.map((s) => s.i));
          const add = songs.filter((s) => !have.has(s.videoId));
          if (!add.length) return toast(t('alreadyInPlaylist', p.n));
          app.editList(p.k, (l) => ({ s: [...l.s, ...add.map(toV)] }));
          toast(t('addedTo', p.n));
        },
      })),
    ],
    loadFriends,
    sendTo: async (friend, song) => {
      try {
        const r = await api.share(friend.id, { videoId: song.videoId, title: song.title, artist: song.artist ?? '', album: song.album ?? undefined, durationMs: song.durationMs ?? undefined, cover: song.cover ?? undefined, url: `https://music.youtube.com/watch?v=${song.videoId}` });
        sync.change((v) => edit.sent(v, friend.id, r.share.id, song, r.share.at));
        toast(t('sentTo', friend.name));
      } catch (e) {
        toast(e instanceof ApiError && e.code === 'not_friends' ? t('notFriends') : t('loadFailed'));
      }
    },
    /** O menu de uma música (clique direito ou "…"). */
    songMenu: (song, { extra } = {}) => {
      const liked = isLiked(song.videoId);
      const artist = song.artists?.find((a) => a.id);
      const friends = friendsCache?.friends ?? [];
      if (!friendsCache) loadFriends().catch(() => {});
      return [
        { label: t('playNext'), icon: 'next', onClick: () => { player.playNext([song]); toast(t('playingNext')); } },
        { label: t('addToQueue'), icon: 'queue', onClick: () => { player.append([song]); toast(t('addedToQueue')); } },
        { label: t('startRadio'), icon: 'radio', onClick: () => player.playRadio(song) },
        '-',
        { label: liked ? t('unlike') : t('like'), icon: 'heart', onClick: () => app.toggleLike(song) },
        { label: t('addToPlaylist'), icon: 'plus', sub: app.playlistTargets([song]) },
        ...(extra ? [extra] : []),
        '-',
        ...(artist ? [{ label: t('goToArtist'), icon: 'person', onClick: () => { location.hash = `/artist/${artist.id}`; } }] : []),
        ...(song.albumId ? [{ label: t('goToAlbum'), icon: 'album', onClick: () => { location.hash = `/album/${song.albumId}`; } }] : []),
        { label: t('sendToFriend'), icon: 'send', sub: friends.length ? friends.map((f) => ({ label: f.name, icon: 'person', onClick: () => app.sendTo(f, song) })) : [{ label: t('noFriendsShort'), disabled: true }] },
        { label: t('copyLink'), icon: 'link', onClick: () => app.copy(`https://freesong.simplyonegoal.com/${song.video ? 'v' : 'm'}/${song.videoId}`) },
      ];
    },
    rename: async (name) => {
      const r = await api.rename(name);
      me = r.me;
      await store.set('me', me);
      rerender();
    },
    unlink: () => unlink(true),
    canInstall: () => !!installOffer && !shared,
    install: async () => {
      if (!installOffer) return;
      await installOffer.prompt();
      installOffer = null;
    },
  };

  function rerender() {
    root.render(<Ctx.Provider value={{ ...app }}><App /></Ctx.Provider>);
  }

  // O que muda aqui é guardado na hora; ao sair da aba (ou fechar), o que foi ouvido e o resto vão.
  addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') { player.finishListen(); sync.flush(); } });
  addEventListener('pagehide', () => { player.finishListen(); sync.save().catch(() => {}); });
  addEventListener('online', () => sync.push());

  if (shared) {
    // Computador de outra pessoa: fechar a aba desliga este computador (o pedido sai mesmo com a aba
    // fechando: keepalive), assinado antes (a assinatura vale dois minutos: refeita a cada minuto).
    let farewell = null;
    const sign = async () => { farewell = await api.sign('DELETE', '/v1/link').catch(() => farewell); };
    await sign();
    setInterval(sign, 60_000);
    addEventListener('pagehide', () => {
      if (unlinked || !farewell) return;
      fetch('/v1/link', { method: 'DELETE', keepalive: true, credentials: 'omit', headers: { Authorization: `Bearer ${token}`, 'X-FreeSong-Sig': farewell } }).catch(() => {});
    });
    api.markShared().catch(() => {});
  }

  // O perfil de novo (nome, foto ou números trocados no celular).
  const refreshMe = () => api.me().then(async (r) => { me = r.me; await store.set('me', me); rerender(); }, () => {});
  refreshMe();
  setInterval(refreshMe, 10 * 60_000);

  rerender();
  sync.run();

  // Um link compartilhado (#/m/<id>) aberto aqui: toca a música e vai pra Início.
  const shared_ = parse(location.hash);
  if ((shared_.name === 'm' || shared_.name === 'v') && /^[A-Za-z0-9_-]{11}$/.test(shared_.id)) {
    history.replaceState(null, '', '#/');
    api.song(shared_.id).then((s) => player.playRadio(s), () => player.playRadio({ videoId: shared_.id, title: '…', artist: '', artists: [], cover: `https://i.ytimg.com/vi/${shared_.id}/hqdefault.jpg` }));
  }
}

function NewList({ songs, onClose }) {
  const app = useApp();
  return (
    <Dialog title={app.t('newPlaylist')} onClose={onClose}>
      {(close) => (
        <form className="form" onSubmit={(e) => {
          e.preventDefault();
          const name = new FormData(e.currentTarget).get('name')?.toString().trim();
          if (!name) return;
          app.saveAsList(name, songs);
          close();
        }}>
          <label>{app.t('name')}<input name="name" defaultValue={app.t('myPlaylist')} maxLength={100} autoFocus onFocus={(e) => e.currentTarget.select()} /></label>
          {songs.length ? <p className="faint">{app.t('songsCount', songs.length)}</p> : null}
          <div className="form-actions"><button type="button" className="pill ghost" onClick={close}>{app.t('cancel')}</button><button type="submit" className="pill on">{app.t('create')}</button></div>
        </form>
      )}
    </Dialog>
  );
}

start().catch((e) => {
  console.error(e);
  root.render(<div className="center"><p className="faint">{String(e?.message ?? e)}</p></div>);
});
