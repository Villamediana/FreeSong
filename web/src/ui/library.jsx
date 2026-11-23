// O que é da pessoa (vem do cofre, junto com o celular): a Biblioteca, as curtidas, cada playlist dela
// (renomear, apagar, tirar música, mudar a ordem) e o histórico do que ouviu.
import { useEffect, useMemo, useRef, useState } from 'react';
import { recent, topArtists } from '../core/taste.js';
import { fromV, playSong } from '../core/vault.js';
import { ago, Collage, Cover, Dialog, Icon, IconBtn, sized, useApp, useWatch } from './kit.jsx';
import { Header, length } from './browse.jsx';
import { Card, Grid, SongList } from './songs.jsx';

const total = (songs) => songs.reduce((n, s) => n + (s.durationMs ?? 0), 0);

export function Library() {
  const app = useApp();
  const sync = useWatch(app.sync);
  const v = sync.data;
  const [tab, setTab] = useState('lists');
  const lists = [...v.lists].sort((a, b) => (b.p - a.p) || b.up - a.up);
  const artists = useMemo(() => topArtists(v, Date.now(), 40), [v.plays, v.likes]);
  return (
    <div className="page">
      <div className="lib-head">
        <h1 className="page-title">{app.t('library')}</h1>
        <button type="button" className="pill" onClick={() => app.newListDialog()}><Icon name="plus" size={17} /> {app.t('newPlaylist')}</button>
      </div>
      <div className="chips">
        {['lists', 'artists', 'imported'].map((k) => (k === 'imported' && !v.imp.length ? null : (
          <button key={k} type="button" className={`chip${tab === k ? ' on' : ''}`} onClick={() => setTab(k)}>{app.t(k === 'lists' ? 'playlists' : k === 'artists' ? 'artists' : 'fromSpotify')}</button>
        )))}
      </div>
      {tab === 'lists' ? (
        <Grid>
          <Card item={{ kind: 'liked', href: '#/liked', title: app.t('likedSongs'), subtitle: app.t('songsCount', v.likes.length), cover: null, covers: v.likes.length >= 4 ? [...v.likes].sort((a, b) => b.at - a.at).map((l) => l.s.c) : null }} />
          {lists.map((p) => (
            <Card
              key={p.k}
              item={{ kind: 'list', id: p.k, title: p.n, subtitle: `${p.p ? '📌 ' : ''}${app.t('songsCount', p.s.length)}`, covers: p.s.length >= 4 ? p.s.map((s) => s.c) : null, cover: p.s[0]?.c }}
              onPlay={p.s.length ? () => app.playFrom(p.s.map(fromV), 0, { kind: 'list', id: p.k, title: p.n }) : undefined}
            />
          ))}
        </Grid>
      ) : null}
      {tab === 'artists' ? (
        artists.length ? (
          <Grid>
            {artists.map((a) => (
              <Card key={a.name} item={{ kind: 'artistSearch', href: `#/search?q=${encodeURIComponent(a.name)}&type=artists`, title: a.name, subtitle: app.t('artist'), cover: a.covers[0] ?? a.best?.cover }} onPlay={a.best ? () => app.playRadio(a.best, { kind: 'radio', id: a.best.videoId, title: app.t('radioOf', a.name) }) : undefined} />
            ))}
          </Grid>
        ) : <div className="empty"><p>{app.t('nothingYet')}</p></div>
      ) : null}
      {tab === 'imported' ? (
        <Grid>
          {v.imp.map((p) => (
            <Card key={p.u} item={{ kind: 'imported', href: `#/imported/${encodeURIComponent(p.u)}`, title: p.n, subtitle: p.o ? `Spotify · ${p.o}` : 'Spotify', cover: p.c }} />
          ))}
        </Grid>
      ) : null}
    </div>
  );
}

