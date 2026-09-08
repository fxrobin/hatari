# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Projet

Hatari : émulateur Atari ST/STE/TT/Falcon en C99 (GPL v2+), build CMake, frontend SDL2 (SDL3 optionnel via `ENABLE_SDL3`).
Priorité du projet : fidélité cycle-exacte au hardware (jeux/démos), pas confort GEM.
Docs de référence : `readme.txt` (build, dépendances, layout), `doc/coding.txt` (style), `tests/readme.txt`, `doc/debugger.html`.

## Build

Build hors-source obligatoire (le script `configure` refuse un `CMakeCache.txt` dans les sources).

```sh
mkdir -p build && cd build
cmake ..                                   # ou ../configure --enable-debug --enable-werror
cmake --build . -j$(getconf _NPROCESSORS_ONLN)
# binaire : build/src/hatari
```

Options CMake utiles (`-D<OPT>:BOOL=1`) : `ENABLE_DSP_EMU` (défaut 1), `ENABLE_TRACING` (défaut 1, requis pour `--trace`), `ENABLE_WERROR`, `ENABLE_ASAN`, `ENABLE_UBSAN`, `ENABLE_SDL3`, `CMAKE_BUILD_TYPE=Debug`.
Le wrapper `./configure --help` liste les équivalents (`--enable-debug`, `--enable-asan`, `--disable-dsp`, cross-compile mingw…).

La CI (`.gitlab-ci.yml`, `.github/workflows/`) compile avec `-Werror` : toute nouvelle warning casse le build. Le job `build-minimal` compile sans zlib/png/readline et `--disable-dsp` : garder les `#if HAVE_*` corrects.

Cross-compilation Windows : `-DCMAKE_TOOLCHAIN_FILE=cmake/Toolchain-mingw32-win64_64.cmake`. Emscripten : `emcmake cmake ..` puis target `hatari`.

## Tests

Tests = CTest, lancés depuis `build/` après compilation. Ils utilisent `--tos none` (TOS factice interne, `faketos.s`) : pas d'image TOS requise.

```sh
cd build
ctest -j$(nproc)                       # tout (équivalent make test)
ctest -V -R command-fifo               # un test, sortie verbeuse
ctest -V -R 'serial-scc-.*'            # un groupe
cat Testing/Temporary/LastTest.log     # log en cas d'échec
```

Familles de tests (nom CTest → répertoire `tests/`) : `blitter`, `buserror-*`, `cpu-integer-*`, `cycles-*`, `debugger-*`, `gemdos`, `natfeats-*`, `cli-opts-*` (options), `profile-*`, `screen-fullscreen-*`, `serial-{mfp,scc,midi}-*`, `xbios-*`, `unit-file` (`tests/unit/`), `command-fifo`, `config-file`.
Beaucoup de tests sont des scripts shell qui lancent le binaire `hatari` avec un `.prg` précompilé (sources Atari + binaires commités dans `tests/*`). Rebuild des binaires Atari via AHCC (voir `tests/readme.txt`), pas via CMake.
`tests/check-bashisms.sh` vérifie que les scripts restent POSIX sh. `tests/tosboot/` : testeur manuel de boot multi-TOS (non lancé par ctest).

## Style de code (extrait de `doc/coding.txt`)

- Indentation TAB (largeur 8, doit rester lisible en 4). Fins de ligne LF, ASCII uniquement dans les sources.
- Commentaires doxygen sur chaque fonction ; pas de valeurs magiques (defines/enums).
- Pas d'include dans un header : tous les includes dans le `.c`, dans le bon ordre.
- **`src/cpu/` est synchronisé avec WinUAE** : garder le style d'origine, changements minimaux. Idem pour tout fichier importé d'un autre projet. Les warnings y sont volontairement masquées (`src/cpu/CMakeLists.txt`).
- Chaque `.c` déclare `const char Xxx_fileid[] = "Hatari xxx.c";` en tête.
- Les gros fichiers d'émulation (`video.c`, `m68000.c`, `fdc.c`…) ont un journal daté (`[NP]`…) en tête décrivant chaque fix et la démo/jeu qui l'a motivé : ajouter une entrée pour tout changement de timing.

## Architecture

### Cœur CPU (`src/cpu/`)
Cœur 68000–68060 + FPU + MMU de WinUAE. Les fichiers `cpuemu_*.c`, `cpustbl.c`, `cpudefs.c` **n'existent pas dans les sources** : générés au build par `build68k` (lit `table68k`) puis `gencpu`. Ne jamais les éditer ; modifier `table68k`/`gencpu.c`.
`hatari-glue.c` est le pont vers le reste de Hatari (opcodes "illégaux" Hatari pour GEMDOS HD, NatFeats, VDI, cartouche). `src/m68000.c` + `includes/m68000.h` : comptage de cycles, wait-states, pairing d'instructions, bus errors côté Hatari.

