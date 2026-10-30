---
name: st-game-engine
description: Use when designing or writing the engine of an Atari ST/STE action game in 68000 assembly — main loop and frame pacing, screen buffering, tile/scroll renderer, parallax, masked sprites, object lists, enemy waves and paths, collisions, HUD, fixed-point movement. Patterns are taken from Xenon 2 Megablast as reverse-engineered under Hatari, plus the STE hardware upgrades (hardware scroll, blitter, DMA sound) that replace the ST software tricks.
---

# Moteur de jeu ST/STE (patrons éprouvés sur Xenon 2)

Ce skill donne une architecture de jeu d'action ST/STE qui **marche sur le vrai
matériel**, déduite du moteur de Xenon 2 Megablast (Bitmap Brothers, 1989) relu
sous Hatari (`/home/user/hatari/xenon-2-findings.md`, version longue
`xenon-2/docs/xenon-2-findings.md`). Chaque patron cite le § des findings et
l'adresse ST d'origine pour qu'on puisse aller relire le code sous le débogueur.

Pour le détail matériel (registres, timings) : skill `st-hardware`. Pour le
coût cycle par cycle : skill `st-asm-optimize`. Pour compiler et tester :
skill `st-dev-loop`.

## 1. Décider d'abord : budget, cadence, machine cible

| Machine | Cycles/VBL (PAL 50 Hz) | Remarque |
|---|---|---|
| ST / STE 8 MHz | 160 256 (313 lignes × 512) | recopier 32 000 o par `movem.l` coûte ~143 000 cycles (≈ 90 % d'une VBL) : d'où le tick de 3 VBL de Xenon 2 |
| STE + blitter | idem, blitter et CPU se partagent le bus | copie brute ≈ `movem` (4 cycles/o) ; le gain est sur les transferts avec décalage/masque/opération logique, faits sans instruction |
| 60 Hz (NTSC) | 133 604 (263 × 508) | Xenon 2 passe alors de 3 à 4 VBL par tick (§10.7) |

Règles (Xenon 2 §10.7) :

- **Tick logique = N VBL fixe**, pas « aussi vite que possible ». Xenon 2 tourne à
  3 VBL/tick en PAL (16,68 Hz) avec une charge moyenne de 1,99 VBL et un max de
  2,58 VBL : 14 % de marge. Choisir N d'après le **pire cas mesuré**, pas la
  moyenne.
- Le jeu lit le bit 1 de `$FF820A` (50/60 Hz) au démarrage et fixe N
  (`$410` = 3 ou 4) ; il ne réécrit jamais `$FF820A`. Toutes les vitesses sont
  en **px/tick**, donc la vitesse réelle change avec N : le dire dans le design.
- Attente de fin de tick en deux temps : compteur VBL (`$41C.w − $404.w ≥ N`)
  puis **attente du faisceau** sur une ligne donnée (lecture du compteur vidéo
  `$FF8205/07/09` ≥ base + 192×160) avant l'échange d'écrans. On échange donc
  sous le HUD, pas au VBL.

## 2. Tampons écran

- Xenon 2 : 4 tampons de 32 000 o à `$30000`, `$38000`, `$40000`, `$48000`
  (§2), alignés sur 256 o (le ST n'a pas l'octet bas `$FF820D`).
- Triple buffering = le rendu n'attend jamais l'affichage quand la charge varie
  entre 2 et 3 VBL. Double buffering suffit si le tick est **toujours** ≤ N VBL.
- STE : `$FF820D` (octet bas) permet n'importe quelle adresse paire ; la base
  est relue par le Shifter au début de la trame suivante — l'écrire n'importe
  quand dans la trame courante.

## 3. Décor à tuiles (scroll vertical)

Format Xenon 2 (§3–§4), excellent compromis mémoire/vitesse :

- Tilemap : 20 mots par rangée (320 px), niveau = 300 rangées. Mot `0` = rien
  (on recopie le fond), `idx` = tuile **opaque** à `banque + idx×16`,
  `$8000|idx` = tuile **masquée**. Index en unités de 16 o → tas de tuiles de
  tailles différentes sans table de pointeurs.
- Tuile opaque : 16 lignes × `p0 p1 p2 p3` (8 o/ligne, 128 o), copie `move.l`.
- Tuile masquée : 16 lignes × `m0 m1 p0 p1 p2 p3` (12 o/ligne, 192 o) :
  `plan01 = (fond_plan01 AND m) OR p01` puis `plan23 = p23` (le fond n'a que 2
  plans, voir §4). Masque à 1 = fond visible.
- Rendu **déroulé** : offsets d'écran calculés en dur (`+$98`, `+$138`, …),
  ligne de départ décalée de `15 − scroll_fin` pour le scroll fin.
- Scroll : offset en octets dans la tilemap (`$CCE`, multiple de 40) + scroll
  fin 0–15 (`$CD6`). Le niveau part de la fin de la carte et remonte.

ST : tout le playfield est **redessiné à chaque tick** (pas de scroll matériel).
**STE : ne pas recopier**. Faire avancer la base vidéo (`$FF8201/03/0D`)
d'une ligne (160 o) par pixel de scroll et ne dessiner que la ligne/rangée
entrante. Le compteur vidéo ne reboucle pas : tampon de **400 lignes (64 000 o)**,
chaque ligne entrante écrite **deux fois** (en `y` et `y ± 200`), la base
parcourt 200 lignes puis saute à l'autre copie. Le coût du décor tombe de
~90 % d'une VBL à quelques %. Prix : 64 Ko par tampon, et les sprites doivent
restaurer le fond qu'ils écrasent (sauvegarde sous sprite, ou copie propre du
décor recopiée au blitter sur la zone sale).
Horizontal : `$FF8265` (scroll fin 0–15) + `$FF820F` (largeur de ligne, en
mots en plus) — voir `st-hardware/references/video.md`.

