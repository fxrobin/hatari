| STE blitter: 16x16 masked 4-plane sprite drawn in 8 passes (recipe of
| st-hardware/references/blitter.md), at several x positions. The resulting
| screen area is dumped in hex on stderr; blitspr_check.py recomputes it
| pixel by pixel and compares. Run with --machine ste.
	.equ	SPR_Y,3
	.equ	DUMP_Y0,SPR_Y-1		| dump lines DUMP_Y0..DUMP_Y0+17
	.equ	DUMP_LINES,18
	.text
start:
	clr.l	-(sp)
	move.w	#0x20,-(sp)
	trap	#1
	addq.l	#6,sp
	bsr	nf_init
	move	#0x2700,sr
	bsr	make_sprite

	lea	xlist(pc),a3
1:	move.w	(a3)+,d0
	bmi.s	2f
	move.w	d0,spr_x
	bsr	make_background
	bsr	cycles
	move.l	d0,t0
	move.w	spr_x,d0
	moveq	#SPR_Y,d1
	lea	sprite,a1
	lea	bg,a2
	bsr	blit_sprite
	bsr	cycles
	sub.l	t0,d0
	move.l	d0,t0
	bsr	dump
	bra.s	1b
2:	moveq	#0,d0
	bsr	nf_exit
3:	bra.s	3b

xlist:	.dc.w	37,48,5,300,-1

| d0 = x, d1 = y, a1 = sprite (16 lines x [p0w0 p1w0 p2w0 p3w0 p0w1 p1w1 p2w1 p3w1],
| second word of each plane = 0: one extra word for the skew), mask follows
| (16 lines x [m, $FFFF], 1 = transparent), a2 = screen (160 bytes/line)
blit_sprite:
	movem.l	d0-d4/a0-a4,-(sp)
	lea	0xffff8a00.w,a0
	| destination = screen + y*160 + (x/16)*8
	mulu	#160,d1
	move.w	d0,d2
	lsr.w	#4,d2
	lsl.w	#3,d2
	add.w	d2,d1
	lea	0(a2,d1.l),a4		| a4 = dest, plane 0
	and.w	#15,d0			| skew
	moveq	#-1,d3
	lsr.w	d0,d3			| endmask1 = $FFFF >> skew
	move.w	d3,0x28(a0)
	move.w	#0xffff,0x2a(a0)	| endmask2 (unused, xcount = 2)
	not.w	d3
	move.w	d3,0x2c(a0)		| endmask3 = ~endmask1
	move.w	#8,0x2e(a0)		| dst x inc: next 16 px group, same plane
	move.w	#160-8,0x30(a0)		| dst y inc
	move.b	#2,0x3a(a0)		| HOP = source
	move.b	d0,0x3d(a0)		| skew, FXSR = NFSR = 0
	| --- 4 mask passes: D = S AND D (OP 1) ----------------------------
	move.w	#2,0x20(a0)		| src x inc (mask: 2 words per line)
	move.w	#2,0x22(a0)		| src y inc (4 - 2)
	move.b	#1,0x3b(a0)
	lea	16*16(a1),a3		| mask
	moveq	#3,d4
	move.l	a4,a2
4:	move.l	a3,0x24(a0)
	move.l	a2,0x32(a0)
	move.w	#2,0x36(a0)		| x count
	move.w	#16,0x38(a0)		| y count
	move.b	#0xc0,0x3c(a0)		| start, HOG
	nop
	addq.l	#2,a2
	dbra	d4,4b
	| --- 4 plane passes: D = S OR D (OP 7) ----------------------------
	move.w	#8,0x20(a0)		| src x inc: next word of the same plane
	move.w	#8,0x22(a0)		| src y inc (16 - 8)
	move.b	#7,0x3b(a0)
	move.l	a1,a3
	moveq	#3,d4
	move.l	a4,a2
5:	move.l	a3,0x24(a0)
	move.l	a2,0x32(a0)
	move.w	#2,0x36(a0)
	move.w	#16,0x38(a0)
	move.b	#0xc0,0x3c(a0)
	nop
	addq.l	#2,a2
	addq.l	#2,a3
	dbra	d4,5b
	movem.l	(sp)+,d0-d4/a0-a4
	rts

| deterministic patterns, reproduced by blitspr_check.py
make_sprite:
	lea	sprite,a0
	moveq	#0,d0			| index = line*4 + plane
	moveq	#15,d2
1:	moveq	#3,d3
2:	move.w	d0,d1
	mulu	#0x3c6f,d1
	add.w	#0x5a5a,d1
	move.w	d1,(a0)+
	addq.w	#1,d0
	dbra	d3,2b
	clr.l	(a0)+
	clr.l	(a0)+
	dbra	d2,1b
	moveq	#0,d0			| mask: line
	moveq	#15,d2
3:	move.w	d0,d1
	mulu	#0x7a3d,d1
	add.w	#0x0f0f,d1
	move.w	d1,(a0)+
	move.w	#0xffff,(a0)+
	addq.w	#1,d0
	dbra	d2,3b
	rts

make_background:
	lea	bg,a0
	moveq	#0,d0			| word index
	move.w	#24*80-1,d2
1:	move.w	d0,d1
	mulu	#0x9e37,d1
	add.w	#0x1234,d1
	move.w	d1,(a0)+
	addq.w	#1,d0
	dbra	d2,1b
	rts

| print "X x cycles" then DUMP_LINES lines of 80 words
dump:
	lea	s_x(pc),a0
	bsr	puts
	moveq	#0,d0
	move.w	spr_x,d0
	bsr	putdec
	bsr	space
	move.l	t0,d0
	bsr	putdec
	bsr	newline
	lea	bg+DUMP_Y0*160,a1
	moveq	#DUMP_LINES-1,d2
1:	moveq	#79,d3
2:	move.w	(a1)+,d0
	bsr	puthex
	dbra	d3,2b
	bsr	newline
	dbra	d2,1b
	rts

newline: move.l	a0,-(sp)
	lea	s_nl(pc),a0
	bsr	puts
	move.l	(sp)+,a0
	rts
space:	move.l	a0,-(sp)
	lea	s_sp(pc),a0
	bsr	puts
	move.l	(sp)+,a0
	rts

	.include "nf.inc"

s_x:	.asciz	"X "
s_nl:	.asciz	"\n"
s_sp:	.asciz	" "
	.even
	.bss
spr_x:	.ds.w	1
t0:	.ds.l	1
sprite:	.ds.b	16*16+16*4
bg:	.ds.b	24*160
