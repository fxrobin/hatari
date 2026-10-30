---
name: st-reverse-engineering
description: Use when studying how an existing Atari ST/STE game or demo works under Hatari (MCP server or built-in debugger) — finding its renderer, sprite/tile/tilemap formats, object lists, timing, wave tables, or ripping assets from a RAM dump. Method distilled from the Xenon 2 Megablast investigation, with the debugger and MCP pitfalls met along the way.
---

# Rétro-ingénierie d'un jeu ST sous Hatari

Méthode qui a permis de décoder Xenon 2 (`xenon-2-findings.md`). Elle vaut pour
tout jeu 68000 ST/STE. Les volumes (dumps, désassemblages, séries de
positions) vont **dans des fichiers**, jamais dans la conversation : pour une
question précise, déléguer à l'agent `st-game-analyst`.

## 0. Avant tout

- Les findings existants répondent peut-être déjà (`xenon-2-findings.md`).
- Noter les conditions : image disque exacte (STX/MSA/ST), machine
  (`--machine st|ste`), TOS (EmuTOS ou TOS réel), RAM, **50 ou 60 Hz**. Sous
  Hatari/EmuTOS le défaut est 60 Hz (`$FF820A` = 0) : un jeu PAL y tourne plus
  lentement par tick s'il cale sa logique sur N VBL (Xenon 2 : 4 VBL au lieu de
  3). Pour mesurer la vraie cadence PAL : booter ~200 trames puis
  `write_memory FF820A ["02"]` (si le jeu ne réécrit pas ce registre).

## 1. Booter et atteindre l'état voulu

- MCP : `boot_disk path=… frames=…`, `press_joystick fire`, `run_frames n`,
  `mount_disk` pour la disquette 2. Les menus exigent souvent un **front** sur
  fire (impulsion `press_joystick`), un fire tenu (`set_joystick`) ne passe pas
  « GET READY ».
- Écrire la recette (appel, nombre de trames, ce qu'on voit) dans les
  findings : chaque séance repart de là.
- Trouver la **boucle principale** : PC qui revient en attente de trame
  (`read_registers` à plusieurs instants ; Xenon 2 : `$294E`). Un point d'arrêt
  sur le **début d'itération** donne un état cohérent pour les dumps (Xenon 2 :
  `$7A58`).

## 2. Dump RAM et carte mémoire

- `debug_command "savebin f.bin $0 $100000"` (1 Mo) ou `$80000` (512 Ko).
  Attention : dans le débogueur, `s` seul = *step*, pas *save*.
- Repérer les zones : tampons écran (base dans `$FF8201/03`, `$44E`
  `_v_bas_ad` pour le TOS), code (densité d'opcodes `$4E75` rts, `$4EB9` jsr),
  graphismes (entropie, motifs répétés de période 8/10/12 o), tables de
  pointeurs (longs pairs < taille RAM).
- Comparer deux dumps espacés d'un tick : ce qui change = variables d'état.

## 3. Trouver un renderer

1. Choisir un mot du tampon écran qui change quand l'élément est dessiné.
2. Watchpoint dessus (MCP `set_watchpoint`, ou débogueur :
   `b ($409E0).w ! ($409E0).w && pc > $2300` — condition « la valeur a
   changé », filtrée par PC).
3. À l'arrêt : `read_registers` + `disassemble` autour du PC. Les registres
   d'adresse donnent directement les bases (Xenon 2 : A1 carte, A2 tuiles,
   A4 fond, A0 sprite).
4. Remonter l'appelant (`history`, ou point d'arrêt sur le `rts`).

## 4. Décoder un format graphique

- Écran ST basse résolution : 4 plans entrelacés par mot, 160 o/ligne, un
  groupe de 16 px = 8 o `p0 p1 p2 p3`. Moyenne : 2 plans, 640 px. Haute :
  1 plan, 80 o/ligne.
- Sprites/tuiles : chercher la **période** (autocorrélation d'octets : 8 o =
  4 plans sans masque, 10 o = 4 plans + masque, 12 o = 2 masques + 4 plans),
  puis la hauteur (en-tête `[xo, yo, W, H]` souvent juste avant).
- Vérifier la cohérence masque/plans : un pixel opaque a un masque à 0 ; un
  pixel transparent a tous ses plans à 0 (sinon ce n'est pas un masque, ou il
  est inversé).
- Palette : `$FF8240` (16 mots) ; le jeu en garde souvent une copie par niveau.
  ST = 3 bits/composante (`0RGB`, 0–7) ; STE = 4 bits avec le bit de poids
  faible en bit 3 (`%0321`).
- Valider en **re-rendant** avec un script Python/Pillow identique au code
  désassemblé, et comparer à une capture.

## 5. Structures de jeu

- Listes d'objets : chercher un parcours `move.l $12(a0),a0 / cmp.l a0,a1`.
  Sentinelles = nœuds de type 0 en RAM basse. Décoder tous les champs d'un
  record en suivant **qui les écrit** (watchpoint sur le champ).
- Tables de données (vagues, chemins, animations) : repérer les terminateurs
  (`$FFFF`, `NULL.l`), les tailles d'enregistrement fixes, les index 1-based.
- Bytecode : table de saut indexée par opcode pair (`jmp tab(pc,d0.w)`).

## 6. Mesurer temps et vitesses

- Positions : au moins 2 échantillons espacés d'un nombre connu de ticks, et
  le calcul écrit (px/tick, puis px/s = px/tick × Hz / VBL par tick).
- Cycles : variables du débogueur `CycleCounter`, `FrameCycles`,
  `LineCycles`, `VBL`, `HBL` dans les conditions de points d'arrêt. Pour
  journaliser sans s'arrêter : points d'arrêt `:trace :quiet :file`, ou
  écrire les compteurs en RAM libre depuis un point d'arrêt et dumper.
- `info video`, `info mfp`, `info ym`, `info blitter`, `info dmasnd`,
  `info ikbd` donnent l'état des puces.

## 7. Pièges connus (vus en vrai)

- La commande `logfile` du débogueur **plante le serveur MCP Hatari**.
- Lire `$FF8800/$FF8802` par le débogueur rend l'écho de la dernière écriture :
  utiliser `info ym`.
- `debug_command "b all"` retire les points d'arrêt posés par le débogueur ;
  le serveur MCP ne connaît que les siens (`list_breakpoints`).
- Sauter dans une routine depuis le débogueur alors que le jeu tourne **dans
  le handler VBL** : abaisser l'IPL (`set_register sr 2300`) sinon la routine
  attend une VBL qui ne vient jamais (Xenon 2 §8).
- Patcher une variable « niveau » avant le premier chargement peut casser le
  chargeur (tables remplies au fil des chargements, Xenon 2 §8).
- Les vidéos MCP sont recalées par cycles CPU : à 60 Hz,
  `video_time_scale` ≈ 0,83.

## 8. Consigner

Chaque fait stable va dans le fichier de findings du jeu, avec : adresse,
format, comment on l'a vérifié (dump, trace, re-rendu), et date. Pas les
hypothèses en cours. Les outils de décodage (scripts Python) sont rangés à côté
et cités.
