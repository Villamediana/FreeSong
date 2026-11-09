# FreeSong relay: API (v1.1.0)

Contrato entre o app e o servidor. Endereço: `https://freesong.simplyonegoal.com`.
Instalação do servidor: [DEPLOY.md](DEPLOY.md).

## Novidades da 1.1.0 (o que o app precisa adaptar)

1. **Só HTTPS/WSS.** Pedido que chega por `http://` (ou `ws://`) recebe `403 {"error":"https_only"}`
   e não é processado. Use sempre `https://` e `wss://`.
2. **Foto** (`PUT /v1/me/avatar`): tem que ser um JPEG de verdade, até 2048 px de lado (mande 512 px).
   O servidor confere a estrutura e tira EXIF/GPS, XMP, IPTC, comentários e o que vier depois do fim
   da imagem. JPEG torto, cortado ou gigante → `400 not_jpeg`.
3. **Capa e link das músicas**: só `https` sem usuário/porta. `cover` só de `i.ytimg.com`,
   `i0..i9.ytimg.com`, `*.googleusercontent.com`, `*.ggpht.com`; `url` só de `youtube.com`,
   `www.`/`m.`/`music.youtube.com` e `youtu.be`. Fora disso o campo é descartado em silêncio (a
   música passa sem ele).
4. **Textos** (nome, título, artista, álbum): caracteres invisíveis, de controle e de direção do
   texto (U+202E etc.) são removidos; quebra de linha vira espaço; acento empilhado é cortado em 4.
   Nome que fica vazio depois disso → `400 invalid_name`.
   **O `@nome` não muda** (1.2.0): é o identificador da pessoa, sorteado quando a conta nasce.
   `PATCH /v1/me` com outro `username` → `400 username_fixed` (o mesmo, de volta, passa).
5. **Aparelhos**: até 10 tokens por perfil; o 11º (recuperação) derruba o mais antigo, que passa a
   receber `401`. Novo `DELETE /v1/me/sessions` ("sair dos outros aparelhos").
6. **WebSocket**: no máximo 5 conexões por pessoa (a mais antiga é fechada com **4002**); token
   revogado fecha com **4003**. Ver "Códigos de fechamento": 4002 e 4003 **não** devem reconectar em
   laço.
7. **Jam**: `POST /v1/jams` com `state` inválido → `400 invalid_state` (antes criava a jam vazia).
   Teto de 120 mudanças por minuto por jam: `POST /state` → `429 slow_down`; pelo WebSocket volta
   `{t:"jam", jam, rejected:"slow_down"}` (até 10 por minuto por pessoa; depois disso, sem resposta).
   Convidar quem já foi convidado não manda `jam.invite` de novo.
8. **Amizade**: pedir de novo quem já tem pedido seu em aberto devolve o mesmo `requestId` sem novo
   aviso. Pedir/cancelar/pedir tem limite (3 vezes por dia pro mesmo perfil; 60 pedidos novos por
   hora) → `429 slow_down`.
9. **`429 slow_down`** pode vir de vários limites (lista no fim). Trate sempre igual: espere e tente
   de novo com backoff (ex.: 2 s, 5 s, 15 s...), sem laço apertado.
10. `ping`: o `c` só volta se for número ou texto de até 64 caracteres.

## Regras gerais

- JSON UTF-8 nos dois sentidos, corpo até 64 KB (foto até 600 KB). Respostas com `cache-control: no-store`.
- Erro: status HTTP + `{"error": "<código>", ...extra}`. Códigos comuns: `unauthorized` (401),
  `slow_down` (429), `bad_json` (400), `not_found` (404), `method` (405), `too_large` (413),
  `server_error` (500).
- **Token** só no cabeçalho `Authorization: Bearer <token>`, nunca na URL. Guarde no Keystore
  (EncryptedSharedPreferences ou equivalente).
- `401 unauthorized` com token salvo = o token não vale mais (perfil apagado, saiu dos outros
  aparelhos ou passou do teto de 10): leve a pessoa pra tela do código de recuperação.

## Objetos

**Perfil público** (todos veem):
```json
{ "id": "u…", "username": "groove_482", "name": "Groove 482", "avatar": "/avatars/u….jpg?v=3", "since": 1794000000000 }
```
`avatar` é caminho relativo ao endereço do relay (ou `null`); a imagem fica 1 h em cache.

**Perfil completo** (a própria pessoa e amigos): o público + `listenedMs` (número) + `top` (até 5 músicas).

