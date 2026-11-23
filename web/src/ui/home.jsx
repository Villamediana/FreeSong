// A Início: a saudação com os atalhos (curtidas, playlists, mixes), "Feitos pra você", tocadas
// recentemente, suas playlists e o que está em alta no YouTube Music.
import { useEffect, useMemo, useState } from 'react';
import { recent } from '../core/taste.js';
import { fromV } from '../core/vault.js';
import { Collage, Cover, Icon, PlayBtn, sized, useApp, useLoad, useWatch } from './kit.jsx';
import { Card, Shelf } from './songs.jsx';

function greeting(t) {
  const h = new Date().getHours();
  return h < 5 ? t('goodNight') : h < 12 ? t('goodMorning') : h < 18 ? t('goodAfternoon') : t('goodNight');
}

/** Um mix na Início: carrega as músicas quando aparece; o cartão abre a página dele. */
function MixCard({ plan }) {
  const app = useApp();
  const [mix, setMix] = useState(null);
  useEffect(() => {
    let live = true;
    app.mixes.get(plan.id).then((m) => { if (live) setMix(m); }, () => {});
    return () => { live = false; };
  }, [plan.id]);
  const title = app.mixTitle(plan);
  const sub = mix?.artists?.length ? app.t('mixWith', mix.artists.join(', ')) : '';
  return (
    <Card
      item={{ kind: 'mix', id: plan.id, title, subtitle: sub || '…', covers: mix?.covers?.length >= 4 ? mix.covers : null, cover: mix?.covers?.[0] }}
      onPlay={mix?.tracks?.length ? () => app.playFrom(mix.tracks, 0, { kind: 'mix', id: plan.id, title }) : undefined}
    />
  );
}

/** Um atalho largo da saudação (capa pequena, nome, play que aparece). */
function Tile({ title, cover, covers, href, onPlay, icon }) {
  const app = useApp();
  return (
    <a className="tile" href={href}>
      {covers ? <Collage covers={covers.map((c) => sized(c, 120))} size={56} /> : cover ? <Cover src={sized(cover, 120)} size={56} /> : <span className="tile-icon"><Icon name={icon} size={24} fill={icon === 'heart'} /></span>}
      <span className="tile-title">{title}</span>
      {onPlay ? <span className="tile-play"><PlayBtn size="sm" label={app.t('play')} onClick={onPlay} /></span> : null}
    </a>
  );
}

export function Home() {
  const app = useApp();
  const sync = useWatch(app.sync);
  const v = sync.data;
  const plans = useMemo(() => app.mixes.plan(), [sync.status.synced, v.plays.length > 0, v.likes.length > 0, v.gen?.at]);
  const recents = useMemo(() => recent(v, 16), [v.plays]);
  const lists = [...v.lists].sort((a, b) => (b.p - a.p) || b.up - a.up);
  const explore = useLoad(() => app.api.explore(), [app.lang]);
  const liked = [...v.likes].sort((a, b) => b.at - a.at).map((l) => fromV(l.s));
  const tiles = [
    liked.length ? { key: 'liked', title: app.t('likedSongs'), icon: 'heart', href: '#/liked', play: () => app.playFrom(liked, 0, { kind: 'liked', title: app.t('likedSongs') }) } : null,
    ...lists.slice(0, 5).map((p) => ({ key: p.k, title: p.n, covers: p.s.length >= 4 ? p.s.map((s) => s.c) : null, cover: p.s[0]?.c, icon: 'queue', href: `#/list/${p.k}`, play: p.s.length ? () => app.playFrom(p.s.map(fromV), 0, { kind: 'list', id: p.k, title: p.n }) : null })),
    ...plans.slice(0, 2).map((m) => ({ key: m.id, title: app.mixTitle(m), icon: 'spark', href: `#/mix/${encodeURIComponent(m.id)}` })),
  ].filter(Boolean).slice(0, 6);

  return (
    <div className="page home">
      <div className="hello">
        <h1>{greeting(app.t)}</h1>
        {tiles.length ? <div className="tiles">{tiles.map((x) => <Tile key={x.key} title={x.title} cover={x.cover} covers={x.covers} icon={x.icon} href={x.href} onPlay={x.play} />)}</div> : null}
      </div>
      {!v.plays.length && !v.likes.length && sync.status.synced ? (
        <div className="hint-card">
          <Icon name="spark" size={22} />
          <p>{app.t('homeEmpty')}</p>
        </div>
      ) : null}
      {plans.length ? (
        <Shelf title={app.t('madeForYou')}>
          {plans.map((p) => <MixCard key={p.id} plan={p} />)}
        </Shelf>
      ) : null}
      {recents.length ? (
        <Shelf title={app.t('recentlyPlayed')} more="#/history">
          {recents.map((s) => <Card key={s.videoId} item={{ ...s, subtitle: s.artist }} />)}
        </Shelf>
      ) : null}
      {lists.length ? (
        <Shelf title={app.t('yourPlaylists')} more="#/library">
          {lists.map((p) => (
            <Card
              key={p.k}
              item={{ kind: 'list', id: p.k, title: p.n, subtitle: app.t('songsCount', p.s.length), covers: p.s.length >= 4 ? p.s.map((s) => s.c) : null, cover: p.s[0]?.c }}
              onPlay={p.s.length ? () => app.playFrom(p.s.map(fromV), 0, { kind: 'list', id: p.k, title: p.n }) : undefined}
            />
          ))}
        </Shelf>
      ) : null}
      {(explore.data?.shelves ?? []).map((sh) => (
        <Shelf key={sh.title} title={sh.title}>
          {sh.items.map((it) => <Card key={it.videoId ?? `${it.kind}:${it.id}`} item={it.videoId ? { ...it, subtitle: it.artist } : it} />)}
        </Shelf>
      ))}
    </div>
  );
}
