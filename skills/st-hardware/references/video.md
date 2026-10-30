# Vidéo ST/STE (Shifter + GLUE/MMU)

Sources : `src/video.c` (journal en tête : chaque correction et la démo qui la
justifie), `src/includes/video.h`, `src/clocks_timings.c`, `src/spec512.c`,
`src/convert/conv_st.c`.

## Timings de base

| | 50 Hz | 60 Hz | 71 Hz (mono) | Source |
|---|---|---|---|---|
| Cycles par ligne | 512 (128 `nop`) | 508 (127) | 224 | video.h:11-34 |
| Lignes par trame | 313 = 63 haut + 200 + 47 bas | 263 = 34 + 200 + 26 | 501 | video.h:41-49 |
| Cycles par VBL | 160 256 | 133 604 | 112 224 | video.h:23-25, clocks_timings.c:142 |
| 1re ligne affichée | 63 | 34 | | video.h:53-54 |
| DE (basse rés.) | cycles 56 → 376 | 52 → 372 | | video.h:91-95 |

- CPU STE PAL = 32 084 988 / 4 = **8,021247 MHz** (clocks_timings.c:144).
- 1 cycle CPU = 1 pixel en basse résolution, 2 en moyenne, 4 en haute.
- HBL en fin de ligne ; VBL au cycle 64 de sa ligne (68 sur STE)
  (video.h:119-123).
- Timer B (mode événement) se déclenche **24 cycles après la fin de DE** :
  cycle 400 à 50 Hz ; si AER bit 3 = 1, au début de DE + 24 (video.c:3179-3189).

## Base écran et compteur vidéo

- `$FF8201` (haut), `$FF8203` (milieu), `$FF820D` (bas, **STE seulement** ;
  0 sur ST). Copiés dans le compteur `$FF8205/07/09` à la **ligne 310** (50 Hz)
  ou 260 (60 Hz), cycle 56 (ST) / 60 (STE) (video.h:125-130,
  video.c:4634-4639). → Écrire la base n'importe quand pendant la trame : elle
  sert à la trame suivante.
- **STE : écrire `$FF8201` ou `$FF8203` remet `$FF820D` à 0** (video.c:5100-5117).
  Ordre : haut, milieu, **puis** bas. Sur ST, aligner l'écran sur 256 o.
- Patron (ST) : `lsr.l #8,d0 ; lea $ffff8201.w,a0 ; movep.w d0,0(a0)`
  (`tests/screen/flixfull.s`).
- Compteur `$FF8205/07/09` en lecture : position courante du balayage, avance
  de **1 octet / 2 cycles** pendant DE, figé hors DE (video.c:1560, 1396-1398).
  C'est la référence de phase des routines synchronisées au cycle
  (`move.b $ffff8209.w,d0` en boucle jusqu'à ≠ 0, puis `lsl.w d0,d0` pour
  absorber la gigue — patron de `tests/cycles/cyccheck.s:264-270`).
