# Timing CPU 68000 sur ST/STE

Sources : `src/includes/m68000.h` (commentaires détaillés et mesures),
`src/m68000.c` (table de pairing), `src/cpu/` (cœur WinUAE cycle-exact).

## Règles propres au ST

1. **Arrondi à 4** : le GLUE/MMU n'accorde le bus qu'aux cycles 4n ; chaque
   instruction est arrondie au multiple de 4 supérieur
   (`(cycles+3)&~3`, m68000.h:231-236). `exg` (6) coûte 8, `lsl.w #1` (8) 8,
   `lsl.w #2` (10) 12.
2. **Pairing** : deux instructions consécutives de 4n+2 cycles, ou une
   4n+2 suivie d'une instruction en `d8(An,Xn)`, économisent 4 cycles
   (m68000.h:244-329). Paires reconnues (m68000.c:211-264) :
   - `exg` → `dbcc`, `move`, `movea`
   - `cmp`/`cmpa` → `bcc` ; `btst` → `bcc`
   - décalage/rotation (`asl asr lsl lsr rol ror roxl roxr`) → `dbcc move
     movea lea jmp add sub or and eor not clr neg addx subx abcd sbcd`
   - `mulu`/`muls` → `movea move divu divs jsr`
   - `add`/`sub` → `move` ; `abcd`/`sbcd` → `dbcc`
   Mesures : `lsl.w #4,d1 ; move.w 0(a4,d2.w),d1` = 28 ;
   `add.l (a5,d1.w),d0 ; move.b 7(a5,d1.w),d5` = 36.
3. **`d8(An,Xn)`** : +2 cycles sur ST (14 → 16 pour `move.w 0(a4,d2.w),d1`)
   (m68000.h:264-268).
4. **Interruptions** : 56 cycles sur ST (44 dans la doc Motorola) ; HBL/VBL
   (autovecteurs) se synchronisent sur l'E-clock (CPU/10) : **+0 à 8 cycles
   de gigue** (motif 0 8 6 4 2) ; vecteur MFP non autovectorisé
   (m68000.h:142-197, m68000.c:803-823). Une routine de raster qui doit être
   exacte au cycle se resynchronise elle-même (compteur vidéo + `lsl`).
5. **Wait states périphériques** : MFP +4, YM +4 (premier accès), ACIA +6
   (+0–8), Shifter couleurs/résolution/hscroll alignés sur 4n
   (m68000.c:825-856).

## Coûts courants (cycles ST, déjà arrondis)

| Instruction | Cycles | | Instruction | Cycles |
|---|---|---|---|---|
| `moveq #n,Dn` | 4 | | `move.w Dn,Dn` / `.l` | 4 |
| `move.w (An),Dn` | 8 | | `move.l (An),Dn` | 12 |
| `move.w Dn,(An)+` | 8 | | `move.l Dn,(An)+` | 12 |
| `move.w (An)+,(An)+` | 12 | | `move.l (An)+,(An)+` | 20 |
| `move.w d16(An),Dn` | 12 | | `move.l d16(An),Dn` | 16 |
| `move.w d8(An,Xn),Dn` | 16 | | `move.w abs.w,Dn` | 12 |
| `move.w #imm,(An)` | 12 | | `move.l #imm,(An)` | 20 |
| `clr.w Dn` | 4 | | `clr.l Dn` | 8 (`moveq #0` = 4) |
| `clr.w (An)` | 12 | | `clr.l (An)` | 20 (`move.l Dn,(An)` = 12) |
| `add.w Dn,Dn` | 4 | | `add.l Dn,Dn` | 8 |
| `addq.w #n,Dn` | 4 | | `addq.l #n,Dn` / `An` | 8 |
| `add.w #imm,Dn` | 8 | | `add.l #imm,Dn` | 16 |
| `lea d16(An),An` | 8 | | `lea d8(An,Xn),An` | 12 (+pairing) |
| `and.w (An),Dn` | 8 | | `or.w Dn,(An)` | 12 |
| `or.l Dn,(An)+` | 20 | | `not.w Dn` / `not.l` | 4 / 8 |
| `lsl.w #n,Dn` | 6+2n → arrondi | | `lsl.l #n,Dn` | 8+2n → arrondi |
| `swap Dn` / `ext.w` | 4 | | `exg` | 8 (6 + pairing) |
| `mulu` | 38+2·(bits à 1), ≤ 70 | | `muls` | ≤ 70 |
| `divu` | ≤ 140 | | `divs` | ≤ 158 |
| `dbra` pris / épuisé | 12 / 16 | | `bra.s` / `bcc.s` pris | 12 |
| `bcc.s` non pris | 8 | | `bcc.w` non pris | 12 |
| `bsr` / `jsr abs.l` | 20 | | `rts` | 16 |
| `jmp (An)` | 8 | | `jmp d8(PC,Xn)` | 16 |
| `movem.l (An)+,n regs` | 12+8n | | `movem.l n regs,(An)` / `-(An)` | 8+8n |
| `movem.l n regs,d16(An)` | 12+8n | | `movem.w (An)+,n regs` | 12+4n |
| `movep.w` / `movep.l` | 16 / 24 | | `rte` | 20 |

Valeurs du manuel Motorola arrondies selon la règle 1. **Mesurées sous
Hatari le 2026-10-30** (`--machine ste` et `st`, résultats identiques) par
`skills/st-dev-loop/bench/cycbench.s` (64 copies déroulées, `NF_CYCLES`) :
`nop` 4, `moveq` 4, `clr.l d0` 8, `exg` 8, `lsl.w #1` 8, `lsl.w #2` 12,
`move.w 0(a4,d2.w),d1` 16, paire `lsl.w #4` + `d8(An,Xn)` 28, `exg`+`exg` 16
(pas de pairing entre eux), `move.l (a0)+,(a1)+` 20, `move.l d0,(a0)+` 12,
`clr.l (a0)+` 20, `lea 160(a0),a0` 8, `add.w #160,a0` 12,
`movem.l d0-d7/a0-a5,-(a6)` 120, `movem.l (a0),d0-d7` 76,
`movem.l d0-d7,(a1)` 72, `mulu` 40 (multiplicateur 0) à 72 (`$FFFF`),
`bra.w` pris 12, `bne.s` non pris 8, `dbra` 12/16, `bsr`+`rts` 36,
écriture palette `.w` 12 (`.l` 16), lecture MFP 16, écriture YM 16,
`movep.w` YM 20, lecture ACIA 20 (12 + 6 + synchro E-clock). Pour toute autre
instruction : ajouter une ligne au banc et relancer.

## Ordres de grandeur utiles

- Une ligne de balayage = 512 cycles = 128 `nop`.
- Effacer 32 000 o par `movem.l d0-d7/a0-a5,-(a6)` (14 registres, 56 o) :
  ~572 × 120 ≈ **69 000 cycles** (43 % d'une VBL PAL).
- Copier 32 000 o par `movem.l` 13 registres (lecture + écriture `d16(An)`) :
  ~615 × 232 ≈ **143 000 cycles** (89 % d'une VBL).
- 16 couleurs par `movem.l pal,d0-d7 ; movem.l d0-d7,$ffff8240.w` :
  76 + 76 = **152 cycles** (mesuré, source `(An)`).
