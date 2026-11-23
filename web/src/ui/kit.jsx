// As peças pequenas da página: o contexto do app, ícones (os de linha do app), a marca, capas,
// botões, o menu de contexto, os avisos e como um tempo é escrito.
import { createContext, useCallback, useContext, useEffect, useLayoutEffect, useRef, useState, useSyncExternalStore } from 'react';

export const Ctx = createContext(null);
export const useApp = () => useContext(Ctx);

/** Redesenha quando [source] (o cofre, o player…) muda. */
export function useWatch(source) {
  useSyncExternalStore((fn) => source.subscribe(fn), () => source.version);
  return source;
}

const PATHS = {
  home: 'M4 11l8-7 8 7v9a1 1 0 0 1-1 1h-4v-6H9v6H5a1 1 0 0 1-1-1z',
  search: 'M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM20 20l-4-4',
  library: 'M5 4v16M10 4v16M15 5l4 15',
  heart: 'M12 20s-7-4.4-9-9.2C1.7 7.5 4 4.5 7.2 4.5c2 0 3.6 1.1 4.8 2.8 1.2-1.7 2.8-2.8 4.8-2.8 3.2 0 5.5 3 4.2 6.3C19 15.6 12 20 12 20z',
  play: 'M8 5.5v13a1 1 0 0 0 1.5.86l10.5-6.5a1 1 0 0 0 0-1.72L9.5 4.64A1 1 0 0 0 8 5.5z',
  pause: 'M7 5h3.5v14H7zM13.5 5H17v14h-3.5z',
  next: 'M6 5.5v13l9-6.5zM17 5v14',
  prev: 'M18 5.5v13l-9-6.5zM7 5v14',
  shuffle: 'M4 7h3c4 0 5 10 10 10h3M17 14l3 3-3 3M4 17h3c1.6 0 2.7-1.6 3.6-3.4M13.4 9.4C14.3 8 15.4 7 17 7h3M17 4l3 3-3 3',
  repeat: 'M5 11V9a2 2 0 0 1 2-2h12M16 4l3 3-3 3M19 13v2a2 2 0 0 1-2 2H5M8 20l-3-3 3-3',
  volume: 'M5 10v4h3l5 4V6L8 10zM16 9a4 4 0 0 1 0 6M18.5 6.5a8 8 0 0 1 0 11',
  volumeLow: 'M5 10v4h3l5 4V6L8 10zM16 9a4 4 0 0 1 0 6',
  mute: 'M5 10v4h3l5 4V6L8 10zM17 10l4 4M21 10l-4 4',
  lyrics: 'M5 6h14M5 10h10M5 14h12M5 18h7',
  queue: 'M4 6h12M4 11h12M4 16h7M17 14v6M14 17h6',
  more: 'M5 12h.01M12 12h.01M19 12h.01',
  plus: 'M12 5v14M5 12h14',
  close: 'M6 6l12 12M18 6L6 18',
  back: 'M15 18l-6-6 6-6',
  forward: 'M9 18l6-6-6-6',
  check: 'M5 12.5l4.5 4.5L19 7.5',
  radio: 'M12 12h.01M8.5 8.5a5 5 0 0 0 0 7M15.5 8.5a5 5 0 0 1 0 7M5.6 5.6a9 9 0 0 0 0 12.8M18.4 5.6a9 9 0 0 1 0 12.8',
  person: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 20c1.5-3.5 4.5-5 8-5s6.5 1.5 8 5',
  people: 'M9 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7zM2.5 19.5c1.2-3 3.6-4.5 6.5-4.5s5.3 1.5 6.5 4.5M16 4.5a3.5 3.5 0 0 1 0 6.5M18 15c1.6.7 2.8 2.2 3.5 4.5',
  album: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 10a2 2 0 1 0 0 4 2 2 0 0 0 0-4z',
  mic: 'M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3zM5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21',
  history: 'M4 12a8 8 0 1 0 2.4-5.7M4 4v4h4M12 8v4l3 2',
  link: 'M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1',
  send: 'M4 12l16-8-6 16-2.5-6.5z',
  edit: 'M4 20h4L19 9l-4-4L4 16zM14 6l4 4',
  trash: 'M5 7h14M9 7V5h6v2M7 7l1 12h8l1-12',
  pin: 'M9 4h6l-1 5 3 3v2H7v-2l3-3zM12 14v6',
  laptop: 'M5 6h14v9H5zM3 18h18',
  phone: 'M8 3h8a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1zM11 18h2',
  settings: 'M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM19.4 15a1.6 1.6 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.6 1.6 0 0 0-1.8-.3 1.6 1.6 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.6 1.6 0 0 0-1-1.5 1.6 1.6 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.6 1.6 0 0 0 .3-1.8 1.6 1.6 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.6 1.6 0 0 0 1.5-1 1.6 1.6 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.6 1.6 0 0 0 1.8.3H9a1.6 1.6 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.6 1.6 0 0 0 1 1.5 1.6 1.6 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.6 1.6 0 0 0-.3 1.8V9a1.6 1.6 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.6 1.6 0 0 0-1.5 1z',
  expand: 'M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5',
  collapse: 'M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5',
  retry: 'M20 11a8 8 0 1 0-2.3 5.7M20 5v6h-6',
  dot: 'M12 11.5a.5.5 0 1 0 0 1 .5.5 0 0 0 0-1z',
  spark: 'M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z',
  sync: 'M20 8a8 8 0 0 0-14.5-2M4 4v4h4M4 16a8 8 0 0 0 14.5 2M20 20v-4h-4',
  offline: 'M3 3l18 18M8.5 16.5a5 5 0 0 1 7 0M5 13a10 10 0 0 1 4-2.5M19 13a10 10 0 0 0-2-1.7M2 9a15 15 0 0 1 5-3M22 9a15 15 0 0 0-9.5-4M12 20h.01',
  lock: 'M7 11h10v9H7zM9 11V8a3 3 0 0 1 6 0v3',
  key: 'M14.5 4a5.5 5.5 0 0 0-5.2 7.3L3 17.6V21h3.4l.9-.9v-2h2v-2h2l1.4-1.4A5.5 5.5 0 1 0 14.5 4zM16 8h.01',
  globe: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM3 12h18M12 3c2.5 2.5 3.8 5.6 3.8 9s-1.3 6.5-3.8 9c-2.5-2.5-3.8-5.6-3.8-9S9.5 5.5 12 3z',
  sun: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4',
  moon: 'M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z',
  download: 'M12 4v11M7 10l5 5 5-5M5 20h14',
  video: 'M4 6h12v12H4zM16 10l4-2.5v9L16 14',
  copy: 'M9 9h10v11H9zM5 15V4h10',
};

