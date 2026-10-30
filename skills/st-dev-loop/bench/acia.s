| ACIA/IKBD check: the handler documented in st-hardware/references/
| io-sound-input.md, run with the MFP in software end-of-interrupt mode
| (as the TOS does). Joystick 1 in event mode ($14). Every change of joy1 or
| of the last keyboard byte is logged with its VBL number, printed at VBL
| LOG_VBLS. Input is driven by acia_harness.c (libretro core).
	.equ	LOG_VBLS,300
	.text
start:
	clr.l	-(sp)
	move.w	#0x20,-(sp)
	trap	#1
	addq.l	#6,sp
	bsr	nf_init
	move	#0x2700,sr
	move.l	#vbl,0x70.w
	move.l	#acia_irq,0x118.w
	move.b	#0x48,0xfffffa17.w	| MFP vectors at $100, software EOI
	clr.b	0xfffffa07.w
	clr.b	0xfffffa09.w
	bset	#6,0xfffffa09.w		| IERB bit 6 = ACIA
	bset	#6,0xfffffa15.w		| IMRB
	clr.b	0xfffffa11.w		| ISRB
	move.b	#3,0xfffffc00.w		| ACIA master reset
	move.b	#0x96,0xfffffc00.w	| /64, 8N1, RX interrupt
	move	#0x2300,sr
	moveq	#0x14,d0		| joystick event mode (mouse off)
	bsr	ikbd_send

	lea	log,a1
	moveq	#0,d6			| previous joy1
	moveq	#0,d7			| previous key
1:	move.w	vbl_count,d0
2:	cmp.w	vbl_count,d0		| wait next VBL
	beq.s	2b
	move.b	joy1,d1
	move.b	last_key,d2
	cmp.b	d1,d6
	bne.s	3f
	cmp.b	d2,d7
	beq.s	4f
3:	move.w	vbl_count,(a1)+
	move.b	d1,(a1)+
	move.b	d2,(a1)+
	move.b	d1,d6
	move.b	d2,d7
4:	cmp.w	#LOG_VBLS,vbl_count
	blt.s	1b

	move	#0x2700,sr
	lea	log,a2
5:	cmp.l	a1,a2
	bge.s	6f
	lea	s_vbl(pc),a0
	bsr	puts
	moveq	#0,d0
	move.w	(a2)+,d0
	bsr	putdec
	lea	s_joy(pc),a0
	bsr	puts
	moveq	#0,d0
	move.b	(a2)+,d0
	bsr	puthex
	lea	s_key(pc),a0
	bsr	puts
	moveq	#0,d0
	move.b	(a2)+,d0
	bsr	puthex
	lea	s_nl(pc),a0
	bsr	puts
	bra.s	5b
6:	lea	s_end(pc),a0
	bsr	puts
	move.w	irq_count,d0
	bsr	puthex
	lea	s_nl(pc),a0
	bsr	puts
7:	bra.s	7b			| harness stops the emulation

ikbd_send:
1:	btst	#1,0xfffffc00.w		| TDRE
	beq.s	1b
	move.b	d0,0xfffffc02.w
	rts

vbl:	addq.w	#1,vbl_count
	rte

| ---- handler from io-sound-input.md (unchanged, plus an IRQ counter) ----
acia_irq:
	addq.w	#1,irq_count
	move.w	d0,-(sp)
.Lnext:	btst	#0,0xfffffc00.w		| octet recu ?
	beq.s	.Ldone
	move.b	0xfffffc02.w,d0
	tst.b	joy_state		| on attend la donnee d'un paquet $FF ?
	bne.s	.Ljoy
	cmp.b	#0xff,d0
	bne.s	.Lkey
	st	joy_state
	bra.s	.Lnext
.Ljoy:	move.b	d0,joy1			| bit7 feu, bits 0-3 directions
	sf	joy_state
	bra.s	.Lnext
.Lkey:	move.b	d0,last_key
	bra.s	.Lnext
.Ldone:	move.w	(sp)+,d0
	bclr	#6,0xfffffa11.w		| EOI (MFP en mode logiciel)
	rte

	.include "nf.inc"

s_vbl:	.asciz	"vbl "
s_joy:	.asciz	" joy1="
s_key:	.asciz	" key="
s_end:	.asciz	"end irq_count="
s_nl:	.asciz	"\n"
	.even
	.bss
vbl_count: .ds.w 1
irq_count: .ds.w 1
joy_state: .ds.b 1
joy1:	.ds.b	1
last_key: .ds.b	1
	.even
log:	.ds.b	4*512
