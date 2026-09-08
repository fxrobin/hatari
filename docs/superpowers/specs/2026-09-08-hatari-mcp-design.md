# Hatari MCP — conception

Date : 2026-09-08
Statut : validé (design approuvé en discussion)

## 1. Objectif

Piloter Hatari depuis Claude via un serveur MCP stdio, avec le même style d'outils que
`toje-mcp` (émulateur Thomson TO8 en Java) pour la mise au point de programmes Atari ST :
exécuter N trames, poser des breakpoints et watchpoints, lire et écrire la mémoire, lire les
registres, désassembler, capturer l'écran, taper au clavier, monter des disquettes.

Décision de fond : on garde Hatari (fidélité cycle-exacte, cœur WinUAE), on ne réécrit
pas un émulateur ST en Java. Le serveur MCP est en Java et charge le cœur libretro de Hatari
par FFM (Foreign Function & Memory API).

## 2. Périmètre de l'itération 1

Inclus :

- Exécution : `ping`, `machine_state`, `reset`, `run_frames`, `run_until_pc`,
  `run_to_breakpoint`, `step`.
- Mémoire et CPU : `read_memory`, `write_memory`, `read_registers`, `set_register`,
  `disassemble`.
- Debug : `set_breakpoint`, `clear_breakpoint`, `set_watchpoint`, `clear_watchpoint`,
  `load_symbols`, `debug_command` (passerelle brute vers le debugger Hatari).
- Vidéo : `screenshot` (PNG).
- Entrées : `press_key`, `type_keys`.
- Disques : `mount_disk`, `boot_disk`.

Exclus (itération 2 ou plus) : profileur, GIF et capture vidéo, anneau de trace, joystick,
souris, snapshots (`retro_serialize`), fenêtre live. Headless uniquement.

## 3. Architecture

```
Claude ──stdio/JSON-RPC──▶ hatari-mcp (Java 25, MCP SDK 2.0.0)
                              │  FFM (downcalls / upcalls)
                              ▼
                     libretro-hatari.so  (cœur libretro Hatari + debug_api.c)
```

Deux composants, deux dépôts logiques dans le même repo :

| Composant | Emplacement | Langage | Rôle |
|---|---|---|---|
| Couche C | `src/retro/debug_api.{c,h}` sur la branche `mcp` | C99 | Exports structurés + passerelle texte vers le debugger |
| Serveur MCP | `hatari-mcp/` (Maven) | Java 25 | Outils MCP, bindings FFM, formatage JSON |

Le cœur libretro est déjà en modèle *pull* : `retro_run()` exécute une trame puis rend la
main (`Timing_WaitOnVbl` pose `SPCFLAG_BRK`). Tout appel au cœur se fait depuis un seul
thread Java (le thread du transport stdio), comme dans toje-mcp.

## 4. Couche C (`src/retro/debug_api.c`)

### 4.1 Approche : hybride

- **Exports structurés** pour les chemins chauds et sans ambiguïté : exécution, mémoire,
  registres, framebuffer, clavier, disquette, reset, compteurs.
- **Passerelle texte** `hatari_dbg_command` pour tout le reste (breakpoints, watchpoints,
  symboles, historique, désassemblage avancé, profil). Elle réutilise le parseur de commandes
  du debugger Hatari (`DebugUI_ParseLine`), mature et documenté dans `doc/debugger.html`.

### 4.2 Modification du code existant : un hook dans `DebugUI()`

Aujourd'hui, un breakpoint ou une fin de pas-à-pas appelle `DebugUI(reason)` qui ouvre le
REPL readline sur stdin. On ajoute dans `src/debug/debugui.c` :

```c
typedef bool (*DebugUI_StopHook)(debug_reason_t reason);
void DebugUI_SetStopHook(DebugUI_StopHook hook);
```

En tête de `DebugUI()` : si un hook est posé et renvoie `true`, la fonction retourne sans
ouvrir le REPL. Le hook de `debug_api.c` mémorise la raison, pose `SPCFLAG_BRK` et
`quit_program = UAE_QUIT`, ce qui fait sortir `m68k_run()` après l'instruction courante,
donc fait revenir `retro_run()`. Changement minimal, sans effet quand le hook est absent.

### 4.3 Exports

Tous en `RETRO_API`, préfixe `hatari_`, types C simples (pas de struct exposée) pour garder
les bindings FFM triviaux. Les chaînes de sortie sont copiées dans un buffer fourni par
l'appelant, tronquées si trop petit, terminées par `\0`.

