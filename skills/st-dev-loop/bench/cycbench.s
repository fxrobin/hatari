| Cycle bench: each test runs N=64 unrolled copies, prints cycles per copy.
	.equ	N,64
	.text
start:
	clr.l	-(sp)
	move.w	#0x20,-(sp)
	trap	#1			| Super
	addq.l	#6,sp
	bsr	nf_init
	move	#0x2700,sr

	| reference: empty routine
	lea	t_empty,a5
	bsr	measure
	move.l	d0,overhead

	lea	tests,a3
next:	move.l	(a3)+,d0
	beq	done
	move.l	d0,a0
	bsr	puts
	lea	sep(pc),a0
	bsr	puts
	move.l	(a3)+,a5
	bsr	measure
	sub.l	overhead,d0
	| d0 = total for N copies -> x100 / N for 2 decimals
	mulu	#100,d0
	divu	#N,d0
	and.l	#0xffff,d0
	move.l	d0,d1
	divu	#100,d1
	moveq	#0,d0
	move.w	d1,d0
	bsr	putdec
	lea	dot(pc),a0
	bsr	puts
	swap	d1
	moveq	#0,d0
	move.w	d1,d0
	cmp.w	#10,d0
	bge.s	1f
	lea	zero(pc),a0
	bsr	puts
1:	bsr	putdec
	lea	nl(pc),a0
	bsr	puts
	bra	next
done:	moveq	#0,d0
	bsr	nf_exit
	bra.s	done

| a5 = routine ; -> d0 = cycles of jsr (a5) (min of 3 runs)
measure:
	move.l	#0x7fffffff,m_min
	move.w	#2,m_cnt
1:	bsr	setregs
	bsr	cycles
	move.l	d0,m_t0
	move.l	a5,m_fn
	movem.l	m_zero,d0-d7
	move.l	m_fn,a5
	jsr	(a5)
	bsr	cycles
	sub.l	m_t0,d0
	cmp.l	m_min,d0
	bge.s	2f
	move.l	d0,m_min
2:	subq.w	#1,m_cnt
	bpl.s	1b
	move.l	m_min,d0
	move.l	m_fn,a5
	rts

setregs:
	moveq	#0,d0
	moveq	#0,d1
	moveq	#0,d2
	moveq	#0,d3
	lea	buf1,a0
	lea	buf2,a1
	lea	buf1,a4
	lea	buf2+8192,a6
	rts

	.include "nf.inc"

	.macro	T name,lbl
	.data	0
	.long	9f,\lbl
	.data	1
9:	.asciz	"\name"
	.text
	.endm

	.data	0
	.even
tests:
	.text

t_empty: rts

	T "nop",t_nop
t_nop:	.rept N
	nop
	.endr
	rts
	T "moveq #0,d0",t_moveq
t_moveq: .rept N
	moveq	#0,d0
	.endr
	rts
	T "clr.l d0",t_clrl
t_clrl:	.rept N
	clr.l	d0
	.endr
	rts
	T "exg d0,d1",t_exg
t_exg:	.rept N
	exg	d0,d1
	.endr
	rts
	T "lsl.w #1,d0",t_lsl1
t_lsl1:	.rept N
	lsl.w	#1,d0
	.endr
	rts
	T "lsl.w #2,d0",t_lsl2
t_lsl2:	.rept N
	lsl.w	#2,d0
	.endr
	rts
	T "add.w d0,d0",t_addw
t_addw:	.rept N
	add.w	d0,d0
	.endr
	rts
	T "move.w 0(a4,d2.w),d1",t_d8
t_d8:	.rept N
	move.w	0(a4,d2.w),d1
	.endr
	rts
	T "lsl.w #4,d1 + move.w 0(a4,d2.w),d1 (pair)",t_pair
t_pair:	.rept N
	lsl.w	#4,d1
	move.w	0(a4,d2.w),d1
	.endr
	rts
	T "exg d0,d1 + exg d0,d1",t_exg2
t_exg2:	.rept N/2
	exg	d0,d1
	exg	d0,d1
	.endr
	rts
	T "move.l (a0)+,(a1)+",t_mll
