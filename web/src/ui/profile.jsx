// O perfil (o mesmo do celular): foto, nome, @nome e os números do mês e do ano; os amigos (e mandar
// uma música pra eles); e os ajustes deste computador (tema, cor, idioma, desligar).
import { useState } from 'react';
import { Cover, Dialog, Failed, Icon, Loading, sized, useApp, useLoad, useWatch } from './kit.jsx';
import { LANGS } from './i18n.js';

export const ACCENTS = [
  ['blue', '#4C6FFF', '#7C9BFF'], ['green', '#149E5C', '#5FD69C'], ['pink', '#E0457B', '#FF7AA8'], ['orange', '#EF6A1A', '#FF9A5A'],
  ['purple', '#7B4DFF', '#A88BFF'], ['red', '#E5484D', '#FF7B7F'], ['teal', '#0E9C9C', '#5ED8D8'], ['graphite', '#2B2E36', '#E6E8EE'],
];

function Avatar({ user, size = 120 }) {
  return user?.avatar ? <Cover src={user.avatar} size={size} round icon="person" /> : (
    <span className="avatar-letter" style={{ width: size, height: size, fontSize: size * 0.42 }}>{(user?.name ?? '?').slice(0, 1).toUpperCase()}</span>
  );
}

function Numbers({ n, title }) {
  const app = useApp();
  if (!n || (!n.minutes && !n.songs)) return null;
  return (
    <section className="numbers">
      <h3>{title}</h3>
      <div className="numbers-row">
        <div><b>{n.minutes.toLocaleString(app.lang)}</b><span>{app.t('minutesListened')}</span></div>
        <div><b>{n.songs.toLocaleString(app.lang)}</b><span>{app.t('songs')}</span></div>
        <div><b>{n.artists.toLocaleString(app.lang)}</b><span>{app.t('artists')}</span></div>
      </div>
      {n.top?.length ? (
        <ol className="numbers-top">
          {n.top.map((a, i) => (
            <li key={i}>
              <Cover src={sized(a.cover, 120)} size={44} round icon="person" />
              <a href={`#/search?q=${encodeURIComponent(a.name)}&type=artists`}>{a.name}</a>
              <span>{app.t('minutes', a.minutes)}</span>
            </li>
          ))}
        </ol>
      ) : null}
    </section>
  );
}

export function Profile() {
  const app = useApp();
  const sync = useWatch(app.sync);
  const me = app.me();
  const [editing, setEditing] = useState(false);
  const v = sync.data;
  return (
    <div className="page">
      <header className="profile-hero">
        <Avatar user={me} size={168} />
        <div>
          <span className="hero-kind">{app.t('profile')}</span>
          <h1>{me?.name ?? '…'}</h1>
          <p className="hero-meta">
            <span>@{me?.username}</span>
            <span>{app.t('likesCount', v.likes.length)}</span>
            <span>{app.t('playlistsCount', v.lists.length)}</span>
          </p>
          <button type="button" className="pill" onClick={() => setEditing(true)}><Icon name="edit" size={16} /> {app.t('editName')}</button>
        </div>
      </header>
      <div className="numbers-grid">
        <Numbers n={me?.numbers?.month} title={app.t('thisMonth')} />
        <Numbers n={me?.numbers?.year} title={app.t('thisYear')} />
      </div>
      <Settings />
      {editing ? <NameDialog onClose={() => setEditing(false)} /> : null}
    </div>
  );
}

function NameDialog({ onClose }) {
  const app = useApp();
  const [name, setName] = useState(app.me()?.name ?? '');
  const [busy, setBusy] = useState(false);
  return (
    <Dialog title={app.t('editName')} onClose={onClose}>
      {(close) => (
        <form className="form" onSubmit={async (e) => {
          e.preventDefault();
          if (!name.trim()) return;
          setBusy(true);
          try {
            await app.rename(name.trim());
            close();
          } catch {
            app.toast(app.t('loadFailed'));
            setBusy(false);
          }
        }}>
          <label>{app.t('name')}<input value={name} onChange={(e) => setName(e.target.value)} maxLength={40} autoFocus /></label>
          <div className="form-actions"><button type="button" className="pill ghost" onClick={close}>{app.t('cancel')}</button><button type="submit" className="pill on" disabled={busy}>{app.t('save')}</button></div>
        </form>
      )}
    </Dialog>
  );
}

