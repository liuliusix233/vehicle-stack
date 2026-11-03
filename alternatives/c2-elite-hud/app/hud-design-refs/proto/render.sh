#!/bin/bash
# Render HUD sport prototypes to PNG with headless Firefox (runs on SER).
DIR="$HOME/hud-proto"
cd "$DIR" || exit 1
FF=/usr/bin/firefox
mkdir -p shots
export MOZ_HEADLESS=1
shot(){ # out w h url
  rm -f "$1"
  timeout 120 "$FF" --headless --window-size="$2,$3" --screenshot="$1" "$4" >/dev/null 2>&1
  if [ -s "$1" ]; then echo "OK   $1 $(stat -c%s "$1")"; else echo "FAIL $1"; fi
}
BASE="file://$DIR"
case "$1" in
  test)
    shot shots/test.png 480 240 "$BASE/sport.html?v=s1&frame=14"
    shot shots/testz.png 960 480 "$BASE/zoom.html?v=s1&frame=14"
    ;;
  all)
    shot shots/hero_s1.png    480 240 "$BASE/sport.html?v=s1&frame=14"
    shot shots/hero_s1_x2.png 960 480 "$BASE/zoom.html?v=s1&frame=14"
    shot shots/hero_s2.png    480 240 "$BASE/sport.html?v=s2&frame=14"
    shot shots/hero_s2_x2.png 960 480 "$BASE/zoom.html?v=s2&frame=14"
    for i in $(seq 0 23); do
      shot "shots/f$(printf %02d $i).png" 480 240 "$BASE/sport.html?v=s1&frame=$i"
    done
    ;;
esac
echo DONE
