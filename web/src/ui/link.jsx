// Ligar este computador ao perfil: o QR code (lido pelo FreeSong no celular, ou pela câmera do
// celular) ou, sem o celular por perto, o código de recuperação. E a página num celular (lá é o app).
import { useEffect, useRef, useState } from 'react';
import qrcode from 'qrcode-generator';
import * as lock from '../core/crypto.js';
import { ApiError } from '../core/api.js';
import { Icon, Logo } from './kit.jsx';

/** "Chrome no Windows": o nome que o celular mostra na lista de computadores. */
export function browserName(t) {
  const ua = navigator.userAgent;
  const b = /Edg\//.test(ua) ? 'Edge' : /OPR\//.test(ua) ? 'Opera' : /Firefox\//.test(ua) ? 'Firefox' : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : t('browser');
  const os = /Windows/.test(ua) ? 'Windows' : /Mac OS X/.test(ua) ? 'Mac' : /CrOS/.test(ua) ? 'ChromeOS' : /Linux/.test(ua) ? 'Linux' : '';
  return os ? t('browserOn', b, os) : b;
}

function Qr({ text }) {
  const qr = qrcode(0, 'M');
  qr.addData(text, 'Byte');
  qr.make();
  const n = qr.getModuleCount();
  let d = '';
  for (let r = 0; r < n; r++) for (let c = 0; c < n; c++) if (qr.isDark(r, c)) d += `M${c} ${r}h1v1h-1z`;
  return (
    <svg viewBox={`-2 -2 ${n + 4} ${n + 4}`} shapeRendering="crispEdges" role="img" aria-label="QR">
      <rect x="-2" y="-2" width={n + 4} height={n + 4} fill="#fff" />
      <path d={d} fill="#0E0F14" />
    </svg>
  );
}

function CodeForm({ api, t, onLinked, keep, onBack }) {
  const [code, setCode] = useState('');
  const [state, setState] = useState({ busy: false, error: null });
  const submit = async (e) => {
    e.preventDefault();
    const norm = lock.normalizeCode(code);
    if (norm.length !== 16) return setState({ busy: false, error: t('codeShape') });
    setState({ busy: true, error: null });
    try {
      const proof = await lock.proofKeys();
      const r = await api.recover(norm, proof.pub, browserName(t));
      await onLinked({ token: r.token, proof: proof.privateKey, me: r.me, code: norm, owner: '', shared: !keep });
    } catch (err) {
      const msg = err instanceof ApiError && err.status === 404 ? t('codeWrong') : err instanceof ApiError && err.status === 429 ? t('slowDown') : err instanceof ApiError && err.code === 'too_many' ? t('tooManyComputers') : err?.message === 'locked' ? t('codeLocked') : t('linkFailed');
      setState({ busy: false, error: msg });
    }
  };
  return (
    <form className="code-form" onSubmit={submit}>
      <h2>{t('useCode')}</h2>
      <p className="faint">{t('useCodeHint')}</p>
      <input
        value={code}
        onChange={(e) => setCode(e.target.value.toUpperCase())}
        placeholder="7K2Q-M9XD-4TFA-H3RB"
        autoComplete="off"
        spellCheck={false}
        autoFocus
        maxLength={24}
        aria-label={t('recoveryCode')}
      />
      {state.error ? <p className="error">{state.error}</p> : null}
      <div className="form-actions">
        <button type="button" className="pill ghost" onClick={onBack}>{t('back')}</button>
        <button type="submit" className="pill on" disabled={state.busy}>{state.busy ? '…' : t('enter')}</button>
      </div>
    </form>
  );
}

