#!/usr/bin/env bash
# Mantém o relay de pé e reinicia se cair (o Debian do Termux, no proot, não tem systemd).
#   scripts/run.sh            # PORT=8787 HOST=127.0.0.1 DATA_DIR=~/freesong-relay-data
cd "$(dirname "$0")/.." || exit 1
export PORT=${PORT:-8787} HOST=${HOST:-127.0.0.1} DATA_DIR=${DATA_DIR:-$HOME/freesong-relay-data}
# nvm, quando o Node veio por ele (shell não interativo do Termux:Boot / tmux).
[ -s "$HOME/.nvm/nvm.sh" ] && . "$HOME/.nvm/nvm.sh" >/dev/null
mkdir -p "$DATA_DIR" && chmod 700 "$DATA_DIR"
while true; do
  node --no-warnings=ExperimentalWarning src/server.js >> "$DATA_DIR/relay.log" 2>&1
  echo "[run] relay saiu (código $?) em $(date -Is); reinicia em 3 s" >> "$DATA_DIR/relay.log"
  sleep 3
done
