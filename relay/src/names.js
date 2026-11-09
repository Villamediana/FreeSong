// Nomes de usuário: únicos, minúsculos, 3–20 caracteres. Quem não escolhe ganha um sozinho.
import { randomInt } from 'node:crypto';

const WORDS = [
  'vinil', 'acorde', 'batida', 'refrao', 'groove', 'melodia', 'ritmo', 'compasso', 'solo', 'coro',
  'bossa', 'samba', 'tango', 'salsa', 'cumbia', 'bolero', 'jazz', 'blues', 'funk', 'reggae',
  'indie', 'lofi', 'eco', 'nota', 'verso', 'ponte', 'fone', 'palco', 'agudo', 'grave',
  'tambor', 'violao', 'piano', 'baixo', 'cuica', 'pandeiro', 'sanfona', 'guitarra', 'flauta', 'bateria',
];

const RESERVED = new Set([
  'admin', 'administrador', 'root', 'freesong', 'suporte', 'support', 'soporte', 'oficial', 'official',
  'moderador', 'moderator', 'sistema', 'system', 'null', 'undefined', 'me', 'eu', 'yo',
]);

const PATTERN = /^[a-z][a-z0-9_.]{2,19}$/;

/** Normaliza o que a pessoa digitou ("@Miguel.V " → "miguel.v"). */
export function normalizeUsername(raw) {
  return String(raw ?? '').trim().replace(/^@/, '').toLowerCase();
}

/** Motivo de recusa, ou null se o nome serve. */
export function usernameProblem(name) {
  if (!PATTERN.test(name)) return 'invalid';
  if (name.endsWith('.') || name.includes('..')) return 'invalid';
  if (RESERVED.has(name)) return 'reserved';
  return null;
}

/** "groove_482" e o nome de exibição "Groove 482". */
export function randomIdentity() {
  const word = WORDS[randomInt(WORDS.length)];
  const number = randomInt(100, 10000);
  return {
    username: `${word}_${number}`,
    name: `${word[0].toUpperCase()}${word.slice(1)} ${number}`,
  };
}

/** Nome de exibição: 1–40 caracteres, sem quebras nem espaços nas pontas. */
export function cleanDisplayName(raw) {
  const name = String(raw ?? '').replace(/[\u0000-\u001f\u007f]/g, ' ').replace(/\s+/g, ' ').trim();
  if (name.length < 1 || [...name].length > 40) return null;
  return name;
}
