#!/bin/bash
# fetch_fonts.sh — download font candidates on a host with a healthy CA store.
set -u
OUT="$HOME/fontddl"
mkdir -p "$OUT"
cd "$OUT" || exit 1
B=https://raw.githubusercontent.com/google/fonts/main/ofl
get(){ curl -sSL --max-time 30 -o "$2" "$1"; s=$(stat -c%s "$2" 2>/dev/null || echo 0); echo "$2 $s"; }
get $B/rajdhani/Rajdhani-Bold.ttf                        Rajdhani-Bold.ttf
get $B/sairacondensed/SairaCondensed-Bold.ttf            SairaCondensed-Bold.ttf
get $B/barlowsemicondensed/BarlowSemiCondensed-Bold.ttf  BarlowSemiCondensed-Bold.ttf
get $B/chakrapetch/ChakraPetch-Bold.ttf                  ChakraPetch-Bold.ttf
get $B/titilliumweb/TitilliumWeb-Bold.ttf                TitilliumWeb-Bold.ttf
get $B/quantico/Quantico-Bold.ttf                        Quantico-Bold.ttf
get $B/michroma/Michroma-Regular.ttf                     Michroma-Regular.ttf
get $B/sharetechmono/ShareTechMono-Regular.ttf           ShareTechMono-Regular.ttf
get $B/electrolize/Electrolize-Regular.ttf               Electrolize-Regular.ttf
get $B/teko/Teko-Bold.ttf                                Teko-Bold.ttf
get $B/baijamjuree/BaiJamjuree-Bold.ttf                  BaiJamjuree-Bold.ttf
get https://github.com/keshikan/DSEG/raw/main/fonts/DSEG7-Classic/DSEG7Classic-Bold.ttf DSEG7Classic-Bold.ttf
echo "--- done"
