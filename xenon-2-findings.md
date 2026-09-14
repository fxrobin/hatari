# Xenon 2 Megablast (Atari ST) — trouvailles de rétro-ingénierie

Notes stables sur le jeu observé dans Hatari (version TOSEC originale, 2 disquettes,
`resources/Xenon-2-Megablast_Atari-ST_EN.zip`, images `Disk 1 of 2 [!]` et
`Disk 2 of 2 [!]`, format STX). Machine émulée : ST 1 Mo, EmuTOS. Toutes les adresses
sont celles observées pendant le niveau 1 ; elles peuvent varier d'un niveau à l'autre
quand c'est signalé.

Mettre ce fichier à jour à chaque trouvaille stable (voir `CLAUDE.md`).

## 1. Boot et lancement (recette Hatari MCP)

| Étape | Appel MCP | Remarque |
|---|---|---|
| Boot disque 1 | `boot_disk` (3000 trames) | écran-titre noir et blanc à ~60 s |
| Sauter l'intro | `press_joystick fire` puis `run_frames 1500` | « INSERT OTHER DISK » vers 90 s (3 min sans fire) |
| Disque 2 | `mount_disk` disque 2 puis `run_frames 1500` | titre couleur puis crédits |
| Menu SELECT | `press_joystick fire` puis `run_frames 300` | 1 PLAYER GAME / 2 PLAYER GAME / MUSIC ON ; menu silencieux |
| Niveau 1 | `press_joystick fire` puis `run_frames 3600` | LOADING LEVEL 1 (~60 s en STX) puis GET READY PLAYER 1 |
| Jeu | `set_joystick` + `run_frames` | fire tenu tire ; **le (re)démarrage exige un front sur fire** (impulsion), un fire tenu bloque sur GET READY |

- Le jeu et le menu tournent à **60 Hz** sous Hatari (`$FF820A` = 0) : 1490 trames
  émulées = 24,8 s. Les captures vidéo MCP sont recalées par cycles CPU
  (`video_time_scale` ≈ 0,83).
- Joystick sur le port 1 (port jeu), lu par le cœur libretro sur le port libretro 0.
- PC en attente de trame dans la boucle principale : `$00294E`.

## 2. Mémoire : carte générale (niveau 1)

