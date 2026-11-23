// A moldura da página: a barra do lado (Início, Buscar, Biblioteca, as playlists), a barra de cima
// (voltar, a busca com sugestões, o perfil), a tela aberta, o painel "Tocando agora" e o player embaixo.
import { useEffect, useRef, useState } from 'react';
import { fromV } from '../core/vault.js';
import { Collage, Cover, Icon, IconBtn, Logo, Menu, Toasts, sized, useApp, useWatch } from './kit.jsx';
import { Home } from './home.jsx';
import { Search } from './search.jsx';
import { Album, Artist, Mix, Playlist } from './browse.jsx';
import { History, Imported, Library, Liked, MyList } from './library.jsx';
import { Friends, Profile } from './profile.jsx';
import { NowPlaying, PlayerBar, VideoDock, useKeys } from './nowplaying.jsx';

export function parse(hash) {
  const h = hash.replace(/^#\/?/, '');
  const [path, query] = h.split('?');
  const parts = path.split('/').map((p) => { try { return decodeURIComponent(p); } catch { return p; } });
  return { name: parts[0] || 'home', id: parts.slice(1).join('/'), params: new URLSearchParams(query ?? '') };
}

function useRoute() {
  const [r, setR] = useState(() => parse(location.hash));
  useEffect(() => {
    const on = () => setR(parse(location.hash));
    window.addEventListener('hashchange', on);
    return () => window.removeEventListener('hashchange', on);
  }, []);
  return r;
}

function Sidebar({ route }) {
  const app = useApp();
  const sync = useWatch(app.sync);
  const lists = [...sync.data.lists].sort((a, b) => (b.p - a.p) || b.up - a.up);
  const me = app.me();
  const nav = [
    ['home', 'home', '#/'],
    ['search', 'search', '#/search'],
    ['library', 'library', '#/library'],
    ['history', 'history', '#/history'],
    ['friends', 'people', '#/friends'],
  ];
  const st = sync.status;
  return (
    <nav className="side" aria-label="FreeSong">
      <a className="brand" href="#/"><Logo size={34} /><span>FreeSong</span></a>
      <div className="side-nav">
        {nav.map(([name, icon, href]) => (
          <a key={name} href={href} className={route.name === name || (name === 'home' && route.name === 'home') ? 'on' : ''} title={app.t(name)}>
            <Icon name={icon} size={21} /><span>{app.t(name)}</span>
          </a>
        ))}
      </div>
      <div className="side-lists">
        <div className="side-lists-head">
          <span>{app.t('yourLibrary')}</span>
          <IconBtn icon="plus" label={app.t('newPlaylist')} size={18} onClick={() => app.newListDialog()} />
        </div>
        <div className="side-scroll">
          <a href="#/liked" className={`side-item${route.name === 'liked' ? ' on' : ''}`} title={app.t('likedSongs')}>
            <span className="liked-art"><Icon name="heart" fill size={18} /></span>
            <span className="side-item-text"><b>{app.t('likedSongs')}</b><small>{app.t('songsCount', sync.data.likes.length)}</small></span>
          </a>
          {lists.map((p) => (
            <a key={p.k} href={`#/list/${p.k}`} className={`side-item${route.name === 'list' && route.id === p.k ? ' on' : ''}`} title={p.n}>
              {p.s.length >= 4 ? <Collage covers={p.s.map((s) => sized(s.c, 96))} size={42} /> : <Cover src={sized(p.s[0]?.c, 96)} size={42} icon="queue" />}
              <span className="side-item-text"><b>{p.n}</b><small>{p.p ? <Icon name="pin" size={11} /> : null}{app.t('songsCount', p.s.length)}</small></span>
            </a>
          ))}
        </div>
      </div>
      <a className={`side-me${route.name === 'profile' ? ' on' : ''}`} href="#/profile" title={app.t('profile')}>
        {me?.avatar ? <Cover src={me.avatar} size={32} round icon="person" /> : <span className="avatar-letter" style={{ width: 32, height: 32, fontSize: 14 }}>{(me?.name ?? '?').slice(0, 1).toUpperCase()}</span>}
        <span className="side-item-text"><b>{me?.name ?? ''}</b><small className={`sync-dot ${!st.online ? 'off' : st.syncing ? 'busy' : 'ok'}`}>{!st.online ? app.t('offline') : st.syncing ? app.t('syncing') : app.t('synced')}</small></span>
      </a>
    </nav>
  );
}

/** A busca da barra de cima: o texto vai pro endereço (#/search?q=) e as sugestões aparecem embaixo. */
function SearchBox({ route }) {
  const app = useApp();
  const input = useRef(null);
  const [text, setText] = useState(route.name === 'search' ? route.params.get('q') ?? '' : '');
  const [sugg, setSugg] = useState(null);
  const [open, setOpen] = useState(false);
  const timer = useRef(null);
  useEffect(() => {
    if (route.name === 'search') setText(route.params.get('q') ?? '');
  }, [route.name, route.params.get('q')]);
  useEffect(() => {
    const focus = () => input.current?.focus();
    window.addEventListener('freesong:search', focus);
    return () => window.removeEventListener('freesong:search', focus);
  }, []);
  useEffect(() => {
    if (!text.trim() || !open) return setSugg(null);
    const ctl = new AbortController();
    const h = setTimeout(() => app.api.suggest(text.trim(), ctl.signal).then(setSugg, () => {}), 160);
    return () => { clearTimeout(h); ctl.abort(); };
  }, [text, open]);
  const go = (q) => {
    const type = route.name === 'search' ? route.params.get('type') ?? '' : '';
    const target = `#/search?q=${encodeURIComponent(q)}${type ? `&type=${type}` : ''}`;
    if (route.name === 'search') history.replaceState(null, '', target);
    else location.hash = target.slice(1);
    window.dispatchEvent(new HashChangeEvent('hashchange'));
  };
  return (
    <div className="search-box">
      <Icon name="search" size={18} />
      <input
        ref={input}
        value={text}
        placeholder={app.t('searchPlaceholder')}
        onFocus={() => setOpen(true)}
        onBlur={() => setTimeout(() => setOpen(false), 150)}
        onChange={(e) => {
          const v = e.target.value;
          setText(v);
          setOpen(true);
          clearTimeout(timer.current);
          // A busca acontece sozinha um instante depois de parar de digitar (e as sugestões saem da frente).
          timer.current = setTimeout(() => { if (v.trim()) { go(v.trim()); setOpen(false); } }, 650);
        }}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && text.trim()) { clearTimeout(timer.current); go(text.trim()); setOpen(false); }
          if (e.key === 'Escape') { setOpen(false); e.currentTarget.blur(); }
        }}
        aria-label={app.t('search')}
      />
      {text ? <IconBtn icon="close" label={app.t('clear')} size={16} onClick={() => { setText(''); input.current?.focus(); }} /> : null}
      {open && sugg && (sugg.queries.length || sugg.items.length) ? (
        <div className="suggest">
          {sugg.queries.map((q) => (
            <button key={q} type="button" onMouseDown={(e) => e.preventDefault()} onClick={() => { setText(q); go(q); setOpen(false); }}>
              <Icon name="search" size={16} /><span>{q}</span>
            </button>
          ))}
          {sugg.items.map((it) => (
            <button
              key={it.videoId ?? it.id}
              type="button"
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => {
                setOpen(false);
                if (it.videoId) app.playRadio(it);
                else location.hash = it.kind === 'artist' ? `/artist/${it.id}` : it.kind === 'album' ? `/album/${it.id}` : `/playlist/${it.id}`;
              }}
            >
              <Cover src={sized(it.cover, 96)} size={34} round={it.kind === 'artist'} icon={it.kind === 'artist' ? 'person' : 'album'} />
              <span className="sugg-text"><b>{it.title ?? it.name}</b><small>{it.videoId ? `${app.t('song')} · ${it.artist}` : it.kind === 'artist' ? app.t('artist') : `${app.t(it.kind)}${it.artist ? ` · ${it.artist}` : it.author ? ` · ${it.author}` : ''}`}</small></span>
            </button>
          ))}
        </div>
      ) : null}
    </div>
  );
}

