#!/system/bin/sh
# =============================================================================
# auto_brightness.sh v4 — 车载HUD自动亮度 (Fast-Attack Peak-Hold)
#
# 解决的问题:
#   ❌ v1 闪烁（瞬时值 → 路灯/树荫交替时疯狂跳变）
#   ❌ v2 滞后（30s滑动平均 → 过了亮的地方才亮）
#   ✅ v4 Peak-Hold: 快速起跳 + 峰值保持 + 缓慢衰减 + 迟滞防抖
#
# 设计原理（参考汽车OEM HUD规范）:
#   1. Fast Attack: 环境变亮 → HUD 立即响应（1帧内跳变）
#   2. Peak Hold: 高亮度保持 4秒，不因瞬间遮挡而暗掉
#   3. Slow Release: 峰值过后，指数平滑衰减至当前值
#   4. Hysteresis: ≥15单位的偏差才触发写入，防止过零抖动
#   5. 隧道检测: 环境突然变暗时不降亮度（保持隧道出口可见）
#   6. 夜间上限: 18:00-06:00 最高亮度钳位在 35
#   7. 自动I2C探测: 启动后扫描真实DLP LED驱动芯片
#
# 安装:
#   adb push auto_brightness_v4.sh /data/local/tmp/auto_brightness.sh
#   adb shell chmod 755 /data/local/tmp/auto_brightness.sh
#   adb shell su -c 'cp /data/local/tmp/auto_brightness.sh /data/adb/service.d/'
#   adb shell su -c 'chmod 755 /data/adb/service.d/auto_brightness.sh'
#
# 调试:
#   adb shell logcat -s auto_brightness:* -d
# =============================================================================

# ---- Paths ----
ALS_PATH="/sys/bus/platform/drivers/als_ps/als"
BACKLIGHT_PATH="/sys/class/leds/lcd-backlight/brightness"
MAX_BL_PATH="/sys/class/leds/lcd-backlight/max_brightness"

# ---- Constants ----
POLL_INTERVAL=1              # 轮询间隔（秒）
PEAK_HOLD_SEC=4              # 峰值保持时间（秒）
DECAY_ALPHA=0.25             # 衰减EMA系数（0=永不衰减, 1=立刻跳变）
HYSTERESIS=15                # 迟滞阈值（brightness单位，<15不触发写入）
MIN_CHANGE=3                 # 最小有效变化量
TUNNEL_THRESHOLD=200         # ALS骤降判断阈值（lux/ADC）

# ---- 夜间模式 ----
NIGHT_START_H=18
NIGHT_END_H=6
NIGHT_MAX_BL=35              # 夜间最高亮度
DAY_MIN_BL=15                # 白天最低亮度
DAY_MAX_BL=255               # 白天最高亮度（由max_brightness钳位）

# ---- 状态变量（重启不清） ----
PEAK_BL=0                    # 当前峰值亮度
PEAK_COUNTER=0               # 峰值已保持轮数
SMOOTHED_BL=0                # 平滑值
LAST_WRITTEN=-1              # 上次写入值
DAY_MODE=1                   # 1=白天, 0=夜间
MAX_BL=255                   # 硬件最大亮度

# =============================================================================
# Functions
# =============================================================================

# 读取 ALS 环境光传感器
get_als() {
    local raw
    raw=$(cat "$ALS_PATH" 2>/dev/null | tr -d '\n\r ')
    # ALS 可能是 hex，统一转十进制
    echo $((raw))
}

# 读取硬件最大亮度
get_max_bl() {
    cat "$MAX_BL_PATH" 2>/dev/null || echo 255
}

