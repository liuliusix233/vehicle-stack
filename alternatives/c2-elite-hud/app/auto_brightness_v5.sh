#!/system/bin/sh
# =============================================================================
# auto_brightness.sh v5 — 车载HUD自动亮度 (Asymmetric Debounce)
#
# 设计逻辑（按盒子的要求）:
#   1. 亮度变大 → 等 5-10 秒确认（排除路灯/树缝闪烁）
#   2. 亮度变小 → 立刻降低（不刺眼）
#   3. 晚上低亮度保持不动（盒子喜欢）
#   4. 只有加油站/停车场才需要拉高亮度
#
# 比起 v3 的 30s 滑动平均，这个更符合直觉:
#   闪烁 = 处理了（瞬亮不追）
#   滞后 = 不是问题（就是要等确认）
# =============================================================================

ALS_PATH="/sys/bus/platform/drivers/als_ps/als"
BACKLIGHT_PATH="/sys/class/leds/lcd-backlight/brightness"
MAX_BL_PATH="/sys/class/leds/lcd-backlight/max_brightness"

# ---- 核心参数 ----
POLL_INTERVAL=1          # 每秒检查一次
DEBOUNCE_UP=8            # 亮度升高前等 8 秒确认（排除路灯/树缝）
MIN_CHANGE=10            # 最小有效变化量（防过零抖动）
HYSTERESIS=10            # 迟滞带

# ---- 夜间模式 ----
NIGHT_START_HR=18
NIGHT_END_HR=6
NIGHT_MAX=40             # 盒子说喜欢晚上低亮度

# ---- 状态变量 ----
CURRENT_BL=0             # 当前实际亮度
PREV_TARGET=-1           # 上次计算的目标亮度
BRIGHT_COUNTER=0         # 亮度升高计数器（debounce用）
CHANGING_UP=0            # 是否正在等待亮度升高确认

# =============================================================================
# 工具函数
# =============================================================================

get_als() {
    local raw=$(cat "$ALS_PATH" 2>/dev/null | tr -d '\n\r ')
    echo $((raw))
}

get_max_bl() {
    cat "$MAX_BL_PATH" 2>/dev/null || echo 255
}

is_night() {
    local h=$(date +%H)
    h=${h#0}
    [ "$h" -ge "$NIGHT_START_HR" -o "$h" -lt "$NIGHT_END_HR" ] && echo 1 || echo 0
}

# =============================================================================
# ALS → 亮度映射曲线
#
# 盒子说: 只有加油站/停车场才需要高亮
# 所以曲线在正常范围内不高，只有极高 ALS 才拉到高亮度
# =============================================================================
als_to_brightness() {
    local lux=$1 max_bl=$2 night=$3 target

    [ "$lux" -gt 65535 ] && lux=65535

    if [ "$night" -eq 1 ]; then
        # ===== 夜间: 全程低亮度 =====
        if   [ "$lux" -le 10 ];   then target=3
        elif [ "$lux" -le 50 ];   then target=$(( 3 + (lux-10)*5/40 ))
        elif [ "$lux" -le 500 ];  then target=$(( 8 + (lux-50)*12/450 ))
        elif [ "$lux" -le 3000 ]; then target=$(( 20 + (lux-500)*10/2500 ))
        else
            target=$(( 30 + (lux-3000)*10/20000 ))
            [ "$target" -gt "$NIGHT_MAX" ] && target=$NIGHT_MAX
        fi
    else
        # ===== 白天: 正常低亮，只有加油站/停车场才拉高 =====
        if   [ "$lux" -le 100 ];  then target=20
        elif [ "$lux" -le 500 ];  then target=$(( 20 + (lux-100)*30/400 ))
        elif [ "$lux" -le 2000 ]; then target=$(( 50 + (lux-500)*30/1500 ))
        elif [ "$lux" -le 5000 ]; then target=$(( 80 + (lux-2000)*40/3000 ))
        elif [ "$lux" -le 15000 ];then target=$(( 120 + (lux-5000)*60/10000 ))
        else
            target=$(( 180 + (lux-15000)*75/50535 ))
            [ "$target" -gt "$max_bl" ] && target=$max_bl
        fi
    fi

    echo "$target"
}

# 写亮度（带边界检查）
write_bl() {
    local val=$1 max_bl=$2
    [ "$val" -lt 0 ] && val=0
    [ "$val" -gt "$max_bl" ] && val=$max_bl
    echo "$val" > "$BACKLIGHT_PATH" 2>/dev/null
}

log_i() { log -t auto_brightness -p i "$1"; }

# =============================================================================
# 主循环
# =============================================================================

MAX_BL=$(get_max_bl)
log_i "=== v5 STARTED (max_bl=$MAX_BL, debounce_up=${DEBOUNCE_UP}s) ==="

# 杀掉旧实例
for oldpid in $(busybox pgrep -f "auto_brightness.sh" 2>/dev/null); do
    [ "$oldpid" != "$$" ] && kill "$oldpid" 2>/dev/null
done

# 首次读取 → 直接设置
FIRST_ALS=$(get_als)
FIRST_DAY=$(is_night)
FIRST_TARGET=$(als_to_brightness "$FIRST_ALS" "$MAX_BL" "$FIRST_DAY")
write_bl "$FIRST_TARGET" "$MAX_BL"
CURRENT_BL=$FIRST_TARGET
PREV_TARGET=$FIRST_TARGET
log_i "INIT: ALS=$FIRST_ALS night=$FIRST_DAY → bl=$FIRST_TARGET"

while true; do
    sleep "$POLL_INTERVAL"

    ALS=$(get_als)
    [ -z "$ALS" ] && continue
    [ "$ALS" -eq 0 ] 2>/dev/null && continue

    NIGHT=$(is_night)
    TARGET=$(als_to_brightness "$ALS" "$MAX_BL" "$NIGHT")

    # ====================================================================
    # 核心逻辑: 不对称响应
    # ====================================================================

    DIFF=$(( TARGET - CURRENT_BL ))

    if [ "$DIFF" -gt 0 ]; then
        # ===== 需要变亮 → 等确认 =====
        BRIGHT_COUNTER=$((BRIGHT_COUNTER + 1))

        if [ "$BRIGHT_COUNTER" -ge "$DEBOUNCE_UP" ]; then
            # 确认了确实变亮了 → 写到硬件
            write_bl "$TARGET" "$MAX_BL"
            CURRENT_BL=$TARGET
            BRIGHT_COUNTER=0
            log_i "UP_OK: ALS=$ALS → $CURRENT_BL (confirmed ${DEBOUNCE_UP}s)"
        else
            # 还在等 → 但不写入
            # 如果变化幅度极小，重置计数器（排除噪声）
            if [ "$DIFF" -lt "$MIN_CHANGE" ]; then
                BRIGHT_COUNTER=0
            fi
        fi

    elif [ "$DIFF" -lt 0 ]; then
        # ===== 需要变暗 → 立刻降 =====
        # 但如果变化量太小，忽略（防抖动）
        DIFF_ABS=$(( -DIFF ))
        if [ "$DIFF_ABS" -ge "$HYSTERESIS" ]; then
            write_bl "$TARGET" "$MAX_BL"
            CURRENT_BL=$TARGET
            BRIGHT_COUNTER=0
            log_i "DOWN_NOW: ALS=$ALS → $CURRENT_BL (diff=$DIFF)"
        fi

    else
        # ===== 亮度不变 =====
        # 如果 debounce 在等但 ALS 没有继续变亮 → 重置
        if [ "$BRIGHT_COUNTER" -gt 0 ]; then
            BRIGHT_COUNTER=0
        fi
    fi

done
