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
/** Ninguém se passa pela equipe: "freesong_oficial", "admin.br", "suporte2"... */
const RESERVED_PARTS = ['freesong', 'admin', 'suporte', 'support', 'soporte', 'moderador', 'moderator'];

const PATTERN = /^[a-z][a-z0-9_.]{2,19}$/;

// Invisíveis e de direção do texto: com eles dá pra esconder ou inverter parte de um nome
// (U+202E faz "exe.png" aparecer como "gnp.exe"), ou ter nome em branco. ZWJ/ZWNJ ficam (emoji de familia, escritas indianas).
const INVISIBLE = /[\u00AD\u034F\u061C\u115F\u1160\u17B4\u17B5\u180E\u200B\u200E\u200F\u202A-\u202E\u2060-\u2064\u2066-\u2069\u3164\uFEFF\uFFA0]|[\u{E0000}-\u{E007F}]/gu;
const CONTROL = /[\p{Cc}\u2028\u2029]/gu;
// "Zalgo": acento empilhado sem fim invade a linha de cima na tela dos outros.
const MARK_PILE = /(\p{M}{4})\p{M}+/gu;

/** Texto que vai pra tela de outra pessoa: uma linha, sem invisíveis, até [max] caracteres. */
export function cleanText(raw, max) {
  if (typeof raw !== 'string') return '';
  const text = raw.toWellFormed().replace(INVISIBLE, '').replace(CONTROL, ' ').replace(MARK_PILE, '$1').replace(/\s+/g, ' ').trim();
  const chars = [...text];
  return chars.length > max ? chars.slice(0, max).join('').trim() : text;
}

export const MAX_MESSAGE = 2000;
const MAX_MESSAGE_LINES = 40;

/**
 * Mensagem de conversa: como [cleanText], mas guarda as quebras de linha (no máximo uma linha em
 * branco seguida e [MAX_MESSAGE_LINES] linhas; o resto vira espaço) e vai até [max] caracteres.
 */
export function cleanMessage(raw, max = MAX_MESSAGE) {
  if (typeof raw !== 'string') return '';
  const text = raw.toWellFormed().replace(/\r\n?/g, '\n').replace(INVISIBLE, '')
    .replace(/(?!\n)[\p{Cc}\u2028\u2029]/gu, ' ').replace(MARK_PILE, '$1')
    .replace(/[^\S\n]+/g, ' ').replace(/ *\n */g, '\n').replace(/\n{3,}/g, '\n\n').trim();
  const lines = text.split('\n');
  const flat = lines.length > MAX_MESSAGE_LINES
    ? [...lines.slice(0, MAX_MESSAGE_LINES - 1), lines.slice(MAX_MESSAGE_LINES - 1).join(' ')].join('\n')
    : text;
  const chars = [...flat];
  return chars.length > max ? chars.slice(0, max).join('').trim() : flat;
}

/** Normaliza o que a pessoa digitou ("@Miguel.V " → "miguel.v"). */
export function normalizeUsername(raw) {
  return String(raw ?? '').trim().replace(/^@/, '').toLowerCase();
}

/** Motivo de recusa, ou null se o nome serve. */
export function usernameProblem(name) {
  if (!PATTERN.test(name)) return 'invalid';
  if (name.endsWith('.') || name.includes('..')) return 'invalid';
  if (RESERVED.has(name) || RESERVED_PARTS.some((part) => name.includes(part))) return 'reserved';
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

/** Nome de exibição: 1–40 caracteres, sem quebras, invisíveis nem espaços nas pontas. */
export function cleanDisplayName(raw) {
  const name = cleanText(raw, 41);
  if (name.length < 1 || [...name].length > 40) return null;
  return name;
}
