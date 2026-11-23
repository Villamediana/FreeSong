// As páginas de uma coleção: artista, álbum, playlist do YouTube Music e os mixes da Início — e o
// cabeçalho que as playlists da pessoa e as curtidas também usam.
import { useEffect, useState } from 'react';
import { Collage, Cover, Failed, Icon, IconBtn, Loading, PlayBtn, sized, time, useApp, useLoad, useWatch } from './kit.jsx';
import { Card, Shelf, SongList } from './songs.jsx';

const total = (songs) => songs.reduce((n, s) => n + (s.durationMs ?? 0), 0);

/** A duração de uma lista ("1 h 12 min", "42 min"). */
export function length(ms, t) {
  const m = Math.round(ms / 60_000);
  return m >= 60 ? t('hoursMinutes', Math.floor(m / 60), m % 60) : t('minutes', m);
}

/** O cabeçalho de uma coleção: capa (a cor dela por trás), o que é, o nome, quem fez, e os botões. */
export function Header({ kind, title, cover, covers, round = false, meta = [], description, actions, onPlay, onShuffle, playing, menu }) {
  const app = useApp();
  return (
    <header className={`hero${round ? ' artist' : ''}`}>
      {cover || covers?.[0] ? <img className="hero-wash" src={sized(cover ?? covers?.[0], 120)} alt="" referrerPolicy="no-referrer" aria-hidden="true" /> : null}
      <div className="hero-body">
        {covers?.length >= 4 ? <Collage covers={covers.map((c) => sized(c, 300))} size={208} /> : <Cover src={sized(cover, 480)} size={208} round={round} icon={round ? 'person' : 'album'} />}
        <div className="hero-text">
          <span className="hero-kind">{kind}</span>
          <h1 className={title.length > 28 ? 'long' : ''}>{title}</h1>
          {description ? <p className="hero-desc">{description}</p> : null}
          <p className="hero-meta">{meta.filter(Boolean).map((m, i) => <span key={i}>{m}</span>)}</p>
        </div>
      </div>
      <div className="hero-actions">
        {onPlay ? <PlayBtn size="lg" playing={playing} label={app.t('play')} onClick={onPlay} /> : null}
        {onShuffle ? <IconBtn icon="shuffle" label={app.t('shuffle')} size={22} onClick={onShuffle} /> : null}
        {actions}
        {menu ? <IconBtn icon="more" label={app.t('more')} size={22} onClick={(e) => app.openMenu(e, menu)} /> : null}
      </div>
    </header>
  );
}

/** Tocando esta coleção agora? (o play vira pausa) */
function usePlaying(source) {
  const app = useApp();
  const p = useWatch(app.player);
  return p.playing && p.source?.kind === source.kind && p.source?.id === source.id;
}

function playOrToggle(app, songs, source, playing) {
  if (playing) return app.player.toggle();
  if (app.player.source?.kind === source.kind && app.player.source?.id === source.id && app.player.current) return app.player.toggle();
  return app.playFrom(songs, 0, source);
}

function listMenu(app, songs, title) {
  return [
    { label: app.t('addToQueue'), icon: 'queue', onClick: () => { app.player.append(songs); app.toast(app.t('addedToQueue')); } },
    { label: app.t('addToPlaylist'), icon: 'plus', sub: app.playlistTargets(songs) },
    { label: app.t('saveAsPlaylist'), icon: 'library', onClick: () => app.saveAsList(title, songs) },
  ];
}

export function Artist({ id }) {
  const app = useApp();
  const r = useLoad(() => app.api.artist(id), [id, app.lang]);
  const source = { kind: 'artist', id };
  const playing = usePlaying(source);
  if (r.loading) return <Loading />;
  if (r.error) return <Failed onRetry={r.retry} />;
  const a = r.data;
  const start = () => playOrToggle(app, a.top, { ...source, title: a.name }, playing);
  return (
    <div className="page">
      <header className="artist-hero" style={a.cover ? { '--art': `url("${sized(a.cover, 1080)}")` } : undefined}>
        {a.cover ? <img className="artist-img" src={sized(a.cover, 1080)} alt="" referrerPolicy="no-referrer" /> : null}
        <div className="artist-shade" />
        <div className="artist-text">
          <span className="hero-kind">{app.t('artist')}</span>
          <h1>{a.name}</h1>
          {a.subtitle ? <p className="hero-meta"><span>{a.subtitle}</span></p> : null}
        </div>
      </header>
      <div className="hero-actions">
        <PlayBtn size="lg" playing={playing} label={app.t('play')} onClick={start} />
        <IconBtn icon="shuffle" label={app.t('shuffle')} size={22} onClick={() => app.playFrom(a.top, -1, { ...source, title: a.name })} />
        <button type="button" className="pill" onClick={() => a.top[0] && app.playRadio(a.top[0], { kind: 'radio', id: a.top[0].videoId, title: app.t('radioOf', a.name) })}><Icon name="radio" size={17} /> {app.t('radio')}</button>
      </div>
      {a.top.length ? (
        <section className="section">
          <h2>{app.t('popular')}</h2>
          <SongList songs={a.top} source={{ ...source, title: a.name }} numbered />
          {a.songsId ? <a className="more-link" href={`#/playlist/${a.songsId}`}>{app.t('allSongs')}</a> : null}
        </section>
      ) : null}
      {a.albums.length ? <Shelf title={app.t('albums')}>{a.albums.map((x) => <Card key={x.id} item={x} />)}</Shelf> : null}
      {a.singles.length ? <Shelf title={app.t('singles')}>{a.singles.map((x) => <Card key={x.id} item={x} />)}</Shelf> : null}
      {a.videos.length ? <Shelf title={app.t('videos')}>{a.videos.map((s) => <Card key={s.videoId} item={{ ...s, subtitle: s.artist }} />)}</Shelf> : null}
      {a.playlists.length ? <Shelf title={app.t('featuredOn')}>{a.playlists.map((x) => <Card key={x.id} item={x} />)}</Shelf> : null}
      {a.similar.length ? <Shelf title={app.t('fansAlsoLike')}>{a.similar.map((x) => <Card key={x.id} item={x} />)}</Shelf> : null}
      {a.description ? (
        <section className="section about">
          <h2>{app.t('about')}</h2>
          <p>{a.description}</p>
        </section>
      ) : null}
    </div>
  );
}

