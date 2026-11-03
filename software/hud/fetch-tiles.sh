#!/bin/bash
cd "$(dirname "$0")"
LAT0=31.5896; LON0=120.3607
ok=0; fail=0
for spec in "14 2" "15 3" "16 6"; do
  z=${spec% *}; half=${spec#* }
  cx=$(python3 -c "print(int(($LON0+180)/360*2**$z))")
  cy=$(python3 -c "import math;la=math.radians($LAT0);print(int((0.5-math.log((1+math.sin(la))/(1-math.sin(la)))/(4*math.pi))*2**$z))")
  for ((x=cx-half; x<=cx+half; x++)); do
    mkdir -p "tiles/$z/$x"
    for ((y=cy-half; y<=cy+half; y++)); do
      f="tiles/$z/$x/$y.png"
      [ -s "$f" ] && continue
      code=$(curl -s -m 20 -A "diyhud/1.0 (personal use)" -o "$f" -w "%{http_code}" "https://tile.openstreetmap.org/$z/$x/$y.png")
      if [ "$code" = "200" ]; then ok=$((ok+1)); else fail=$((fail+1)); rm -f "$f"; fi
    done
  done
done
echo "ok=$ok fail=$fail"
find tiles -name '*.png' | wc -l
du -sh tiles
