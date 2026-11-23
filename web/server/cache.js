// Respostas do YouTube Music guardadas um tempo (cada busca, artista ou álbum é pedido uma vez e serve
// a todos), com teto de itens. Também junta pedidos iguais que chegam ao mesmo tempo num só.
export class Cache {
  constructor({ max = 2000, clock = Date.now } = {}) {
    this.max = max;
    this.clock = clock;
    this.map = new Map(); // chave → {at, ttl, value}
    this.flying = new Map(); // chave → Promise
  }

  async get(key, ttl, load) {
    const hit = this.map.get(key);
    if (hit && this.clock() - hit.at < hit.ttl) {
      // Usado agora: vai pro fim (o mais antigo sem uso sai primeiro).
      this.map.delete(key);
      this.map.set(key, hit);
      return hit.value;
    }
    const flying = this.flying.get(key);
    if (flying) return flying;
    const p = (async () => {
      try {
        const value = await load();
        this.map.delete(key);
        this.map.set(key, { at: this.clock(), ttl, value });
        while (this.map.size > this.max) this.map.delete(this.map.keys().next().value);
        return value;
      } finally {
        this.flying.delete(key);
      }
    })();
    this.flying.set(key, p);
    return p;
  }
}
