// As páginas do FreeSong no computador (web/public), servidas em /web/. Nada aqui é feito por
// pessoa: a página é a mesma pra todos e faz tudo no navegador (o cofre chega cifrado e é aberto lá).
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.txt': 'text/plain; charset=utf-8',
};

const YT = 'https://www.youtube-nocookie.com';

// Só o que a página é: scripts e estilos dela mesma, as capas dos servidores de imagem do YouTube e
// do Google, e o player do YouTube num frame (o som toca lá). Nenhum texto vira HTML (Trusted Types).
export const CSP = [
  "default-src 'none'",
  "script-src 'self'",
  "style-src 'self'",
  "img-src 'self' data: blob: https://i.ytimg.com https://*.ytimg.com https://*.googleusercontent.com https://*.ggpht.com",
  "font-src 'self'",
  "connect-src 'self'",
  `frame-src ${YT}`,
  "manifest-src 'self'",
  "media-src 'none'",
  "worker-src 'none'",
  "frame-ancestors 'none'",
  "base-uri 'none'",
  "form-action 'none'",
  "require-trusted-types-for 'script'",
  "trusted-types 'none'",
].join('; ');

export const HEADERS = {
  'Content-Security-Policy': CSP,
  'X-Content-Type-Options': 'nosniff',
  'Referrer-Policy': 'no-referrer',
  'Cross-Origin-Opener-Policy': 'same-origin',
  'Cross-Origin-Resource-Policy': 'same-origin',
  // O player do YouTube pode tocar sozinho e ir pra tela cheia; câmera, microfone e o resto, nada.
  'Permissions-Policy': `autoplay=(self "${YT}"), encrypted-media=(self "${YT}"), fullscreen=(self "${YT}"), picture-in-picture=(self "${YT}"), camera=(), microphone=(), geolocation=(), payment=(), usb=()`,
  'Strict-Transport-Security': 'max-age=31536000',
};

export class Pages {
  constructor({ dir }) {
    this.dir = path.resolve(dir);
    this.cache = new Map(); // arquivo → {bytes, etag, mtime, size}
  }

  serve(req, res, p) {
    if (p === '/web') {
      res.writeHead(301, { Location: '/web/', 'Cache-Control': 'no-store' });
      return res.end();
    }
    const rel = decodeURIComponent(p.slice('/web/'.length)) || 'index.html';
    let file = this.file(rel);
    // Uma tela do app (sem arquivo com esse nome, sem extensão): o próprio app, que lê o endereço.
    if (!file && !path.extname(rel)) file = this.file('index.html');
    if (file && path.basename(file.path) === 'index.html') file = this.page(file);
    if (!file) {
      const body = Buffer.from('Não existe.');
      res.writeHead(404, { ...HEADERS, 'Content-Type': 'text/plain; charset=utf-8', 'Content-Length': body.length });
      return res.end(body);
    }
    // Pedido pela versão (?v=<hash>, como a página pede o script e o estilo): guardado pra sempre.
    const v = new URL(req.url, 'http://x').searchParams.get('v');
    const forever = !!v && v === file.etag.slice(1, -1);
    const cache = forever ? 'public, max-age=31536000, immutable, no-transform' : 'no-cache, no-transform';
    if (req.headers['if-none-match'] === file.etag) {
      res.writeHead(304, { ...HEADERS, ETag: file.etag, 'Cache-Control': cache });
      return res.end();
    }
    res.writeHead(200, {
      ...HEADERS,
      'Content-Type': TYPES[path.extname(file.path)] ?? 'application/octet-stream',
      'Content-Length': file.bytes.length,
      ETag: file.etag,
      'Cache-Control': cache,
    });
    return res.end(req.method === 'HEAD' ? undefined : file.bytes);
  }

  /** A página, pedindo o script, o estilo e o manifesto pela versão (?v=<hash>). */
  page(index) {
    const parts = ['app.js', 'style.css', 'manifest.webmanifest'].map((n) => [n, this.file(n)]);
    const key = index.etag + parts.map(([, f]) => f?.etag ?? '').join('');
    if (this.pageKey === key) return this.pageFile;
    let html = index.bytes.toString('utf8');
    for (const [n, f] of parts) if (f) html = html.replaceAll(`"${n}"`, `"${n}?v=${f.etag.slice(1, -1)}"`);
    const bytes = Buffer.from(html);
    this.pageKey = key;
    this.pageFile = { path: index.path, bytes, etag: `"${crypto.createHash('sha256').update(bytes).digest('base64url').slice(0, 22)}"` };
    return this.pageFile;
  }

  /** Um arquivo de dentro da pasta (nunca de fora, nem escondido); null se não há. */
  file(rel) {
    if (!rel || rel.includes('\0')) return null;
    const full = path.resolve(this.dir, rel);
    if (!full.startsWith(this.dir + path.sep)) return null;
    if (path.relative(this.dir, full).split(path.sep).some((part) => part.startsWith('.'))) return null;
    let st;
    try {
      st = fs.statSync(full);
    } catch {
      return null;
    }
    if (!st.isFile()) return null;
    const hit = this.cache.get(full);
    if (hit && hit.mtime === st.mtimeMs && hit.size === st.size) return hit;
    const bytes = fs.readFileSync(full);
    const f = { path: full, bytes, etag: `"${crypto.createHash('sha256').update(bytes).digest('base64url').slice(0, 22)}"`, mtime: st.mtimeMs, size: st.size };
    this.cache.set(full, f);
    return f;
  }
}
