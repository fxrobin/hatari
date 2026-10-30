| STE hardware checks for the skills (run with --machine ste)
	.text
start:
	clr.l	-(sp)
	move.w	#0x20,-(sp)
	trap	#1
	addq.l	#6,sp
	bsr	nf_init
	move	#0x2700,sr

| --- H1: writing $FF8203 resets $FF820D -------------------------------
	lea	s_h1(pc),a0
	bsr	puts
	move.b	#0x01,0xffff8201.w
	move.b	#0x23,0xffff8203.w
	move.b	#0x46,0xffff820d.w
	moveq	#0,d0
	move.b	0xffff820d.w,d0
	bsr	puthex			| expect 0046
	move.b	#0x24,0xffff8203.w
	move.b	0xffff820d.w,d0
	bsr	puthex			| expect 0000
	bsr	newline

| --- H2: byte write to palette is duplicated --------------------------
	lea	s_h2(pc),a0
	bsr	puts
	move.w	0xffff8240.w,-(sp)
	clr.w	0xffff8240.w
	move.b	#7,0xffff8240.w
	move.w	0xffff8240.w,d0
	bsr	puthex			| expect 0707
	move.b	#0x55,0xffff8241.w
	move.w	0xffff8240.w,d0
	bsr	puthex			| expect 0555
	move.w	(sp)+,0xffff8240.w
	bsr	newline

| --- H3: byte access to $FF9200 -> bus error; word ok ------------------
	lea	s_h3(pc),a0
	bsr	puts
	move.l	0x8.w,-(sp)
	move.l	sp,savesp
	move.l	#berr,0x8.w
	clr.w	buserr
	move.w	0xffff9200.w,d0
	move.w	buserr,d0
	bsr	puthex			| word: expect 0000
	move.b	0xffff9200.w,d0
berr_back:
	move.w	buserr,d0
	bsr	puthex			| byte: expect 0001
	move.l	(sp)+,0x8.w
	bsr	newline

| --- H4: Microwire busy wait -------------------------------------------
	lea	s_h4(pc),a0
	bsr	puts
	move.w	#0x07ff,0xffff8924.w
	move.w	#0x04e8,0xffff8922.w	| master volume 0 dB
	move.w	0xffff8924.w,d0
	bsr	puthex			| mask right after write (rotating?)
	moveq	#0,d1
1:	addq.l	#1,d1
	cmp.w	#0x07ff,0xffff8924.w
	bne.s	1b
	move.l	d1,d0
	bsr	putdec			| loop count until mask restored
	bsr	newline

| --- H5: blitter clear recipe + bset restart loop ---------------------
	lea	s_h5(pc),a0
	bsr	puts
	lea	bbuf,a0
	move.w	#2048/4-1,d0
2:	move.l	#0xffffffff,(a0)+
	dbra	d0,2b
	lea	0xffff8a00.w,a0
	moveq	#-1,d0
	move.w	d0,0x28(a0)		| endmasks
	move.w	d0,0x2a(a0)
	move.w	d0,0x2c(a0)
	move.w	#2,0x2e(a0)		| dst x inc
	move.w	#160-(8-1)*2,0x30(a0)	| dst y inc (8 words = 32 px wide)
	move.l	#bbuf+160,0x32(a0)	| dst = line 1
	move.w	#8,0x36(a0)		| x count
	move.w	#4,0x38(a0)		| y count
	move.b	#0,0x3a(a0)		| HOP all ones
	move.b	#0,0x3b(a0)		| OP 0
	move.b	#0,0x3d(a0)		| skew 0
	move.b	#0x80,0x3c(a0)		| start, no HOG
	move	#0x2300,sr
3:	bset.b	#7,0x3c(a0)
	nop
	bne.s	3b
	move	#0x2700,sr
	| verify: lines 1..4, first 16 bytes zero, byte 16 still $FF, line 0 and 5 untouched
	lea	bbuf,a1
	moveq	#0,d2			| error count
	moveq	#0,d3
4:	move.w	d3,d4
	mulu	#160,d4
	lea	0(a1,d4.l),a2
	moveq	#0,d5			| byte index
5:	move.b	(a2,d5.w),d6
	moveq	#-1,d7			| expected $FF
	cmp.w	#1,d3
	blt.s	6f
	cmp.w	#4,d3
	bgt.s	6f
	cmp.w	#16,d5
	bge.s	6f
	moveq	#0,d7
6:	cmp.b	d7,d6
	beq.s	7f
	addq.w	#1,d2
7:	addq.w	#1,d5
	cmp.w	#32,d5
	blt.s	5b
	addq.w	#1,d3
	cmp.w	#6,d3
	blt.s	4b
	move.l	d2,d0
	bsr	putdec			| expect 0 errors
	lea	s_ycnt(pc),a0
	bsr	puts
	move.w	0xffff8a38.w,d0
	bsr	puthex			| ycount after blit: expect 0000
	bsr	newline

| --- H6: DMA sound registers via move.b + counter advancing -----------
	lea	s_h6(pc),a0
	bsr	puts
	lea	0xffff8900.w,a0
	clr.b	1(a0)
	move.l	#snd,d0
	move.b	d0,7(a0)
	lsr.w	#8,d0
	move.b	d0,5(a0)
	swap	d0
	move.b	d0,3(a0)
	move.l	#snd_end,d0
	move.b	d0,0x13(a0)
	lsr.w	#8,d0
	move.b	d0,0x11(a0)
	swap	d0
	move.b	d0,0xf(a0)
	move.b	#0x81,0x21(a0)		| mono 12517
	bsr	rd_start
	sub.l	#snd,d0
	bsr	putdec			| start readback - snd : expect 0
	bsr	space
	bsr	rd_end
	sub.l	#snd_end,d0
	bsr	putdec			| end readback - snd_end : expect 0
	bsr	space
	move.b	#1,1(a0)		| play
	move.l	#20000,d1