function Page({ route }) {
  switch (route.name) {
    case 'search': return <Search q={route.params.get('q') ?? ''} type={route.params.get('type') ?? ''} />;
    case 'artist': return <Artist id={route.id} />;
    case 'album': return <Album id={route.id} />;
    case 'playlist': return <Playlist id={route.id} />;
    case 'mix': return <Mix id={route.id} />;
    case 'liked': return <Liked />;
    case 'list': return <MyList id={route.id} />;
    case 'library': return <Library />;
    case 'history': return <History />;
    case 'friends': return <Friends />;
    case 'profile': return <Profile />;
    case 'imported': return <Imported url={route.id} />;
    default: return <Home />;
  }
}

export function App() {
  const app = useApp();
  const route = useRoute();
  const prefs = app.prefs();
  const [panel, setPanel] = useState(prefs.panel ?? innerWidth >= 1180);
  const [tab, setTab] = useState(prefs.tab ?? 'lyrics');
  const [menu, setMenu] = useState(null);
  const [toasts, setToasts] = useState([]);
  const [dialog, setDialog] = useState(null);
  const main = useRef(null);
  const slot = useRef(null);
  const togglePanel = (on) => {
    setPanel(on);
    app.setPrefs({ ...app.prefs(), panel: on }, { quiet: true });
  };
  useEffect(() => { app.setPrefs({ ...app.prefs(), tab }, { quiet: true }); }, [tab]);
  useEffect(() => {
    app.bindUi({
      menu: (e, items) => {
        const r = e?.currentTarget?.getBoundingClientRect?.();
        const at = e?.type === 'contextmenu' || !r ? { x: e.clientX, y: e.clientY } : { x: r.left, y: r.bottom + 4 };
        setMenu({ at, items, key: Date.now() });
      },
      toast: (text, action) => {
        const id = Math.random();
        setToasts((l) => [...l.slice(-2), { id, text, action }]);
        setTimeout(() => setToasts((l) => l.filter((x) => x.id !== id)), action ? 6000 : 2600);
      },
      dialog: setDialog,
    });
  }, []);
  useEffect(() => { main.current?.scrollTo(0, 0); }, [route.name, route.id, route.params.get('type')]);
  useKeys((what) => { if (what === 'search') { if (route.name !== 'search') location.hash = '/search'; setTimeout(() => window.dispatchEvent(new Event('freesong:search')), 30); } });
  return (
    <div className={`shell${panel ? ' with-np' : ''}`}>
      <Sidebar route={route} />
      <div className="main-wrap">
        <div className="top">
          <div className="top-nav">
            <IconBtn icon="back" label={app.t('back')} onClick={() => history.back()} />
            <IconBtn icon="forward" label={app.t('forward')} onClick={() => history.forward()} />
          </div>
          <SearchBox route={route} />
          <div className="top-end">
            {app.shared ? <span className="shared-badge" title={app.t('sharedComputerHint')}><Icon name="lock" size={14} /> {app.t('sharedComputer')}</span> : null}
            <IconBtn icon="video" label={app.t('nowPlaying')} active={panel} onClick={() => togglePanel(!panel)} />
          </div>
        </div>
        <main className="main" ref={main}>
          <Page route={route} />
        </main>
      </div>
      {panel ? <NowPlaying slot={slot} tab={tab} setTab={setTab} onClose={() => togglePanel(false)} /> : null}
      <PlayerBar panel={panel} togglePanel={togglePanel} tab={tab} setTab={setTab} />
      <VideoDock slot={slot} host={app.dockRef} open={panel} />
      {menu ? <Menu key={menu.key} at={menu.at} items={menu.items} onClose={() => setMenu(null)} /> : null}
      {dialog}
      <Toasts list={toasts} />
    </div>
  );
}

export { fromV };
