# 自动 AC 策略

> 代码：`software/power/pi-auto-ac/bridge.py` → `async def auto_ac()`
> 参数：`policy.json`（模板见 `policy.example.json`）

## 判定流程（每轮）

```
1. enabled?           否 → disabled
2. arm_switch 为 off? 是 → paused(HA)
3. 手动接管期内?       是 → manual(Ns)（默认 900s 后交还）
4. 读母线电压 v：优先本地 BLE，无值才回退 HA 实体
5. 电量守卫：SOC < soc_min_pct → 强制关 AC，state=soc-low
6. 迟滞判定：v >= on_v 想开；v <= off_v 想关；中间保持
7. 持续 hold_on_s / hold_off_s 秒后才执行
8. 冷却 min_interval_s 内不重复写
9. 反向保护：刚开 60s 内不关；刚关 30s 内不开
10. 写 BLE 端口 (2,1) → 同步 HA helper
```

## 默认参数

| 参数 | 默认 | 作用 |
| --- | --- | --- |
| `on_v` / `off_v` | 13.2 / 13.0 V | 开/关阈值（迟滞 0.2V 防抖） |
| `hold_on_s` / `hold_off_s` | 12 / 15 s | 必须持续满足才动作 |
| `min_interval_s` | 30 s | 两次写 AC 的最小间隔 |
| `ignore_off_after_on_s` | 60 s | 开完 60s 内不关（躲过启动瞬间掉压） |
| `ignore_on_after_off_s` | 30 s | 关完 30s 内不开 |
| `manual_hold_s` | 900 s | 手动拨 AC 后的接管窗口 |
| `soc_min_pct` | 10 % | 低于此值禁止自动开（保护主电源） |
| `soc_resume_pct` | 12 % | 回升到此值以上解除 |
| `rail_warn_v` / `rail_cut_v` | 11.8 / 11.5 V | 车轨压保护：报警 / 降载 |

## 状态机

| state | 含义 |
| --- | --- |
| `disabled` | 策略关闭 |
| `paused(HA)` | HA 开关关掉（人工暂停） |
| `manual(Ns)` | 手动接管中，N 秒后恢复 |
| `no_data` | 读不到电压 |
| `soc-low(x%)` | 电量守卫触发，强制关 AC |
| `wait(x.xxV)` | 电压在阈值中间 / 未满足保持时间 |
| `cool(x.xxV)` | 冷却中 |
| `keep-on` / `keep-off` | 反向保护窗口内 |
| `ac=on(x.xxV)` / `ac=off(x.xxV)` | 已执行 |
| `write_fail:...` | BLE 写入失败 |

## 为什么用电压而不是点火信号

1. 不改车、不接 CAN、不等 VHAL。
2. 熄火后 12V 母线维持一段时间，关 AC 有天然延时，不会一熄火就断电。
3. 发电机在转时电压稳定在 13.8–14.6V，远高于 13.2V 阈值，误判率低。

## 已知边界

- 电瓶老化或大负载启动时母线可能瞬时跌到 11.5V，触发 rail guard 属正常保护。
- 只用 BLE 本地读数时，电源休眠会导致 `no_data`，此时策略不动（不会乱开）。
- 手动拨 AC 被视为人工意图，900s 内策略不抢控制权。