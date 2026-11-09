// "Seus números" que os amigos veem no perfil: só totais do mês e do ano (minutos, músicas,
// artistas, ritmo da semana, horário de pico, top artistas). Nenhum histórico:
// o celular faz as contas e manda o resultado. Tudo conferido e limitado aqui; o que vier
// estranho vira vazio, nunca erro (um campo ruim não derruba o resto).
import { cleanText } from './names.js';
import { cleanCover } from './jams.js';

const int = (v, max) => (Number.isFinite(v) && v >= 0 ? Math.min(Math.round(v), max) : 0);

function cleanPeriod(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  const weekdays = Array.isArray(raw.weekdays) && raw.weekdays.length === 7 ? raw.weekdays.map((m) => int(m, 600_000)) : [0, 0, 0, 0, 0, 0, 0];
  const hour = Number.isInteger(raw.hour) && raw.hour >= 0 && raw.hour <= 23 ? raw.hour : null;
  const top = (Array.isArray(raw.top) ? raw.top : []).slice(0, 5).map((a) => {
    if (!a || typeof a !== 'object') return null;
    const name = cleanText(a.name, 120);
    if (!name) return null;
    const out = { name, minutes: int(a.minutes, 600_000) };
    const cover = cleanCover(a.cover);
    if (cover) out.cover = cover;
    return out;
  }).filter(Boolean);
  return {
    minutes: int(raw.minutes, 600_000),
    songs: int(raw.songs, 1_000_000),
    artists: int(raw.artists, 1_000_000),
    hour,
    weekdays,
    top,
  };
}

/** Os números do mês e do ano; null quando não serve. */
export function cleanNumbers(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  const month = cleanPeriod(raw.month);
  const year = cleanPeriod(raw.year);
  if (!month && !year) return null;
  return { month, year };
}
