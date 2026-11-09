#!/usr/bin/env bash
# Mantém o relay de pé e reinicia se cair (o Debian do Termux, no proot, não tem systemd).
#   scripts/run.sh            # PORT=8787 HOST=127.0.0.1 DATA_DIR=~/freesong-relay-data
cd "$(dirname "$0")/.." || exit 1
export PORT=${PORT:-8787} HOST=${HOST:-127.0.0.1} DATA_DIR=${DATA_DIR:-$HOME/freesong-relay-data}
# nvm, quando o Node veio por ele (shell não interativo do Termux:Boot / tmux).
[ -s "$HOME/.nvm/nvm.sh" ] && . "$HOME/.nvm/nvm.sh" >/dev/null
mkdir -p "$DATA_DIR" && chmod 700 "$DATA_DIR"
umask 077
LOG="$DATA_DIR/relay.log"
# Pausa reserva (read com timeout num FIFO), caso o sleep falhe.
WAIT_FIFO="$DATA_DIR/.run-wait"
[ -p "$WAIT_FIFO" ] || { rm -f "$WAIT_FIFO"; mkfifo "$WAIT_FIFO"; }
while true; do
  # Log com teto: passou de 5 MB, o atual vira relay.log.1 (só um antigo fica).
  if [ -f "$LOG" ] && [ "$(wc -c < "$LOG")" -gt 5242880 ]; then mv -f "$LOG" "$LOG.1"; fi
  # Caixa de areia do Node: mesmo que um dia apareça uma falha no relay, o processo só lê o próprio
  # código e os dados, só escreve nos dados, não abre shell/programa nem worker e não roda código
  # montado a partir de texto (eval). Os outros sites, o túnel e as chaves deste aparelho ficam fora.
  node --permission --allow-fs-read="$PWD" --allow-fs-read="$DATA_DIR" --allow-fs-write="$DATA_DIR" \
    --disallow-code-generation-from-strings --disable-proto=delete \
    --no-warnings=ExperimentalWarning src/server.js >> "$LOG" 2>&1
  echo "[run] relay saiu (código $?) em $(date -Is); reinicia em 3 s" >> "$LOG"
  # No Termux, logo depois do boot, o sleep às vezes falha na hora; sem o "|| true" de nada
  # adiantaria, e um relay que cai de cara viraria um laço girando sem pausa.
  sleep 3 >/dev/null 2>&1 || read -r -t 3 _ <> "$WAIT_FIFO" || true
done
