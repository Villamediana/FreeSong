// Os links que o FreeSong compartilha: /m/<vídeo> (música), /v/<vídeo> (vídeo fora do catálogo de
// música), /l/<playlist> e /a/<álbum>. No celular com o FreeSong eles abrem direto no app (App
// Links verificados: o Android confere /.well-known/assetlinks.json com a chave que assina o app).
// Em qualquer outro lugar abrem esta página: a capa, "Abrir no FreeSong" e a mesma música no
// YouTube Music. Nada é guardado nem consultado: o link já diz tudo, e a página não tem script.

const PACKAGE = 'com.villamediana.sound';
/** SHA-256 do certificado da chave de release (a mesma do tools/release.sh). */
const CERT = '463fc12f58856f182397337e0fe868497c2bffb4fa145383bea7f8dacbb14644';
const DOWNLOAD = 'https://github.com/Villamediana/FreeSong/releases/latest';

export const ASSET_LINKS = JSON.stringify([
  {
    relation: ['delegate_permission/common.handle_all_urls'],
    target: {
      namespace: 'android_app',
      package_name: PACKAGE,
      sha256_cert_fingerprints: [CERT.toUpperCase().match(/../g).join(':')],
    },
  },
]);

const VIDEO = /^[A-Za-z0-9_-]{11}$/;
const LIST = /^[A-Za-z0-9_-]{10,80}$/;
const ALBUM = /^MPREb_[A-Za-z0-9_-]{4,74}$/;

/** O link do YouTube que um caminho nosso representa; null se o caminho não é um link do FreeSong. */
export function target(pathname) {
  const m = /^\/([mvla])\/([A-Za-z0-9_-]{1,80})$/.exec(pathname);
  if (!m) return null;
  const [, kind, id] = m;
  if (kind === 'm' && VIDEO.test(id)) return { kind: 'song', id, url: `https://music.youtube.com/watch?v=${id}` };
  if (kind === 'v' && VIDEO.test(id)) return { kind: 'song', id, url: `https://www.youtube.com/watch?v=${id}` };
  if (kind === 'l' && LIST.test(id)) return { kind: 'list', id, url: `https://music.youtube.com/playlist?list=${id}` };
  if (kind === 'a' && ALBUM.test(id)) return { kind: 'list', id, url: `https://music.youtube.com/browse/${id}` };
  return null;
}

const TEXT = {
  pt: {
    lang: 'pt-BR',
    song: 'Uma música pra você',
    list: 'Uma playlist pra você',
    about: 'Ouça no FreeSong: sem conta, sem anúncio e sem assinatura.',
    open: 'Abrir no FreeSong',
    get: 'Baixar o FreeSong',
    youtube: 'Ouvir no YouTube Music',
    android: 'O FreeSong é um app para Android.',
  },
  es: {
    lang: 'es',
    song: 'Una canción para ti',
    list: 'Una playlist para ti',
    about: 'Escúchala en FreeSong: sin cuenta, sin anuncios y sin suscripción.',
    open: 'Abrir en FreeSong',
    get: 'Descargar FreeSong',
    youtube: 'Escuchar en YouTube Music',
    android: 'FreeSong es una app para Android.',
  },
};

/** Só o que a página usa: a capa vem do YouTube, o estilo vai junto, nenhum script. */
export const PAGE_CSP = "default-src 'none'; img-src https://i.ytimg.com; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

/**
 * A página de um link. [host] é o nome pelo qual o pedido chegou (o link "Abrir no FreeSong" aponta
 * pra ele), [language] o Accept-Language, [agent] o User-Agent (só Android ganha o botão do app).
 * Tudo o que entra aqui já passou pelos padrões acima: nada do pedido é escrito sem conferir.
 */
export function page(link, pathname, { host = 'freesong.simplyonegoal.com', language = '', agent = '' } = {}) {
  const t = /^\s*es\b/i.test(language) ? TEXT.es : TEXT.pt;
  const safeHost = /^[a-z0-9.-]{1,100}$/i.test(host) ? host.toLowerCase() : 'freesong.simplyonegoal.com';
  const title = link.kind === 'song' ? t.song : t.list;
  const cover = link.kind === 'song' ? `https://i.ytimg.com/vi/${link.id}/hqdefault.jpg` : null;
  const android = /android/i.test(agent);
  // Chrome no Android: abre o app pelo pacote (mesmo que o link ainda não esteja verificado nele);
  // sem o app, cai na página de download.
  const intent = `intent://${safeHost}${pathname}#Intent;scheme=https;package=${PACKAGE};S.browser_fallback_url=${encodeURIComponent(DOWNLOAD)};end`;
  const buttons = android
    ? `<a class="b main" href="${intent}">${t.open}</a><a class="b" href="${link.url}">${t.youtube}</a><a class="s" href="${DOWNLOAD}">${t.get}</a>`
    : `<a class="b main" href="${link.url}">${t.youtube}</a><p class="s">${t.android} <a href="${DOWNLOAD}">${t.get}</a></p>`;
  return `<!doctype html>
<html lang="${t.lang}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>${title} · FreeSong</title>
<meta property="og:site_name" content="FreeSong">
<meta property="og:title" content="${title}">
<meta property="og:description" content="${t.about}">
${cover ? `<meta property="og:image" content="${cover}">` : ''}
<meta name="theme-color" content="#0f0d0b">
<style>
:root{color-scheme:dark}
*{box-sizing:border-box}
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#0f0d0b;color:#f4efe8;font:16px/1.45 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;padding:32px 20px}
main{width:100%;max-width:340px;text-align:center}
.c{width:220px;height:220px;margin:0 auto 28px;border-radius:6px;overflow:hidden;background:#1c1916}
.c img{width:100%;height:100%;object-fit:cover;display:block}
.o{font-size:11px;letter-spacing:.14em;text-transform:uppercase;color:#a39a8e;margin:0 0 8px}
h1{font-size:28px;line-height:1.12;letter-spacing:-.02em;font-weight:800;margin:0 0 10px;text-wrap:balance}
.d{color:#a39a8e;margin:0 0 28px;text-wrap:pretty}
.b{display:block;padding:14px 18px;border-radius:999px;text-decoration:none;font-weight:700;color:#f4efe8;background:#262220;margin:0 0 10px}
.b.main{background:#ff7a2f;color:#1a0f06}
.s{display:block;margin:14px 0 0;font-size:14px;color:#a39a8e}
a.s,.s a{color:#f4efe8}
</style>
</head>
<body>
<main>
${cover ? `<div class="c"><img src="${cover}" alt="" referrerpolicy="no-referrer"></div>` : ''}
<p class="o">FreeSong</p>
<h1>${title}</h1>
<p class="d">${t.about}</p>
${buttons}
</main>
</body>
</html>
`;
}