function Settings() {
  const app = useApp();
  const sync = useWatch(app.sync);
  const prefs = app.prefs();
  const [confirm, setConfirm] = useState(false);
  const st = sync.status;
  return (
    <section className="settings">
      <h2>{app.t('settings')}</h2>
      <div className="set-row">
        <span><Icon name="sun" size={18} /> {app.t('theme')}</span>
        <div className="seg">
          {['system', 'light', 'dark'].map((m) => <button key={m} type="button" className={(prefs.mode ?? 'system') === m ? 'on' : ''} onClick={() => app.setPrefs({ ...prefs, mode: m })}>{app.t(`mode_${m}`)}</button>)}
        </div>
      </div>
      <div className="set-row">
        <span><Icon name="spark" size={18} /> {app.t('accent')}</span>
        <div className="swatches">
          {ACCENTS.map(([id, light, dark]) => (
            <button key={id} type="button" className={`swatch${(prefs.accent ?? 'blue') === id ? ' on' : ''}`} style={{ '--c': document.documentElement.dataset.mode === 'dark' ? dark : light }} aria-label={id} onClick={() => app.setPrefs({ ...prefs, accent: id })} />
          ))}
        </div>
      </div>
      <div className="set-row">
        <span><Icon name="globe" size={18} /> {app.t('language')}</span>
        <div className="seg">
          {LANGS.map(([id, label]) => <button key={id} type="button" className={app.lang === id ? 'on' : ''} onClick={() => app.setPrefs({ ...prefs, lang: id })}>{label}</button>)}
        </div>
      </div>
      <div className="set-row">
        <span><Icon name="sync" size={18} /> {app.t('syncState')}</span>
        <span className="faint">{!st.online ? app.t('offline') : st.novault ? app.t('noVaultYet') : st.syncing ? app.t('syncing') : st.synced ? app.t('syncedWith', app.owner || app.t('yourPhone')) : '…'}</span>
      </div>
      {app.canInstall() ? (
        <div className="set-row">
          <span><Icon name="download" size={18} /> {app.t('installApp')}</span>
          <button type="button" className="pill" onClick={() => app.install()}>{app.t('install')}</button>
        </div>
      ) : null}
      <div className="set-row">
        <span><Icon name="laptop" size={18} /> {app.shared ? app.t('sharedComputer') : app.t('thisComputer')}</span>
        <button type="button" className="pill danger" onClick={() => setConfirm(true)}>{app.t('unlinkComputer')}</button>
      </div>
      <p className="faint small">{app.t('keysHint')}</p>
      {confirm ? (
        <Dialog title={app.t('unlinkComputer')} onClose={() => setConfirm(false)}>
          {(close) => (
            <div className="form">
              <p>{app.t('unlinkHint')}</p>
              <div className="form-actions"><button type="button" className="pill ghost" onClick={close}>{app.t('cancel')}</button><button type="button" className="pill danger on" onClick={() => app.unlink()}>{app.t('unlink')}</button></div>
            </div>
          )}
        </Dialog>
      ) : null}
    </section>
  );
}

export function Friends() {
  const app = useApp();
  const r = useLoad(() => app.loadFriends(true), []);
  if (r.loading) return <Loading />;
  if (r.error) return <Failed onRetry={r.retry} />;
  const friends = r.data?.friends ?? [];
  return (
    <div className="page">
      <h1 className="page-title">{app.t('friends')}</h1>
      {friends.length ? (
        <div className="friends">
          {friends.map((f) => {
            const top = f.numbers?.month?.top?.length ? f.numbers.month.top : f.numbers?.year?.top ?? [];
            return (
              <div key={f.id} className="friend">
                <Avatar user={f} size={64} />
                <div className="friend-main">
                  <b>{f.name}</b>
                  <span className="faint">@{f.username}</span>
                  {top.length ? <span className="friend-top">{app.t('listensTo', top.slice(0, 3).map((a) => a.name).join(', '))}</span> : null}
                </div>
                {f.top?.length ? (
                  <div className="friend-songs">
                    {f.top.slice(0, 3).map((s) => (
                      <button key={s.videoId} type="button" className="mini-song" onClick={() => app.playRadio({ ...s, durationMs: s.durationMs ?? null, cover: s.cover ?? `https://i.ytimg.com/vi/${s.videoId}/hqdefault.jpg`, artists: [] })} title={`${s.title} · ${s.artist}`}>
                        <Cover src={s.cover ?? `https://i.ytimg.com/vi/${s.videoId}/hqdefault.jpg`} size={40} />
                        <span>{s.title}</span>
                      </button>
                    ))}
                  </div>
                ) : null}
              </div>
            );
          })}
        </div>
      ) : (
        <div className="empty"><Icon name="people" size={34} /><p>{app.t('noFriends')}</p></div>
      )}
    </div>
  );
}
