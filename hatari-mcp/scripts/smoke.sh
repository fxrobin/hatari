#!/usr/bin/env bash
# Handshake MCP + ping + run_frames + screenshot, sans client MCP.
#
# stdin est un named pipe qu'on garde ouvert : on écrit les requêtes, puis on
# attend que les 3 réponses attendues (ping, run_frames, screenshot) soient
# apparues dans la sortie avant de fermer stdin (fin de flux = arrêt côté
# serveur, cf. EofSignalingInputStream). Une simple pause fixe serait sujette
# aux aléas de charge de la machine ; ici on attend le résultat réel, borné
# par un timeout de garde.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FIFO="$(mktemp -u)"
OUT="$(mktemp)"
ERR="$(mktemp)"
mkfifo "$FIFO"
trap 'rm -f "$FIFO" "$OUT" "$ERR"' EXIT

"$ROOT/scripts/hatari-mcp.sh" < "$FIFO" > "$OUT" 2> "$ERR" &
SERVER_PID=$!

exec 9> "$FIFO"
{
  echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"smoke","version":"0"}}}'
  echo '{"jsonrpc":"2.0","method":"notifications/initialized"}'
  echo '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"ping","arguments":{}}}'
  echo '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"run_frames","arguments":{"n":300}}}'
  echo '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"screenshot","arguments":{"path":"/tmp/hatari-mcp-smoke.png"}}}'
} >&9

READY=0
for _ in $(seq 1 60); do
  if rg -q '"id":2' "$OUT" 2>/dev/null && rg -q '"frames_done":300' "$OUT" 2>/dev/null \
      && rg -q 'hatari-mcp-smoke.png' "$OUT" 2>/dev/null; then
    READY=1
    break
  fi
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    break
  fi
  sleep 0.5
done

# Fin de stdin : signale l'arrêt au serveur (cf. EofSignalingInputStream).
exec 9>&-

# Laisse le temps à l'arrêt gracieux (drain 250ms + hooks) avant de couper.
for _ in $(seq 1 20); do
  kill -0 "$SERVER_PID" 2>/dev/null || break
  sleep 0.5
done
kill -0 "$SERVER_PID" 2>/dev/null && kill "$SERVER_PID" 2>/dev/null || true
wait "$SERVER_PID" 2>/dev/null || true

if [[ "$READY" == "1" ]] && rg -q '"id":2' "$OUT" && rg -q '"frames_done":300' "$OUT" \
    && rg -q 'hatari-mcp-smoke.png' "$OUT"; then
  echo "smoke OK ($(wc -l < "$OUT") réponses, /tmp/hatari-mcp-smoke.png)"
else
  echo "smoke KO"
  echo "--- stdout ($OUT) ---"
  cat "$OUT"
  echo "--- stderr ($ERR) ---"
  cat "$ERR"
  exit 1
fi