export function Liked() {
  const app = useApp();
  const sync = useWatch(app.sync);
  const songs = [...sync.data.likes].sort((a, b) => b.at - a.at).map((l) => ({ ...fromV(l.s), likedAt: l.at }));
  const src = { kind: 'liked', id: 'liked', title: app.t('likedSongs') };
  const p = useWatch(app.player);
  const playing = p.playing && p.source?.kind === 'liked';
  return (
    <div className="page">
      <Header
        kind={app.t('playlist')}
        title={app.t('likedSongs')}
        covers={songs.length >= 4 ? songs.map((s) => s.cover) : null}
        cover={songs[0]?.cover}
        meta={[app.me()?.name, app.t('songsCount', songs.length), length(total(songs), app.t)]}
        playing={playing}
        onPlay={songs.length ? () => (playing ? p.toggle() : app.playFrom(songs, 0, src)) : undefined}
        onShuffle={songs.length ? () => app.playFrom(songs, -1, src) : undefined}
      />
      {songs.length ? <SongList songs={songs} source={src} numbered /> : <div className="empty"><Icon name="heart" size={34} /><p>{app.t('noLikes')}</p></div>}
    </div>
  );
}

export function MyList({ id }) {
  const app = useApp();
  const sync = useWatch(app.sync);
  const p = useWatch(app.player);
  const list = sync.data.lists.find((x) => x.k === id);
  const [editing, setEditing] = useState(false);
  if (!list) return <div className="page"><div className="empty"><p>{app.t('listGone')}</p><a className="pill" href="#/library">{app.t('library')}</a></div></div>;
  const songs = list.s.map(fromV);
  const src = { kind: 'list', id, title: list.n };
  const playing = p.playing && p.source?.kind === 'list' && p.source?.id === id;
  const move = (from, to) => {
    if (from === to) return;
    app.editList(id, (l) => {
      const s = [...l.s];
      const [x] = s.splice(from, 1);
      s.splice(to, 0, x);
      return { s };
    });
  };
  return (
    <div className="page">
      <Header
        kind={app.t('playlist')}
        title={list.n}
        covers={list.s.length >= 4 ? list.s.map((s) => s.c) : null}
        cover={list.s[0]?.c}
        description={list.ds}
        meta={[list.p ? app.t('pinned') : null, app.t('songsCount', songs.length), length(total(songs), app.t)]}
        playing={playing}
        onPlay={songs.length ? () => (playing ? p.toggle() : app.playFrom(songs, 0, src)) : undefined}
        onShuffle={songs.length ? () => app.playFrom(songs, -1, src) : undefined}
        actions={<IconBtn icon="edit" label={app.t('rename')} size={20} onClick={() => setEditing(true)} />}
        menu={[
          { label: list.p ? app.t('unpin') : app.t('pin'), icon: 'pin', onClick: () => app.editList(id, (l) => ({ p: !l.p })) },
          { label: app.t('rename'), icon: 'edit', onClick: () => setEditing(true) },
          { label: app.t('addToQueue'), icon: 'queue', disabled: !songs.length, onClick: () => { app.player.append(songs); app.toast(app.t('addedToQueue')); } },
          '-',
          { label: app.t('deletePlaylist'), icon: 'trash', danger: true, onClick: () => app.deleteList(id) },
        ]}
      />
      {songs.length ? (
        <div className="songs editable">
          <SongList
            songs={songs}
            source={src}
            numbered
            extra={(s, i) => ({ label: app.t('removeFromPlaylist'), icon: 'trash', onClick: () => app.editList(id, (l) => ({ s: l.s.filter((_, k) => k !== i) })) })}
          />
          <DragLayer count={songs.length} move={move} />
        </div>
      ) : (
        <div className="empty"><Icon name="search" size={34} /><p>{app.t('emptyList')}</p><a className="pill" href="#/search">{app.t('search')}</a></div>
      )}
      {editing ? <RenameDialog list={list} onClose={() => setEditing(false)} /> : null}
    </div>
  );
}

/** Arrastar uma linha pra mudar a ordem (as linhas da lista ganham o "puxador" pelo CSS). */
function DragLayer({ count, move }) {
  const onDown = (e) => {
    const row = e.target.closest?.('.editable .row');
    if (!row || e.button !== 0 || e.target.closest('button, a')) return;
    const rows = [...row.parentElement.children].filter((x) => x.classList.contains('row'));
    const from = rows.indexOf(row);
    const startY = e.clientY;
    const h = row.getBoundingClientRect().height;
    let to = from;
    let moved = false;
    const onMove = (ev) => {
      const dy = ev.clientY - startY;
      if (!moved && Math.abs(dy) < 6) return;
      moved = true;
      to = Math.max(0, Math.min(count - 1, from + Math.round(dy / h)));
      rows.forEach((r, i) => {
        r.style.transform = i === from ? `translateY(${dy}px)` : i > from && i <= to ? `translateY(${-h}px)` : i < from && i >= to ? `translateY(${h}px)` : '';
        r.classList.toggle('dragging', i === from);
      });
    };
    const onUp = () => {
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
      rows.forEach((r) => { r.style.transform = ''; r.classList.remove('dragging'); });
      if (moved) move(from, to);
    };
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
  };
  // Escuta no documento as linhas da lista editável.
  const ref = useRef(onDown);
  ref.current = onDown;
  useEffect(() => {
    const h = (e) => ref.current(e);
    document.addEventListener('pointerdown', h);
    return () => document.removeEventListener('pointerdown', h);
  }, []);
  return null;
}

