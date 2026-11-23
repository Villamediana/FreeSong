// O que toca: a barra de baixo (capa, título, curtir, controles, tempo, volume), o painel do lado
// (o vídeo do YouTube, a letra que acompanha a música e a fila) e o lugar fixo do player do YouTube,
// que nunca sai do lugar no DOM (mover um iframe recarrega ele): só muda de posição e tamanho.
import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Cover, Icon, IconBtn, Loading, sized, time, useApp, useWatch } from './kit.jsx';

/** O lugar do vídeo: segue o "buraco" do painel quando ele está aberto; senão, fica pequeno no canto. */
export function VideoDock({ slot, host, open }) {
  const app = useApp();
  const player = useWatch(app.player);
  const has = !!player.current;
  useLayoutEffect(() => {
    const el = host.current;
    if (!el) return undefined;
    let raf = 0;
    const place = () => {
      const s = slot.current;
      const r = s?.getBoundingClientRect();
      if (s && r && r.width > 40 && s.offsetParent !== null) {
        Object.assign(el.style, { left: `${r.left}px`, top: `${r.top}px`, width: `${r.width}px`, height: `${r.height}px` });
        el.classList.remove('mini');
      } else {
        el.style.removeProperty('left');
        el.style.removeProperty('top');
        el.style.removeProperty('width');
        el.style.removeProperty('height');
        el.classList.add('mini');
      }
    };
    const loop = (n) => {
      place();
      if (n > 0) raf = requestAnimationFrame(() => loop(n - 1));
    };
    loop(30);
    const ro = new ResizeObserver(() => loop(4));
    if (slot.current) ro.observe(slot.current);
    ro.observe(document.body);
    window.addEventListener('resize', place);
    const scroller = slot.current?.closest('.np-scroll');
    scroller?.addEventListener('scroll', place, { passive: true });
    return () => {
      cancelAnimationFrame(raf);
      ro.disconnect();
      window.removeEventListener('resize', place);
      scroller?.removeEventListener('scroll', place);
    };
  }, [open, has]);
  useEffect(() => {
    host.current?.classList.toggle('empty', !has);
  }, [has]);
  return null;
}