# 判断是否为夜间（基于时间）
is_night() {
    local h
    h=$(date +%H)
    # 去除前导零
    h=${h#0}
    [ "$h" -ge "$NIGHT_START_H" -o "$h" -lt "$NIGHT_END_H" ] && echo 0 || echo 1
}

# =============================================================================
# ALS → Brightness 映射曲线
#
# 两套独立曲线（白天/夜间）：
#   ALS(lux) → brightness(0-255)
#
# 白天曲线：从15到255，中段爬升快（适应城市驾驶常见范围）
# 夜间曲线：从3到35封顶，保证舒适不刺眼
# =============================================================================
lux_to_brightness() {
    local lux=$1
    local max_bl=$2
    local night=$3
    local target

    # 钳位 lux 范围
    [ "$lux" -gt 65535 ] && lux=65535

    if [ "$night" -eq 1 ]; then
        # ===== 夜间曲线 =====
        # 极暗(<10): 3   | 暗(10-50): 3→8     |
        # 正常(50-500): 8→18  | 亮(500-3000): 18→30 |
        # 很亮(>3000): 30→35 max
        if [ "$lux" -le 10 ]; then
            target=3
        elif [ "$lux" -le 50 ]; then
            target=$(( 3 + (lux - 10) * 5 / 40 ))
        elif [ "$lux" -le 500 ]; then
            target=$(( 8 + (lux - 50) * 10 / 450 ))
        elif [ "$lux" -le 3000 ]; then
            target=$(( 18 + (lux - 500) * 12 / 2500 ))
        else
            target=$(( 30 + (lux - 3000) * 5 / 20000 ))
            [ "$target" -gt "$NIGHT_MAX_BL" ] && target=$NIGHT_MAX_BL
        fi
    else
        # ===== 白天曲线 =====
        # 暗(<10): 15       | 室内(10-100): 15→75   |
        # 阴天(100-500): 75→115  | 多云(500-3000): 115→185 |
        # 晴天(3000-10000): 185→235 | 暴晒(>10000): 235→255
        if [ "$lux" -le 10 ]; then
            target=$DAY_MIN_BL
        elif [ "$lux" -le 100 ]; then
            target=$(( DAY_MIN_BL + (lux - 10) * 60 / 90 ))
        elif [ "$lux" -le 500 ]; then
            target=$(( 75 + (lux - 100) * 40 / 400 ))
        elif [ "$lux" -le 3000 ]; then
            target=$(( 115 + (lux - 500) * 70 / 2500 ))
        elif [ "$lux" -le 10000 ]; then
            target=$(( 185 + (lux - 3000) * 50 / 7000 ))
        else
            target=$(( 235 + (lux - 10000) * 20 / 55535 ))
            [ "$target" -gt "$max_bl" ] && target=$max_bl
        fi
    fi

    echo "$target"
}

# 写亮度到硬件（带边界检查）
write_backlight() {
    local val=$1
    [ "$val" -lt 0 ] && val=0
    [ "$val" -gt "$MAX_BL" ] && val=$MAX_BL
    echo "$val" > "$BACKLIGHT_PATH" 2>/dev/null
}

# 日志
log_i() { log -t auto_brightness -p i "$1"; }
log_w() { log -t auto_brightness -p w "$1"; }
log_e() { log -t auto_brightness -p e "$1"; }

# =============================================================================
# I2C Scan — 自动发现真实的 DLP LED 驱动芯片
#
# 车萝卜 C2-Elite 的亮度控制问题:
#   lcd-backlight 0-255 大部分范围无响应
#   真正的 DLP 投射灯亮度可能由独立 LED 驱动芯片控制
#   常见 LED 驱动 I2C 地址: LP5569(0x28), TPS61165(0x2E), MP3388(0x3C)
# =============================================================================
probe_i2c() {
    for bus_path in /sys/bus/i2c/devices/i2c-*; do
        local bus
        bus=${bus_path##*/i2c-}
        [ -d "$bus_path" ] || continue
        for addr in 28 29 2a 2b 2e 2f 3c 3d 3e 3f 58 60 61 62 63 64 65 66 67 6c 6d 6e 6f; do
            local node="/sys/bus/i2c/devices/${bus}-00${addr}/name"
            [ -f "$node" ] || continue
            local name
            name=$(cat "$node" 2>/dev/null)
            [ -n "$name" ] && log_i "I2C$bus:0x$addr = $name"
        done
    done
}

# =============================================================================
# 探索其他可能的亮度控制路径
# =============================================================================
probe_alt_brightness() {
    # 1. MTK disp_pwm
    for p in /sys/devices/platform/11006000.pwm/pwm_debug \
             /sys/devices/platform/disp_aal0/aal_dim \
             /sys/devices/platform/mtk_disp_mgr/*brightness* \
             /sys/class/backlight/*/brightness; do
        [ -f "$p" ] && log_i "AltBright: $p = $(cat "$p" 2>/dev/null)"
    done

    # 2. 其他背光路径
    for p in /sys/devices/virtual/leds/*/brightness; do
        [ -f "$p" ] && {
            local name="${p%/brightness}"
            name="${name##*/}"
            [ "$name" != "lcd-backlight" ] && log_i "LED: $name = $(cat "$p" 2>/dev/null)"
        }
    done
}

# =============================================================================
# Main Loop
# =============================================================================

