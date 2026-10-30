---
name: st-dev-loop
description: Use when building, running, debugging or timing your own 68000 program for Atari ST/STE — assembler choice and flags, PRG skeleton (supervisor, save/restore hardware, clean exit), running headless under the Hatari CLI or the Hatari MCP server with an STE machine, printing and measuring cycles from inside the program with NatFeats, profiling, and checking that a routine fits its raster/VBL budget.
---

# Boucle de développement ST/STE sous Hatari

Objectif : **assembler → lancer sous Hatari → mesurer → corriger** en quelques
secondes, sans interface graphique, et prouver le timing par des chiffres.

## 1. Assembleur

**Chaîne validée** (2026-10-30) : image Docker **`fxrobin/m68k-compiler`**
(dépôt `fxrobin/docker-m68k-compiler`, chaîne `m68k-atari-mint` de Vincent
Rivière : GNU as 2.30 MiNT, ld, gcc 4.6.4, objdump). Le script
`bench/build.sh nom` assemble `nom.s` en `nom.prg` :

```sh
docker run --rm -u "$(id -u):$(id -g)" -v "$PWD:/app" -w /app fxrobin/m68k-compiler sh -c \
  "m68k-atari-mint-as --register-prefix-optional -m68000 -o x.o x.s && m68k-atari-mint-ld -s -o x.prg x.o"
```

(`dockerd` doit tourner ; dans une session cloud, le lancer à la main.)
Désassembler pour vérifier ce qui a été émis :
`m68k-atari-mint-objdump -d x.o`.

Syntaxe GNU as (avec `--register-prefix-optional`), différences avec Devpac :

| Devpac / vasm | GNU as |
|---|---|
| `$FF8240`, `%1010` | `0xff8240`, `0b1010` |
| `; commentaire` | `\| commentaire` (`;` sépare des instructions) |
| `.loop` (label local) | `1:` puis `1b` / `1f` |
| `dc.w`, `dc.b "x",0`, `ds.l`, `even` | `.dc.w`, `.asciz "x"`, `.ds.l`, `.even` |
| `rept n` / `endr`, `macro` | `.rept n` / `.endr`, `.macro nom args` / `.endm` (`\arg`) |
| `section bss` | `.bss` ; sous-sections `.data 0`, `.data 1` |
| `$ffff8240.w` | `0xffff8240.w` (absolu court, accepté tel quel) |

Autres assembleurs courants : **vasm** (`vasmm68k_mot -Ftos -devpac`,
syntaxe Devpac, optimise par défaut : `-no-opt` pour un timing exact),
**rmac**, **AHCC** (utilisé par les tests de Hatari). Quel que soit l'outil,
vérifier au désassemblage le code d'une routine à timing exact.

- Adresses matérielles en **absolu court** : `0xffff8240.w` (le 68000 n'a
  que 24 bits d'adresse ; 4 cycles de moins qu'en absolu long).

## 2. Squelette de programme (à respecter)

Modèles éprouvés dans ce dépôt : `tests/screen/flixfull.s` (overscan complet,
sauvegarde/restauration MFP, IKBD, palette, résolution) et
`tests/cycles/cyccheck.s` (mesure de cycles synchronisée sur `$FF8209`).

Ordre à l'entrée :
1. `Super` (GEMDOS `$20`) ; garder l'ancien SSP.
2. `move #$2700,sr` pendant l'installation.
3. Sauver : `$FF820A` (sync), `$FF8260` (rés.), palette `$FF8240` (16 mots,
   `movem.l`), base `$FF8201/03` (+ `$FF820D`, `$FF820F`, `$FF8265` sur STE),
   vecteurs touchés (`$70` VBL, `$68` HBL, `$118` ACIA, `$120` Timer B,
   `$134` Timer A…), registres MFP touchés (`$FFFA07/09/13/15/17/1B/1F/21`).
4. Écran aligné sur 256 o (ST) ; sur STE, écrire `$FF8201`, `$FF8203` **puis**
   `$FF820D` (écrire l'octet haut/moyen remet l'octet bas à 0 sur STE).
5. Couper la souris (IKBD `$12`), passer le joystick en mode événement (`$14`)
   si on lit le joystick soi-même.
6. Installer les vecteurs, MFP en **fin d'interruption automatique**
   (`$FFFA17` = `$40`) sauf besoin d'imbrication, puis `move #$2300,sr`.

À la sortie : restaurer **tout** dans l'ordre inverse, remettre la souris
(`$08`), attendre une VBL entre les changements de sync/rés., `Super` retour,
`Pterm0`. Un programme qui ne restaure pas casse le test suivant.

