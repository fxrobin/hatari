#!/bin/sh
# Run name.prg headless under Hatari (fake TOS, NatFeats on), print its stderr output.
# usage: run.sh name [st|ste|megaste]   (HATARI=path/to/hatari to override)
set -e
[ $# -ge 1 ] || { echo "usage: $0 name [machine]" >&2; exit 1; }
dir=$(cd "$(dirname "$0")" && pwd)
hatari=${HATARI:-$dir/../../../build/src/hatari}
cd "$dir"
SDL_VIDEODRIVER=dummy SDL_AUDIODRIVER=dummy HOME="$dir" \
	"$hatari" --machine "${2:-ste}" --tos none --natfeats on --fast-forward on \
	--sound off --log-level error --run-vbls 3000 "$1.prg" 2>&1 | grep -v '^$'
