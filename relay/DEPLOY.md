# FreeSong relay — instalar no servidor

(Contrato do app com o servidor: [API.md](API.md).)

Instruções pra quem vai configurar o servidor do FreeSong: um celular com **Termux + Debian**
(proot-distro), atrás de um **Cloudflare Tunnel**. Siga na ordem e no fim mande a saída do passo 7.

## Dados

| | |
|---|---|
| Endereço público | `https://freesong.simplyonegoal.com` |
| Código | `https://github.com/Villamediana/FreeSong`, pasta `relay/` (branch `main`) |
| Porta local | `8787`, só em `127.0.0.1` (a única entrada é o túnel) |
| Node | **22.13 ou mais novo** (usa o SQLite embutido do Node, `node:sqlite`) |
| Dependências | só `ws` (JavaScript puro, nada pra compilar) |
| Dados | `~/freesong-relay-data/` (`freesong.db` + `avatars/`): é isso que se guarda em backup |
| Segredos | nenhum. Não há chave, senha nem variável obrigatória |

O relay guarda **só o básico**: perfil (id, @nome, nome, foto, horas escutadas, 5 músicas mais
ouvidas, data de entrada), hashes dos tokens e dos códigos de recuperação, amizades e pedidos de
amizade. Música compartilhada não fica no servidor: ele segura a entrega só até o celular do amigo
confirmar que recebeu (ou 14 dias) e apaga. Jam vive só na memória. Não guarda IP, último acesso
nem versão do app.

## 1. Node 22.13+

Dentro do Debian:

```bash
node -v   # serve se for v22.13 ou mais novo
```

Se não tiver ou for mais velho, instale pelo nvm (funciona no arm64 do proot):

```bash
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.3/install.sh | bash
. ~/.nvm/nvm.sh
nvm install 22
node -v
```

## 2. Código

```bash
sudo apt-get install -y git   # se faltar (ou apt-get sem sudo, se for root no proot)
git clone --depth 1 https://github.com/Villamediana/FreeSong.git ~/freesong
cd ~/freesong/relay
npm ci --omit=dev
```

## 3. Rodar e testar local

```bash
cd ~/freesong/relay
npm test                         # opcional: 38 testes, ~5 s
scripts/run.sh &                 # sobe em 127.0.0.1:8787, dados em ~/freesong-relay-data
curl -s http://127.0.0.1:8787/v1/health
# {"ok":true,"version":"1.0.0","time":...}
```

`scripts/run.sh` reinicia o relay se ele cair e escreve o log em `~/freesong-relay-data/relay.log`
(só a linha de início e erros; passou de 5 MB, vira `relay.log.1`). Variáveis opcionais: `PORT`
(se a 8787 já estiver em uso no servidor, escolha outra e aponte o túnel pra ela), `HOST`,
`DATA_DIR`, `NEW_ACCOUNTS_PER_HOUR` (teto de contas novas no servidor todo, padrão 300).
O `run.sh` roda o Node na caixa de areia (`--permission`): ver "Segurança" no fim.

## 4. Deixar rodando sempre

O Debian do Termux não tem systemd. Se esse servidor já tem um jeito de manter serviços de pé
(o mesmo usado pro `cloudflared`, por exemplo), use o mesmo com o comando
`~/freesong/relay/scripts/run.sh`. Se não tem, um jeito que funciona:

```bash
# dentro do Debian
apt-get install -y tmux
tmux new-session -d -s freesong ~/freesong/relay/scripts/run.sh
```

E pra subir quando o celular ligar, com o app **Termux:Boot** instalado, crie no Termux (fora do
Debian) o arquivo `~/.termux/boot/freesong-relay` com permissão de execução:

```sh
#!/data/data/com.termux/files/usr/bin/sh
termux-wake-lock
proot-distro login debian -- bash -lc 'tmux new-session -d -s freesong ~/freesong/relay/scripts/run.sh'
```

Troque `debian` pelo nome da distro instalada, se for outro. O `termux-wake-lock` evita que o
Android adormeça o Termux; confira também se o Termux está sem otimização de bateria.

## 5. Cloudflare Tunnel

Adicionar no túnel que já existe neste servidor o hostname **`freesong.simplyonegoal.com` →
`http://localhost:8787`**. O proot compartilha a rede com o Termux, então `localhost:8787` vale
tanto pro `cloudflared` rodando no Termux quanto dentro do Debian.

**Túnel gerenciado pelo painel** (o `cloudflared` roda com `--token`):
Zero Trust → Networks → Tunnels → (o túnel) → Public Hostname → Add a public hostname:
Subdomain `freesong`, Domain `simplyonegoal.com`, Type `HTTP`, URL `localhost:8787`. Salvar.

**Túnel com `config.yml`**: acrescentar antes da regra final (`- service: http_status:404`):

