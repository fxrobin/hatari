---
name: st-hardware
description: Use when writing or reviewing 68000 code that touches Atari ST/STE hardware directly — video Shifter/GLUE (screen base, palette, 50/60 Hz, borders, STE hardware scroll and linewidth), blitter, STE DMA sound and Microwire, MFP timers and interrupts, YM2149, IKBD/ACIA keyboard-joystick-mouse, STE joypads, memory map and system variables, CPU cycle costs and wait states. Every fact is cross-referenced to the Hatari source file and line that models it.
---

# Matériel Atari ST / STE (référence vérifiée dans Hatari)

Hatari vise la **fidélité au cycle** sur les jeux et démos : son code source est
une documentation exécutable du matériel. Chaque fait des fichiers de
`references/` cite `src/<fichier>:<ligne>` (dépôt Hatari, oct. 2026) ; ce qui
est déduit et non lu dans le code est marqué *(déduit)*.

## Quelle référence lire

| Sujet | Fichier |
|---|---|
| Carte mémoire, vecteurs, variables système, bus errors | `references/memory-map.md` |
| Vidéo : timings de ligne/trame, base écran, palette, bordures, **scroll matériel STE** | `references/video.md` |
| Blitter STE : registres, coûts, HOG, recettes sprites/effacement | `references/blitter.md` |
| Son DMA STE + Microwire/LMC1992 | `references/dma-sound.md` |
| MFP 68901 : timers, vecteurs, priorités, fin d'interruption, rasters | `references/mfp.md` |
| YM2149, IKBD/ACIA, joypads STE | `references/io-sound-input.md` |
| CPU : arrondi à 4 cycles, pairing, coût des interruptions, wait states | `references/cpu-timing.md` |

## Les 15 faits qu'on oublie le plus

1. **512 cycles/ligne à 50 Hz, 508 à 60 Hz** ; 313 lignes PAL (160 256
   cycles/VBL), 263 NTSC (133 604). CPU STE PAL = 8,021247 MHz.
2. Le 68000 du ST **arrondit chaque accès bus à 4 cycles** : une instruction de
   10 cycles en coûte 12 (sauf pairing). Compter en multiples de 4.
3. Une interruption coûte **56 cycles** sur ST (pas 44), plus 0–8 cycles de
   gigue E-clock pour HBL/VBL.
4. La base écran `$FF8201/03(/0D)` n'est relue qu'**une fois par trame**
   (ligne 310 à 50 Hz, cycle 56 ST / 60 STE) : on peut l'écrire n'importe quand.
5. STE : écrire `$FF8201` ou `$FF8203` **remet `$FF820D` à 0** → écrire
   l'octet bas en dernier.
6. STE : le compteur vidéo `$FF8205/07/09` est **inscriptible** ; prise
   d'effet immédiate seulement avant le début d'affichage de la ligne, sinon
   en fin de ligne (artefacts sur vrai STE si écrit pendant l'affichage).
7. STE : `$FF8265` ≠ 0 fait lire **8 octets de plus par ligne** (168 au lieu de
   160) → compenser avec `$FF820F` (largeur de ligne en **mots**).
8. Palette STE 4 bits par composante, bit de poids faible **en bit 3**
   (`%0321`) ; une écriture `.b` sur un registre couleur est dupliquée.
9. Timer B en mode événement (`$FFFA1B` = 8) compte les **lignes affichées**
   (fin de DE + 24 cycles) : base de tous les rasters.
10. MFP en fin d'interruption **logicielle** (`$FFFA17` bit 3 = 1) : le
    handler doit effacer son bit ISR (`bclr #n,$FFFA0F/11`), sinon plus
    aucune interruption de priorité ≤.
11. Tout accès MFP coûte +4 cycles, tout accès ACIA +6 (+0–8 E-clock), le
    premier accès YM d'une instruction +4.
12. Le blitter en mode non-HOG alterne **64 accès bus** avec le CPU ; en HOG
    le CPU est bloqué jusqu'à la fin. Fin de blit = GPIP3 du MFP.
13. Fin de trame DMA son → GPIP7 (vecteur `$13C`) **et** entrée Timer A
    (événement) : un Timer A à 1 en mode événement compte les fins de buffer.
14. Accès à `$FF8000+` en mode utilisateur = bus error ; registre non câblé
    sur la machine (ex. blitter sur ST) = bus error : détecter avant d'utiliser.
15. Un octet IKBD = ~1,28 ms (7812,5 bauds) : ne pas attendre le clavier dans
    une boucle à budget serré ; tout passe par l'interruption ACIA `$118`.

## Méthode quand un fait manque

1. Chercher dans `src/` (registre → handler via `src/ioMemTabSTE.c` /
   `ioMemTabST.c`, puis le module : `video.c`, `blitter.c`, `dmaSnd.c`,
   `mfp.c`, `psg.c`, `ikbd.c`, `acia.c`, `joy.c`).
2. Lire le **journal daté** en tête de `video.c` / `mfp.c` / `m68000.c` :
   chaque correction y cite la démo ou le jeu qui l'a motivée (preuve
   matérielle indirecte).
3. Confirmer par une expérience (agent `ste-hardware-verifier`).
4. Ajouter le fait au bon fichier `references/` avec sa source.
