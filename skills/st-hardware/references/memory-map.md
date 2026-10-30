# Carte mémoire ST/STE

Sources : `src/cpu/memory.c`, `src/ioMem.c`, `src/tos.c`, `src/mfp.c`,
`src/includes/m68000.h`.

## Espaces

| Adresse | Contenu | Source |
|---|---|---|
| `$000000–$000007` | miroir des 8 premiers octets de la ROM, **lecture seule** | cpu/memory.c:671-675, 734 |
| `$000008–$0007FF` | vecteurs + variables système, **superviseur seulement** | cpu/memory.c:671-701 |
| `$000800–phystop` | RAM utilisateur (512 Ko – 4 Mo, ST/STE) | |
| `$A00000–$DFFFFF` | VME (Mega STE seulement) | cpu/memory.c:51-52 |
| `$E00000` | ROM TOS ≥ 1.06 (TOS 2.0x des STE) | tos.c:1135-1136 |
| `$FA0000` | cartouche 128 Ko, sans wait state | cpu/memory.c:1792 |
| `$FC0000` | ROM TOS 1.00–1.04 | tos.c:1135-1136 |
| `$FF8000–$FFFFFF` | registres matériels, **superviseur seulement** ; adresse non câblée = bus error | ioMem.c:50, 462 |

- Bus 24 bits : `$FFFF8240` = `$FF8240`. En absolu court, `$8240.w` est
  étendu en `$FFFF8240` : écrire `move.w d0,$ffff8240.w` (2 o d'extension au
  lieu de 4, 4 cycles de moins).
- Écriture en ROM ou dans la ROM non utilisée (`$E00000` si TOS en `$FC0000`
  et inversement) : bus error (cpu/memory.c:1043-1048, 1777-1786).
- Vidéo : avec ≤ 4 Mo, les adresses vidéo sont bornées à `$3FFFFF`
  (video.c:5107).

## Vecteurs d'exception utiles

| Vecteur | Usage | Source |
|---|---|---|
| `$08` | bus error | |
| `$0C` | address error (accès mot/long à adresse impaire) | |
| `$10` | instruction illégale (détection NatFeats) | |
| `$68` | HBL, niveau 2 (exception 26) | m68000.h:82 |
| `$70` | VBL, niveau 4 (exception 28) | m68000.h:83 |
| `$78` | niveau 6 = MFP (le MFP fournit son propre vecteur) | m68000.h:84 |
| `$80–$BC` | `trap #0–15` (GEMDOS #1, BIOS #13, XBIOS #14) | |
| `$100–$13C` | 16 vecteurs MFP (VR = `$40`) — tableau dans `mfp.md` | mfp.c:700-708 |

Vecteurs MFP courants : `$110` Timer D, `$114` Timer C (200 Hz système),
`$118` ACIA clavier/MIDI, `$10C` fin de blit (GPIP3), `$120` Timer B,
`$134` Timer A, `$13C` GPIP7 (fin de trame DMA son / détection mono).

## Variables système (TOS)

| Adresse | Nom | Rôle |
|---|---|---|
| `$42E` | `phystop` | fin de la RAM |
| `$44E` | `_v_bas_ad` | base écran logique du TOS |
| `$462` | `_vbclock` | compteur de VBL |
| `$466` | `_frclock` | compteur de trames |
| `$4BA` | `_hz_200` | compteur 200 Hz (Timer C) |
| `$4F2` | `_sysbase` | en-tête OS |
| `$5A0` | `_p_cookies` | cookie jar (TOS ≥ 1.06) : `_MCH` donne la machine (`$00010000` = STE, `$00010010` = Mega STE) |

Sources : stMemory.c:369, tos.c:125, debug/debugInfo.c:54-58,
tests/debugger/data/etos1024k.sym.

Un jeu qui prend toute la machine n'a besoin d'aucune de ces variables : il
remplace `$70`, `$118`, `$120`… et ne rend la main au TOS qu'à la sortie.
