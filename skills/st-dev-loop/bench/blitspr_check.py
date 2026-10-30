#!/usr/bin/env python3
"""Check blitspr.prg output: recompute the expected screen pixel by pixel.

usage: ./run.sh blitspr ste | ./blitspr_check.py
Same patterns as make_sprite / make_background in blitspr.s.
"""
import sys

SPR_Y, DUMP_Y0, DUMP_LINES = 3, 2, 18


def background():
    return [(i * 0x9E37 + 0x1234) & 0xFFFF for i in range(24 * 80)]


def sprite():
    planes = [[((l * 4 + k) * 0x3C6F + 0x5A5A) & 0xFFFF for k in range(4)] for l in range(16)]
    mask = [(l * 0x7A3D + 0x0F0F) & 0xFFFF for l in range(16)]
    return planes, mask


def bit(word, px):
    return (word >> (15 - px)) & 1


def expected(x):
    scr = background()
    planes, mask = sprite()
    for l in range(16):
        y = SPR_Y + l
        for sx in range(16):
            col = x + sx
            group, px = divmod(col, 16)
            m = bit(mask[l], sx)          # 1 = transparent
            for k in range(4):
                idx = y * 80 + group * 4 + k
                d = bit(scr[idx], px)
                v = (d & m) | bit(planes[l][k], sx)
                scr[idx] = (scr[idx] & ~(1 << (15 - px)) & 0xFFFF) | (v << (15 - px))
    return scr


def main():
    lines = [l.rstrip("\n") for l in sys.stdin]
    ok = True
    i = 0
    while i < len(lines):
        if not lines[i].startswith("X "):
            i += 1
            continue
        _, x, cyc = lines[i].split()
        x = int(x)
        got = []
        for row in lines[i + 1:i + 1 + DUMP_LINES]:
            got += [int(row[j:j + 4], 16) for j in range(0, 320, 4)]
        exp = expected(x)[DUMP_Y0 * 80:(DUMP_Y0 + DUMP_LINES) * 80]
        bad = sum(1 for a, b in zip(got, exp) if a != b)
        ok &= bad == 0 and len(got) == len(exp)
        print(f"x={x:3d}  cycles={cyc:>5}  words differing={bad}")
        i += 1 + DUMP_LINES
    print("OK" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
