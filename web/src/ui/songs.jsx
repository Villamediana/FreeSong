// Músicas e coleções na tela: a linha de uma música (e a tabela delas), o cartão de um álbum, playlist,
// artista ou mix, a prateleira que rola de lado, e o menu de uma música.
import { useRef, useState } from 'react';
import { Collage, Cover, Icon, IconBtn, PlayBtn, sized, time, useApp, useWatch } from './kit.jsx';

/** O "tocando agora" pequeno (as barrinhas) ou o número da linha. */
function Bars({ paused }) {
  return <span className={`bars${paused ? ' paused' : ''}`} aria-hidden="true"><i /><i /><i /></span>;
}

function Artists({ song }) {
  const app = useApp();
  const list = song.artists?.length ? song.artists : [{ id: null, name: song.artist }];
  return (
    <span className="artists">
      {list.map((a, i) => (
        <span key={i}>
          {i ? ', ' : ''}
          {a.id ? <a href={`#/artist/${a.id}`} onClick={(e) => e.stopPropagation()}>{a.name}</a> : <span>{a.name}</span>}
        </span>
      ))}
      {song.video ? <span className="tag">{app.t('video')}</span> : null}
    </span>
  );
}

/** Uma linha: número (ou o "tocando"), capa, título e artistas, álbum, curtir, duração, menu. */
export function SongRow({ song, index, list, source, numbered = false, showAlbum = true, showCover = true, extra = null, onPlay }) {
  const app = useApp();
  const player = useWatch(app.player);
  useWatch(app.sync);
  const current = player.current?.videoId === song.videoId;
  const liked = app.isLiked(song.videoId);
  const play = () => (onPlay ? onPlay() : app.playFrom(list ?? [song], index ?? 0, source));
  return (
    <div
      className={`row${current ? ' current' : ''}`}
      onDoubleClick={play}
      onContextMenu={(e) => { e.preventDefault(); app.openMenu(e, app.songMenu(song, { source, extra })); }}
      role="row"
    >
      <div className="row-n">
        {current ? <Bars paused={!player.playing} /> : <span className="n">{numbered ? (index ?? 0) + 1 : ''}</span>}
        <button type="button" className="row-play" aria-label={app.t('play')} onClick={current ? () => player.toggle() : play}>
          <Icon name={current && player.playing ? 'pause' : 'play'} fill size={15} />
        </button>
      </div>
      {showCover ? <Cover src={sized(song.cover, 120)} size={40} /> : null}
      <div className="row-main">
        <span className="row-title" title={song.title}>{song.title}</span>
        <Artists song={song} />
      </div>
      {showAlbum ? (
        <div className="row-album">
          {song.albumId ? <a href={`#/album/${song.albumId}`}>{song.album}</a> : <span>{song.album ?? ''}</span>}
        </div>
      ) : null}
      <div className="row-end">
        <IconBtn icon="heart" fill={liked} label={liked ? app.t('unlike') : app.t('like')} kind={`like${liked ? ' liked' : ''}`} size={17} onClick={() => app.toggleLike(song)} />
        <span className="row-time">{time(song.durationMs)}</span>
        <IconBtn icon="more" label={app.t('more')} size={18} kind="row-more" onClick={(e) => app.openMenu(e, app.songMenu(song, { source, extra }))} />
      </div>
    </div>
  );
}

export function SongList({ songs, source, numbered = false, showAlbum = true, showCover = true, extra }) {
  return (
    <div className="songs" role="table">
      {songs.map((s, i) => (
        <SongRow key={`${s.videoId}-${i}`} song={s} index={i} list={songs} source={source} numbered={numbered} showAlbum={showAlbum} showCover={showCover} extra={extra ? extra(s, i) : null} />
      ))}
    </div>
  );
}

/** Um cartão: capa (redonda pra artista), título, embaixo o que ele é; o play aparece por cima. */
export function Card({ item, onPlay }) {
  const app = useApp();
  const href = item.kind === 'artist' ? `#/artist/${item.id}`
    : item.kind === 'album' ? `#/album/${item.id}`
      : item.kind === 'playlist' ? `#/playlist/${item.id}`
        : item.kind === 'mix' ? `#/mix/${encodeURIComponent(item.id)}`
          : item.kind === 'list' ? `#/list/${item.id}`
            : item.href ?? null;
  const title = item.title ?? item.name;
  const sub = item.kind === 'artist' ? app.t('artist')
    : item.kind === 'album' ? [item.year, item.artist].filter(Boolean).join(' · ') || item.type
      : item.kind === 'playlist' ? item.author ?? app.t('playlist')
        : item.subtitle ?? item.artist ?? '';
  const open = () => {
    if (href) location.hash = href.slice(1);
    else if (item.videoId) app.playRadio(item);
  };
  return (
    <div className="card" onClick={open} onContextMenu={(e) => { if (item.videoId) { e.preventDefault(); app.openMenu(e, app.songMenu(item, {})); } }} role="link" tabIndex={0} onKeyDown={(e) => { if (e.key === 'Enter') open(); }}>
      <div className="card-art">
        {item.covers ? <Collage covers={item.covers.map((c) => sized(c, 300))} /> : <Cover src={sized(item.cover, 400)} round={item.kind === 'artist'} icon={item.kind === 'artist' ? 'person' : 'album'} />}
        {onPlay || item.videoId ? <span className="card-play"><PlayBtn label={app.t('play')} onClick={() => (onPlay ? onPlay() : app.playRadio(item))} /></span> : null}
      </div>
      <div className="card-title" title={title}>{title}</div>
      <div className="card-sub">{sub}</div>
    </div>
  );
}

/** Uma prateleira: título (com "Mostrar tudo") e os cartões rolando de lado, com setas. */
export function Shelf({ title, more, children, sub }) {
  const app = useApp();
  const ref = useRef(null);
  const [edges, setEdges] = useState({ start: true, end: false });
  const check = () => {
    const el = ref.current;
    if (!el) return;
    setEdges({ start: el.scrollLeft < 8, end: el.scrollLeft + el.clientWidth >= el.scrollWidth - 8 });
  };
  const go = (dir) => {
    const el = ref.current;
    el?.scrollBy({ left: dir * el.clientWidth * 0.85, behavior: 'smooth' });
  };
  return (
    <section className="shelf">
      <div className="shelf-head">
        <div>
          <h2>{more ? <a href={more}>{title}</a> : title}</h2>
          {sub ? <p className="shelf-sub">{sub}</p> : null}
        </div>
        <div className="shelf-nav">
          {more ? <a className="more-link" href={more}>{app.t('showAll')}</a> : null}
          <IconBtn icon="back" label={app.t('back')} size={18} disabled={edges.start} onClick={() => go(-1)} />
          <IconBtn icon="forward" label={app.t('forward')} size={18} disabled={edges.end} onClick={() => go(1)} />
        </div>
      </div>
      <div className="shelf-row" ref={(el) => { ref.current = el; if (el) requestAnimationFrame(check); }} onScroll={check}>
        {children}
      </div>
    </section>
  );
}

/** A grade de cartões (as telas de "Mostrar tudo" e a busca por tipo). */
export function Grid({ children }) {
  return <div className="grid">{children}</div>;
}
