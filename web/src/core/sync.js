// O cofre deste computador junto com o dos celulares do perfil, nos dois sentidos:
// - o que muda aqui (curtir, playlists, o que foi ouvido) fica guardado neste navegador na hora e vai
//   pro relay um instante depois (o que foi ouvido, com mais calma: junto com a próxima mudança, ao
//   sair da aba ou a cada 10 minutos);
// - o que o celular grava chega aqui na hora (uma espera longa no relay, que responde quando o cofre muda);
// - as três versões se juntam como no app (vault.js: merge), com a última sincronização como base.
import { ApiError } from './api.js';
import { openLocal, openVault, sealLocal, sealVault } from './crypto.js';
import { compact, empty, merge, normalize, same } from './vault.js';

const SOON_MS = 1500;
const PLAYS_MS = 10 * 60_000;

export class VaultSync {
  constructor({ api, key, store, clock = () => Date.now(), onUnlinked = () => {} }) {
    Object.assign(this, { api, key, store, clock, onUnlinked });
    this.rev = 0;
    this.base = null; // o cofre como ficou na última sincronização (null: ainda nenhuma)
    this.local = empty(); // o cofre como está aqui (com o que mudou e ainda não foi)
    this.status = { online: true, syncing: false, synced: false, error: null, novault: false, at: 0 };
    this.listeners = new Set();
    this.version = 0;
    this.running = false;
    this.lock = Promise.resolve();
  }

  subscribe(fn) {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  emit() {
    this.version++;
    for (const f of this.listeners) f();
  }

  setStatus(s) {
    this.status = { ...this.status, ...s };
    this.emit();
  }

  get data() {
    return this.local;
  }

  /** O que este navegador guardou (abre na hora, antes de falar com o relay). */
  async load() {
    const sealed = await this.store.get('vault');
    if (!sealed) return;
    try {
      const v = await openLocal(sealed, this.key);
      this.rev = v.rev ?? 0;
      this.base = v.base ? normalize(v.base) : null;
      this.local = normalize(v.local);
      this.emit();
    } catch { /* de outra chave: começa do relay */ }
  }

  async save() {
    const sealed = await sealLocal({ rev: this.rev, base: this.base, local: this.local }, this.key);
    await this.store.set('vault', sealed);
  }

  /** Uma mudança aqui: [fn] recebe o cofre e devolve o novo. [quiet] (uma escuta) vai com calma. */
  change(fn, { quiet = false } = {}) {
    const next = fn(this.local);
    if (!next || next === this.local) return;
    this.local = normalize(next);
    this.emit();
    clearTimeout(this.saving);
    this.saving = setTimeout(() => this.save().catch(() => {}), 300);
    if (quiet) {
      this.plays ??= setTimeout(() => { this.plays = null; this.push(); }, PLAYS_MS);
    } else {
      clearTimeout(this.soon);
      this.soon = setTimeout(() => this.push(), SOON_MS);
    }
  }

  /** O que mudou aqui vai agora (ao sair da aba, por exemplo). */
  flush() {
    if (this.base && same(this.base, this.local)) return Promise.resolve();
    return this.push();
  }

  push() {
    clearTimeout(this.soon);
    clearTimeout(this.plays);
    this.plays = null;
    return this.sync().catch(() => {});
  }

  /** Uma sincronização (uma de cada vez). [remote]: o cofre que acabou de chegar da espera longa. */
  sync(remote = null) {
    const run = this.lock.then(() => this.syncNow(remote));
    this.lock = run.catch(() => {});
    return run;
  }

  async syncNow(given) {
    this.setStatus({ syncing: true });
    try {
      for (let attempt = 0; attempt < 4; attempt++) {
        const r = given ?? (await this.api.vault());
        given = null;
        if (!r) {
          // O perfil ainda não tem cofre: o celular faz o primeiro (ao abrir o app).
          this.setStatus({ syncing: false, novault: true, online: true });
          return;
        }
        if (r.same) return this.setStatus({ syncing: false, online: true, error: null });
        let remote;
        try {
          remote = normalize(await openVault(r.data, this.key));
        } catch {
          // A chave daqui não abre mais (um código novo trocou a chave? não: a chave do cofre não muda).
          this.setStatus({ syncing: false, error: 'locked' });
          return;
        }
        const now = this.clock();
        const mine = this.local;
        const merged = merge(this.base, mine, remote, now);
        let rev = r.rev;
        if (!same(merged, remote)) {
          try {
            rev = (await this.api.putVault(r.rev, await sealVault(compact(merged), this.key))).rev;
          } catch (e) {
            // Outro aparelho gravou no meio: lê de novo e junta de novo.
            if (e instanceof ApiError && e.status === 409) continue;
            throw e;
          }
        }
        // O que mudou aqui enquanto isso tudo acontecia fica (junto por cima do que se juntou).
        const after = this.local === mine ? merged : normalize(merge(mine, this.local, merged, this.clock()));
        this.rev = rev;
        this.base = merged;
        this.local = after;
        this.status = { ...this.status, syncing: false, synced: true, online: true, error: null, novault: false, at: this.clock() };
        this.emit();
        await this.save();
        if (after !== merged && !same(after, merged)) this.push();
        return;
      }
      this.setStatus({ syncing: false, error: 'busy' });
    } catch (e) {
      const offline = e instanceof ApiError && (e.status === 0 || e.status >= 500);
      this.setStatus({ syncing: false, online: !offline, error: offline ? null : e?.code ?? 'error' });
      throw e;
    }
  }

  /** Fica esperando o que o celular gravar (e começa sincronizando o que está aqui). */
  async run() {
    if (this.running) return;
    this.running = true;
    await this.sync().catch(() => {});
    let pause = 1000;
    while (this.running) {
      try {
        this.abort = new AbortController();
        const r = await this.api.vault(this.rev, 25, this.abort.signal);
        pause = 1000;
        if (!this.status.online) this.setStatus({ online: true });
        if (!r) {
          if (!this.status.novault) this.setStatus({ novault: true });
          await wait(20_000);
        } else if (!r.same && r.rev !== this.rev) await this.sync(r).catch(() => {});
      } catch (e) {
        if (!this.running) return;
        if (e instanceof ApiError && e.status === 401) return;
        if (e instanceof ApiError && e.status === 0) this.setStatus({ online: false });
        await wait(pause);
        pause = Math.min(pause * 2, 30_000);
      }
    }
  }

  stop() {
    this.running = false;
    this.abort?.abort();
    clearTimeout(this.soon);
    clearTimeout(this.plays);
    clearTimeout(this.saving);
  }
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms));
