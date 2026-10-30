| NatFeats helpers (GNU as, --register-prefix-optional)
	.text
nf_init:
	pea	nfn_stderr(pc)
	subq.l	#4,sp
	.dc.w	0x7300
	addq.l	#8,sp
	move.l	d0,nfid_stderr
	pea	nfn_cycles(pc)
	subq.l	#4,sp
	.dc.w	0x7300
	addq.l	#8,sp
	move.l	d0,nfid_cycles
	pea	nfn_exit(pc)
	subq.l	#4,sp
	.dc.w	0x7300
	addq.l	#8,sp
	move.l	d0,nfid_exit
	rts

| a0 = string -> stderr
puts:
	movem.l	d0-d2/a0-a2,-(sp)
	move.l	a0,-(sp)
	move.l	nfid_stderr,-(sp)
	subq.l	#4,sp
	.dc.w	0x7301
	lea	12(sp),sp
	movem.l	(sp)+,d0-d2/a0-a2
	rts

| -> d0 = cycle counter
cycles:
	move.l	nfid_cycles,-(sp)
	subq.l	#4,sp
	.dc.w	0x7301
	addq.l	#8,sp
	rts

| d0.l (signed) -> decimal on stderr
putdec:
	movem.l	d0-d2/a0,-(sp)
	lea	decbuf_end,a0
	clr.b	-(a0)
	move.l	d0,d2
	bpl.s	1f
	neg.l	d0
1:	moveq	#0,d1
	move.l	d0,d1
2:	move.l	d1,d0	| 32/16 division by 10 in two halves
	clr.w	d0
	swap	d0
	divu	#10,d0
	move.w	d0,-(sp)
	move.w	d1,d0
	divu	#10,d0
	swap	d0
	add.b	#'0',d0
	move.b	d0,-(a0)
	clr.w	d0
	swap	d0
	move.w	(sp)+,d1
	swap	d1
	move.w	d0,d1
	tst.l	d1
	bne.s	2b
	tst.l	d2
	bpl.s	3f
	move.b	#'-',-(a0)
3:	bsr	puts
	movem.l	(sp)+,d0-d2/a0
	rts

| d0.w -> 4 hex digits
puthex:
	movem.l	d0-d2/a0,-(sp)
	lea	decbuf_end,a0
	clr.b	-(a0)
	moveq	#3,d2
1:	move.w	d0,d1
	and.w	#15,d1
	move.b	hexd(pc,d1.w),-(a0)
	lsr.w	#4,d0
	dbra	d2,1b
	bsr	puts
	movem.l	(sp)+,d0-d2/a0
	rts
hexd:	.ascii	"0123456789ABCDEF"

| d0.l = exit code
nf_exit:
	move.l	d0,-(sp)
	move.l	nfid_exit,-(sp)
	subq.l	#4,sp
	.dc.w	0x7301
	lea	12(sp),sp
	rts

nfn_stderr:	.asciz	"NF_STDERR"
nfn_cycles:	.asciz	"NF_CYCLES"
nfn_exit:	.asciz	"NF_EXIT"
	.even
	.bss
nfid_stderr:	.ds.l	1
nfid_cycles:	.ds.l	1
nfid_exit:	.ds.l	1
decbuf:		.ds.b	16
decbuf_end:	.ds.b	2
	.text
