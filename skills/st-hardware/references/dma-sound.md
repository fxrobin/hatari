# Son DMA STE et Microwire (LMC1992)

Sources : `src/dmaSnd.c`, `src/includes/dmaSnd.h`, `src/ioMemTabSTE.c:142-165`.

## Registres DMA (`$FFFF89xx`)

| Adresse | Rôle |
|---|---|
| `$8900.w` | contrôle : bit 0 PLAY, bit 1 LOOP (écriture masquée `&3`) |
| `$8903/05/07` | début de trame (octets haut/milieu/bas) |
| `$8909/0B/0D` | compteur (lecture ; rend le début quand arrêté) |
| `$890F/11/13` | fin de trame (bit 0 forcé à 0) — **adresse exclusive** |
| `$8921` | mode : bits 0-1 fréquence (0 = 6258, 1 = 12517, 2 = 25033, 3 = 50066 Hz), bit 7 MONO |

Source : dmaSnd.c:23-38, 216-218, 463-464, 750-762, 804 ; dmaSnd.h:11-13.

- Échantillons **8 bits signés** (−128..127). Stéréo = octets G/D alternés.
- Début/fin sont **verrouillés** au passage PLAY 0→1 et à chaque rebouclage
  (dmaSnd.c:461-479) : on peut donc écrire le buffer suivant **pendant** la
  lecture du courant (double buffer sans trou).
- Début = fin et LOOP = 0 → arrêt immédiat sans interruption ; avec LOOP,
  lit 2^24 octets (dmaSnd.c:338-342, 455-459).
- FIFO de 8 octets remplie par mots à chaque HBL, hors affichage
  (dmaSnd.c:17-19, 714-740) : le DMA son **vole un peu de bus** en bordure,
  sans impact sur le CPU en pratique.

## Fin de trame et interruptions

Le signal de fin de trame (XSINT) est câblé sur **GPIP7** et sur l'entrée de
**Timer A** (dmaSnd.c:293-310) :

- Timer A en mode événement (`$FFFA19` = 8), `$FFFA1F` = 1, vecteur `$134` :
  une interruption **par fin de buffer** (patron courant pour enchaîner des
  buffers de mixage logiciel).
- Ou GPIP7 (interruption 15, vecteur `$13C`) — partagé avec la détection
  moniteur mono sur ST.
- Sur les STE tardifs, le signal vers Timer A est retardé de 32 cycles (non
  émulé, dmaSnd.c:488-496).

Bruitage simple (sans interruption), octet par octet pour ne toucher que les
registres voulus :
```asm
        lea     $ffff8900.w,a0
        clr.b   1(a0)                 ; $8901 : stop
        move.l  #snd,d0
        move.b  d0,7(a0)              ; $8907 : début, octet bas
        lsr.w   #8,d0
        move.b  d0,5(a0)              ; $8905 : milieu
        swap    d0
        move.b  d0,3(a0)              ; $8903 : haut
        move.l  #snd_end,d0
        move.b  d0,$13(a0)            ; $8913 : fin, octet bas
        lsr.w   #8,d0
        move.b  d0,$11(a0)            ; $8911
        swap    d0
        move.b  d0,$f(a0)             ; $890F
        move.b  #%10000001,$21(a0)    ; $8921 : mono, 12517 Hz
        move.b  #1,1(a0)              ; PLAY, sans LOOP
```
Vérifié sous Hatari STE (`bench/hwtest.s`, H6) : début et fin relus
identiques, ~625 octets joués en ~400 000 cycles (12 517 Hz). (Le raccourci `movep.l d0,1(a0)`
écrit aussi l'octet haut de d0 dans `$8901`, le contrôle : à éviter si le son
joue.)

## Microwire / LMC1992 (volume, graves, aigus, mixage YM)

- `$FFFF8922.w` = données, `$FFFF8924.w` = masque. Le mot de commande fait
  11 bits : `10` (adresse du LMC1992) + `CCC` (fonction) + `DDDDDD`
  (dmaSnd.c:1081-1123). Masque usuel `$07FF`, données alignées à droite.
- Transfert : 16 décalages × 8 cycles = **128 cycles (16 µs)** ; une
  écriture pendant un transfert est ignorée (dmaSnd.c:1033-1068, 1195-1206).
  Attendre que le masque relu revienne à sa valeur (`cmp.w #$07FF,$ffff8924.w`)
  avant la commande suivante. Vérifié sous Hatari STE (H4) : juste après
  l'écriture, le masque se relit `$0FFE` (en rotation), puis la boucle sort.

| CCC | Fonction | Valeurs |
|---|---|---|
| 000 | mixage | 00 = DMA seul, 01 = DMA + YM |
| 001 | graves (4 bits) | 0 = −12 dB, 6 = 0 dB, 12 = +12 dB |
| 010 | aigus (4 bits) | idem |
| 011 | volume général (6 bits) | 0 = −80 dB, 20 = −40 dB, 40 (`%101000`) = 0 dB |
| 100 | volume droit (5 bits) | 0 = −40 dB, 20 = 0 dB |
| 101 | volume gauche (5 bits) | idem |

Exemple : volume général à 0 dB = `%10 011 101000` = `$04E8`.

```asm
mw_send:                        ; d0.w = commande 11 bits
        move.w  #$07ff,$ffff8924.w
        move.w  d0,$ffff8922.w
.wait:  cmp.w   #$07ff,$ffff8924.w
        bne.s   .wait
        rts
```

Les réglages LMC1992 **survivent à la sortie du programme** : les remettre
(mixage DMA+YM, volumes 0 dB, tonalité neutre) avant de quitter.

## Conseils jeu

- Bruitages : un canal DMA mono à 12,5 kHz ; pour mixer plusieurs bruitages,
  mixage logiciel par buffer (Timer A événement), coût CPU proportionnel au
  nombre de voix × fréquence.
- Musique : garder le YM (player sur VBL) et activer le mixage DMA + YM
  (commande Microwire `%10 000 000001` = `$0401`).