t_mll:	.rept N
	move.l	(a0)+,(a1)+
	.endr
	rts
	T "move.l d0,(a0)+",t_mlr
t_mlr:	.rept N
	move.l	d0,(a0)+
	.endr
	rts
	T "clr.l (a0)+",t_clrm
t_clrm:	.rept N
	clr.l	(a0)+
	.endr
	rts
	T "lea 160(a0),a0",t_lea
t_lea:	.rept N
	lea	160(a0),a0
	.endr
	rts
	T "add.w #160,a0",t_adda
t_adda:	.rept N
	add.w	#160,a0
	.endr
	rts
	T "movem.l d0-d7/a0-a5,-(a6)",t_movem
t_movem: .rept N
	movem.l	d0-d7/a0-a5,-(a6)
	.endr
	rts
	T "mulu d1,d0 (d1=0)",t_mulu0
t_mulu0: .rept N
	mulu	d1,d0
	.endr
	rts
	T "mulu d2,d0 (d2=$FFFF)",t_mulu1
t_mulu1: move.w #0xffff,d2
	.rept N
	mulu	d2,d0
	.endr
	rts
	T "bra.w to next (taken)",t_bra
t_bra:	.rept N
	bra.w	1f
1:
	.endr
	rts
	T "bne.s not taken + nop",t_bnt
t_bnt:	moveq	#0,d0
	.rept N
	bne.s	1f
	nop
1:
	.endr
	rts
	T "dbra loop, per taken iteration",t_dbra
t_dbra:	move.w	#N-1,d0
1:	dbra	d0,1b
	rts
	T "bsr + rts",t_bsr
t_bsr:	.rept N
	bsr.w	t_rts
	.endr
	rts
t_rts:	rts
	T "move.w d0,$ffff8240.w (palette)",t_pal
t_pal:	.rept N
	move.w	d0,0xffff8240.w
	.endr
	rts
	T "move.b $fffffa01.w,d0 (MFP)",t_mfp
t_mfp:	.rept N
	move.b	0xfffffa01.w,d0
	.endr
	rts
	T "move.b d0,$ffff8800.w (YM select)",t_ym
t_ym:	.rept N
	move.b	d0,0xffff8800.w
	.endr
	rts
	T "movep.w d0,0(a6) (YM)",t_ymp
t_ymp:	lea	0xffff8800.w,a6
	.rept N
	movep.w	d0,0(a6)
	.endr
	rts
	T "move.b $fffffc00.w,d0 (ACIA)",t_acia
t_acia:	.rept N
	move.b	0xfffffc00.w,d0
	.endr
	rts
	T "movem.l (a0),d0-d7 + movem.l d0-d7,$ffff8240.w",t_pal16
t_pal16: .rept N
	movem.l	(a0),d0-d7
	movem.l	d0-d7,0xffff8240.w
	.endr
	rts

	T "movem.l (a0),d0-d7",t_mr
t_mr: .rept N
	movem.l	(a0),d0-d7
	.endr
	rts
	T "movem.l d0-d7,$ffff8240.w",t_mw
t_mw: .rept N
	movem.l	d0-d7,0xffff8240.w
	.endr
	rts
	T "movem.l d0-d7,(a1)",t_mw2
t_mw2: .rept N
	movem.l	d0-d7,(a1)
	.endr
	rts
	T "move.l d0,$ffff8240.w",t_ml
t_ml: .rept N
	move.l	d0,0xffff8240.w
	.endr
	rts

	T "movem.l (a1),d0-d7",t_mr1
t_mr1: .rept N
	movem.l	(a1),d0-d7
	.endr
	rts
	T "movem.l (a1),d0-d3",t_mr2
t_mr2: .rept N
	movem.l	(a1),d0-d3
	.endr
	rts

	.data	0
	.long	0
	.text
sep:	.asciz	" : "
dot:	.asciz	"."
zero:	.asciz	"0"
nl:	.asciz	"\n"
	.even
	.bss
overhead: .ds.l	1
m_min:	.ds.l	1
m_t0:	.ds.l	1
m_fn:	.ds.l	1
m_cnt:	.ds.w	1
m_zero:	.ds.l	8
buf1:	.ds.b	8192
buf2:	.ds.b	8192+64