export function LinkPage({ api, t, onLinked }) {
  const [code, setCode] = useState(null); // {url, id, poll, privateKey, secret}
  const [state, setState] = useState('waiting'); // waiting | expired | linking | failed
  const [keep, setKeep] = useState(true);
  const [withCode, setWithCode] = useState(false);
  const keepRef = useRef(true);
  keepRef.current = keep;
  const round = useRef(0);

  async function start() {
    const mine = ++round.current;
    setState('waiting');
    setCode(null);
    try {
      const keys = await lock.linkKeys();
      const proof = await lock.proofKeys();
      const s = await api.startLink(keys.pub, proof.pub, browserName(t));
      if (mine !== round.current) return;
      const c = { url: lock.linkUrl(location.origin, s.id, keys.pub, keys.secret), id: s.id, poll: s.poll, privateKey: keys.privateKey, secret: keys.secret };
      setCode(c);
      for (;;) {
        if (mine !== round.current) return;
        let res;
        try {
          res = await api.pollLink(c.id, c.poll, 25);
        } catch (e) {
          if (e instanceof ApiError && (e.status === 410 || e.status === 404)) {
            setState('expired');
            return;
          }
          await new Promise((r) => setTimeout(r, 3000));
          continue;
        }
        if (res.status !== 'linked') continue;
        setState('linking');
        const opened = await lock.unwrapLink(c.privateKey, c.id, c.secret, res.wrapped);
        // O que o celular disse (só ele podia dizer) e o que o servidor diz têm de ser o mesmo perfil.
        if (opened.user !== res.me?.id) throw new Error('perfil diferente');
        await onLinked({ token: res.token, proof: proof.privateKey, me: res.me, key: opened.key, owner: opened.owner, shared: !keepRef.current });
        return;
      }
    } catch {
      if (mine === round.current) setState('failed');
    }
  }

  useEffect(() => {
    start();
    return () => { round.current++; };
  }, []);

  return (
    <div className="center link-page">
      <div className="link">
        <div className="link-text">
          <div className="brand"><Logo size={44} /><span>FreeSong</span></div>
          <h1>{t('linkTitle')}</h1>
          <p className="lead">{t('linkLead')}</p>
          {withCode ? (
            <CodeForm api={api} t={t} onLinked={onLinked} keep={keep} onBack={() => setWithCode(false)} />
          ) : (
            <>
              <ol>
                <li>{t('link1')}</li>
                <li>{t('link2')}</li>
                <li>{t('link3')}</li>
              </ol>
              <button type="button" className="text-btn" onClick={() => setWithCode(true)}><Icon name="key" size={16} /> {t('noPhone')}</button>
            </>
          )}
          <label className="keep">
            <input type="checkbox" checked={keep} onChange={(e) => setKeep(e.target.checked)} />
            <span><b>{t('keepLinked')}</b><br />{t('keepLinkedHint')}</span>
          </label>
          <p className="safe"><Icon name="lock" size={15} /> {t('linkSafe')}</p>
        </div>
        <div className="link-qr">
          <div className="qr" data-code={code?.url}>
            {code ? <Qr text={code.url} /> : null}
            {state !== 'waiting' || !code ? (
              <div className="over">
                {state === 'expired' || state === 'failed' ? (
                  <>
                    <p>{state === 'expired' ? t('linkExpired') : t('linkFailed')}</p>
                    <button type="button" className="pill on" onClick={start}><Icon name="retry" size={16} /> {t('linkNew')}</button>
                  </>
                ) : <p>{state === 'linking' ? t('linking') : '…'}</p>}
              </div>
            ) : null}
          </div>
          <p className="qr-note">{state === 'waiting' && code ? t('linkWaiting') : ''}</p>
        </div>
      </div>
    </div>
  );
}

/** Num celular: o FreeSong de lá é o app. */
export function PhonePage({ t }) {
  return (
    <div className="center">
      <div className="phone-page">
        <Logo size={84} />
        <h1>{t('mobileTitle')}</h1>
        <p className="lead">{t('mobileLead')}</p>
        <a className="pill on big" href="https://github.com/Villamediana/FreeSong/releases/latest" rel="noopener noreferrer">{t('mobileGet')}</a>
      </div>
    </div>
  );
}

/** Celular ou tablet (a página é pro computador). */
export function isPhone() {
  const ua = navigator.userAgent;
  if (/Android|iPhone|iPad|iPod|Mobile|Silk|Kindle|BlackBerry|Opera Mini/i.test(ua)) return true;
  return /Macintosh/.test(ua) && navigator.maxTouchPoints > 1;
}