**Música**:
```json
{ "videoId": "dQw4w9WgXcQ", "title": "…", "artist": "…", "album": "…", "durationMs": 213000,
  "cover": "https://i.ytimg.com/vi/…/hqdefault.jpg", "url": "https://music.youtube.com/watch?v=…" }
```
`videoId` (6–20 de `[A-Za-z0-9_-]`) e `title` (até 200) obrigatórios; `artist` sempre volta (pode ser
`""`); `album`, `durationMs` (< 6 h), `cover` e `url` opcionais (regras na novidade 3).

**Jam**:
```json
{ "id": "j…", "hostId": "u…", "members": [perfil público], "invited": [perfil público],
  "state": { "queue": [música], "index": 0, "positionMs": 0, "playing": false,
             "updatedAt": 1794…, "rev": 7, "by": "u…" },
  "serverTime": 1794… }
```

## Conta e perfil

| Método e rota | Corpo | Resposta | Erros |
|---|---|---|---|
| `POST /v1/accounts` | `{}` | `201 {token, recoveryCode, me}` | `429 slow_down` |
| `POST /v1/accounts/recover` | `{code}` | `{token, me}` | `404 unknown_code`, `429` |
| `GET /v1/me` | | `{me}` | |
| `PATCH /v1/me` | `{name}` | `{me}` | `invalid_name`, `400 username_fixed` (o `@nome` não muda) |
| `PUT /v1/me/avatar` | JPEG cru | `{me}` | `400 not_jpeg`, `413 too_large` |
| `DELETE /v1/me/avatar` | | `{me}` | |
| `PUT /v1/me/stats` | `{listenedMs, top: [música]}` | `{me}` | `400 invalid_stats` |
| `POST /v1/me/recovery` | | `{recoveryCode}` (o antigo deixa de valer) | |
| `DELETE /v1/me/sessions` | | `{revoked}` (só este aparelho continua) | |
| `DELETE /v1/me` | | `{ok: true}` | |

A conta nasce sozinha na primeira abertura: mostre o `recoveryCode` (formato `7K2Q-M9XD-4TFA-H3RB`) pra
pessoa guardar; é o único jeito de voltar ao perfil num celular novo. `@nome`: 3–20 caracteres,
`a-z 0-9 _ .`, começa com letra, sem `..` nem `.` no fim.

## Lista global e amizades

| Método e rota | Corpo | Resposta | Erros |
|---|---|---|---|
| `GET /v1/users?q=&offset=&limit=` | | `{users, next}` (`limit` até 50; `next` = offset da próxima página ou `null`) | |
| `GET /v1/users/:id` | | `{user}` | `404 unknown_user` |
| `GET /v1/friends` | | `{friends, incoming, outgoing}` | |
| `POST /v1/friends/requests` | `{userId}` ou `{username}` | `{status: "pending", requestId}` ou `{status: "friends", user}` | `404 unknown_user`, `400 self`, `429 too_many_requests` (100 em aberto), `429 slow_down` |
| `POST /v1/friends/requests/:id/accept` | | `{status: "friends", user}` | `404 unknown_request` |
| `POST /v1/friends/requests/:id/decline` | | `{status: "none"}` (recusar recebido ou cancelar enviado) | `404 unknown_request` |
| `DELETE /v1/friends/:id` | | `{status: "none"}` | |

Cada item de `users` é o perfil (completo se amigo) + `relation`: `none`, `friends`, `incoming` ou
`outgoing` (os dois últimos com `requestId`); em `/v1/users/:id` também `self`. `friends` traz
`friendsSince`; `incoming`/`outgoing` são `{requestId, at, user}`. Os dois pedindo um ao outro já
viram amigos.

## Músicas compartilhadas

| Método e rota | Corpo | Resposta | Erros |
|---|---|---|---|
| `POST /v1/shares` | `{to, track}` | `201 {share: {id, at}}` | `404 unknown_user`, `403 not_friends`, `400 invalid_track`, `429 inbox_full`, `429 slow_down` |
| `GET /v1/mailbox` | | `{deliveries, from, requests}` | |
| `POST /v1/mailbox/ack` | `{ids: [número]}` | `{removed}` | |

Só entre amigos. Quem está com o app aberto recebe na hora (`delivery` no WebSocket); quem não está
busca na caixa. Entrega = `{id, kind: "share", from, at, track}`; `from` da caixa = perfis de quem
mandou; `requests` = pedidos de amizade recebidos. **Confirme (`ack`) o que recebeu**: o servidor
apaga na hora; o que não for confirmado some em 14 dias. O histórico fica só no celular.

## Jam

