// A página: web/src (React) → web/public/app.js (um arquivo, sem source map, as licenças do que
// entrou nele ao lado). Rodar: npm run build (depois de mudar web/src).
import * as esbuild from 'esbuild';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
await esbuild.build({
  entryPoints: [path.join(here, 'src/main.jsx')],
  outfile: path.join(here, 'public/app.js'),
  bundle: true,
  format: 'esm',
  minify: true,
  target: ['es2022', 'chrome111', 'firefox115', 'safari16.4'],
  jsx: 'automatic',
  define: { 'process.env.NODE_ENV': '"production"' },
  legalComments: 'linked',
  charset: 'utf8',
  logLevel: 'warning',
});
console.log('web/public/app.js pronto');
