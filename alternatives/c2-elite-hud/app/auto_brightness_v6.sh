#!/system/bin/sh
# auto_brightness.sh v6 — 消除"眨眼睛"闪烁
# 相比 v5：变暗也要确认(2s)、两次变化最小间隔 3s、所有变化做成 6 步渐变(≈0.5s 淡入淡出)
ALS_PATH="/sys/bus/platform/drivers/als_ps/als"
BACKLIGHT_PATH="/sys/class/leds/lcd-backlight/brightness"
MAX_BL_PATH="/sys/class/leds/lcd-backlight/max_brightness"

POLL_INTERVAL=1
DEBOUNCE_UP=8
DEBOUNCE_DOWN=2
MIN_DWELL=3
MIN_CHANGE=10
HYSTERESIS=10
RAMP_STEPS=6
RAMP_SLEEP=0.07

NIGHT_START_HR=18
NIGHT_END_HR=6
NIGHT_MAX=40

CURRENT_BL=0
BRIGHT_COUNTER=0
DARK_COUNTER=0
LAST_CHANGE=0

get_als() { echo $(( $(cat "$ALS_PATH" 2>/dev/null | tr -d '\n\r ') )); }
get_max_bl() { cat "$MAX_BL_PATH" 2>/dev/null || echo 255; }
is_night() { local h=$(date +%H); h=${h#0}; [ "$h" -ge "$NIGHT_START_HR" -o "$h" -lt "$NIGHT_END_HR" ] && echo 1 || echo 0; }
log_i() { log -t auto_brightness -p i "$1"; }

als_to_brightness() {
    local lux=$1 max_bl=$2 night=$3 target
    [ "$lux" -gt 65535 ] && lux=65535
    if [ "$night" -eq 1 ]; then
        if   [ "$lux" -le 10 ];   then target=3
        elif [ "$lux" -le 50 ];   then target=$(( 3 + (lux-10)*5/40 ))
        elif [ "$lux" -le 500 ];  then target=$(( 8 + (lux-50)*12/450 ))
        elif [ "$lux" -le 3000 ]; then target=$(( 20 + (lux-500)*10/2500 ))
        else target=$(( 30 + (lux-3000)*10/20000 )); [ "$target" -gt "$NIGHT_MAX" ] && target=$NIGHT_MAX
        fi
    else
        if   [ "$lux" -le 100 ];  then target=20
        elif [ "$lux" -le 500 ];  then target=$(( 20 + (lux-100)*30/400 ))
        elif [ "$lux" -le 2000 ]; then target=$(( 50 + (lux-500)*30/1500 ))
        elif [ "$lux" -le 5000 ]; then target=$(( 80 + (lux-2000)*40/3000 ))
        elif [ "$lux" -le 15000 ];then target=$(( 120 + (lux-5000)*60/10000 ))
        else target=$(( 180 + (lux-15000)*75/50535 )); [ "$target" -gt "$max_bl" ] && target=$max_bl
        fi
    fi
    echo "$target"
}

write_raw() { echo "$1" > "$BACKLIGHT_PATH" 2>/dev/null; }

# 渐变：分 6 步走完，肉眼是淡入淡出而不是眨眼
ramp_to() {
    local target=$1 max_bl=$2 from=$CURRENT_BL
    [ "$target" -lt 0 ] && target=0
    [ "$target" -gt "$max_bl" ] && target=$max_bl
    local delta=$(( target - from )) step=$(( (target - from) / RAMP_STEPS )) i=0 cur=$from
    [ "$step" -eq 0 ] && { write_raw "$target"; CURRENT_BL=$target; return; }
    while [ "$i" -lt "$RAMP_STEPS" ]; do
        cur=$(( cur + step ))
        i=$(( i + 1 ))
        write_raw "$cur"
        sleep "$RAMP_SLEEP"
    done
    write_raw "$target"
    CURRENT_BL=$target
}

MAX_BL=$(get_max_bl)
log_i "=== v6 STARTED (max_bl=$MAX_BL up=${DEBOUNCE_UP}s down=${DEBOUNCE_DOWN}s dwell=${MIN_DWELL}s) ==="
for oldpid in $(busybox pgrep -f "auto_brightness.sh" 2>/dev/null); do [ "$oldpid" != "$$" ] && kill "$oldpid" 2>/dev/null; done

FIRST_ALS=$(get_als); [ -z "$FIRST_ALS" ] && FIRST_ALS=0
FIRST_TARGET=$(als_to_brightness "$FIRST_ALS" "$MAX_BL" "$(is_night)")
write_raw "$FIRST_TARGET"; CURRENT_BL=$FIRST_TARGET
log_i "INIT: ALS=$FIRST_ALS -> bl=$FIRST_TARGET"

while true; do
    sleep "$POLL_INTERVAL"
    ALS=$(get_als); [ -z "$ALS" ] && continue; [ "$ALS" -eq 0 ] 2>/dev/null && continue
    TARGET=$(als_to_brightness "$ALS" "$MAX_BL" "$(is_night)")
    DIFF=$(( TARGET - CURRENT_BL ))
    NOW=$(date +%s)
    SINCE=$(( NOW - LAST_CHANGE ))
    DIFF_ABS=$(( DIFF < 0 ? -DIFF : DIFF ))

    if [ "$DIFF_ABS" -lt "$HYSTERESIS" ]; then
        BRIGHT_COUNTER=0; DARK_COUNTER=0; continue
    fi
    [ "$SINCE" -lt "$MIN_DWELL" ] && continue

    if [ "$DIFF" -gt 0 ]; then
        DARK_COUNTER=0
        BRIGHT_COUNTER=$(( BRIGHT_COUNTER + 1 ))
        if [ "$BRIGHT_COUNTER" -ge "$DEBOUNCE_UP" ]; then
            ramp_to "$TARGET" "$MAX_BL"
            BRIGHT_COUNTER=0; LAST_CHANGE=$NOW
            log_i "UP_OK: ALS=$ALS bl=$CURRENT_BL"
        fi
    else
        BRIGHT_COUNTER=0
        DARK_COUNTER=$(( DARK_COUNTER + 1 ))
        if [ "$DARK_COUNTER" -ge "$DEBOUNCE_DOWN" ]; then
            ramp_to "$TARGET" "$MAX_BL"
            DARK_COUNTER=0; LAST_CHANGE=$NOW
            log_i "DOWN_OK: ALS=$ALS bl=$CURRENT_BL"
        fi
    fi
done
