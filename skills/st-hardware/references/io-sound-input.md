# YM2149, IKBD/ACIA, joypads STE

## YM2149 (`src/psg.c`, `src/includes/psg.h`)

- `$FF8800` : écriture = sélection du registre, lecture = donnée du registre
  sélectionné. `$FF8802` : écriture = donnée (lecture = `$FF`). Seul le bit 1
  d'adresse compte : `$FF8804–$FF88FF` sont des miroirs (psg.c:102-109).
  Sélection ≥ 16 : lectures `$FF`, écritures ignorées (psg.c:32-37).
- Écriture classique : `move.b #reg,$ffff8800.w ; move.b #val,$ffff8802.w`.
  En une instruction : `movep.w d0,0(a0)` avec a0 = `$FFFF8800`, d0 =
  `reg<<8 | val` ; `movep.l` écrit deux couples reg/val *(déduit du câblage)*.
  Ou `move.l #$RR00VV00,$ffff8800.w` (registre et valeur d'un coup).
- **Wait state** : +4 cycles au premier accès YM d'une instruction ;
  `move.b` = 12, `movep.w` = 20, `movep.l` = 28, `movem.l d0-d7` = 88
  (psg.c:124-171).
- Registres : 0–5 périodes A/B/C (fin/grossier, grossier `&$0F`), 6 bruit
  (`&$1F`), 7 mixer (bits 0-2 ton A/B/C, 3-5 bruit, actifs à 0 ; bits 6-7
  sens des ports, **laisser bit 6 = 1** = port A en sortie), 8–10 volumes
  (`&$1F`, bit 4 = enveloppe), 11–12 période d'enveloppe, 13 forme, 14 port
  A, 15 port B (psg.h:13-28, psg.c:355-361).
- **Port A** (registre 14) : bit 0 face disquette, bits 1/2 sélection
  lecteur A/B (actifs à 0), 3 RTS, 4 DTR, 5 strobe imprimante, 6 GPO
  (psg.c:399-420). Un jeu qui touche le YM doit **préserver les bits 0-2**
  (lecture-modification-écriture) sinon il coupe le lecteur en plein
  chargement.
- Samples : écrire un volume 4 bits dans 8/9/10 à fréquence fixe (Timer A/D),
  avec une table de conversion 8 bits → triplet de volumes pour plus de
  niveaux *(déduit, technique standard)*.
- Débogueur : `info ym` (la lecture de `$FF8800` par le débogueur rend l'écho
  de la dernière écriture).

## ACIA et IKBD (`src/acia.c`, `src/ikbd.c`)

- `$FFFC00` contrôle (écriture) / état (lecture), `$FFFC02` données clavier ;
  `$FFFC04/06` MIDI (acia.c:69-73).
- État : bit 0 RDRF (octet reçu), bit 1 TDRE (émetteur libre), bit 7 IRQ
  (acia.c:101-120).
- 7812,5 bauds (8N1) : **≈ 1024 cycles par bit, ≈ 10 240 cycles (1,28 ms)
  par octet** (acia.c:126-127, 478-479). MIDI : 31 250 bauds.
- Chaque accès ACIA : +6 cycles + 0–8 de synchro E-clock (acia.c:130-143).
- Interruption : GPIP4 → MFP n° 6, vecteur **`$118`**, **partagée** clavier /
  MIDI : le handler doit tester le bit 7 des deux ACIA (acia.c:44, 370-387).

Commandes IKBD utiles (écrire sur `$FFFC02` quand TDRE = 1) (ikbd.c:226-268) :

| Cmd | Effet |
|---|---|
| `$08` | souris relative (défaut TOS) |
| `$12` | coupe la souris |
| `$14` | joystick en **mode événement** (coupe aussi la souris) |
| `$15` | joystick en mode interrogation |
| `$16` | interroger le joystick (réponse `$FD j0 j1`) |
| `$1A` | coupe les joysticks |
| `$80 $01` | reset |

Paquets reçus : `$F6` état, `$F7` souris absolue, `$F8–$FB` souris relative
(bits 0-1 = boutons) + dx + dy, `$FC` heure, `$FD` interrogation joystick,
**`$FE` joystick 0, `$FF` joystick 1**, suivis d'un octet
(`bit 7` = feu, bits 0-3 = haut/bas/gauche/droite). Tout autre octet < `$F6`
= scancode clavier (bit 7 = relâché).

Handler minimal (jeu, joystick 1 = port de jeu) :
```asm
acia_irq:
        move.w  d0,-(sp)
.next:  btst    #0,$fffffc00.w          ; octet reçu ?
        beq.s   .done
        move.b  $fffffc02.w,d0
        tst.b   joy_state               ; on attend la donnée d'un paquet $FF ?
        bne.s   .joy
        cmp.b   #$ff,d0
        bne.s   .key
        st      joy_state
        bra.s   .next
.joy:   move.b  d0,joy1                 ; bit7 feu, bits 0-3 directions
        sf      joy_state
        bra.s   .next
.key:   move.b  d0,last_key
        bra.s   .next
.done:  move.w  (sp)+,d0
        bclr    #6,$fffffa11.w          ; EOI si MFP en mode logiciel (sans effet en auto)
        rte
```
Ce squelette ignore les paquets souris et `$FE` : après `$12`/`$14` il n'en
arrive plus. Un vrai handler suit une petite machine à états par type de
paquet.

## Joypads STE (`src/joy.c:400-569`, `src/ioMemTabSTE.c:206-209`)

- Écrire un masque de sélection (mot) dans **`$FF9202`** : quartet bas = pad
  A, quartet haut = pad B, un bit à 0 sélectionne une rangée.
- Relire **`$FF9202`** : directions/boutons de la rangée, actifs à 0, pad A
  bits 8-11, pad B bits 12-15.
- Lire **`$FF9200`** : feu/pause actifs à 0, pad A bits 0-1, pad B bits 2-3.
  **Un accès `.b` à `$FF9200` = bus error** (joy.c:412-419, vérifié H3 :
  `.w` passe, `.b` lève le bus error) ; `$FF9201` en `.b` est permis.
- Paddles : `$FF9211/13/15/17`.
- Absents du ST : bus error.
