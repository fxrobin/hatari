#!/usr/bin/env bash
# Lanceur du serveur MCP stdio Hatari. stdout est réservé au protocole JSON-RPC :
# toute sortie Maven part sur stderr.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT/target/hatari-mcp.jar"
CORE="${HATARI_CORE:-$ROOT/../build/src/libretro-hatari.so}"
TOS="${HATARI_TOS:-$HOME/.hatari/tos.img}"

# JDK : l'API Foreign Function & Memory n'est stable qu'à partir de Java 25.
# Ordre de sélection, du plus explicite au plus implicite :
#   1. $HATARI_JDK (choix explicite de l'utilisateur pour ce serveur) ;
#   2. le JDK 25 installé par sdkman, s'il existe ;
#   3. le $JAVA_HOME hérité de l'environnement appelant.
# Le $JAVA_HOME hérité vient souvent d'un « sdk use » ou d'un lien « current »
# pointant une version plus ancienne : il ne peut donc pas être la première
# source, et la version retenue est vérifiée avant tout lancement.
SDKMAN_JDK25="$HOME/.sdkman/candidates/java/25.0.4-tem"
if [[ -n "${HATARI_JDK:-}" ]]; then
  JDK="$HATARI_JDK"
elif [[ -x "$SDKMAN_JDK25/bin/java" ]]; then
  JDK="$SDKMAN_JDK25"
else
  JDK="${JAVA_HOME:-}"
fi

if [[ -z "$JDK" || ! -x "$JDK/bin/java" ]]; then
  echo "hatari-mcp: aucun JDK utilisable (essayés : \$HATARI_JDK, $SDKMAN_JDK25, \$JAVA_HOME)." >&2
  echo "hatari-mcp: installez un JDK 25 et exportez HATARI_JDK=/chemin/vers/jdk-25." >&2
  exit 1
fi

JAVA_SPEC="$("$JDK/bin/java" -XshowSettings:properties -version 2>&1 \
  | sed -n 's/^ *java\.specification\.version = \([0-9][0-9]*\).*$/\1/p' | head -1)"
if [[ -z "$JAVA_SPEC" || "$JAVA_SPEC" -lt 25 ]]; then
  echo "hatari-mcp: JDK 25 requis (API Foreign Function & Memory), trouvé « ${JAVA_SPEC:-inconnu} » dans $JDK." >&2
  echo "hatari-mcp: exportez HATARI_JDK=/chemin/vers/jdk-25 (ex. $SDKMAN_JDK25)." >&2
  exit 1
fi
export JAVA_HOME="$JDK"

if [[ ! -f "$JAR" || "$ROOT/pom.xml" -nt "$JAR" || -n "$(find "$ROOT/src/main" -newer "$JAR" -type f 2>/dev/null | head -1)" ]]; then
  if ! command -v mvn >/dev/null 2>&1; then
    echo "hatari-mcp: $JAR est absent ou périmé et « mvn » est introuvable dans le PATH." >&2
    echo "hatari-mcp: installez Maven, ou construisez le jar à la main (mvn -DskipTests package dans $ROOT)." >&2
    exit 1
  fi
  (cd "$ROOT" && mvn -q -DskipTests package 1>&2)
fi

exec "$JAVA_HOME/bin/java" --enable-native-access=ALL-UNNAMED -jar "$JAR" \
  --core "$CORE" --tos "$TOS" "$@"