8:	subq.l	#1,d1
	bne.s	8b
	bsr	rd_cnt
	sub.l	#snd,d0
	bsr	putdec			| bytes played after ~400k cycles (~625 expected at 12517 Hz)
	clr.b	1(a0)
	bsr	newline

| --- H7/H8: Timer B line count + hscroll/linewidth recipe --------------
	lea	s_h7(pc),a0
	bsr	puts
	move.l	#screen+255,d0
	clr.b	d0
	move.l	d0,scr
	lsr.l	#8,d0
	move.b	d0,0xffff8203.w
	lsr.w	#8,d0
	move.b	d0,0xffff8201.w
	clr.b	0xffff820d.w
	move.b	#2,0xffff820a.w		| 50 Hz
	clr.b	0xffff8265.w
	clr.b	0xffff820f.w
	move.l	#vbl,0x70.w
	move.l	#timerb,0x120.w
	move.b	#0x40,0xfffffa17.w	| auto EOI
	clr.b	0xfffffa07.w
	clr.b	0xfffffa09.w
	bset	#0,0xfffffa07.w
	bset	#0,0xfffffa13.w
	move.w	#192,tb_lines
	clr.w	tb_done
	move	#0x2300,sr
9:	cmp.w	#3,tb_done		| wait a few frames for stable values
	blt.s	9b
	move	#0x2700,sr
	move.l	tb_addr,d0
	sub.l	scr,d0
	bsr	putdec			| counter offset at timer B (192 lines)
	bsr	space
	divu	#160,d0
	moveq	#0,d1
	move.w	d0,d1
	move.l	d1,d0
	bsr	putdec			| -> line
	bsr	space
	swap	d0
	and.l	#0xffff,d0
	bsr	putdec			| -> byte in line
	bsr	newline

	lea	s_h8(pc),a0
	bsr	puts
	move.b	#5,hs_val
	move.b	#76,lw_val		| W=320 bytes: (320-160-8)/2
	move.w	#10,tb_lines
	clr.w	tb_done
	move	#0x2300,sr
10:	cmp.w	#3,tb_done
	blt.s	10b
	move	#0x2700,sr
	move.l	tb_addr,d0
	sub.l	scr,d0
	bsr	putdec			| expect ~10*320 (+ position in line)
	bsr	space
	move.b	#0,hs_val
	move.b	#80,lw_val		| hscroll 0: (320-160)/2
	clr.w	tb_done
	move	#0x2300,sr
11:	cmp.w	#3,tb_done
	blt.s	11b
	move	#0x2700,sr
	move.l	tb_addr,d0
	sub.l	scr,d0
	bsr	putdec
	bsr	newline

	moveq	#0,d0
	bsr	nf_exit
99:	bra.s	99b

vbl:	clr.b	0xfffffa1b.w
	move.b	tb_lines+1,0xfffffa21.w
	move.b	#8,0xfffffa1b.w
	move.b	hs_val,0xffff8265.w
	move.b	lw_val,0xffff820f.w
	rte
timerb:	move.l	d0,-(sp)
	moveq	#0,d0
	move.b	0xffff8205.w,d0
	swap	d0
	move.b	0xffff8207.w,d0
	lsl.w	#8,d0
	move.b	0xffff8209.w,d0
	move.l	d0,tb_addr
	addq.w	#1,tb_done
	clr.b	0xfffffa1b.w		| one shot per frame
	move.l	(sp)+,d0
	rte

berr:	move.w	#1,buserr
	move.l	savesp,sp
	jmp	berr_back

rd_start: moveq	#0,d0
	move.b	3(a0),d0
	swap	d0
	move.b	5(a0),d0
	lsl.w	#8,d0
	move.b	7(a0),d0
	rts
rd_end:	moveq	#0,d0
	move.b	0xf(a0),d0
	swap	d0
	move.b	0x11(a0),d0
	lsl.w	#8,d0
	move.b	0x13(a0),d0
	rts
rd_cnt:	moveq	#0,d0
	move.b	9(a0),d0
	swap	d0
	move.b	0xb(a0),d0
	lsl.w	#8,d0
	move.b	0xd(a0),d0
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

s_h1:	.asciz	"H1 $820D after base writes (0046 then 0000): "
s_h2:	.asciz	"H2 palette .b dup (0707 0555): "
s_h3:	.asciz	"H3 $FF9200 buserr word/byte (0000 0001): "
s_h4:	.asciz	"H4 microwire mask readback / wait loops: "
s_h5:	.asciz	"H5 blitter clear errors (0): "
s_ycnt:	.asciz	" ycount="
s_h6:	.asciz	"H6 DMA start/end readback (0 0) played: "
s_h7:	.asciz	"H7 timer B 192: offset line byte: "
s_h8:	.asciz	"H8 hscroll5+lw76 / hscroll0+lw80 at 10 lines (both 3200+x): "
s_nl:	.asciz	"\n"
s_sp:	.asciz	" "
	.even
	.data
snd:	.dcb.b	4096,0
snd_end:
	.bss
savesp:	.ds.l	1
buserr:	.ds.w	1
tb_lines: .ds.w	1
tb_done: .ds.w	1
tb_addr: .ds.l	1
scr:	.ds.l	1
hs_val:	.ds.b	1
lw_val:	.ds.b	1
bbuf:	.ds.b	2048
screen:	.ds.b	64000+256