## 4. Parallaxe à 2 plans

Xenon 2 (§5, §8) : fond = bitmap **2 plans** (couleurs 0–3) de 384×320 px,
tuiles 16×16 de 64 o ; le pointeur de fond avance de 4 o (une ligne) par pixel
de scroll **à mi-vitesse** et boucle sur 192 lignes. Les tuiles du premier plan
n'écrasent que les plans 0–1 à travers leur masque et posent les plans 2–3 :
un seul passage de rendu donne deux couches.

Ce qu'il faut retenir : **réserver des couleurs à chaque couche** (ici 0–3 au
fond, 4–15 au reste, dont 12 communes à tous les niveaux pour vaisseau et HUD,
§8) permet d'écrire des plans entiers sans masque.

## 5. Sprites logiciels

Format Xenon 2 (§6) : en-tête 8 o `[xoff, yoff, W, H]`, puis H+1 lignes de
`p0 p1 p2 p3 mask` (10 o, bandes de 16 px) ; au-delà de 16 px, bandes
contiguës. `mask` à 1 = transparent ; à l'écran
`dest = (dest AND mask_décalé) OR plan_décalé`.

- Xenon 2 **décale au vol** (`ror.l d0`, débordement dans le mot suivant via
  `swap`) : 0 o de pré-décalage, coût CPU par ligne. Exception : les tirs du
  joueur sont **pré-décalés** (16 copies, 1 bit, plan 2 forcé à 1) car il y en
  a beaucoup et ils sont petits (§13.6).
- Règle : pré-décaler ce qui est **petit et nombreux** (tirs, particules),
  décaler au vol ce qui est **gros et rare** (boss), et sur STE confier les
  gros au blitter (`SKEW`, `ENDMASK`, `HOP`/`OP`, cf.
  `st-hardware/references/blitter.md`).
- Le sprite ne mémorise pas ce qu'il écrase : on redessine le décor (ST) ou on
  efface par blitter la zone sale du tampon (STE, quand le décor ne bouge pas
  dans le tampon grâce au scroll matériel : il faut alors sauver/restaurer le
  fond sous chaque sprite, ou garder une copie propre du décor).
- Collision au pixel : 16 copies pré-décalées du masque du vaisseau
  (`$3519C + s×$96`) ANDées avec un masque du décor reconstruit sur 3 mots
  (§11). Les ennemis, eux, n'ont qu'une boîte.

## 6. Objets

Xenon 2 (§10.7) :

