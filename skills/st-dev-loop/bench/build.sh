#!/bin/sh
# Assemble name.s -> name.prg with the m68k-atari-mint toolchain
# (Docker image fxrobin/m68k-compiler, repo fxrobin/docker-m68k-compiler).
# usage: build.sh name
set -e
[ $# -eq 1 ] || { echo "usage: $0 name   (name.s -> name.prg)" >&2; exit 1; }
dir=$(cd "$(dirname "$0")" && pwd)
docker run --rm -u "$(id -u):$(id -g)" -v "$dir:/app" -w /app fxrobin/m68k-compiler sh -c \
	"m68k-atari-mint-as --register-prefix-optional -m68000 -o $1.o $1.s && m68k-atari-mint-ld -s -o $1.prg $1.o && rm -f $1.o"