export function Icon({ name, size = 20, stroke = 1.8, fill = false }) {
  return (
    <svg viewBox="0 0 24 24" width={size} height={size} fill={fill ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth={fill ? 0 : stroke} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={PATHS[name] ?? ''} />
    </svg>
  );
}

/** A marca do FreeSong: o fone com asas, no ladrilho grafite. */
export function Logo({ size = 32 }) {
  return <img className="logo" src="icon-192.png" width={size} height={size} alt="" />;
}

export function IconBtn({ icon, label, onClick, active = false, size = 20, kind = '', disabled = false, fill = false }) {
  return (
    <button type="button" className={`icon-btn ${kind}${active ? ' on' : ''}`} aria-label={label} title={label} onClick={onClick} disabled={disabled} aria-pressed={active || undefined}>
      <Icon name={icon} size={size} fill={fill} />
    </button>
  );
}

export function PlayBtn({ playing = false, onClick, size = 'md', label }) {
  return (
    <button type="button" className={`play-btn ${size}`} aria-label={label} title={label} onClick={(e) => { e.preventDefault(); e.stopPropagation(); onClick?.(); }}>
      <Icon name={playing ? 'pause' : 'play'} fill size={size === 'lg' ? 26 : size === 'sm' ? 16 : 20} />
    </button>
  );
}

/** Uma capa: carrega só quando aparece; sem imagem (ou com erro), a cor da música e um ícone. */
export function Cover({ src, size, round = false, icon = 'album', className = '', alt = '' }) {
  const [broken, setBroken] = useState(false);
  useEffect(() => setBroken(false), [src]);
  const style = size ? { width: size, height: size } : undefined;
  return (
    <span className={`cover${round ? ' round' : ''} ${className}`} style={style}>
      {src && !broken ? <img src={src} alt={alt} loading="lazy" decoding="async" referrerPolicy="no-referrer" onError={() => setBroken(true)} draggable={false} /> : <Icon name={icon} size={22} />}
    </span>
  );
}

/** Quatro capas num quadrado (as playlists e os mixes). */
export function Collage({ covers = [], size }) {
  const four = covers.filter(Boolean).slice(0, 4);
  if (four.length < 4) return <Cover src={four[0]} size={size} icon="queue" />;
  return (
    <span className="cover collage" style={size ? { width: size, height: size } : undefined}>
      {four.map((c, i) => <img key={i} src={c} alt="" loading="lazy" referrerPolicy="no-referrer" draggable={false} />)}
    </span>
  );
}

/** A capa no tamanho de quem mostra (os servidores do Google redimensionam pela URL). */
export function sized(url, size) {
  if (!url) return url;
  if (/googleusercontent\.com\/|ggpht\.com\//.test(url)) return url.replace(/=w\d+-h\d+/, `=w${size}-h${size}`).replace(/=s\d+(?=-|$)/, `=s${size}`);
  return url;
}

export function time(ms) {
  if (!Number.isFinite(ms) || ms <= 0) return '';
  const s = Math.floor(ms / 1000);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const ss = String(s % 60).padStart(2, '0');
  return h ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`;
}

/** "há 3 dias", no idioma da página. */
export function ago(at, lang) {
  const rtf = new Intl.RelativeTimeFormat(lang, { numeric: 'auto' });
  const s = (at - Date.now()) / 1000;
  const units = [['year', 31536000], ['month', 2592000], ['week', 604800], ['day', 86400], ['hour', 3600], ['minute', 60]];
  for (const [u, n] of units) if (Math.abs(s) >= n) return rtf.format(Math.round(s / n), u);
  return rtf.format(0, 'minute');
}

/** Sai com uma animação curta antes de sumir de vez. */
export function useLeave(done, ms = 150) {
  const [leaving, setLeaving] = useState(false);
  const leave = useCallback(() => {
    if (leaving) return;
    setLeaving(true);
    setTimeout(done, matchMedia('(prefers-reduced-motion: reduce)').matches ? 0 : ms);
  }, [leaving, done, ms]);
  return [leaving, leave];
}

// ------------------------------------------------------------------ menu de contexto

/**
 * Um menu perto de onde se clicou (ou do botão): [items] = [{label, icon, onClick, danger, sub: items}]
 * ou '-' (linha). Fecha com Esc, clique fora ou rolando.
 */
export function Menu({ at, items, onClose }) {
  const ref = useRef(null);
  const [pos, setPos] = useState({ left: at.x, top: at.y, ready: false });
  const [open, setOpen] = useState(null); // o submenu aberto
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const r = el.getBoundingClientRect();
    const left = Math.max(8, Math.min(at.x, innerWidth - r.width - 8));
    const top = at.y + r.height > innerHeight - 8 ? Math.max(8, at.y - r.height) : at.y;
    setPos({ left, top, ready: true });
  }, [at.x, at.y]);
  useEffect(() => {
    const key = (e) => { if (e.key === 'Escape') onClose(); };
    const down = (e) => { if (!ref.current?.contains(e.target)) onClose(); };
    const scroll = (e) => { if (!ref.current?.contains(e.target)) onClose(); };
    window.addEventListener('keydown', key);
    window.addEventListener('mousedown', down, true);
    window.addEventListener('scroll', scroll, true);
    window.addEventListener('resize', onClose);
    return () => {
      window.removeEventListener('keydown', key);
      window.removeEventListener('mousedown', down, true);
      window.removeEventListener('scroll', scroll, true);
      window.removeEventListener('resize', onClose);
    };
  }, [onClose]);
  const list = open ? open.sub : items;
  return (
    <div ref={ref} className={`menu${pos.ready ? ' ready' : ''}`} style={{ left: pos.left, top: pos.top }} role="menu">
      {open ? (
        <button type="button" className="menu-item head" onClick={() => setOpen(null)}><Icon name="back" size={16} /> {open.label}</button>
      ) : null}
      {list.map((it, i) => (it === '-' ? <div key={i} className="menu-sep" /> : (
        <button
          key={i}
          type="button"
          role="menuitem"
          className={`menu-item${it.danger ? ' danger' : ''}`}
          disabled={it.disabled}
          onClick={() => {
            if (it.sub) return setOpen(it);
            onClose();
            it.onClick?.();
          }}
        >
          {it.icon ? <Icon name={it.icon} size={17} /> : <span className="menu-gap" />}
          <span className="menu-label">{it.label}</span>
          {it.sub ? <Icon name="forward" size={15} /> : null}
        </button>
      )))}
      {list.length === 0 ? <div className="menu-empty">—</div> : null}
    </div>
  );
}

/** Um diálogo no meio da tela. */
export function Dialog({ title, children, onClose, wide = false }) {
  const [leaving, leave] = useLeave(onClose);
  useEffect(() => {
    const key = (e) => { if (e.key === 'Escape') leave(); };
    window.addEventListener('keydown', key);
    return () => window.removeEventListener('keydown', key);
  }, [leave]);
  return (
    <div className={`scrim${leaving ? ' leaving' : ''}`} onMouseDown={(e) => { if (e.target === e.currentTarget) leave(); }}>
      <div className={`dialog${wide ? ' wide' : ''}`} role="dialog" aria-label={title}>
        <div className="dialog-head">
          <h2>{title}</h2>
          <IconBtn icon="close" label="×" onClick={leave} />
        </div>
        {typeof children === 'function' ? children(leave) : children}
      </div>
    </div>
  );
}

/** Os avisos rápidos de baixo ("Curtida", "Adicionada à fila"…). */
export function Toasts({ list }) {
  return (
    <div className="toasts" aria-live="polite">
      {list.map((t) => (
        <div key={t.id} className="toast">
          <span>{t.text}</span>
          {t.action ? <button type="button" onClick={t.action.run}>{t.action.label}</button> : null}
        </div>
      ))}
    </div>
  );
}

export function Spinner() {
  return <span className="spinner" aria-hidden="true" />;
}

/** Os dados de uma tela, buscados quando ela abre (com "carregando" e "tentar de novo"). */
export function useLoad(load, deps) {
  const [state, setState] = useState({ loading: true, data: null, error: null });
  const [round, setRound] = useState(0);
  useEffect(() => {
    let live = true;
    setState((s) => ({ loading: true, data: s.data && deps.length === 0 ? s.data : null, error: null }));
    load().then(
      (data) => { if (live) setState({ loading: false, data, error: null }); },
      (error) => { if (live) setState({ loading: false, data: null, error }); },
    );
    return () => { live = false; };
  }, [...deps, round]);
  return { ...state, retry: () => setRound((r) => r + 1) };
}

export function Failed({ onRetry }) {
  const app = useApp();
  return (
    <div className="empty">
      <Icon name="offline" size={34} />
      <p>{app.t('loadFailed')}</p>
      <button type="button" className="pill" onClick={onRetry}><Icon name="retry" size={16} /> {app.t('tryAgain')}</button>
    </div>
  );
}

export function Loading() {
  return <div className="loading"><Spinner /></div>;
}
