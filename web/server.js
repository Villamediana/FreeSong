// node server.js — o FreeSong no computador: as páginas e a ponte com o YouTube Music.
// Variáveis: PORT (3982), HOST (127.0.0.1). Ver web/README.md.
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createWeb } from './server/app.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const port = parseInt(process.env.PORT ?? '3982', 10);
const host = process.env.HOST ?? '127.0.0.1';
const web = createWeb({ publicDir: path.join(here, 'public') });
web.server.listen(port, host, () => console.log(`[web] FreeSong web em http://${host}:${port}/web/`));
const stop = () => {
  setTimeout(() => process.exit(0), 3000).unref();
  web.server.close(() => process.exit(0));
  web.server.closeIdleConnections?.();
};
process.on('SIGTERM', stop);
process.on('SIGINT', stop);