Lire `$FF820A` : bit 1 = 50 Hz. Les écrans ST/STE en basse résolution sont à
`$FF8260` = 0 ; ne pas toucher la résolution en monochrome (`$FF8260` = 2).

## 3. Lancer sans interface (CLI Hatari)

Construire Hatari : voir `CLAUDE.md` (`build/src/hatari`) ; il faut
`libsdl2-dev` (+ `zlib1g-dev`, `libpng-dev`), sinon CMake s'arrête sur
« SDL library not found ». Puis (c'est ce que fait `bench/run.sh nom [st|ste]`) :

```sh
SDL_VIDEODRIVER=dummy SDL_AUDIODRIVER=dummy \
build/src/hatari --machine ste --tos none --fast-forward on --sound off \
  --log-level fatal --natfeats on --run-vbls 500 prog.prg
```

- `--tos none` : TOS factice interne (pas d'image TOS), suffit pour un PRG
  qui tape le matériel ; GEMDOS de base (Cconws, Fcreate/Fwrite) disponible —
  les tests `tests/cycles` y écrivent leurs résultats dans un fichier.
- **EmuTOS** (TOS libre, GPL) pour booter une disquette ou un bureau : images
  officielles sur SourceForge (`emutos-192k-1.3.zip` → `etos192uk.img` pour
  ST, `emutos-256k-1.3.zip` → `etos256uk.img` pour STE), pas sur GitHub ni
  dans le paquet Ubuntu `hatari` (version dfsg). La version **UK démarre en
  PAL 50 Hz** (vérifié, `info video`), la US en 60 Hz. Limite : les jeux
  protégés qui dépendent du TOS Atari peuvent planter (Rick Dangerous, protégé
  par Copylock, lève un bus error au boot sous EmuTOS 1.3) → `--tos` d'un TOS
  Atari 1.0x.
- `--machine st|ste|megaste` ; `--memsize` en Mo.
- `--run-vbls N` : quitte après N VBL (tests). `--benchmark` : sans limite de
  vitesse. `--auto C:\\PROG.PRG` + `-d dossier` : lancer depuis un disque
  GEMDOS. `tools/hatari-prg-args.sh` passe des arguments au PRG.
- Captures : `--screenshot-dir dir` + touche/commande, ou comparer à une
  référence comme `tests/screen/run_test.sh`.
- Débogueur scripté : `--parse cmds.ini` (points d'arrêt posés au démarrage).

## 4. Lancer sous le serveur MCP Hatari

`.mcp.json` lance `hatari-mcp/scripts/hatari-mcp.sh`, machine **`st` par
défaut**. Pour l'STE, ajouter les arguments du serveur :

```json
{ "mcpServers": { "hatari": {
    "command": "hatari-mcp/scripts/hatari-mcp.sh",
    "args": ["--machine", "ste", "--memsize", "1"] } } }
```

Le serveur boote une **disquette** (`boot_disk`) : mettre le PRG dans une
image auto-bootable (dossier `AUTO\` d'une image `.st` — `tools/zip2st.sh`
fabrique une image depuis un zip), ou le lancer depuis le bureau avec
`type_keys`. Ensuite : `run_frames`, `screenshot`, `read_memory`,
`set_breakpoint`, `run_to_breakpoint`, `step`, `disassemble`,
`debug_command` (tout le débogueur Hatari).

## 5. Parler à l'émulateur depuis le programme (NatFeats)

Avec `--natfeats on`, deux opcodes illégaux : `$7300` (NF_ID) et `$7301`
(NF_CALL) ; convention C sur la pile (voir `tests/natfeats/nf_asmv.s`).
Fonctions utiles (`src/debug/natfeats.c`) :

| Nom | Effet |
|---|---|
| `NF_STDERR` | imprime une chaîne sur le stderr de l'hôte (traces de test) |
| `NF_CYCLES` | renvoie les 32 bits bas du compteur global de cycles |
| `NF_DEBUGGER` | entre dans le débogueur (point d'arrêt « dans le code ») |
| `NF_EXIT` | quitte Hatari avec un code (verdict de test pour ctest/CI) |
| `NF_FASTFORWARD` | active/désactive l'avance rapide |
| `NF_COMMAND` | exécute une commande Hatari/débogueur |

```asm
; d0 = id de "NF_CYCLES" (une fois, en superviseur)
        pea     nf_cycles_name(pc)
        subq.l  #4,sp                 ; place d'une adresse de retour factice
        dc.w    $7300                 ; NF_ID
        addq.l  #8,sp
        move.l  d0,nf_cycles_id
; lire le compteur
        move.l  nf_cycles_id(pc),-(sp)
        subq.l  #4,sp
        dc.w    $7301                 ; NF_CALL -> d0 = cycles
        addq.l  #8,sp
nf_cycles_name: dc.b "NF_CYCLES",0
        even
```

Mesurer une routine : `NF_CYCLES` avant/après, soustraire le coût de l'appel
NF mesuré à vide. Détecter NatFeats en déroutant le vecteur illégal `$10`
(sur une vraie machine, `$7300` lève une exception illégale).

## 6. Mesurer et profiler

- Débogueur : variables `CycleCounter`, `FrameCycles` (cycles depuis la VBL),
  `LineCycles` (depuis la HBL, multiple de 4), `VBL`, `HBL`, `CpuInstr`
  dans les conditions : `b pc = $1234 :trace` puis lire `FrameCycles`.
  Options de points d'arrêt : `:once`, `:quiet`, `:trace`, `:lock`,
  `:file cmds.ini` (src/debug/breakcond.c).
- Profileur : `profile on`, laisser tourner, puis `profile counts|cycles|
  symbols|callers|loops|stats` ; symboles via `symbols prog.prg` ou fichier
  `nm`. `tools/hatari_profile.py` (python-ui/tools) post-traite.
- `info video` (position faisceau, registres Shifter), `info mfp`,
  `info blitter`, `info dmasnd`, `info ym`, `info ikbd`.
- Visualiser la charge à l'ancienne : changer `$FF8240` en début/fin de
  routine (bande de couleur dans la bordure) ; la hauteur en lignes × 512
  cycles = coût.
- Synchronisation exacte sur le faisceau (pour mesurer ou pour un effet) :
  boucle sur l'octet bas du compteur vidéo puis `lsl.w d0,d0` (patron de
  `tests/cycles/cyccheck.s` et des routines overscan).

## 7. Banc de test fourni (`bench/`)

Vérifié le 2026-10-30 avec la chaîne ci-dessus et Hatari compilé depuis ce
dépôt :

| Fichier | Rôle |
|---|---|
| `nf.inc` | NatFeats : `nf_init`, `puts` (a0), `putdec` (d0.l), `puthex` (d0.w), `cycles` (→ d0), `nf_exit` (d0) |
| `cycbench.s` | coût en cycles de séquences (64 copies déroulées, IRQ masquées, coût de mesure soustrait) ; ajouter un test = une macro `T "nom",label` + la routine |
| `hwtest.s` | contrôles matériels STE (H1–H8) : `$FF820D`, palette `.b`, bus error joypad, Microwire, blitter, DMA son, Timer B, hscroll + linewidth |
| `build.sh`, `run.sh` | assembler (Docker) / lancer sous Hatari sans écran |
| `scenario_harness.c` | pilote le cœur libretro (celui du serveur MCP) sans Java : scénario daté en trames — `joy`, `key`, `poke`, `dump`, `shot` (PPM), `dbg <commande>`, `onstop <commande>` (sur point d'arrêt), `end` ; arguments Hatari après `--` (disquette, `--tos`, `--machine`) |

```sh
cd skills/st-dev-loop/bench
./build.sh cycbench && ./run.sh cycbench ste     # puis: ./run.sh cycbench st
./build.sh hwtest   && ./run.sh hwtest ste
```

Résultats de référence : `st-hardware/references/cpu-timing.md` (cycles) et
les mentions « vérifié (H*n*) » des autres références. Un écart après une
mise à jour de Hatari = régression de l'émulateur ou du banc : à creuser.

Piège rencontré : une routine mesurée qui écrase les registres du banc fausse
la mesure (`movem.l (a0),d0-d7` avait donné 535 cycles au lieu de 76) ; le
banc garde donc son état en mémoire.

## 8. Tests de non-régression

Calquer `tests/cycles/run_test.sh` : le PRG écrit un fichier résultat via
GEMDOS (ou `NF_STDERR`), le script compare à un `test_out.txt` attendu ;
enregistrer dans CTest. Un test par effet sensible au timing (bordures,
raster, scroll matériel), comparé par capture (`tests/screen`).

## 9. Différences machine à tester

- ST vs STE : `$FF8205/07/09` en écriture, `$FF820D/0F/65`, palette 4 bits,
  blitter, DMA son, joypads `$FF9200/02` n'existent que sur STE (bus error
  sur ST) : détecter par cookie `_MCH` ou en déroutant le vecteur bus error
  `$8` le temps d'une lecture.
- Timing des bordures légèrement différent ST/STE : tester les deux
  (`--machine st` puis `ste`).
- 50 et 60 Hz.