- Records de taille fixe (`$62` o), **listes doublement chaînées à
  sentinelle** (suivant `+$12`, précédent `+$0E`), une liste par famille
  (vaisseau+flammes, armes, ennemis, tirs ennemis/explosions, tirs joueur) ;
  liste libre simplement chaînée. Allocation : liste libre, sinon on **vole le
  plus vieil objet non-joueur** (jamais d'échec d'allocation).
- Un objet porte ses routines (`+2` IA, `+6` tracé, `+A` dégâts) : appel
  `jsr (a1)` sans switch.
- Positions en **16.16** (`+$20`, `+$24`) en **coordonnées écran** (le scroll
  ne les modifie pas ; les objets « posés sur le décor » ajoutent eux-mêmes le
  pas de scroll du tick, comme les tirs ennemis via `$CD8`).
- Ordre de passage par tick : vaisseau, ennemis, armes, puis tirs ; un objet
  créé dans le tick bouge dans ce même tick.
- Suppression : poser un type « mort » (`4`), la boucle libère.

## 7. Vagues et trajectoires (données, pas code)

Xenon 2 (§10.2–10.4) :

- Table de vagues de 14 o `[trig, classe, nombre, path, espacement, tir,
  vitesse]` indexée par la **position de scroll** : un événement tombe quand le
  scroll franchit `trig` (`prev ≤ trig < cur+1`). Pas d'aléatoire, pas de tri.
- Espacement < 100 = délai entre ennemis (serpent), ≥ 100 = décalage x
  (ligne). Tir < 100 = direction aléatoire, ≥ 100 = visé (atan 8 secteurs).
- Trajectoires = **bytecode polaire** : `$0002 cap avel aacc durée` (segment
  droit, arc ou spirale), `$0004 n` (attente), `$0006` (branche aléatoire),
  `$0008` (saut), `$000A x y` (départ), `$0000` (fin). Le moteur avance de
  `vitesse` pas d'1 px par tick avec une table de sinus de 256 entrées.
- Avantage : 87 trajectoires de niveau tiennent en quelques Ko, le level
  design se fait sans recompiler le moteur.

## 8. Collision avec le décor

Xenon 2 (§11) : le décor **ne tue pas, il bloque**. Historique circulaire des
16 dernières positions (scroll, x, y) ; en contact, on rembobine à la dernière
position libre et on retire l'excédent du pas de scroll (le scroll s'arrête
sous un surplomb). À reproduire tel quel : c'est ce qui rend le jeu jouable.

## 9. HUD

Xenon 2 (§12) : bandeau de 8 lignes dans un **tampon séparé**, affiché par
découpe raster (Timer B en mode événement compte les lignes, cf.
`st-hardware/references/mfp.md`) avec sa propre palette. Le score est stocké
**un chiffre par octet** et seuls les chiffres modifiés sont redessinés
(`movep.l` pour écrire 4 plans d'un glyphe 8×8).

## 10. Nombres et maths

- Positions 16.16, caps 8.8 (256 = tour), vitesses en px/tick.
- Pas de `muls`/`divs` dans la boucle chaude (70 cycles et 140+ cycles) :
  tables (sinus, atan 8 secteurs par seuil `tan 22,5°`), décalages.
- Aléatoire : un LFSR ou `$27FE` (Xenon 2), jamais l'XBIOS `Random` en jeu.

## 11. Son

- ST : YM2149 seul ; musique et bruitages sur le VBL ou un timer MFP, samples
  digitaux = timer à 8–12 kHz qui écrit le volume (très coûteux).
- STE : **DMA son** pour les bruitages (aucun coût CPU, 6–50 kHz 8 bits
  signés), YM pour la musique ; fin de sample par interruption GPI7/Timer A en
  mode événement. Détail : `st-hardware/references/dma-sound.md`.

## Checklist de conception

- [ ] Tick = N VBL choisi sur le pire cas mesuré (skill `st-dev-loop`, §mesure).
- [ ] Vitesses exprimées en px/tick, 50 et 60 Hz traités.
- [ ] Couleurs réparties par couche (fond 2 plans / avant-plan / sprites / HUD).
- [ ] STE : scroll matériel au lieu de recopie ; blitter pour gros sprites.
- [ ] Objets : listes à sentinelle, allocation qui ne peut pas échouer.
- [ ] Vagues et trajectoires en données, déclenchées par le scroll.
- [ ] Sauvegarde/restauration complète du matériel à la sortie (`st-dev-loop`).
