---
name: st-asm-optimize
description: Use when a 68000 routine for Atari ST/STE is too slow or must fit a raster/VBL budget — sprite and tile blitting, screen clears and copies, inner loops, interrupt handlers. Gives the ST-specific cycle rules (4-cycle rounding, pairing, wait states), the classic speed techniques (unrolling, movem, generated code, preshift vs runtime shift, lookup tables, register allocation) and how to prove the gain under Hatari.
---

# Optimiser du 68000 pour ST/STE

Toujours : **mesurer avant, mesurer après** (skill `st-dev-loop` §5-6), sur
la même scène. Coûts de référence : `st-hardware/references/cpu-timing.md`.

## 1. Règles de comptage propres au ST

- Chaque instruction arrondie au **multiple de 4** : une instruction de
  4n+2 cycles gaspille 2 cycles… sauf si elle est **appariée** à la suivante
  (liste des paires dans `cpu-timing.md`). Ex. `lsl.w #2,d0` (10 → 12) suivi
  de `move.w 0(a0,d0.w),d1` : la paire coûte 4 de moins.
- `d8(An,Xn)` coûte 2 de plus que la doc Motorola, mais s'apparie.
- Accès matériel : MFP +4, YM +4, ACIA +6, Shifter sur 4n. Grouper les
  écritures matériel, utiliser `movem.l`/`movep` (une seule pénalité YM par
  instruction).
- Absolu court (`$ffff8240.w`, variables en RAM basse `< $8000`) : −4 cycles
  par rapport à l'absolu long. Le PC-relatif (`var(pc)`) en lecture coûte
  comme `d16(An)` et ne demande pas de registre.

## 2. Techniques, de la plus rentable à la plus pointue

1. **Choisir le bon algorithme / ne pas faire le travail** : STE scroll
   matériel au lieu de redessiner (skill `st-game-engine` §3) ; ne redessiner
   que ce qui change (Xenon 2 ne réécrit que les chiffres du score modifiés,
   §12).
2. **Dérouler** les boucles internes (`rept` / générateur) : `dbra` coûte 12
   par tour ; une boucle de 16 lignes déroulée économise ~190 cycles par
   tuile (Xenon 2 déroule son rendu de tuiles avec des offsets écran en dur,
   findings §4).
3. **`movem.l`** pour les gros transferts : 8 cycles par long en écriture
   (`8+8n`), lecture `12+8n`. Effacer l'écran : 14 registres à zéro,
   `movem.l d0-d7/a0-a5,-(a6)` (56 o, 120 cycles) ≈ 69 000 cycles pour 32 Ko.
4. **Tables** au lieu de calculs : `mulu` jusqu'à 70 cycles, `divu` 140.
   `y×160` → table de 200 mots (ou `lsl`+`add` : `y×160 = (y<<7)+(y<<5)`).
   Sinus 256 entrées (Xenon 2 `$9C80`), atan par secteurs.
5. **Pré-décalage** des graphismes (16 copies, ×16 la mémoire) contre
   **décalage au vol** (`ror.l`/`lsr.l` : 8+2n cycles par long). Xenon 2 :
   au vol pour les sprites (mémoire), pré-décalé pour les tirs nombreux
   (findings §6, §13.6). Compromis : 8 copies décalées de 2 px + un
   `ror.l #1` conditionnel, ou 4 copies (décalage max 3).
6. **Code généré / sprites compilés** : une routine par sprite (et par
   décalage) qui contient les données en immédiat (`move.l #$xxxxyyyy,d16(a0)`,
   `and.l #masque,d16(a0)` + `or.l`) et saute les zones transparentes. Le plus
   rapide pour des sprites fixes, au prix de la mémoire. Les mots entièrement
   opaques deviennent un simple `move`, les transparents disparaissent.
7. **Plans séparés** : réserver des plans par couche (Xenon 2 : fond en
   plans 0-1, avant-plan en 2-3) supprime des masques ; un sprite qui n'utilise
   que 2 plans s'écrit en `move.l` sur un plan pair.
8. **Registres** : toutes les constantes d'une boucle chaude en registres
   (`moveq` hors boucle), pointeurs en `An` avec `(An)+`. Sauvegarde par
   `movem.l` à l'entrée seulement des registres réellement modifiés.
9. **Interruptions** : handlers courts, registres **réservés** (ex. `a6`/`d7`
   jamais utilisés par le jeu → pas de `movem` dans le handler Timer B) ;
   `rte` direct. Une interruption coûte au minimum 56 + 20 (`rte`) cycles.
10. **Blitter (STE)** pour tout ce qui combine décalage + masque + opération
    sur de grandes surfaces ; le CPU prépare le blit suivant pendant le blit
    en cours (mode non-HOG, ou partage de 64 accès).

## 3. Idiomes rapides

```asm
        moveq   #0,d0                 ; 4   (clr.l d0 = 8)
        move.l  d0,(a0)+              ; 12  (clr.l (a0)+ = 20)
        add.w   d0,d0                 ; 4   (lsl.w #1 = 8)
        lea     160(a0),a0            ; 8   (add.w #160,a0 = 12)
        swap    d0 / clr.w d0 / swap d0 ; isoler le mot haut
        subq.w  #1,d7 / bpl.s loop    ; = dbra mais à la fin, sans le -1
        jmp     .tab(pc,d0.w)         ; switch : table de bra.w (d0 = n*4)
        move.l  #$RR00VV00,$ffff8800.w; registre YM + valeur en une écriture
```

- Branche probable **non prise** (8 cycles) plutôt que prise (12).
- Une variable lue dans une boucle → registre ; écrite une fois en fin.
- Dans un raster exact au cycle, remplacer les branches par des chemins de
  même durée (compléter avec `nop`, `exg` × n, ou `lsl d0,d0` à durée
  variable 6+2n).

## 4. Prouver le gain

- Banc Hatari : `NF_CYCLES` avant/après la routine (skill `st-dev-loop` §5),
  plusieurs appels, min/moyenne/max ; même entrée à chaque essai.
- Profileur : `profile on` … `profile cycles` donne les adresses les plus
  coûteuses ; `profile loops` détecte les boucles.
- Bande de couleur en bordure : visible à l'œil, utile en démo/jeu sur
  vraie machine.
- Écrire le résultat (avant/après, en cycles et en % de VBL) dans le message
  de commit ou le journal du projet.

## 5. Pièges

- Adresse impaire en `.w`/`.l` = **address error** (bombe) : aligner
  données et pointeurs (`even`).
- `movem` vers la mémoire écrit les registres dans l'ordre inverse en
  `-(An)` ; en lecture `(An)+` étend le signe des `.w` dans les registres.
- `dbra` teste sur **16 bits** : `d7` = −1 ou 65535 tours si mal initialisé.
- L'assembleur peut optimiser derrière votre dos (vasm : `-no-opt` ou
  options `-opt-*`) : vérifier au désassemblage pour du code à timing exact.
- Optimiser une routine qui n'est pas dans le pire cas mesuré ne change pas
  la cadence du jeu (tick = N VBL, skill `st-game-engine` §1).