| Export | Rôle | Implémentation |
|---|---|---|
| `void hatari_init_args(int argc, char **argv)` | Remplace `retro_init` ; passe `--tos`, `--machine`, `--memsize`… | `Main_Init(argc, argv)` |
| `int hatari_run(int max_frames, int *stop_reason, int *frames_done)` | Boucle `retro_run` jusqu'à arrêt debugger ou trames épuisées | hook + boucle |
| `int hatari_dbg_command(const char *cmd, char *out, size_t outlen)` | Exécute une commande debugger, capture la sortie | `debugOutput` → `open_memstream`, `DebugUI_ParseLine`, puis `DebugCpu_SetDebugging()` |
| `int hatari_mem_read(uint32_t addr, uint8_t *buf, size_t len)` | Lecture mémoire | `STMemory_ReadByte` |
| `int hatari_mem_write(uint32_t addr, const uint8_t *buf, size_t len)` | Écriture mémoire | `STMemory_WriteByte` |
| `void hatari_regs_get(uint32_t out[20])` | D0-D7, A0-A7, PC, SR, USP, ISP | `regs.regs`, `M68000_GetPC()`, `MakeSR()` |
| `int hatari_reg_set(const char *name, uint32_t value)` | Écrit un registre | `DebugCpu_GetRegisterAddress` |
| `int hatari_disasm(uint32_t addr, int count, char *out, size_t outlen, uint32_t *next_pc)` | Désassemble `count` instructions | `Disasm(FILE*)` sur memstream |
| `int hatari_framebuffer(const uint32_t **pixels, int *w, int *h)` | Framebuffer XRGB8888 courant | `Screen_GetDimension` |
| `void hatari_key(uint8_t scancode, bool press)` | Touche clavier ST | `IKBD_PressSTKey` |
| `int hatari_disk_insert(int drive, const char *path)` / `hatari_disk_eject(int drive)` | Disquettes A/B | `Floppy_SetDiskFileName` + `Floppy_InsertDiskIntoDrive` |
| `void hatari_reset(bool cold)` | Reset | `Reset_Cold` / `Reset_Warm` |
| `void hatari_counters(uint32_t *vbl, uint64_t *cycles)` | Compteurs | `nVBLs`, `CyclesGlobalClockCounter` |

Codes de `stop_reason` : `HATARI_STOP_NONE` (trames épuisées), `HATARI_STOP_BREAKPOINT`,
`HATARI_STOP_STEPS`, `HATARI_STOP_EXCEPTION`, `HATARI_STOP_OTHER` (toute autre raison
`debug_reason_t`).

Notes :

- Lire la zone IO (`$FF8000`+) via `hatari_mem_read` a des effets de bord (registres à
  lecture destructive). Documenté côté outil, pas bloqué.
- `hatari_dbg_command` doit appeler `DebugUI_Init()` à la première utilisation (initialise
  `debugOutput` et les tables de commandes) et `DebugCpu_SetDebugging()` après chaque commande :
  c'est ce qui arme `SPCFLAG_DEBUGGER` quand des breakpoints ou des pas sont actifs.
- Le pas-à-pas s'obtient par la commande `c N` (N instructions) puis `hatari_run` ; l'arrêt
  revient avec `HATARI_STOP_STEPS`.

### 4.4 Test C

`tests/retro/test-debug-api.c`, enregistré dans CTest à côté de `test-retro` : charge la
`.so` par `dlopen`, `hatari_init_args` avec `--tos none`, pose un breakpoint sur une adresse
du faux TOS, `hatari_run` jusqu'à l'arrêt, vérifie `stop_reason`, `PC`, lecture et écriture
mémoire, désassemblage non vide.

## 5. Serveur Java (`hatari-mcp/`)

### 5.1 Structure

