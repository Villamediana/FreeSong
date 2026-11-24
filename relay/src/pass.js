// O passe da ponte (o /api do servidor web, que pede ao YouTube pela pessoa). Só computador ligado a um
// perfil usa a ponte: o relay, que sabe quem é, assina "este computador pode usar a ponte até tal hora"
// com uma chave só dele (Ed25519, nova cada vez que o relay sobe: os passes de antes deixam de valer e a
// página pede outro). O servidor web confere com a chave pública, que pede ao relay dentro da máquina
// (GET /v1/pass-key). Sem passe, a ponte não atende: ninguém de fora usa o servidor como API do YouTube.
import { generateKeyPairSync, sign } from 'node:crypto';

export const PASS_CONTEXT = 'freesong-pass-v1';
export const PASS_MS = 2 * 3600_000;

export class Passes {
  constructor({ clock = Date.now } = {}) {
    const { publicKey, privateKey } = generateKeyPairSync('ed25519');
    this.privateKey = privateKey;
    this.clock = clock;
    /** A chave pública crua (32 bytes, base64url). */
    this.publicKey = publicKey.export({ format: 'der', type: 'spki' }).subarray(-32).toString('base64url');
  }

  /** Um passe pra [sub] (o id do computador): "<sub>.<expira em ms>.<assinatura>". */
  issue(sub) {
    const exp = this.clock() + PASS_MS;
    const sig = sign(null, Buffer.from(`${PASS_CONTEXT}\n${sub}\n${exp}`), this.privateKey).toString('base64url');
    return { pass: `${sub}.${exp}.${sig}`, exp };
  }
}