function RenameDialog({ list, onClose }) {
  const app = useApp();
  const [name, setName] = useState(list.n);
  const [desc, setDesc] = useState(list.ds ?? '');
  return (
    <Dialog title={app.t('editPlaylist')} onClose={onClose}>
      {(close) => (
        <form className="form" onSubmit={(e) => { e.preventDefault(); if (!name.trim()) return; app.editList(list.k, () => ({ n: name.trim().slice(0, 100), ds: desc.trim().slice(0, 300) || null })); close(); }}>
          <label>{app.t('name')}<input value={name} onChange={(e) => setName(e.target.value)} maxLength={100} autoFocus /></label>
          <label>{app.t('description')}<textarea value={desc} onChange={(e) => setDesc(e.target.value)} maxLength={300} rows={3} /></label>
          <div className="form-actions"><button type="button" className="pill ghost" onClick={close}>{app.t('cancel')}</button><button type="submit" className="pill on">{app.t('save')}</button></div>
        </form>
      )}
    </Dialog>
  );
}

export function History() {
  const app = useApp();
  const sync = useWatch(app.sync);
  const plays = sync.data.plays.slice(0, 300);
  const covers = useMemo(() => new Map(recent(sync.data, 400).map((s) => [s.videoId, s.cover])), [sync.data]);
  const songs = plays.map((p) => ({ ...playSong(p), cover: covers.get(p.i) ?? playSong(p).cover, at: p.at }));
  const groups = [];
  for (const s of songs) {
    const day = new Date(s.at).toLocaleDateString(app.lang, { weekday: 'long', day: 'numeric', month: 'long' });
    if (!groups.length || groups.at(-1).day !== day) groups.push({ day, songs: [] });
    groups.at(-1).songs.push(s);
  }
  return (
    <div className="page">
      <h1 className="page-title">{app.t('history')}</h1>
      {groups.length ? groups.map((g) => (
        <section key={g.day} className="section">
          <h2 className="day">{g.day}</h2>
          <SongList songs={g.songs} source={{ kind: 'history', id: 'history', title: app.t('history') }} showAlbum={false} />
        </section>
      )) : <div className="empty"><Icon name="history" size={34} /><p>{app.t('nothingYet')}</p></div>}
    </div>
  );
}

export function Imported({ url }) {
  const app = useApp();
  const sync = useWatch(app.sync);
  const p = sync.data.imp.find((x) => x.u === url);
  if (!p) return <div className="page"><div className="empty"><p>{app.t('listGone')}</p></div></div>;
  let tracks = [];
  try {
    tracks = JSON.parse(p.j);
  } catch { /* torto */ }
  const rows = (Array.isArray(tracks) ? tracks : []).map((t) => ({ title: t.title ?? t.name ?? '', artist: Array.isArray(t.artists) ? t.artists.join(', ') : t.artist ?? '', durationMs: t.durationMs ?? null })).filter((t) => t.title);
  return (
    <div className="page">
      <Header kind="Spotify" title={p.n} cover={p.c} meta={[p.o, app.t('songsCount', p.nn)]} />
      <div className="songs">
        {rows.map((t, i) => (
          <div key={i} className="row plain" onDoubleClick={() => app.findAndPlay(t)}>
            <div className="row-n"><span className="n">{i + 1}</span></div>
            <div className="row-main"><span className="row-title">{t.title}</span><span className="artists">{t.artist}</span></div>
            <div className="row-end"><IconBtn icon="search" label={app.t('search')} size={17} onClick={() => app.findAndPlay(t)} /></div>
          </div>
        ))}
      </div>
    </div>
  );
}

export { ago, Collage, Cover, sized };
