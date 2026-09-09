#!/usr/bin/env bash
# Lanceur du serveur MCP stdio Hatari. stdout est réservé au protocole JSON-RPC :
# toute sortie Maven part sur stderr.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT/target/hatari-mcp.jar"
CORE="${HATARI_CORE:-$ROOT/../build/src/libretro-hatari.so}"
TOS="${HATARI_TOS:-$HOME/.hatari/tos.img}"
JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/25.0.4-tem}"

if [[ ! -f "$JAR" || "$ROOT/pom.xml" -nt "$JAR" || -n "$(find "$ROOT/src/main" -newer "$JAR" -type f 2>/dev/null | head -1)" ]]; then
  (cd "$ROOT" && JAVA_HOME="$JAVA_HOME" mvn -q -DskipTests package 1>&2)
fi

exec "$JAVA_HOME/bin/java" --enable-native-access=ALL-UNNAMED -jar "$JAR" \
  --core "$CORE" --tos "$TOS" "$@"
