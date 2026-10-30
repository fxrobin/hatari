# MFP 68901 : timers et interruptions

Sources : `src/mfp.c` (journal en tête), `src/includes/mfp.h`,
`src/ioMemTabSTE.c:213-237`, `src/includes/m68000.h:84`.

## Généralités

- Horloge des timers : **2,4576 MHz** (mfp.c:205). Prédiviseurs (valeur 1–7
  du registre de contrôle) : ÷4, ÷10, ÷16, ÷50, ÷64, ÷100, ÷200
  (mfp.c:343-353). Valeur **8 = mode événement** (compte les fronts de
  l'entrée) ; 9–15 (largeur d'impulsion) traités comme le mode délai.
- Fréquence d'un timer en mode délai : `2457600 / prédiv / data`. Ex. 50 Hz
  exact impossible ; 200 Hz = ÷64, data 192 (Timer C du TOS).
- Niveau CPU **6** (vecteur fourni par le MFP). Le MFP prend sa base de
  vecteurs dans `$FFFA17` : vecteur = (VR & `$F0`) + n° ; VR = `$40` → `$100`
  (mfp.c:700-708).
- Tout accès à un registre MFP : **+4 cycles** (mfp.c, `M68000_WaitState(4)`).
- Changement de la ligne IRQ vu par le CPU **4 cycles** plus tard ; l'IACK
  a lieu 12 cycles après le début de l'exception et une interruption plus
  prioritaire arrivée entre-temps peut prendre le vecteur (mfp.c:309-318).

## Registres (`$FFFFFAxx`, octets impairs)

| | | | |
|---|---|---|---|
| `$01` GPIP (lignes d'entrée) | `$03` AER (front actif) | `$05` DDR | `$07` IERA / `$09` IERB (autorisation) |
| `$0B` IPRA / `$0D` IPRB (en attente) | `$0F` ISRA / `$11` ISRB (en service) | `$13` IMRA / `$15` IMRB (masque) | `$17` VR |
| `$19` TACR | `$1B` TBCR | `$1D` TCDCR (C bits 4-6, D bits 0-2) | `$1F` TADR, `$21` TBDR, `$23` TCDR, `$25` TDDR |

Registres « A » = interruptions 8–15 (bit = n−8), « B » = 0–7.

## Interruptions (priorité décroissante)

| N° | Source | Vecteur | Registres, bit |
|---|---|---|---|
| 15 | GPIP7 : moniteur mono (ST) / fin de trame DMA son (STE) | `$13C` | A, 7 |
| 14 | GPIP6 : RS232 RI | `$138` | A, 6 |
| 13 | **Timer A** (STE : entrée = fin de trame DMA) | `$134` | A, 5 |
| 12 | réception USART pleine | `$130` | A, 4 |
| 11 | erreur réception | `$12C` | A, 3 |
| 10 | émission vide | `$128` | A, 2 |
| 9 | erreur émission | `$124` | A, 1 |
| 8 | **Timer B** (entrée = DE vidéo → lignes) | `$120` | A, 0 |
| 7 | GPIP5 : FDC/ACSI | `$11C` | B, 7 |
| 6 | GPIP4 : **ACIA** clavier + MIDI | `$118` | B, 6 |
| 5 | Timer C (200 Hz TOS) | `$114` | B, 5 |
| 4 | Timer D (horloge USART) | `$110` | B, 4 |
| 3 | GPIP3 : **fin de blit** | `$10C` | B, 3 |
| 2 | GPIP2 : RS232 CTS | `$108` | B, 2 |
| 1 | GPIP1 : RS232 DCD | `$104` | B, 1 |
| 0 | GPIP0 : imprimante busy | `$100` | B, 0 |

Source : mfp.h:13-30, mfp.c:333-347, 1015-1066 (adresses = `$100` + 4n).

Une interruption n'est servie que si IER **et** IMR ont son bit à 1 ; IER à 0
efface aussi le bit en attente.

## Fin d'interruption

- VR bit 3 = 0 → **automatique** : le bit ISR est effacé à l'IACK ; des
  interruptions MFP de même niveau peuvent s'enchaîner immédiatement.
- VR bit 3 = 1 → **logicielle** : le bit ISR reste à 1 jusqu'à ce que le
  handler fasse `bclr #n,$fffffa0f.w` (A) ou `$fffffa11.w` (B) ; tant qu'il
  est à 1, aucune interruption MFP de priorité **≤** n'est servie
  (mfp.c:849-852, 1001). Le TOS utilise ce mode (`$48`) : un handler qui
  oublie le `bclr` bloque le clavier et Timer C.
- Pour un jeu : mode automatique (`move.b #$40,$fffffa17.w`) sauf besoin
  explicite de priorités imbriquées.

## Timer B : rasters

- En mode événement (`$FFFA1B` = 8), Timer B compte les fins de DE : une
  interruption tous les `TBDR` lignes affichées, **24 cycles après la fin de
  l'affichage** de la ligne (cycle 400 à 50 Hz). AER bit 3 = 1 : au début de
  DE + 24 (video.c:3179-3189, mfp.c:2735-2740).
- Recette (changement de palette ligne 192, HUD de Xenon 2) :
  ```asm
  vbl:    clr.b   $fffffa1b.w           ; stop Timer B
          move.b  #192,$fffffa21.w      ; déclenchement après 192 lignes
          move.b  #8,$fffffa1b.w        ; mode événement
          ...
  timer_b:
          movem.l d0-d7,-(sp)           ; ou mieux : registres réservés
          movem.l hud_pal(pc),d0-d7
          movem.l d0-d7,$ffff8240.w
          movem.l (sp)+,d0-d7
          rte                           ; EOI automatique supposé
  ```
  Le changement arrive en bordure droite/gauche de la ligne suivante : à
  l'écran, la nouvelle palette commence à la ligne 193 (marge de quelques
  dizaines de cycles pour une palette complète).
- Recharger `TBDR` dans la VBL (le compteur se recharge seul à chaque
  passage à 0 avec la valeur du registre de données).
- Pour plusieurs rasters : `TBDR` = 1 et une table de couleurs indexée dans
  le handler, ou reprogrammer `TBDR` dans le handler (la nouvelle valeur ne
  sert qu'au rechargement suivant).

## Timer A / D pour le son

- Samples sur YM : Timer A ou D en mode délai à 8–12 kHz, handler minimal
  (registres dédiés, `move.b (a6)+,…` + `movep`), soit 50–100 cycles × 10 000
  /s ≈ 6–12 % du CPU *(déduit)*.
- STE : Timer A en mode événement, data = 1 → une interruption par fin de
  buffer DMA (voir `dma-sound.md`).

## Sauvegarde/restauration

Sauver au minimum `$07 $09 $13 $15 $17 $19 $1B $1F $21` (+ vecteurs), tout
couper (`IERA/IERB` = 0), installer, restaurer à la sortie dans le même ordre
inverse, IRQ masquées (`$2700`). Modèle : `tests/screen/flixfull.s`
(`init_mfp` / `restore_mfp`).
