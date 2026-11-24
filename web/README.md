# FreeSong no computador (web)

`https://freesong.simplyonegoal.com` — o FreeSong no navegador do computador, com as mesmas
curtidas, playlists e o que você ouve do celular, nos dois sentidos. No celular a página só diz pra
baixar o app.

- **Ligar**: o computador mostra um QR code; o app (Ajustes › Computador) ou a câmera do celular lê e
  manda a chave do cofre cifrada só pra este navegador (ECDH + HKDF com o segredo do QR code). Sem o
  celular: o código de recuperação. "Manter ligado" desmarcado = nada fica no disco e fechar a aba desliga.
- **O cofre**: o mesmo do app (`social/Vault.kt`), aberto e fechado aqui (`src/core/crypto.js`,
  `vault.js`, `sync.js`), juntado com a mesma regra (`VaultMerge`). O relay guarda bytes que não lê; uma
  espera longa traz na hora o que o celular grava, e o celular recebe `{t: "vault"}` pelo WebSocket.
- **O som**: o player do próprio YouTube num frame (`youtube-nocookie.com`), comandado por mensagens
  (`src/core/youtube.js`), sem script de fora na página. Vídeo que o dono não deixa tocar fora do YouTube
  → tenta outro da mesma música; três seguidos sem tocar → para.
- **A ponte** (`server/`): busca, artista, álbum, playlist, rádio, em alta (YouTube Music pela
  youtubei.js) e letras (LRCLIB, depois YouTube Music), com cache na memória. Nada é guardado nem vai
  pra log (só o tipo do pedido, quando dá erro).
- **O passe da ponte** (1.38): a ponte só atende computador ligado. O relay dá um passe assinado
  (Ed25519, `GET /v1/pass`, pedido assinado pelo computador, vale 2 h; `relay/src/pass.js`); a ponte
  confere com a chave pública que pede ao relay dentro da máquina (`GET /v1/pass-key`,
  `server/pass.js`, `RELAY_URL`, padrão `http://127.0.0.1:3980`). Relay reiniciado = chave nova: a
  página pede outro passe sozinha. Limites: por IP (300/min) e por computador (300 pedidos/min, 120
  idas ao YouTube/min fora do cache, 6 ao mesmo tempo). **Deploy: relay antes da web.**
- **O primeiro play** (1.38): comandos que chegam antes do frame ficar pronto não se acumulam (um vídeo
  novo tira os velhos da fila); o "estou ouvindo" não para aos 10 s; um frame que não fica pronto em
  8 s é refeito; "tocar" de novo aos 0,9 / 2,2 / 4,5 s; o botão gira enquanto carrega e, travado por
  12 s, aparece "Clique para tocar". Nada tocando: o painel sugere por onde começar.
- **CSP**: só o que é da página, as capas do YouTube/Google e o frame do player; Trusted Types; sem
  Referer (o frame do player manda só a origem, que o YouTube exige).

## Rodar e testar

```bash
export PATH=/opt/node/bin:$PATH
cd web && npm ci
npm run build            # src/ → public/app.js (commite o app.js)
npm test                 # cofre, cifra, sincronização e a paridade com o app
node test/e2e.mjs /tmp/fotos   # ponta a ponta no Chromium (rede de verdade, ~2 min)
PORT=3982 npm start      # http://127.0.0.1:3982/
```

A paridade com o celular: `WebParityTest` (no app) abre o que `tools/fixtures.mjs` gerou e grava
`test/fixtures/phone.json`, que `test/parity.test.js` abre. Mudou a cifra ou o formato do cofre? Rode os dois.

Neste VPS o YouTube recusa o player embutido (IP de datacenter): o E2E confere que o frame abre, não
que o som sai. Num computador de casa ele toca.

## No servidor

`/srv/freesong/web` (o mesmo clone do relay), unit `freesong-web.service` em `127.0.0.1:3982`; o túnel
manda pro relay o que é dele (`/v1`, `/avatars`, `/.well-known`, os links `/m|v|l|a/…`) e o resto pra ela. Atualizar:
`cd /srv/freesong && git pull && cd web && npm ci --omit=dev && systemctl restart freesong-web`.
