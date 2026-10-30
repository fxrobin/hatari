# Skills et agents : programmer l'Atari ST / STE

Boîte à outils Claude Code pour écrire des jeux et démos 68000 sur Atari ST et
surtout **STE**, construite à partir de :

- la rétro-ingénierie de **Xenon 2 Megablast** sous Hatari
  (`../xenon-2-findings.md`, version longue dans le dépôt xenon-2) ;
- le **code source de Hatari** lui-même, émulateur cycle-exact : chaque fait
  matériel cite `src/<fichier>:<ligne>` ;
- les techniques classiques de la scène ST (overscan, rasters, scroll
  matériel STE, blitter, code généré).

Les sources sont ici ; `.claude/skills/` et `.claude/agents/` contiennent des
liens symboliques pour que Claude Code les découvre.

## Skills

| Skill | Quand |
|---|---|
| [`st-hardware`](st-hardware/SKILL.md) | tout code qui touche le matériel : vidéo et scroll STE, blitter, DMA son, MFP, YM, IKBD, joypads, carte mémoire, timings CPU (`references/`) |
| [`st-game-engine`](st-game-engine/SKILL.md) | architecture d'un jeu d'action : cadence, tampons, tuiles, parallaxe, sprites, objets, vagues, collisions, HUD — patrons de Xenon 2 + équivalents STE |
| [`st-asm-optimize`](st-asm-optimize/SKILL.md) | faire tenir une routine dans son budget : règles de cycles ST, techniques, preuve du gain |
| [`st-dev-loop`](st-dev-loop/SKILL.md) | assembler, lancer sous Hatari (CLI ou MCP, machine STE), NatFeats, mesures, tests de non-régression |
| [`st-reverse-engineering`](st-reverse-engineering/SKILL.md) | comprendre un jeu existant sous Hatari : dumps, renderers, formats, structures, pièges |

## Agents

| Agent | Rôle |
|---|---|
| [`ste-asm-reviewer`](agents/ste-asm-reviewer.md) | relit tout `.s` ST/STE avant exécution/commit (lecture seule) |
| [`ste-hardware-verifier`](agents/ste-hardware-verifier.md) | tranche une question matérielle par le code Hatari et une mesure |
| [`st-game-analyst`](agents/st-game-analyst.md) | étudie un jeu de référence sous Hatari MCP, rend adresses et chiffres |

## Flux type

1. Concevoir avec `st-game-engine`, vérifier les registres dans `st-hardware`.
2. Écrire le code, le faire relire par `ste-asm-reviewer`.
3. Lancer et mesurer avec `st-dev-loop` (`--machine ste`, `NF_CYCLES`).
4. Trop lent ? `st-asm-optimize`, re-mesurer.
5. Doute matériel ? `ste-hardware-verifier`. Comment faisait le jeu X ?
   `st-game-analyst`.

## Maintenance

- Un fait matériel nouveau et vérifié va dans
  `st-hardware/references/<sujet>.md` **avec sa source** (ligne de `src/` ou
  mesure reproductible). Les lignes citées datent d'oct. 2026 : si le code de
  Hatari bouge, revérifier par `grep`.
- Un fait sur Xenon 2 va d'abord dans `xenon-2-findings.md` ; le patron
  réutilisable qu'on en tire va dans `st-game-engine`.
- **Vérification** : le banc `st-dev-loop/bench/` (GNU as via l'image Docker
  `fxrobin/m68k-compiler`, Hatari compilé depuis ce dépôt) a mesuré le
  tableau de cycles et validé les recettes STE marquées « vérifié » dans
  `st-hardware/references/`. Restent non exécutés : les extraits marqués
  *(déduit)*, le handler ACIA et le sprite blitter multi-passes. Programmes de
  référence de la CI Hatari : `tests/screen/flixfull.s` (overscan, MFP,
  IKBD) et `tests/cycles/cyccheck.s`.