```yaml
  - hostname: freesong.simplyonegoal.com
    service: http://localhost:8787
```

e criar o DNS e reiniciar o `cloudflared`:

```bash
cloudflared tunnel route dns <nome-ou-id-do-tunel> freesong.simplyonegoal.com
```

Cuidados nesse hostname (o app não tem tela de login nem resolve desafio):
- **Sem Cloudflare Access** (nenhuma aplicação do Access cobrindo `freesong.simplyonegoal.com`
  nem `*.simplyonegoal.com`).
- **Sem desafio de bot** (Bot Fight Mode / Under Attack / regra de WAF com challenge). Se a zona
  tiver isso ligado, crie uma regra que pule esse hostname.
- **WebSockets ligado** (Network → WebSockets; já vem ligado).
- Recomendado: **Always Use HTTPS** (SSL/TLS → Edge Certificates). O relay já recusa com
  `403 https_only` o que chega por `http://`, mas com isso nem o primeiro pedido sai aberto.

## 6. Atualizar depois

```bash
cd ~/freesong && git pull && cd relay && npm ci --omit=dev
tmux kill-session -t freesong; tmux new-session -d -s freesong ~/freesong/relay/scripts/run.sh
```

## 7. Conferir de fora (mande esta saída)

```bash
curl -s https://freesong.simplyonegoal.com/v1/health
cd ~/freesong/relay && npm run smoke -- https://freesong.simplyonegoal.com
```

O `smoke` faz o que dois celulares fariam pelo túnel (perfis, amizade, música compartilhada,
jam pelo WebSocket) e apaga o que criou. Tem que terminar com `Tudo certo.`

## Backup

Copiar `~/freesong-relay-data/` (com o relay parado) ou, com ele rodando:

```bash
sqlite3 ~/freesong-relay-data/freesong.db ".backup '$HOME/freesong-backup.db'"
```

## Segurança (já vem assim)

O servidor:
- Escuta só em `127.0.0.1`; HTTPS fica com a Cloudflare. Pedido que chegou na Cloudflare por
  `http://` é recusado (`403 https_only`), pro token nunca andar aberto.
- Roda na caixa de areia do Node (`run.sh`): só lê o próprio código e `DATA_DIR`, só escreve em
  `DATA_DIR`, não abre shell, programa nem worker, e não executa código montado de texto
  (`--disallow-code-generation-from-strings`, `--disable-proto=delete`). Mesmo com uma falha no
  relay, o resto do servidor (outros sites, túnel, chaves) fica fora do alcance.
- Nada que chega de fora derruba o processo: URL torta, frame de WebSocket inválido ou grande
  demais, erro de banco num instante — tudo vira resposta de erro, não queda.
- Banco e fotos com permissão só do dono (`umask 077`, pasta `700`); token e código de recuperação
  só em hash (SHA-256); token só no cabeçalho `Authorization`, nunca na URL.
- Cabeçalhos em toda resposta: `nosniff`, `Content-Security-Policy: default-src 'none'`,
  `Referrer-Policy: no-referrer`, HSTS.

Limites (por pessoa, salvo indicado):
- Contas novas: 20/h por IP (IPv6 conta pelo /64) e 300/h no servidor todo. Recuperação: 20/h por
  IP. Token errado: 120/min por IP.
- 600 pedidos/min (HTTP + mensagens + conexões do WebSocket); até 5 WebSockets abertos (o mais
  antigo cai) e 2000 no servidor; celular que não lê o que recebe cai em vez de acumular memória.
- Até 10 aparelhos por perfil (o mais antigo sai); `DELETE /v1/me/sessions` tira todos os outros.
- Amizade: 100 pedidos em aberto, 60 novos/h, o mesmo pedido no máximo 3×/dia; pedido repetido
  não toca de novo. Músicas: 120/h, 500 esperando. Jam: 120 mudanças/min por jam, 20 convites/h de
  uma pessoa pra outra, convite repetido não toca de novo.

O que chega no celular dos outros:
- Só o que o servidor montou ou limpou. Nomes e títulos sem caracteres invisíveis, de controle ou
  de inversão de texto (U+202E) e sem acento empilhado; `@nome` não imita a equipe (`freesong`,
  `admin`, `suporte`...).
- Capa só de `i.ytimg.com`, `*.googleusercontent.com`, `*.ggpht.com`; link só de `youtube.com`,
  `music.youtube.com`, `youtu.be` (capa em servidor qualquer entregaria o IP de quem abrisse).
- Foto: JPEG conferido segmento por segmento, até 2048 px de lado; sai sem EXIF (GPS, câmera,
  miniatura da foto original), XMP, IPTC, comentários e sem nada colado depois do fim da imagem.
- Música compartilhada só entre amigos; jam só com amigo (ou convidado por um amigo que está nela).
