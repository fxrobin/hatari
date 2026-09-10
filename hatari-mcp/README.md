# hatari-mcp

Serveur MCP (Model Context Protocol) stdio en Java qui pilote l'émulateur
[Hatari](../readme.txt) (Atari ST/STE/TT/Falcon, CPU 68000) : exécution
trame par trame ou pas-à-pas, lecture/écriture mémoire et registres,
désassemblage, breakpoints/watchpoints, clavier, disquettes, capture
d'écran. Le cœur Hatari est chargé en mémoire via un binding FFM (Java
Foreign Function & Memory API) sur le cœur libretro `libretro-hatari.so`,
sans passer par un processus séparé.

## Prérequis

- **Cœur Hatari compilé avec le support libretro**, dans le dépôt parent :

  ```sh
  mkdir -p build && cd build
  cmake -DENABLE_LIBRETRO=1 -DLIBRETRO_INCLUDE_DIR=$PWD/../hatari-mcp/native/include ..
  cmake --build . -j$(nproc)
  # produit : build/src/libretro-hatari.so
  ```

- **JDK 25** (API Foreign Function & Memory stable) :

  ```sh
  export JAVA_HOME=~/.sdkman/candidates/java/25.0.4-tem
  export PATH="$JAVA_HOME/bin:$PATH"
  ```

- **Une image TOS** dans `~/.hatari/tos.img` (EmuTOS convient et ne
  nécessite aucune licence ; c'est celle utilisée par les tests de ce
  module).

## Lancement

```sh
hatari-mcp/scripts/hatari-mcp.sh
```

Le script recompile automatiquement le jar (`mvn -q -DskipTests package`)
si les sources ou le `pom.xml` sont plus récents que
`target/hatari-mcp.jar`. La sortie Maven part sur stderr : stdout est
réservé au protocole JSON-RPC du transport stdio MCP.

Variables d'environnement reconnues par le lanceur :

| Variable      | Défaut                                  | Rôle                          |
|---------------|------------------------------------------|--------------------------------|
| `HATARI_CORE` | `<repo>/build/src/libretro-hatari.so`     | Chemin du cœur libretro        |
| `HATARI_TOS`  | `~/.hatari/tos.img`                       | Image TOS à charger            |
| `HATARI_JDK`  | *(non défini)*                            | JDK 25 à utiliser, prioritaire sur tout le reste |
| `JAVA_HOME`   | *(hérité de l'environnement)*             | JDK de repli, utilisé en dernier |

Le JDK est choisi dans cet ordre :

1. `$HATARI_JDK` s'il est défini ;
2. `~/.sdkman/candidates/java/25.0.4-tem` si son `bin/java` existe ;
3. le `$JAVA_HOME` hérité de l'environnement appelant.

`$JAVA_HOME` vient en dernier parce qu'il pointe fréquemment un lien sdkman
`current` sur une version plus ancienne. Le lanceur interroge ensuite
`java.specification.version` du JDK retenu et refuse de démarrer (code de
retour 1, message sur stderr) si elle est inférieure à 25 : l'API Foreign
Function & Memory utilisée par le binding n'est stable qu'à partir de Java 25,
et un JDK plus ancien échouerait sur un `UnsupportedClassVersionError`.

Si le jar doit être reconstruit, le lanceur vérifie la présence de `mvn` dans
le `PATH` et s'arrête avec un message explicite s'il est absent.

Le serveur s'arrête à la fermeture de stdin (comportement standard d'un
transport MCP stdio), pas sur un signal.

Déclaration MCP à la racine du dépôt Hatari : [`.mcp.json`](../.mcp.json)
(clé `hatari`, commande `hatari-mcp/scripts/hatari-mcp.sh`).

## Outils exposés

Chaque outil est un `tools/call` JSON-RPC ; les arguments ci-dessous sont
donnés à titre d'exemple (voir le code source dans `src/main/java/fr/hatari/mcp/tools/`
pour le schéma JSON complet de chacun).

### Machine (`MachineTools`)

- `ping` — `{}`
- `machine_state` — `{}`
- `reset` — `{"cold": true}`
- `run_frames` — `{"n": 300}`
- `run_until_pc` — `{"pc": "E00D98", "max_frames": 1000}`
- `run_to_breakpoint` — `{"max_frames": 1000}`
- `step` — `{"n": 1}`