| Método e rota | Corpo | Resposta | Erros |
|---|---|---|---|
| `GET /v1/jams/current` | | `{jam \| null, invites: [{jam, from}], serverTime}` | |
| `POST /v1/jams` | `{invite?: [id], state?: {queue, index, positionMs, playing}}` | `201 {jam}` | `400 jam_full`, `403 not_friends` (+`userId`), `400 invalid_state` |
| `POST /v1/jams/:id/invite` | `{userIds: [id]}` | `{jam}` | `403 not_member`, `403 not_friends`, `400 jam_full` |
| `POST /v1/jams/:id/join` | | `{jam}` | `403 not_invited`, `404 unknown_jam` |
| `POST /v1/jams/:id/decline` | | `{ok: true}` | |
| `POST /v1/jams/:id/leave` | | `{ok: true}` | |
| `DELETE /v1/jams/:id` | | `{ok: true}` (só quem criou) | `403 not_host` |
| `POST /v1/jams/:id/state` | `{baseRev, queue?, index?, positionMs?, playing?}` | `{accepted, jam}` | `403 not_member`, `400 invalid`, `429 slow_down` |

- Até 30 pessoas (membros + convidados). Só se convida amigo; quem está na jam convida os próprios amigos.
- Uma jam por pessoa: criar ou entrar noutra sai da anterior. Quem criou sai ou encerra → acaba pra todos.
- Ninguém conectado por 15 min → a jam acaba sozinha.
- **Mudança** (`state`): mande o estado inteiro de cada vez (`positionMs` ausente vira 0, `playing`
  ausente vira `false`) e `baseRev` = `rev` que você estava vendo. Dois ao mesmo tempo: vale o
  primeiro; o outro recebe `accepted: false` (ou `{t:"jam", rejected:"stale"}` no WebSocket) com a
  jam atual. Fila até 200 músicas; uma música inválida recusa a fila inteira. `positionMs` é cortado
  em 6 h.
- Relógio: use `serverTime`/`updatedAt` (e o `pong`) pra calcular a posição atual.
- Convites além de 20 por hora da mesma pessoa pra mesma pessoa são ignorados em silêncio.

## WebSocket `wss://freesong.simplyonegoal.com/v1/ws`

Uma conexão por app aberto, com `Authorization: Bearer <token>` no pedido de upgrade. Recusas do
upgrade: `401` (token), `403` (sem TLS), `429` (limite), `503` (servidor cheio). O servidor manda
ping a cada 25 s; quem não responde cai (bibliotecas de WebSocket respondem sozinhas).

Do servidor pro app:

| `t` | Campos | Quando |
|---|---|---|
| `hello` | `serverTime, pending` | ao conectar (`pending` = entregas na caixa) |
| `pong` | `c, s` | resposta ao `ping` (`s` = hora do servidor) |
| `delivery` | `delivery, from` | um amigo mandou música |
| `friend.request` | `request: {requestId, at, user}` | pedido de amizade recebido |
| `friend.accepted` | `user` | aceitaram seu pedido |
| `friend.removed` | `userId` | desfizeram a amizade (ou apagaram o perfil) |
| `jam.invite` | `jam, from` | convite pra jam |
| `jam` | `jam, rejected?` | a jam mudou (ou sua mudança foi recusada: `stale`/`invalid`) |
| `jam.ended` | `jamId` | a jam acabou (ou você não está nela) |

Do app pro servidor (até 64 KB por mensagem; toda mensagem conta no limite de 600/min):

| `t` | Campos |
|---|---|
| `ping` | `c` (número ou texto até 64) |
| `ack` | `ids: [número]` (até 500) |
| `jam.state` | `jamId, baseRev, queue?, index?, positionMs?, playing?` |

### Códigos de fechamento

| Código | Motivo | O que o app faz |
|---|---|---|
| 1009 | mensagem acima de 64 KB | corrigir o app |
| 4001 | perfil apagado | não reconectar |
| 4002 | outra conexão da mesma pessoa tomou a vaga (máx. 5) | **não** reconectar sozinho (só ao voltar pro app) |
| 4003 | token revogado (saiu dos outros aparelhos / teto de aparelhos) | não reconectar; ir pra recuperação |
| outros | queda de rede, servidor reiniciando | reconectar com backoff |

## Limites

| O quê | Limite |
|---|---|
| Contas novas | 20/h por IP (IPv6 por /64) e 300/h no servidor todo |
| Recuperação | 20/h por IP |
| Token errado | 120/min por IP |
| Pedidos (HTTP + mensagens + conexões do WebSocket) | 600/min por pessoa |
| WebSockets abertos | 5 por pessoa, 2000 no servidor |
| Aparelhos (tokens) | 10 por perfil |
| Pedidos de amizade | 100 em aberto; 60 novos/h; 3/dia pro mesmo perfil |
| Músicas compartilhadas | 120/h por pessoa; 500 esperando na caixa de cada um |
| Jam | 30 pessoas; fila de 200; 120 mudanças/min por jam; 20 convites/h por par |
