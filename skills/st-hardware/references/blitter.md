# Blitter (STE, Mega ST, Mega STE)

Sources : `src/blitter.c` (modèle cycle-exact, en partie d'après le Verilog
d'Ijor), `src/ioMemTabSTE.c:168-198`, `tests/blitter/`.

Absent du ST de base : un accès à `$FF8A00` y lève un bus error → détecter
(cookie `_MCH`, ou vecteur `$08` dérouté le temps d'un `tst.w $ffff8a00.w`).

## Registres (`$FFFF8Axx`, accès `.w`/`.l` ; les accès `.b` aux registres mot sont ignorés)

| Adresse | Registre | Notes |
|---|---|---|
| `$8A00–$8A1E` | 16 mots de demi-teinte (halftone) | motif indexé par la ligne |
| `$8A20` / `$8A22` | incrément X / Y source | signés, pairs |
| `$8A24` | adresse source (long, paire) | avance pendant le blit |
| `$8A28` / `$8A2A` / `$8A2C` | masques de bord 1 / 2 / 3 | 1 = 1er mot (et ligne d'un seul mot), 2 = milieu, 3 = dernier |
| `$8A2E` / `$8A30` | incrément X / Y destination | |
| `$8A32` | adresse destination (long) | |
| `$8A36` | X count (mots par ligne) | 0 = 65536 |
| `$8A38` | Y count (lignes) | 0 = 65536 ; décompté pendant le blit |
| `$8A3A` | HOP (2 bits) | 0 = tout à 1, 1 = demi-teinte, 2 = source, 3 = source AND demi-teinte |
| `$8A3B` | OP (4 bits) | opération logique source/destination (table ci-dessous) |
| `$8A3C` | contrôle | bit 7 BUSY (écrire 1 = démarrer), bit 6 HOG, bit 5 SMUDGE, bits 0-3 n° de ligne halftone |
| `$8A3D` | skew | bit 7 FXSR, bit 6 NFSR, bits 0-3 décalage à droite |

Source : blitter.c:128-152, 788-795, 972-986, 1229-1241, 1393-1424, 1489-1494.

Opérations (`OP`, S = source après HOP/skew, D = destination) :
0 = 0, 1 = S∧D, 2 = S∧¬D, 3 = S, 4 = ¬S∧D, 5 = D, 6 = S⊕D, 7 = S∨D,
8 = ¬S∧¬D, 9 = ¬S⊕D, 10 = ¬D, 11 = S∨¬D, 12 = ¬S, 13 = ¬S∨D, 14 = ¬S∨¬D,
15 = 1.

- **FXSR** (Force eXtra Source Read) : lit un mot source de plus en début de
  ligne (nécessaire quand le décalage fait entrer des bits du mot précédent).
- **NFSR** (No Final Source Read) : ne lit pas le dernier mot source (évite de
  lire au-delà de la ligne). `xcount = 1` avec NFSR donne des résultats
  « bizarres » (modélisés, blitter.c:94-98, 720-739) : éviter.
- Les incréments Y s'appliquent **après le dernier mot** d'une ligne, à la
  place de l'incrément X : `incY = pas_de_ligne − (xcount−1)×incX`.

## Coûts et partage du bus

- **4 cycles par mot lu, 4 par mot écrit** (blitter.c:254-255). Copie :
  8 cycles/mot = 4 cycles/o, comme `movem.l`. Avec destination lue (OP qui
  utilise D) : 12 cycles/mot. Le gain vient de ce que **masque, décalage et
  opération sont gratuits**.
- Démarrage : après l'écriture de BUSY, le CPU exécute encore ~4 cycles, puis
  4 cycles d'arbitrage : le blitter a le bus à t+8 ; rendre le bus au CPU
  coûte 4 cycles (blitter.c:31-39, 398-414, 1455).
- Instruction suivant le démarrage (blitter.c:44-55) :
  `move.b d0,(a0) ; nop` → le `nop` s'exécute **après** le blit ;
  `bset #7,(a0) ; nop` → **avant** ; `bset #7,(a0) ; mulu` → en parallèle.
- **Non-HOG** (bit 6 = 0) : le blitter et le CPU alternent **64 accès bus**
  chacun (parfois 63 pour le blitter, bug de comptage reproduit)
  (blitter.c:59-73, 251-252). Les interruptions restent servies.
- **HOG** (bit 6 = 1) : le blitter garde le bus jusqu'à la fin ; ni CPU ni DMA
  (blitter.c:1400-1401). Plus rapide, mais **retarde les interruptions**
  (rasters Timer B, HBL) : couper HOG pendant les zones à raster.
- **Pause / reprise** : écrire 0 dans BUSY pendant que le CPU a le bus met en
  pause (BUSY reste lu à 1) ; réécrire 1 relance (blitter.c:88-92,
  1474-1484). Patron classique non-HOG :
  ```asm
  .restart: bset.b  #7,$ffff8a3c.w   ; relance (ou démarre)
            nop
            bne.s   .restart          ; Z=0 si BUSY était encore à 1
  ```
  (le `bset` teste l'ancien bit : tant qu'il était à 1, le blit n'est pas fini).
- Fin de blit : ligne **GPIP3** du MFP → interruption n° 3, vecteur `$10C`
  si IERB bit 3 (blitter.c:895, 916) *(vecteur déduit)*.

## Recettes

**Effacer une zone** (n lignes de 16×k px, 4 plans) :
HOP = 0, OP = 0, destination = coin, incX dest = 2, xcount = 4k,
incY dest = 160 − (4k−1)×2, ycount = n, masques `$FFFF`.

Vérifié sous Hatari STE (`bench/hwtest.s`, H5) : zone 32 px × 4 lignes
effacée, voisinage intact, `ycount` relu à 0 après le blit, boucle
`bset #7` / `bne` terminée.

**Effacer un seul plan** (ex. plan 2 de tout l'écran) : incX dest = 8,
xcount = 20, incY dest = 8, ycount = 200, OP = 0.

**Sprite masqué 4 plans au pixel près** (passes séparées, technique standard) :
1. Masque : source = masque 1 plan (mot par groupe de 16 px), OP = 1
   (S∧D, masque à 1 = transparent) ou OP = 4 (¬S∧D si masque à 1 = opaque),
   **4 passes** (une par plan : dest + 0/2/4/6, incX dest = 8), skew = x & 15,
   FXSR/NFSR selon le décalage, masques de bord = bords du sprite.
2. Plans : source = données du sprite (plan par plan), OP = 7 (S∨D),
   4 passes.
Coût ≈ 8 passes de (lecture S + lecture D + écriture D).
**Vérifié au pixel** sous Hatari STE (`bench/blitspr.s` + `blitspr_check.py`,
x = 37, 48, 5, 300) avec cette mise en œuvre : source large d'un mot de plus
que le sprite (2e mot à 0, masque `$FFFF`) pour absorber le décalage,
FXSR = NFSR = 0, xcount = 2, `endmask1 = $FFFF >> skew`,
`endmask3 = ~endmask1` (0 si skew = 0 : le 2e mot n'est pas touché),
HOP = 2, mode HOG. Mesuré : **~4 730 cycles** pour un 16×16 (mesure NatFeats
comprise), soit ~3 % d'une VBL ; à cette taille, un sprite CPU pré-décalé
est du même ordre : le blitter devient intéressant pour les gros sprites.

**Remplir le playfield depuis des tuiles** : une passe par tuile (xcount = 4,
ycount = 16, incX = 2, incY = 160−6) ; regrouper les tuiles contiguës d'une
même rangée source pour réduire le nombre de passes.

**Écrire des registres matériels au blitter** : destination = registre
couleur, incX/incY dest = 0, source = table : sert aux rasters/plasmas STE
(une couleur toutes les 8 cycles, plus rapide que le CPU).

## Pièges

- **Réécrire ycount avant chaque blit** (il est décompté et vaut 0 à la fin)
  ainsi que les adresses source/destination (elles ont avancé) ; les autres
  registres sont conservés et peuvent rester en place entre deux blits.
- Les incréments sont des **octets** (pairs), pas des mots.
- Le blitter ne voit que la RAM physique et l'I/O, comme le CPU.
- Sous Hatari, `info blitter` affiche tous les registres (débogueur).