### Mémoire et registres (`MemoryTools`)

- `read_memory` — `{"addr": "FF8240", "len": 64}`
- `write_memory` — `{"addr": "600", "bytes": ["DE", "AD"]}`
- `read_registers` — `{}`
- `set_register` — `{"name": "d0", "value": "1234"}`
- `disassemble` — `{"addr": "E00000", "count": 16}`

### Debugger (`DebugTools`)

- `set_breakpoint` — `{"pc": "E00D98"}`
- `clear_breakpoint` — `{"id": 1}` (ou `{"all": true}`)
- `set_watchpoint` — `{"addr": "FF8240", "len": 2, "label": "palette 0"}`
- `clear_watchpoint` — `{"id": 1}` (ou `{"all": true}`)
- `list_breakpoints` — `{}`
- `load_symbols` — `{"path": "~/.hatari/etos1024k.sym"}`
- `debug_command` — `{"cmd": "r"}` (passerelle brute vers le debugger
  Hatari, voir `doc/debugger.html`)

### Vidéo (`VideoTools`)

- `screenshot` — `{"path": "/tmp/hatari.png"}` (`path` optionnel)
- `arm_video_capture` — `{"path": "/tmp/hatari.mp4", "every": 2, "scale": 2, "audio": true}` :
  dès lors, tout outil qui fait avancer la machine filme une image toutes les
  `every` trames (défaut 2 → 25 img/s) et enregistre le son du cœur (YM2149,
  DMA STE) ; MP4 H.264 + AAC encodé par `ffmpeg` (requis sur le PATH). La vidéo
  est recalée sur le temps réel émulé mesuré en cycles CPU (`-itsscale`), donc
  synchrone avec le son même quand la machine tourne à 60 ou 71 Hz. `every > 2`
  (accéléré) coupe le son. Une seule capture à la fois.
- `stop_video_capture` — `{}` : multiplexe et finalise le MP4 (idempotent),
  retourne `path`, `frames`, `seconds`, `audio_seconds`, `bytes`,
  `video_time_scale`.
- `video_capture_status` — `{}`

### Clavier et joystick (`InputTools`)

- `press_key` — `{"key": "RETURN", "hold_frames": 2}`
- `type_keys` — `{"text": "dir\n", "hold_frames": 2, "gap_frames": 1}`
- `set_joystick` — `{"port": 1, "up": true, "fire": true}` : état maintenu
  jusqu'au prochain appel ; `{}` relâche tout (port 1 = port jeu, défaut)
- `press_joystick` — `{"fire": true, "frames": 4}` : impulsion puis relâche.
  Certains jeux (Xenon 2) exigent un front sur fire : préférer les impulsions
  à un fire maintenu.

### Disquettes (`DiskTools`)

- `mount_disk` — `{"path": "~/disks/game.st", "drive": 0}`
- `eject_disk` — `{"drive": 0}`
- `boot_disk` — `{"path": "~/disks/game.st", "frames": 300}`

## Tests

```sh
export JAVA_HOME=~/.sdkman/candidates/java/25.0.4-tem
cd hatari-mcp
mvn test                       # unitaires (FakeMachine) + tests *IT si le cœur/TOS sont trouvés
mvn test -Dhatari.core=/chemin/vers/libretro-hatari.so -Dhatari.tos=/chemin/vers/tos.img
mvn verify                     # même chose via le cycle de vie complet
```

Les tests `*IT` (`CoreIT`, etc.) chargent le vrai cœur libretro et la vraie
image TOS ; ils sont ignorés silencieusement si `hatari.core` (par défaut
`../build/src/libretro-hatari.so`) ou `hatari.tos` (par défaut
`~/.hatari/tos.img`) n'existent pas.

## Smoke test bout en bout

```sh
hatari-mcp/scripts/smoke.sh
```

Lance le serveur, effectue le handshake MCP, appelle `ping`, `run_frames`
(300 trames) et `screenshot`, puis vérifie les réponses JSON-RPC sans passer
par un client MCP complet. Affiche `smoke OK` et sort en erreur (avec la
sortie brute du serveur) en cas d'échec.

## Licence

GPL v2+, comme le reste du dépôt Hatari (voir [`readme.txt`](../readme.txt)
et [`gpl.txt`](../gpl.txt) à la racine).