- **STE** : compteur **inscriptible** (bit 0 forcé à 0, seul l'octet écrit
  change) (video.c:5134-5260). Prise d'effet :
  - avant le début de DE de la ligne (≤ cycle de départ MMU, 16 cycles plus tôt
    si `$FF8265` ≠ 0) → **immédiate** ;
  - pendant DE → décalage ajouté en fin de ligne (« même sur un vrai STE,
    modifier l'adresse vidéo dans ce cas cause des artefacts ») ;
  - bordure droite → en fin de ligne ; hors lignes affichées → immédiate.
  Usage : changer de tampon ou faire du « line doubling » ligne par ligne
  depuis un raster, en écrivant **avant le cycle 56**.

## Scroll matériel STE

| Registre | Effet | Source |
|---|---|---|
| `$FF820F` LINEWIDTH | nombre de **mots** sautés en fin de chaque ligne ; écrit avant la fin de DE → ligne courante, sinon ligne suivante | video.c:5352-5381, 3851-3853, 4394-4398 |
| `$FF8265` HSCROLL (avec prefetch) | décale de 0–15 px vers la gauche ; si ≠ 0 le Shifter lit **168 o** par ligne (+8) et la MMU démarre 16 cycles plus tôt | video.c:5792-5964, 1526-1527 |
| `$FF8264` HSCROLL sans prefetch | lit 160 o, affiche 304 px, commence 16 px plus tard ; rarement utilisé | video.c:5886-5904 |

Prise d'effet de `$FF8264/65` : écrit à `LineCycles ≤ 56` → ligne courante,
sinon fin de ligne (video.c:5920-5940).

**Recette scroll horizontal** dans un monde de `W` octets de large par ligne
(ex. 2 écrans = 320 o) :

```
base   = monde + y*W + (x / 16) * 8          ; 8 o = un groupe de 16 px, 4 plans
fin    = x & 15
$FF8265 = fin
$FF820F = (W - 160 - (fin ? 8 : 0)) / 2      ; en mots     (déduit)
```

Écrire base + `$FF8265` + `$FF820F` pendant la VBL (avant la ligne 1 du haut
d'écran). Changer `$FF820F` selon `fin` à chaque trame est indispensable :
avec le prefetch, la ligne consomme 8 o de plus.

Astuce **336 px** : `$FF8265` ≠ 0 puis `$FF8264` = 0 à ≤ 40 cycles d'écart
(ex. `move.b #X,$ffff8265.w` / `move.b #0,$ffff8264.w`) : 168 o lus, 336 px
affichés, 16 px plus tôt (video.c:5900-5904).

**Scroll vertical** : avancer la base de 160 o (ou W) par ligne. Le compteur
ne reboucle pas : tampon double hauteur, ligne entrante écrite deux fois (cf.
skill `st-game-engine` §3).

## Palette

- `$FF8240–$FF825E`, 16 mots. Masque `$777` (ST), `$FFF` (STE) (video.c:5420-5423).
- STE : chaque quartet = `bit3 | bit2 bit1 bit0` avec **bit 3 = poids faible**
  (ordre `%0321`) : intensité i (0–15) → `((i & 1) << 3) | (i >> 1)`
  (conv_st.c:120-124, video.c:5441-5449). Une palette ST (0–7) reste valable
  sur STE (moitié des niveaux).
- Écriture `.b` dupliquée : `move.b #7,$ffff8240.w` donne `$707`
  (video.c:5389-5397). Toujours écrire en `.w` ou `movem.l`.
- Un changement de couleur après le cycle 460 de la ligne compte pour la
  suivante. Couleurs et résolution n'acceptent les accès qu'aux cycles 4n
  (video.c:5408 ; journal ~l. 372).
- Spec512 : jusqu'à 128 changements de couleur par ligne (un tous les 4 cycles)
  (spec512.c:8-16) — c'est le plafond physique d'un « plasma » CPU.

## Fréquence, résolution, bordures

- `$FF820A` : seul le bit 1 compte (1 = 50 Hz, 0 = 60 Hz) ; bits 2–7 relus à 1
  (video.c:3064, 5267-5271). `$FF8260` : 0 basse, 1 moyenne, 2 haute (3 = haute).
- **Bordure haute** : ligne 33, passer à 60 Hz avant le cycle 504 puis repasser
  à 50 Hz après (STE : 500, « 4 cycles plus tôt ») (video.h:74-79,
  video.c:952, 1031).
- **Bordure basse** : idem ligne 262 (50 Hz) / 233 (60 Hz) ; donne jusqu'à
  47 lignes de plus, dont 2 masquées par VBlank sauf seconde bascule en fin de
  ligne 308 (video.h:63-68).
- Octets par ligne en bordure ouverte : gauche +26 (STE +20 par bascule
  haute/basse rés., « pas de stabilisateur »), droite +44, normale 160
  (video.h:110-115, video.c:371).
- Fin de DE à 460 si bordure droite ouverte (video.h:97).
- ST : scroll matériel « sync » par bascule haute/basse rés. dans la bordure
  gauche aux cycles 20/24/28/32 → décalage 13/9/5/1 px (video.h:101-104) ; sur
  STE, préférer `$FF8265`.
- Exemple complet et testé (overscan plein écran, comptage des lignes) :
  `tests/screen/flixfull.s`, captures de référence `flix_st.png` /
  `flix_ste.png`.

Pour un **jeu**, les bordures ouvertes coûtent une routine synchronisée par
ligne (le CPU est perdu pendant l'affichage) : réservé aux écrans fixes
(titres, HUD). Le scroll matériel STE, lui, est gratuit.