# --- 初始化 ---
MAX_BL=$(get_max_bl)
log_i "=== auto_brightness v4 STARTED (max_bl=$MAX_BL) ==="

# 后台探测硬件
probe_i2c &
probe_alt_brightness &

# 首次读取 → 即时设置（不等待循环）
FIRST_ALS=$(get_als)
FIRST_DAY_MODE=$(is_night)
FIRST_DESIRED=$(lux_to_brightness "$FIRST_ALS" "$MAX_BL" "$FIRST_DAY_MODE")
write_backlight "$FIRST_DESIRED"
PEAK_BL=$FIRST_DESIRED
SMOOTHED_BL=$FIRST_DESIRED
PEAK_COUNTER=$PEAK_HOLD_SEC
LAST_WRITTEN=$FIRST_DESIRED
log_i "INIT: ALS=$FIRST_ALS mode=$FIRST_DAY_MODE → brightness=$FIRST_DESIRED"

# --- 主循环 ---
while true; do
    sleep "$POLL_INTERVAL"

    # 1. 读取 ALS
    ALS=$(get_als)
    [ -z "$ALS" ] && continue
    # 跳过异常值（偶尔 0 可能是 sensor 未就绪）
    [ "$ALS" -eq 0 ] 2>/dev/null && continue

    # 2. 时间模式检测（每秒刷新）
    DAY_MODE=$(is_night)

    # 3. 计算当前环境对应的目标亮度
    DESIRED=$(lux_to_brightness "$ALS" "$MAX_BL" "$DAY_MODE")

    # ====================================================================
    # 4. Peak-Hold Core Logic
    # ====================================================================
    
    # 4a. ⬆️ FAST ATTACK: 环境变亮 → 即时跳变
    if [ "$DESIRED" -gt "$PEAK_BL" ]; then
        PEAK_BL=$DESIRED
        PEAK_COUNTER=$PEAK_HOLD_SEC
        log_i "FAST_ATK: ALS=$ALS → target=$DESIRED (was $PEAK_BL before reset)"

    # 4b. 🌑 隧道/突然变暗检测: 环境骤降但保持亮度
    elif [ $((PEAK_BL - DESIRED)) -gt "$TUNNEL_THRESHOLD" ] 2>/dev/null; then
        # 环境亮度骤降（进入隧道或地下车库）→ 延长保持
        if [ "$PEAK_COUNTER" -lt $((PEAK_HOLD_SEC * 3)) ]; then
            PEAK_COUNTER=$((PEAK_COUNTER + 1))
            log_i "TUNNEL_DETECT: ALS=$ALS hold extended (cnt=$PEAK_COUNTER)"
        fi

    # 4c. ⏸️ PEAK HOLD: 保持当前峰值
    elif [ "$PEAK_COUNTER" -gt 0 ]; then
        PEAK_COUNTER=$((PEAK_COUNTER - 1))

    # 4d. ⬇️ SLOW RELEASE: 峰值保持结束 → 指数衰减
    else
        # 计算新平滑值: EMA
        local new_bl
        new_bl=$(awk "BEGIN{
            v = $SMOOTHED_BL * (1 - $DECAY_ALPHA) + $DESIRED * $DECAY_ALPHA
            printf \"%d\n\", v + 0.5
        }")
        SMOOTHED_BL=$new_bl
        PEAK_BL=$new_bl
    fi

    # ====================================================================
    # 5. Hysteresis & Write
    # ====================================================================
    local diff=$(( PEAK_BL - LAST_WRITTEN ))
    [ "$diff" -lt 0 ] && diff=$(( -diff ))

    local should_write=0
    # 触发条件:
    #   A. 差值超过迟滞阈值
    #   B. 刚发生了 fast attack (peak_counter just reset)
    #   C. 差值为 0 但需要初始化（LAST_WRITTEN 未设置）
    if [ "$diff" -ge "$HYSTERESIS" ]; then
        should_write=1
    elif [ "$PEAK_COUNTER" -eq "$PEAK_HOLD_SEC" ] && [ "$diff" -ge "$MIN_CHANGE" ]; then
        should_write=1
    elif [ "$LAST_WRITTEN" -lt 0 ]; then
        should_write=1
    fi

    if [ "$should_write" -eq 1 ]; then
        write_backlight "$PEAK_BL"
        LAST_WRITTEN=$PEAK_BL
        log_i "WRITE: ALS=$ALS target=$DESIRED peak=$PEAK_BL (pk_ct=$PEAK_COUNTER diff=$diff)"
    fi
done