```
hatari-mcp/
  pom.xml                     Java 25, io.modelcontextprotocol.sdk:mcp 2.0.0, JUnit 5
  scripts/hatari-mcp.sh       lanceur (modèle toje-mcp.sh)
  src/main/java/fr/hatari/mcp/
    StdioMcpMain.java         point d'entrée, stdout réservé à JSON-RPC, logs sur stderr
    Args.java                 --core, --tos, --machine, --memsize (défauts ci-dessous)
    EmulatorSession.java      cycle de vie : init, run, arrêt propre
    Machine.java              interface consommée par les outils (testable avec un fake)
    ffm/HatariCore.java       bindings FFM, seule classe qui manipule MemorySegment
    ffm/RetroCallbacks.java   upcall stubs (env, vidéo, audio, input)
    keymap/StScancodes.java   ASCII / noms de touches → scancodes ST
    Fmt.java, McpJson.java, ToolCatalog.java   repris de toje-mcp
    tools/MachineTools.java   ping, machine_state, reset, run_frames, run_until_pc,
                              run_to_breakpoint, step
    tools/MemoryTools.java    read_memory, write_memory, read_registers, set_register,
                              disassemble
    tools/DebugTools.java     set/clear_breakpoint, set/clear_watchpoint, load_symbols,
                              debug_command
    tools/VideoTools.java     screenshot
    tools/InputTools.java     press_key, type_keys
    tools/DiskTools.java      mount_disk, boot_disk
  src/test/java/...           unitaires (fake Machine) + intégration (vraie .so)
```

Défauts : `--core` = `libretro-hatari.so` à côté du jar, sinon `../build/src/libretro-hatari.so` ;
`--tos` = `~/.hatari/tos.img` ; `--machine st` ; `--memsize 1`.

### 5.2 Sémantique des outils

- `run_frames(n)` : `hatari_run(n)`. Retourne `frames_done`, `stop_reason`, PC, compteur VBL.
- `run_until_pc(pc, max_frames)` : breakpoint temporaire `pc = $xxxx :once`, `hatari_run`,
  retour identique.
- `run_to_breakpoint(max_frames)` : `hatari_run` avec les breakpoints posés.
- `step(n = 1)` : `debug_command("c n")` puis `hatari_run(1)`.
- `set_breakpoint(pc)` → `b pc = $xxxx`. `set_watchpoint(addr, len, label)` → expression
  « valeur changée » `($addr).b ! ($addr).b` (ou `.w`/`.l` selon `len` ∈ {1,2,4}). Les ids
  sont attribués côté Java ; `clear_*` retrouve la position Hatari par `b` (liste) et envoie
  `b <n>`. Le mapping id → expression vit dans `DebugTools`.
- `read_memory(addr, len)` : hexdump 16 octets par ligne + ASCII, `len` ≤ 64 Kio.
- `read_registers` : JSON `{d0..d7, a0..a7, pc, sr, usp, isp, flags}`.
- `disassemble(addr = PC, count = 16)` : texte du désassembleur Hatari.
- `screenshot(path?)` : PNG du framebuffer, retourné en image MCP et sauvé si `path`.
- `press_key(key, hold_frames = 2)` : appui, `hold_frames` trames, relâchement.
  `type_keys(text)` : séquence de `press_key`, Shift géré par la table.
- `mount_disk(path, drive = 0)`. `boot_disk(path, frames = 300)` = mount + reset froid +
  `run_frames`.
- `debug_command(cmd)` : passerelle brute, retourne la sortie texte.
- `load_symbols(path)` → `symbols <path>`.

### 5.3 Erreurs

Toute erreur du cœur (code de retour non nul, `stop_reason` inattendu, buffer tronqué)
devient une réponse MCP `isError` avec message explicite. Une exception Java dans un outil ne
tue jamais le serveur. Fin de session : EOF sur stdin → `retro_deinit`.

### 5.4 Tests

- Unitaires : table de scancodes, formatage hexdump et registres, construction des
  expressions breakcond, avec un `Machine` fake.
- Intégration (`hatari.core` en propriété système, ignorés si absent) : boot EmuTOS 300
  trames et écran non vide ; breakpoint sur PC atteint ; écriture puis relecture mémoire ;
  `type_keys` visible dans le buffer clavier après quelques trames.

## 6. Licence

Hatari est GPL v2 ou ultérieure. `hatari-mcp` charge la `.so` dynamiquement et reprend du
code de toje-mcp : il est publié sous GPL v2 ou ultérieure. Fichier `LICENSE` = `gpl.txt`.

## 7. Risques connus

- Cœur libretro jeune (2026-01) : clavier, disques et options machine sont peu exercés. Le
  test d'intégration les couvre.
- `open_memstream` est POSIX : Linux et macOS OK, Windows hors périmètre.
- Les commandes debugger écrivent aussi parfois sur `stderr` directement (ex. `c N`) : ces
  messages ne sont pas capturés, seul `debugOutput` l'est. Acceptable, stderr est le canal de
  log du serveur.
