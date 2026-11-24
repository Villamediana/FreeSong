// O passe da ponte, conferido aqui (quem assina é o relay: relay/src/pass.js). A chave pública vem do
// relay, dentro da máquina; um passe que não confere com ela pode ser de um relay que reiniciou (chave
// nova): a chave é pedida de novo, no máximo a cada [REFETCH_MS].
import { createPublicKey, verify } from 'node:crypto';

const CONTEXT = 'freesong-pass-v1';
const PASS = /^([A-Za-z0-9_-]{22})\.(\d{13})\.([A-Za-z0-9_-]{86})$/;
const SPKI_ED25519 = Buffer.from('302a300506032b6570032100', 'hex');
const MAX_AHEAD_MS = 2 * 3600_000 + 5 * 60_000;
const REFETCH_MS = 30_000;

export class PassCheck {
  /** [keyUrl]: o GET /v1/pass-key do relay. */
  constructor({ keyUrl, fetcher = fetch, clock = Date.now } = {}) {
    Object.assign(this, { keyUrl, fetcher, clock, key: null, fetchedAt: 0, pending: null });
  }

  async fetchKey() {
    if (this.pending) return this.pending;
    this.pending = (async () => {
      try {
        const r = await this.fetcher(this.keyUrl, { signal: AbortSignal.timeout(4000) });
        const j = r.ok ? await r.json() : null;
        const raw = Buffer.from(String(j?.key ?? ''), 'base64url');
        if (raw.length !== 32) return this.key;
        this.key = createPublicKey({ key: Buffer.concat([SPKI_ED25519, raw]), format: 'der', type: 'spki' });
        return this.key;
      } catch {
        return this.key;
      } finally {
        this.fetchedAt = this.clock();
        this.pending = null;
      }
    })();
    return this.pending;
  }

  /** O computador do passe [header], ou null (sem passe, vencido, ou de outra chave). */
  async check(header) {
    const m = PASS.exec(String(header ?? ''));
    if (!m) return null;
    const exp = Number(m[2]);
    const now = this.clock();
    if (exp <= now || exp - now > MAX_AHEAD_MS) return null;
    const text = Buffer.from(`${CONTEXT}\n${m[1]}\n${m[2]}`);
    const sig = Buffer.from(m[3], 'base64url');
    const ok = (key) => {
      try {
        return !!key && verify(null, text, key, sig);
      } catch {
        return false;
      }
    };
    if (ok(this.key ?? (await this.fetchKey()))) return m[1];
    // A chave pode ter mudado (o relay reiniciou): pede de novo, sem deixar um chute forçar pedidos ao relay.
    if (this.clock() - this.fetchedAt < REFETCH_MS) return null;
    return ok(await this.fetchKey()) ? m[1] : null;
  }
}