/** A letra: as linhas com tempo seguem a música (clicar numa linha pula pra ela); sem tempo, o texto. */
function Lyrics({ song }) {
  const app = useApp();
  const player = useWatch(app.player);
  const [state, setState] = useState({ loading: true, data: null });
  const box = useRef(null);
  const userScroll = useRef(0);
  useEffect(() => {
    let live = true;
    setState({ loading: true, data: null });
    app.api.lyrics(song).then((d) => live && setState({ loading: false, data: d }), () => live && setState({ loading: false, data: null }));
    return () => { live = false; };
  }, [song.videoId]);
  const synced = state.data?.synced;
  const ms = player.time * 1000 + 250;
  let at = -1;
  if (synced) for (let i = 0; i < synced.length; i++) if (synced[i].t <= ms) at = i; else break;
  useEffect(() => {
    if (at < 0 || !box.current || Date.now() - userScroll.current < 4000) return;
    const line = box.current.querySelector(`[data-i="${at}"]`);
    if (!line) return;
    const top = line.offsetTop - box.current.clientHeight * 0.35;
    box.current.scrollTo({ top, behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
  }, [at]);
  if (state.loading) return <Loading />;
  if (state.data?.instrumental) return <div className="lyrics-none"><Icon name="mic" size={28} /><p>{app.t('instrumental')}</p></div>;
  if (!synced && !state.data?.plain) return <div className="lyrics-none"><Icon name="lyrics" size={28} /><p>{app.t('noLyrics')}</p></div>;
  return (
    <div className="lyrics" ref={box} onWheel={() => { userScroll.current = Date.now(); }}>
      {synced ? synced.map((l, i) => (
        <p key={i} data-i={i} className={i === at ? 'now' : i < at ? 'past' : ''} onClick={() => app.player.seek(l.t / 1000)}>{l.l || '♪'}</p>
      )) : state.data.plain.split('\n').map((l, i) => <p key={i} className="plain">{l || ' '}</p>)}
      <span className="lyrics-src">{state.data?.source ? app.t('lyricsFrom', state.data.source) : ''}</span>
    </div>
  );
}

function Queue() {
  const app = useApp();
  const player = useWatch(app.player);
  const cur = player.current;
  const next = player.upcoming.slice(0, 80);
  if (!cur) return <div className="lyrics-none"><Icon name="queue" size={28} /><p>{app.t('queueEmpty')}</p></div>;
  return (
    <div className="queue">
      <h3>{app.t('nowPlaying')}</h3>
      <QueueRow song={cur} current />
      {next.length ? <h3>{player.source?.title ? app.t('nextFrom', player.source.title) : app.t('nextUp')}</h3> : null}
      {next.map(({ song, index }) => <QueueRow key={`${song.videoId}-${index}`} song={song} onPlay={() => player.jump(index)} onRemove={() => player.removeAt(index)} />)}
    </div>
  );
}

function QueueRow({ song, current = false, onPlay, onRemove }) {
  const app = useApp();
  return (
    <div className={`qrow${current ? ' current' : ''}`} onDoubleClick={onPlay} onContextMenu={(e) => { e.preventDefault(); app.openMenu(e, app.songMenu(song, {})); }}>
      <button type="button" className="qrow-art" onClick={onPlay} aria-label={app.t('play')} disabled={current}>
        <Cover src={sized(song.cover, 120)} size={40} />
        {!current ? <span className="qrow-play"><Icon name="play" fill size={14} /></span> : null}
      </button>
      <div className="row-main">
        <span className="row-title">{song.title}</span>
        <span className="artists">{song.artist}</span>
      </div>
      {onRemove ? <IconBtn icon="close" label={app.t('removeFromQueue')} size={16} kind="row-more" onClick={onRemove} /> : null}
    </div>
  );
}

/** O painel do lado: o vídeo em cima, e embaixo a letra ou a fila. */
export function NowPlaying({ slot, tab, setTab, onClose }) {
  const app = useApp();
  const player = useWatch(app.player);
  useWatch(app.sync);
  const s = player.current;
  return (
    <aside className="np" aria-label={app.t('nowPlaying')}>
      <div className="np-head">
        <span>{player.source?.title ?? app.t('nowPlaying')}</span>
        <IconBtn icon="close" label={app.t('close')} size={18} onClick={onClose} />
      </div>
      <div className="np-scroll">
        <div className="np-video" ref={slot}>{!s ? <Icon name="video" size={34} /> : null}</div>
        {s ? (
          <div className="np-info">
            <div className="row-main">
              <span className="np-title">{s.title}</span>
              <span className="artists">{s.artists?.find((a) => a.id) ? <a href={`#/artist/${s.artists.find((a) => a.id).id}`}>{s.artist}</a> : s.artist}</span>
            </div>
            <IconBtn icon="heart" fill={app.isLiked(s.videoId)} kind={`like${app.isLiked(s.videoId) ? ' liked' : ''}`} label={app.t('like')} onClick={() => app.toggleLike(s)} />
          </div>
        ) : null}
        <div className="np-tabs">
          <button type="button" className={tab === 'lyrics' ? 'on' : ''} onClick={() => setTab('lyrics')}>{app.t('lyrics')}</button>
          <button type="button" className={tab === 'queue' ? 'on' : ''} onClick={() => setTab('queue')}>{app.t('queue')}</button>
        </div>
        <div className="np-body">
          {tab === 'lyrics' ? (s ? <Lyrics key={s.videoId} song={s} /> : <div className="lyrics-none"><Icon name="lyrics" size={28} /><p>{app.t('queueEmpty')}</p></div>) : <Queue />}
        </div>
      </div>
    </aside>
  );
}

/** Arrastar pra escolher (o tempo, o volume): a barra é só um <input type=range> estilizado. */
function Slider({ value, max, onChange, onCommit, label, className = '' }) {
  const [drag, setDrag] = useState(null);
  const v = drag ?? value;
  return (
    <input
      type="range"
      className={`slider ${className}`}
      min={0}
      max={max || 1}
      step="any"
      value={Math.min(v, max || 1)}
      aria-label={label}
      style={{ '--p': `${max ? (Math.min(v, max) / max) * 100 : 0}%` }}
      onChange={(e) => {
        const x = Number(e.target.value);
        setDrag(x);
        onChange?.(x);
      }}
      onPointerUp={() => { if (drag != null) onCommit?.(drag); setDrag(null); }}
      onKeyUp={() => { if (drag != null) onCommit?.(drag); setDrag(null); }}
    />
  );
}

export function PlayerBar({ panel, togglePanel, tab, setTab }) {
  const app = useApp();
  const p = useWatch(app.player);
  useWatch(app.sync);
  const s = p.current;
  const liked = s ? app.isLiked(s.videoId) : false;
  const firstArtist = s?.artists?.find((a) => a.id);
  return (
    <footer className={`bar${s ? '' : ' idle'}`}>
      <div className="bar-now">
        {s ? (
          <>
            <button type="button" className="bar-art" onClick={() => togglePanel(true)} aria-label={app.t('nowPlaying')}>
              <Cover src={sized(s.cover, 120)} size={56} />
            </button>
            <div className="row-main">
              <a className="row-title" href={s.albumId ? `#/album/${s.albumId}` : undefined}>{s.title}</a>
              <span className="artists">{firstArtist ? <a href={`#/artist/${firstArtist.id}`}>{s.artist}</a> : s.artist}</span>
            </div>
            <IconBtn icon="heart" fill={liked} kind={`like${liked ? ' liked' : ''}`} label={liked ? app.t('unlike') : app.t('like')} size={19} onClick={() => app.toggleLike(s)} />
          </>
        ) : null}
      </div>
      <div className="bar-mid">
        <div className="bar-controls">
          <IconBtn icon="shuffle" label={app.t('shuffle')} active={p.shuffle} size={18} onClick={() => p.toggleShuffle()} />
          <IconBtn icon="prev" label={app.t('previous')} size={20} fill onClick={() => p.prev()} disabled={!s} />
          <button type="button" className="bar-play" aria-label={p.playing ? app.t('pause') : app.t('play')} onClick={() => p.toggle()} disabled={!s}>
            <Icon name={p.playing ? 'pause' : 'play'} fill size={20} />
          </button>
          <IconBtn icon="next" label={app.t('next')} size={20} fill onClick={() => p.next()} disabled={!s} />
          <IconBtn icon="repeat" label={app.t(p.repeat === 'one' ? 'repeatOne' : p.repeat === 'all' ? 'repeatAll' : 'repeat')} active={p.repeat !== 'off'} kind={p.repeat === 'one' ? 'one' : ''} size={18} onClick={() => p.cycleRepeat()} />
        </div>
        <div className="bar-time">
          <span>{time(p.time * 1000)}</span>
          <Slider value={p.time} max={p.duration} label={app.t('position')} onCommit={(x) => p.seek(x)} className="seek" />
          <span>{time(p.duration * 1000)}</span>
        </div>
        {p.blocked ? <button type="button" className="blocked" onClick={() => p.play()}>{app.t('clickToPlay')}</button> : null}
      </div>
      <div className="bar-end">
        <IconBtn icon="lyrics" label={app.t('lyrics')} active={panel && tab === 'lyrics'} size={18} onClick={() => { if (panel && tab === 'lyrics') togglePanel(false); else { setTab('lyrics'); togglePanel(true); } }} />
        <IconBtn icon="queue" label={app.t('queue')} active={panel && tab === 'queue'} size={18} onClick={() => { if (panel && tab === 'queue') togglePanel(false); else { setTab('queue'); togglePanel(true); } }} />
        <IconBtn icon={p.muted || p.vol === 0 ? 'mute' : p.vol < 50 ? 'volumeLow' : 'volume'} label={app.t('mute')} size={18} onClick={() => p.toggleMute()} />
        <Slider value={p.muted ? 0 : p.vol} max={100} label={app.t('volume')} onChange={(x) => p.setVolume(x)} className="vol" />
      </div>
    </footer>
  );
}

export function useKeys(go) {
  const app = useApp();
  useEffect(() => {
    const key = (e) => {
      const typing = /^(INPUT|TEXTAREA|SELECT)$/.test(e.target.tagName) || e.target.isContentEditable;
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        go('search');
        return;
      }
      if (typing || e.altKey || e.ctrlKey || e.metaKey) return;
      const p = app.player;
      if (e.key === ' ' || e.key === 'k') { e.preventDefault(); p.toggle(); }
      else if (e.key === 'ArrowRight' && e.shiftKey) p.next();
      else if (e.key === 'ArrowLeft' && e.shiftKey) p.prev();
      else if (e.key === 'ArrowRight') p.seek(p.time + 5);
      else if (e.key === 'ArrowLeft') p.seek(p.time - 5);
      else if (e.key === 'ArrowUp') { e.preventDefault(); p.setVolume(p.vol + 5); }
      else if (e.key === 'ArrowDown') { e.preventDefault(); p.setVolume(p.vol - 5); }
      else if (e.key === 'm') p.toggleMute();
      else if (e.key === 'l' && p.current) app.toggleLike(p.current);
      else if (e.key === '/') { e.preventDefault(); go('search'); }
    };
    window.addEventListener('keydown', key);
    return () => window.removeEventListener('keydown', key);
  }, []);
}