export function Album({ id }) {
  const app = useApp();
  const r = useLoad(() => app.api.album(id), [id, app.lang]);
  const source = { kind: 'album', id };
  const playing = usePlaying(source);
  if (r.loading) return <Loading />;
  if (r.error) return <Failed onRetry={r.retry} />;
  const a = r.data;
  const src = { ...source, title: a.title };
  return (
    <div className="page">
      <Header
        kind={a.type || app.t('album')}
        title={a.title}
        cover={a.cover}
        meta={[a.artistId ? <a href={`#/artist/${a.artistId}`}>{a.artist}</a> : a.artist, a.year, app.t('songsCount', a.tracks.length), length(total(a.tracks), app.t)]}
        description={a.description}
        playing={playing}
        onPlay={() => playOrToggle(app, a.tracks, src, playing)}
        onShuffle={() => app.playFrom(a.tracks, -1, src)}
        menu={[...listMenu(app, a.tracks, a.title), { label: app.t('copyLink'), icon: 'link', onClick: () => app.copy(`https://freesong.simplyonegoal.com/a/${a.id}`) }]}
      />
      <SongList songs={a.tracks} source={src} numbered showCover={false} showAlbum={false} />
    </div>
  );
}

export function Playlist({ id }) {
  const app = useApp();
  const r = useLoad(() => app.api.playlist(id), [id, app.lang]);
  const source = { kind: 'playlist', id };
  const playing = usePlaying(source);
  if (r.loading) return <Loading />;
  if (r.error) return <Failed onRetry={r.retry} />;
  const p = r.data;
  const src = { ...source, title: p.title };
  return (
    <div className="page">
      <Header
        kind={app.t('playlist')}
        title={p.title}
        cover={p.cover}
        meta={[p.author && p.author !== 'N/A' ? p.author : null, app.t('songsCount', p.tracks.length), length(total(p.tracks), app.t)]}
        description={p.description}
        playing={playing}
        onPlay={() => playOrToggle(app, p.tracks, src, playing)}
        onShuffle={() => app.playFrom(p.tracks, -1, src)}
        menu={[...listMenu(app, p.tracks, p.title), { label: app.t('copyLink'), icon: 'link', onClick: () => app.copy(`https://freesong.simplyonegoal.com/l/${p.id}`) }]}
      />
      <SongList songs={p.tracks} source={src} numbered />
    </div>
  );
}

export function Mix({ id }) {
  const app = useApp();
  const [state, setState] = useState({ loading: true, mix: null, error: null });
  const [round, setRound] = useState(0);
  useEffect(() => {
    let live = true;
    setState({ loading: true, mix: null, error: null });
    app.mixes.get(id).then((mix) => live && setState({ loading: false, mix, error: null }), (error) => live && setState({ loading: false, mix: null, error }));
    return () => { live = false; };
  }, [id, round]);
  const source = { kind: 'mix', id };
  const playing = usePlaying(source);
  if (state.loading) return <Loading />;
  if (state.error) return <Failed onRetry={() => { app.mixes.cache.delete(id); setRound((x) => x + 1); }} />;
  const m = state.mix;
  const title = app.mixTitle(m);
  const src = { ...source, title };
  return (
    <div className="page">
      <Header
        kind={app.t('mix')}
        title={title}
        covers={m.covers}
        cover={m.covers?.[0]}
        meta={[m.artists?.length ? app.t('mixWith', m.artists.join(', ')) : null, app.t('songsCount', m.tracks.length), length(total(m.tracks), app.t)]}
        playing={playing}
        onPlay={() => playOrToggle(app, m.tracks, src, playing)}
        onShuffle={() => app.playFrom(m.tracks, -1, src)}
        menu={listMenu(app, m.tracks, title)}
      />
      {m.tracks.length ? <SongList songs={m.tracks} source={src} numbered /> : <div className="empty"><p>{app.t('noResults')}</p></div>}
    </div>
  );
}

export { time };