| Zone | Contenu |
|---|---|
| `$0400`–`$0CFF` | variables système et variables du jeu (`$436` = pointeur courant du fond de parallaxe, `$B4C` = tête d'une liste d'objets, `$BAE` = idem, `$CCE` = offset de scroll dans la tilemap en octets, `$CD6` = scroll fin 0–15) |
| `$1000`–`$3FFF` | code du moteur (rendu tuiles `$1E78`–`$2200`, blit sprites `$1150`–`$1264`, gestion objets `$3EA0`–`$3F10`) |
| `$0C000`–`$14000` | police 3D (lettres/chiffres, `$9C00`–`$C800` en plans entrelacés 128 o/tuile) et sprites HUD |
| `$14000`–`$30000` | sprites communs : vaisseau, tirs, explosions, bonus, chiffres, boutique |
| `$30000`, `$38000`, `$40000`, `$48000` | tampons écran (32000 o, 160 o/ligne, triple/quadruple buffering ; `$FF8201/03` vaut `$030000` au moment du dump) |
| `$4F004` | pointeur tilemap (`$56D52`) ; `$4F008` pointeur banque de tuiles (`$59C42`) |
| `$4C000`–`$56D52` | sprites des ennemis du niveau 1 |
| `$56D52`–`$59C34` | tilemap du niveau 1 |
| `$59C34` | copie de la palette (16 mots) |
| `$59C42`–`$63812` | banque de tuiles du niveau 1 |
| `$69800`–`$71000` | fond de parallaxe 2 plans |
| `$8xxxx`–`$9xxxx` | copie/variante des mêmes banques (contenu proche, non identique : probablement l'autre moitié du niveau ou le niveau suivant) |
| `$A0000`+ | vide (machine 1 Mo, RAM utile ≤ `$9FFFF`) |

Palette du niveau 1 (`$FF8240`) :
`0000 0111 0321 0631 0223 0334 0445 0767 0556 0112 0001 0200 0300 0410 0750 0521`.
Les couleurs 0–3 (noir, gris sombre, brun, orange) sont celles du fond 2 plans.

## 3. Tilemap

- 20 mots (big-endian) par rangée = 320 px, 300 rangées = 4800 px pour le niveau 1.
- Adresse de base `$56D52` (pointeur en `$4F004`). Le scroll courant est
  `base + ($CCE)` ; `$CCE` est un multiple de 40 (une rangée). Le niveau commence en bas
  (grandes adresses) et défile vers les petites adresses.
- Valeur d'une cellule :
  - `$0000` : rien, le fond de parallaxe est copié tel quel (2 plans, plans 2 et 3 mis à 0).
  - `idx` > 0 : tuile **opaque**, adresse `$59C42 + idx*16`, 128 o.
  - `$8000 | idx` : tuile **masquée** sur le fond, adresse `$59C42 + (idx & $7FFF)*16`, 192 o.
- Les index sont donc en unités de 16 octets ; les tuiles sont un tas contigu de tailles
  128/192 (ex. `$51` → `$5D` = +12 unités = tuile masquée, `$5D` → `$65` = +8 = opaque).
- 176 valeurs distinctes utilisées par la carte, dont 83 masquées.

## 4. Tuiles

- **Opaque** : 16 lignes × (p0, p1, p2, p3) = 8 o/ligne, 128 o. Copie directe par `move.l`.
- **Masquée** : 16 lignes × (m0, m1, p0, p1, p2, p3) = 12 o/ligne, 192 o.
  Rendu (code `$1EDE`/`$2180`) : `plan01 = (fond_plan01 AND m0m1) OR p0p1`, puis
  `plan23 = p2p3`. Les masques valent 1 là où le fond reste visible. Dans la banque
  observée m0 == m1 presque toujours (masque identique pour les deux plans du fond).
- Le rendu par tuile dans le tampon écran est déroulé : offsets `+$98`, `+$138`,
  `+$1D8`… (160 o par ligne), ligne de départ décalée de `15 - $CD6` lignes pour le
  scroll fin.

## 5. Fond de parallaxe

- Bitmap **2 plans**, 24 tuiles de large (384 px) × 20 rangées (320 px).
- Une tuile = 16 lignes × 4 o = 64 o ; une rangée de tuiles = `$600` o ; total `$7800`.
- Emplacement niveau 1 : `$69800`–`$71000`. Pointeur courant en `$436` (A4 de la
  routine de tuiles), qui décroît lentement quand la carte défile (parallaxe) et boucle
  (`-$77C0` après 11 rangées d'écran).
- Le rendu de la carte hors jeu ne peut donc que figer le fond à une position donnée.

## 6. Sprites (vaisseau, ennemis, tirs, HUD)

- Bandes de **16 px** de large, hauteur variable ; une ligne = 5 mots :
  `p0, p1, p2, p3, mask` (10 o). `mask` bit à 1 = **transparent** (le code fait
  `not`, décale, `not` puis `and.w` sur l'écran, puis `or.w` des plans).
- Un sprite plus large qu'une bande = bandes consécutives de même hauteur.
- Décalage horizontal par `ror.l d0` (0–15) sur des longs, le débordement est écrit dans
  le mot suivant (`swap` + second passage à `+8`), routines `$1168` (ror), `$11F4`
  (lsr, cas d0 ≥ 8 : `$1222`).
- Aucune copie pré-décalée en RAM : le décalage est calculé au blit.
- Banques : ennemis niveau 1 `$4C000`–`$56D52` (fish/mâchoires, orbes argentées,
  méduses, petits vaisseaux, spawners avec frames d'ouverture) ; communs
  `$14000`–`$30000` ; HUD/police `$0C000`–`$14000`. Les frames d'une animation sont
  consécutives en mémoire.
- Record sprite : en-tête 8 o `[xoff,yoff,W,H]` + H+1 lignes (`dbra`, `$10A2`).
  Compact (W ≤ 16) : 10 o/ligne (`p0,p1,p2,p3,mask`) ; large (W > 16) : 20 o/ligne
  planes-major, 2 bandes contiguës par plan (variante `$128A`, ex. poissons L1 :
  `[14,31,30,31]` + 32 lignes de 20 o depuis `$51080`). A0 pointe l'EN-TÊTE ;
  `$4F028` → `$546D6` = en-tête, lignes à `$546DE`. Le jeu dessine toujours H+1
  lignes, sans skip : `dest = (dest & mask) | plan` (le plan gagne),
  `mask=0` efface le fond puis OR, `mask=$FFFF` garde le fond (vérifié au
  désassemblage capstone du dump).
- Animations (par classe d'ennemi) : tables `[ptr.l][dur.w]` + `NULL.l`
  (durée en ticks, 0 = fige), ex. `$4F182` (classe 1, L1). Stepper `$34C2`
  (appelé par `$9A40`) : `d0` = ptr absolu → `$16(a0)`, durée → `$1A(a0)` ;
  fin de table : `NULL.l` + vecteur `$E3C` qui reboucle (`$34EA`). Descripteurs
  de classes : `$4F066` + k×26 (le 7e fait 50 o ; position exacte variable selon
  les niveaux — L3/L4 sans signature classe 0, attribution par scan motif).
- Conséquence outillage (`ripall.py`) : une planche PNG **par type** (animation ;
  `h01`–`h10` = handlers numérotés quand les descripteurs sont lisibles, sinon
  `a<adresse>` + `tirs` pour `$4F028`), cases bordées et espacées, frames en
  ordre d'animation. Restes non attribués : chaînes (même hauteur, gap ≤ 30 o,
  ex. boss `x540FE`) et `divers`. Runs absorbant 1–2 lignes inter-records
  resserrés à H+1 (L1 : 114, L2 : 100, L3 : 109, L4 : 86, L5 : 110), clampés à
  leur banque ; records pointés sans run couvrante secourus (ex. tirs L4
  `$59F94`, poissons 32 px). Les décalages de grille de 6–8 o entre sprites
  consécutifs sont normaux (en-têtes intercalés), pas des crops cassés.
- Objets : liste chaînée de records ; `+0` type/flags (0 = libre), `+2` pointeur routine
  de dessin (`jsr (a1)`), `+6` seconde routine, `+$E`/`+$12` chaînage
  (`$3EC4`–`$3EEA` parcourent la liste). Le sprite courant est en A0 dans le blit.

## 7. Outils et méthode

- Dump : `debug_command "savebin <fichier> $0 $100000"` (attention : `s` seul = step).
- Localiser un renderer : watchpoint MCP (`set_watchpoint`) ou
  `b ($409E0).w ! ($409E0).w && pc > $2300` sur un mot du tampon en cours de rendu,
  puis `read_registers` + `disassemble` : les registres donnent directement les bases
  (A1 carte, A2 tuiles, A4 fond, A0 sprite).
- `debug_command "b all"` retire les breakpoints posés via le debugger (le serveur MCP
  ne connaît que les siens).
- Lecture `$FF8800/$FF8802` par le debugger : écho de la dernière écriture, utiliser
  `info ym`.
- Scripts de décodage (Python/Pillow, scratchpad de session `rip/`) : vue « colonnes
  16 px », vue tuiles 128 o, recherche de période par autocorrélation d'octets, test
  de cohérence masque/plans, rendu de la carte identique au moteur (`level.py`).
- Livrables produits le 2026-09-11, régénérés le 2026-09-14 (crops resserrés par
  en-tête, voir §6 ; doublons d'anciennes passes supprimés des dossiers et zips),
  rangés dans `resources/xenon2/` (gitignoré) :
   `level1-assets/` (carte 320×4800, 176 tuiles, fond, planches de sprites par type
   (`sprites_enemies_level_h01…h10/tirs/divers`, cases bordées et espacées),
  CSV de la tilemap, README, dump RAM `ram_dump_1MB.bin`, scripts Python de décodage
  et `x2rec.c` dans `tools/`, plus chaque élément en PNG RGBA individuel :
  `tiles/tile_<mot>.png` (176, suffixe `_masked`, alpha = fond visible) et
   `sprites/{enemies_level,common,hud}/spr_<adresse>_<l>x<h>.png` (111 + 334 + 49)), `xenon2_level1_assets.zip`, images disque
   `xenon2_disk1.stx` / `xenon2_disk2.stx`. Les scripts lisent `./ram.bin` (liens en place
   vers `ram_dump_1MB.bin`).

## 8. Niveaux : variable, table, chargement, saut direct

- Numéro de niveau : mot `$E1A` (1–5), `$E18` = compteur de boucles (repasse à 1 après le 5).
  Le chiffre affiché dans « LOADING LEVEL n » est écrit en `$8C57` (chaîne en `$8C49`).
- Table des niveaux `$784A`, 8 octets par niveau : `+0` long = copie en RAM du niveau
  (0 si absente ; le niveau 1 est gardé en `$80000`), `+4` mot = secteur de départ,
  `+6` mot = offset dans le secteur. **Seule l'entrée du niveau N+1 est remplie à la fin
  du chargement du niveau N** (fichiers séquentiels sur la disquette, cf. `$90BE`–`$90EC`).
  Patcher `$E1A` avant le tout premier chargement fait donc lire le secteur 0 et bloque.
- Chargeur `$8FDE` : copie RAM (`$C400` longs vers `$4F000`) ou lecture disque
  (`$9824` lit les secteurs via le FDC, `$9146` décompresse, fenêtre 4 Ko en `$3FD2B`)
  vers `$4F000`. Le blob niveau fait ~200 Ko et commence par un en-tête :
  `$4F000` = base du fond de parallaxe, `$4F004` = tilemap, `$4F008` = banque de tuiles,
  `$4F01C` = autre pointeur (~`$59C32`).
- Routine « niveau suivant » `$7E60` : libère les listes d'objets, `addq $E1A`
  (retour à 1 après 5), affiche LOADING, charge, relance. **Saut direct depuis le
  debugger** : `set_register sr 2300` (le jeu tourne DANS le handler VBL, vecteur `$70`
  = `$2924` ; avec IPL 4 la routine attend une VBL qui ne vient jamais) puis
  `set_register pc 7E60`, `run_frames 4500` → GET READY du niveau suivant. Aucun
  changement de disquette n'a été nécessaire pour les niveaux 2 à 5 (disque 2 monté).
- Parallaxe (`$702C`–`$707C`) : d3 = long `$4F000` = base ; le pointeur `$436` avance de
  4 octets (une ligne) par pixel de scroll **à mi-vitesse** (`asr #1` + parité), et boucle
  dans `[base, base+$300]` (192 lignes, soit un demi-rang de 24 tuiles : le bitmap est
  conçu pour que +$300 soit un décalage vertical de 192 px). Zone réellement affichée :
  `[base, base+$300+11*$600)` ≈ 12 rangées de tuiles (384×192 px).

### Adresses par niveau (dumps du 2026-09-11, scroll ≈ rangée 283)

| Niv. | Tilemap | Rangées | Banque tuiles | Tuiles utilisées | Base parallaxe | Sprites niveau | Palette |
|---|---|---|---|---|---|---|---|
| 1 | `$56D52`–`$59C34` | 300 | `$59C42`–`$638D2` | 176 | `$6989C` | `$4F000`–`$56D52` (111 : 10 types + tirs + chaînes + divers) | `0000 0111 0321 0631 …` |
| 2 | `$5873E`–`$5B600` | 299 | `$5B60E`–`$6B99E` | 211 | `$6CE6C` | `$4F000`–`$5873E` (130) | `0000 0110 0320 0630 …` |
| 3 | `$60898`–`$6375A` | 299 | `$63768`–`$6CF38` | 182 | `$6EC58` | `$4F000`–`$60898` (181) | `0000 0010 0221 0631 …` |
| 4 | `$61CD8`–`$64B9A` | 299 | `$64BA8`–`$76638` | 351 | `$78248` | `$4F000`–`$61CD8` (188, dont tirs `$59F94` secourus) | `0000 0110 0320 0630 …` |
| 5 | `$5D21A`–`$600DC` | 299 (21 vides en tête) | `$600EA`–`$6C03A` | 160 | `$73C38` | `$4F000`–`$5D21A` (189) | `0000 0101 0202 0303 …` |

Les 12 couleurs hautes de la palette sont identiques dans les 5 niveaux (vaisseau, HUD).
Les sprites communs (`$14000`–`$30000`, 334) et HUD (`$C000`–`$14000`, 49) ne changent pas.

- Outil : `resources/xenon2/ripall.py <dump> <outdir> <palette32hex>` lit les pointeurs
  dans le dump et produit carte, tuiles, fond, sprites (planches par type + PNG RGBA
  individuels), CSV et README. Dumps et sorties : `resources/xenon2/level{1..5}-assets/`,
  archives `xenon2_level{1..5}_assets.zip` et `xenon2_all_levels_assets.zip`.
  Les handlers 1–10 donnent des planches numérotées quand les descripteurs sont lisibles
  (L1 complet, L2/L5 partiels) ; sinon attribution par scan des tables d'animation
  (`a<adresse>`) ; poissons 32 px décodés en planes-major. Reste non attribué : `divers`.
- Limite : le scan ramène encore quelques faux positifs (bandes rayées = runs
  « chanceuses » à travers code/tables, ex. `5F1AE`, `5E9CE`) et 1–3 ptrs d'animation
  non résolus par niveau (lignes heuristiquement incohérentes sans en-tête lisible,
  ex. `52C84`) ; les sprites < 8 lignes hors animations restent invisibles ;
  le fond de la carte statique est une simulation du défilement de parallaxe
  par blocs de 11 rangées.

## 9. Boot secteur (observé lors de la passe Hatari des skills)

- Disque 1 : secteur de boot chargé à `_dskbufp` = `$1004`, somme des 256 mots =
  `$1234`, commence par un `BRA`, lit `$FF8260` directement et appelle `Cconws`.

## 10. Vagues d'ennemis : mécanique complète (niveau 1, moteur commun)

Résumé : **pas de générateur aléatoire de vagues**. Chaque niveau embarque une table
d'événements indexée par la position de scroll ; chaque événement crée N ennemis d'une
classe, enfilés sur une trajectoire décrite par un petit bytecode polaire.

### 10.1 En-tête du blob niveau (`$4F000`), champs utiles ici

| Adresse | Contenu (niveau 1) |
|---|---|
| `$4F010` | `bra.w` vers le **handler de vagues du niveau** (`$4FD94`) |
| `$4F028` | sprite des tirs ennemis (`$546D6`) |
| `$4F030` | table des trajectoires : 87 longs → `$67BF2` |
| `$4F034` / `$4F038` | bornes des données de trajectoires `$67D4E`–`$6926C` (contrôle de validité au spawn) |
| `$4F038` | **table des vagues** `$6926C`, terminée par un mot négatif (`$FFFF` en `$6989A`) |
| `$4F03C` | `0BB8 0258` (3000, 600 : paramètres non identifiés) |

### 10.2 Déclenchement (`$3DC2`, appelé chaque trame par la boucle de jeu `$7AAC`)

- A1 = table des vagues ; d1 = `$CD0` (scroll précédent), d0 = `$CD2` (scroll courant + 1),
  scroll = `$CE6` en pixels (décroît vers 0 en avançant ; niveau 1 part vers 4600).
- Pour **chaque** enregistrement (14 octets) jusqu'au mot négatif : `trig = (a1)` ;
  si `scroll_prev ≤ trig < scroll_cur+1` → l'événement tombe **exactement une fois** quand
  le scroll franchit `trig`. Rien ne marque l'enregistrement : la table est balayée
  entièrement à chaque trame, l'ordre n'a pas d'importance (elle n'est pas triée ;
  deux événements au même `trig` partent dans l'ordre de la table).
- Puis `d0 = (2,a1)` et `jsr $4F010` (code du niveau) avec A1 = l'enregistrement.

Enregistrement de vague (14 octets) :

| Offset | Rôle |
|---|---|
| `+0` | déclencheur : position de scroll en pixels (`rangée = trig/16`) |
| `+2` | **handler** : index dans la table de saut du niveau = classe d'ennemi (0 = classe du moteur `$3ADA`, type `$64`, « spawner ») |
| `+4` | **nombre** d'ennemis (0 → aucun) |
| `+6` | **trajectoire** : index 1..87 dans la table `$4F030` |
| `+8` | **espacement** : `< 100` → délai en unités entre deux ennemis successifs (serpents) ; `≥ 100` → `(val−100)` px de décalage en x, tous créés en même temps (lignes) |
| `+A` | **tir** → `(5E,a0)` : 0 = ne tire pas ; `< 100` = tirs dans une direction aléatoire (0–7), cadence = valeur ; `≥ 100` = tirs **visés sur le joueur** (`$39A4`, atan 8 directions), cadence = valeur−100 |
| `+C` | **vitesse** → `(54,a0)` : unités de déplacement par trame (7 = standard, 2–12 observés) |

Handler de niveau (`$4FD94`) : `jmp table[d0]` ; entrées 1–10 : `lea classe_k,a2 ; jmp $E72`
(→ `$3B18`) ; entrée 0 : `$E6C` → `$3AC0` (classe interne `$3ADA`). Descripteur de classe
(26 octets, `$4F066` + k×26, le 7e fait 50 o) : `+0` type, `+2` points/HP, `+4` routine
IA (`$E1E`→`$9A40`), `+8` routine de tracé (`$E24`→`$10A2`), `+C` routine de dégâts/mort
(`$E2A`→`$6240`), `+10` pointeur d'animation, `+14` banque, `+16`/`+17` drapeaux.

### 10.3 Création (`$3B18`)

Pour i = 0..N−1 : `$2BE4` alloue un objet (liste libre `$42A`, sinon vole le plus vieux
objet non joueur), l'insère en tête de la liste `$B4C` (ennemis), copie le descripteur de
classe (type, routines, animation), puis :

- espacement < 100 : `(28,a0) = −d6` (compte à rebours d'activation, en unités) ;
  ≥ 100 : `x += d6` ; puis `d6 += espacement mod 100`.
- trajectoire : `a3 = table[(6,a1)−1]` ; si le premier mot vaut `$000A`, `(2,a3)`/`(4,a3)`
  = position initiale (x,y ; y négatif = au-dessus de l'écran), la position est
  **relative au scroll** (`(20,a0)`, `(24,a0)` en 16.16), puis `a3 += 6` ; `(4A,a0)` =
  pointeur d'exécution du bytecode, `(46,a0)` = base du path (pour les sauts).
- `(5E,a0)` = tir, `(54,a0)` = vitesse, `(44,a0)` = identifiant de groupe (`$45C`/`$45E`),
  compteur de groupe dans la table `$43C` (8 paires id/compte) : sert à savoir quand une
  vague entière est détruite (bonus).
- Le premier ennemi devient la tête (`a5`), les suivants sont chaînés (`(56,a0)`, `(5A,a0)`)
  : la mort de la tête peut entraîner les autres (`$9BC6`).

### 10.4 Mouvement : bytecode polaire (`$9A40` → `$9AEC`/`$9B66`)

État par objet : position `(20,a0)`/`(24,a0)` en 16.16, cap `(2A,a0)` (angle 8.8, entier
sur 256 = tour complet), vitesse angulaire `(4E,a0)` (8.8), accélération angulaire
`(52,a0)`, durée restante `(28,a0)`, budget `(54,a0)`.

Chaque trame, `d7 = (54,a0)` unités ; tant que `d7 > 0` : y += sin(cap)·1 px, x +=
cos(cap)·1 px (table `$9C80` : sinus sur 256 entrées, amplitude 64, cos = sin(cap+64)),
cap += vitesse angulaire, vitesse angulaire += accélération, durée−−. Quand la durée
passe sous 0 → opcode suivant (`$9B66`, table de saut `$9B74`, opcodes pairs) :

| Opcode | Taille | Effet |
|---|---|---|
| `$0002 angle angvel angacc dur` | 10 | **segment** : cap = angle (0–255), vitesse angulaire = angvel/256, accélération = angacc, pendant `dur` unités. angvel = 0 → ligne droite ; angvel ≠ 0 → arc de cercle ; angacc ≠ 0 → spirale |
| `$0004 n` | 4 | **attente** de n unités (l'ennemi reste immobile) |
| `$0006 o0..o7` | 18 | **branchement aléatoire** : tire un offset parmi 8 (`$27FE` & $E), négatif = retirer ; saut à base+offset |
| `$0008 o` | 4 | **saut** à base+o (boucles) |
| `$000A x y` | 6 | position initiale (traité au spawn, ignoré ensuite) |
| `$0000` | 2 | **fin** : décrémente le compteur de groupe et supprime l'objet (type 4) |

Les 87 trajectoires du niveau 1 se décodent intégralement avec cette grammaire
(`resources/xenon2/level1-assets/waves_level1.md`). Exemple, path 1 : départ (38,−53),
puis 6 segments droits de 74 à 405 unités avec des caps différents = zigzag descendant.

### 10.5 Tir (`$9A50`–`$9AB8`)

Si `(5F,a0)` ≠ 0 : accumulateur 8 bits `(5E,a0) += cadence` à chaque trame, tir sur
débordement (cadence 2 ≈ un tir toutes les 128 trames). Direction : aléatoire sur 8
si cadence < 100, sinon visée sur la position du joueur (`$3923C`/`$39240`). Projectile
créé par `$39FE` avec le sprite `($4F028)`.

### 10.6 Chiffres du niveau 1

113 vagues, 11 classes (10 du niveau + spawner), 87 trajectoires, vitesses 2–12 (7 = standard),
premières vagues : scroll 4560 (rangée 285, 5 ennemis classe 8 en serpent sur path 1),
4384 (7 × classe 7, path 10), 4176 (6 × classe 10, path 3)… Les vagues « classe 0 »
(`$4FE2E`, path 9, 1 unité) posent les spawners fixes ; les vagues en fin de niveau
(scroll 0x1D0 → 0xE0, classe 2, cadence de tir 4–6, visée) forment l'approche du boss.
