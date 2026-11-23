// O que a página guarda entre uma visita e outra, neste navegador (IndexedDB): a chave do cofre e a
// que assina os pedidos (CryptoKeys que não saem do navegador), o token, o perfil, e o cofre como
// estava mais o que mudou aqui — fechado com a chave do cofre, nada às claras no disco. Apagado inteiro
// quando o computador é desligado.

const DB = 'freesong-web';
const VERSION = 1;

function req(r) {
  return new Promise((resolve, reject) => {
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}

export class Store {
  async open() {
    if (this.db) return this;
    const r = indexedDB.open(DB, VERSION);
    r.onupgradeneeded = () => {
      if (!r.result.objectStoreNames.contains('meta')) r.result.createObjectStore('meta');
    };
    this.db = await req(r);
    // Um banco com esse nome mas sem o que a página guarda (feito torto): fora, e de novo.
    if (!this.db.objectStoreNames.contains('meta')) {
      this.db.close();
      this.db = null;
      await req(indexedDB.deleteDatabase(DB)).catch(() => {});
      return this.open();
    }
    // Outra aba jogou o banco fora (desligou lá): solta aqui na hora.
    this.db.onversionchange = () => this.close();
    return this;
  }

  close() {
    this.db?.close();
    this.db = null;
  }

  get(key) {
    return req(this.db.transaction('meta').objectStore('meta').get(key));
  }

  set(key, value) {
    return req(this.db.transaction('meta', 'readwrite').objectStore('meta').put(value, key));
  }

  async wipe() {
    this.close();
    await req(indexedDB.deleteDatabase(DB)).catch(() => {});
  }
}

/** O mesmo só na memória desta aba: computador de outra pessoa ("Manter ligado" desmarcado). */
export class MemoryStore {
  constructor() {
    this.meta = new Map();
  }
  async open() { return this; }
  close() {}
  async get(key) { return this.meta.get(key); }
  async set(key, value) { this.meta.set(key, value); }
  async wipe() { this.meta.clear(); }
}
