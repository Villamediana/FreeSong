// Buscar: sem texto, os gêneros (cada um abre o mix dele); com texto, o melhor resultado, as músicas,
// artistas, álbuns e playlists — ou um tipo só, com "Mais resultados".
import { useEffect, useState } from 'react';
import { GENRES } from '../core/taste.js';
import { Cover, Icon, Loading, Failed, PlayBtn, sized, useApp, useLoad } from './kit.jsx';
import { Card, Grid, Shelf, SongList } from './songs.jsx';

const TYPES = ['', 'songs', 'artists', 'albums', 'playlists', 'videos'];

function Top({ item }) {
  const app = useApp();
  const href = item.kind === 'artist' ? `#/artist/${item.id}` : item.kind === 'album' ? `#/album/${item.id}` : null;
  const play = () => {
    if (item.kind === 'song') app.playRadio(item);
    else if (item.kind === 'artist') app.playArtist(item.id);
    else if (item.kind === 'album') app.playAlbum(item.id);
  };
  return (
    <div className="top-result" onClick={() => (href ? (location.hash = href.slice(1)) : play())} role="link" tabIndex={0}>
      <Cover src={sized(item.cover, 240)} size={96} round={item.kind === 'artist'} icon={item.kind === 'artist' ? 'person' : 'album'} />
      <div className="top-title">{item.name ?? item.title}</div>
      <div className="top-sub">
        <span className="chip-kind">{item.kind === 'artist' ? app.t('artist') : item.kind === 'album' ? (item.type || app.t('album')) : app.t('song')}</span>
        {item.kind !== 'artist' ? <span>{item.artist}</span> : item.subtitle ? <span>{item.subtitle.split('•').pop().trim()}</span> : null}
      </div>
      <span className="top-play"><PlayBtn size="lg" label={app.t('play')} onClick={play} /></span>
    </div>
  );
}

function Typed({ q, type }) {
  const app = useApp();
  const first = useLoad(() => app.api.search(q, type), [q, type, app.lang]);
  const [extra, setExtra] = useState({ items: [], next: undefined, loading: false });
  useEffect(() => setExtra({ items: [], next: undefined, loading: false }), [q, type]);
  if (first.loading) return <Loading />;
  if (first.error) return <Failed onRetry={first.retry} />;
  const items = [...(first.data.items ?? []), ...extra.items];
  const next = extra.next === undefined ? first.data.next : extra.next;
  const more = async () => {
    setExtra((x) => ({ ...x, loading: true }));
    try {
      const r = await app.api.more(next);
      setExtra((x) => ({ items: [...x.items, ...r.items], next: r.next, loading: false }));
    } catch {
      setExtra((x) => ({ ...x, next: null, loading: false }));
    }
  };
  if (!items.length) return <div className="empty"><p>{app.t('noResults')}</p></div>;
  const songs = items.filter((x) => x.videoId);
  return (
    <>
      {songs.length ? <SongList songs={songs} source={{ kind: 'search', title: q }} /> : (
        <Grid>{items.map((it) => <Card key={`${it.kind}:${it.id}`} item={it} />)}</Grid>
      )}
      {next ? <div className="center-row"><button type="button" className="pill" onClick={more} disabled={extra.loading}>{extra.loading ? '…' : app.t('moreResults')}</button></div> : null}
    </>
  );
}

function All({ q }) {
  const app = useApp();
  const r = useLoad(() => app.api.search(q), [q, app.lang]);
  if (r.loading) return <Loading />;
  if (r.error) return r.error.status === 502 ? <div className="empty"><p>{app.t('noResults')}</p></div> : <Failed onRetry={r.retry} />;
  const d = r.data;
  const top = d.top ?? (d.songs[0] ? { kind: 'song', ...d.songs[0] } : null);
  return (
    <>
      <div className="search-top">
        {top ? (
          <section>
            <h2>{app.t('topResult')}</h2>
            <Top item={top} />
          </section>
        ) : null}
        {d.songs.length ? (
          <section className="grow">
            <h2>{app.t('songs')}</h2>
            <SongList songs={d.songs.slice(0, 5)} source={{ kind: 'search', title: q }} showAlbum={false} />
          </section>
        ) : null}
      </div>
      {d.artists.length ? <Shelf title={app.t('artists')}>{d.artists.map((a) => <Card key={a.id} item={a} />)}</Shelf> : null}
      {d.albums.length ? <Shelf title={app.t('albums')}>{d.albums.map((a) => <Card key={a.id} item={a} />)}</Shelf> : null}
      {d.playlists.length ? <Shelf title={app.t('playlists')}>{d.playlists.map((a) => <Card key={a.id} item={a} />)}</Shelf> : null}
      {d.videos.length ? <Shelf title={app.t('videos')}>{d.videos.map((s) => <Card key={s.videoId} item={{ ...s, subtitle: s.artist }} />)}</Shelf> : null}
      {d.songs.length > 5 ? (
        <section className="section">
          <h2>{app.t('moreSongs')}</h2>
          <SongList songs={d.songs.slice(5)} source={{ kind: 'search', title: q }} />
        </section>
      ) : null}
    </>
  );
}

export function Search({ q, type }) {
  const app = useApp();
  if (!q) {
    return (
      <div className="page">
        <h1 className="page-title">{app.t('browseAll')}</h1>
        <div className="genres">
          {GENRES.map((g) => (
            <a key={g.id} className="genre" style={{ '--tint': g.tint }} href={`#/mix/${encodeURIComponent(`genre:${g.id}`)}`}>
              <span>{g.label[app.lang] ?? g.label.pt}</span>
              <Icon name="spark" size={44} />
            </a>
          ))}
        </div>
      </div>
    );
  }
  return (
    <div className="page">
      <div className="chips">
        {TYPES.map((tp) => (
          <a key={tp || 'all'} className={`chip${(type || '') === tp ? ' on' : ''}`} href={`#/search?q=${encodeURIComponent(q)}${tp ? `&type=${tp}` : ''}`}>
            {app.t(tp ? tp : 'all')}
          </a>
        ))}
      </div>
      {type ? <Typed q={q} type={type} /> : <All q={q} />}
    </div>
  );
}