### Boucle d'émulation et timing
- `src/cycInt.c` : ordonnanceur d'interruptions internes à cycle près. Chaque périphérique programme un `INTERRUPT_*` (enum dans `cycInt.h`) avec un compte de cycles CPU/MFP (précision fractionnaire `CYCINT_SHIFT`). Nouveau périphérique temporisé ⇒ ajouter un id dans l'enum + handler.
- `src/cycles.c`, `clocks_timings.c` : compteurs globaux et fréquences machine (`MachineClocks`).
- `src/video.c` : Shifter/GLUE ST/STE, HBL/VBL, suppression de bordures, sync scroll. Point d'entrée des effets cycle-exacts ; `spec512.c` pour les changements de palette mid-ligne. `falcon/videl.c` pour TT/Falcon.
- Les frontends (`src/sdl/` SDL, `src/retro/` libretro) fournissent `main_*.c`, `screen.c`, `audio.c`, `timing.c`, `keymap.c` : même API, implémentations différentes. Le cœur ne doit pas appeler SDL directement hors de `src/sdl/` et `src/gui-sdl/`.

### Accès hardware (`src/ioMem.c`)
Toute lecture/écriture dans `$FF8000–$FFFFFF` passe par des tables de handlers par adresse : `ioMemTabST.c`, `ioMemTabSTE.c`, `ioMemTabTT.c`, `ioMemTabFalcon.c` (`INTERCEPT_ACCESS_FUNC {addr, size, read_f, write_f}`). Pour ajouter/modifier un registre : handler dans le module concerné (`mfp.c`, `psg.c`, `dmaSnd.c`, `fdc.c`, `blitter.c`, `acia.c`, `ikbd.c`, `scc.c`…) + entrée dans la table de chaque machine concernée. Les adresses non listées génèrent un bus error (testé par `tests/buserror/`).

### Sauvegardes d'état (`src/memorySnapShot.c`)
Chaque module expose `Xxx_MemorySnapShot_Capture(bool bSave)` qui appelle `MemorySnapShot_Store()` sur ses variables statiques ; `memorySnapShot.c` les enchaîne dans un ordre qui compte (commentaires "Before/After"). **Toute nouvelle variable d'état d'émulation doit être ajoutée au capture du module**, sinon les snapshots divergent.

### Configuration
`ConfigureParams` (`CNF_PARAMS`, `includes/configuration.h`) est l'état de config global. Chargement/sauvegarde via `cfgopts.c`, options CLI via `options.c` (`--trace help`, `--tos none`, `--bios-intercept`…). `change.c` compare ancienne/nouvelle config et décide d'un reset (`Change_DoNeedReset`) : nouveau paramètre ⇒ mettre à jour `configuration.c` (défauts + fichier), `options.c`, le dialogue `gui-sdl/dlg*.c` et, si besoin, `change.c`.

### Debugger et traces (`src/debug/`)
Debugger intégré (AltGr+Pause, `debugui.c`), breakpoints conditionnels (`breakcond.c`), profileur CPU/DSP (`profile*.c`), symboles (`symbols.c`), NatFeats (`natfeats.c`).
Traces : `LOG_TRACE(TRACE_xxx, fmt, ...)` avec flags dans `log.h`, activées à l'exécution par `--trace <flags>` (compilées seulement si `ENABLE_TRACING`). `Log_Printf(LOG_WARN|LOG_DEBUG…)` pour les messages persistants.
Contrôle externe : `--control-socket` / `--cmd-fifo` (`control.c`), utilisés par `tools/hconsole/`, `python-ui/` et les tests (`tests/cmdfifo.sh`).

### Autres sous-systèmes
- `src/falcon/` : DSP 56001 (`dsp_core.c`, `dsp_cpu.c`), Crossbar audio, Videl, NVRAM.
- `src/floppies/` : formats disquette (ST, MSA, DIM, STX/Pasti, IPF/CTR via capsimage, SCP, KFS). `fdc.c` = contrôleur WD1772.
- `src/gemdos.c` : émulation GEMDOS HD (répertoire hôte monté), `hdc.c`/`ncr5380.c`/`ide.c` : ACSI/SCSI/IDE.
- `src/convert/` : routines de conversion ST-screen → surface hôte, incluses par `conv_st.c` selon résolution.
- `tools/` : `hmsa` (conversion ST/MSA), `gst2ascii`, `hatari_profile.py`, `hatari-prg-args.sh` (lancer un `.prg` avec args, pratique pour les tests), scripts image HD.
