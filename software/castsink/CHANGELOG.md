# CarBridge (com.vox.castsink) Changelog

## v130 (130) · 2026-11-01 链路降载重构（仅构建，未部署）
- 新增 `CarBus.kt`：单例常驻 127.0.0.1:8888 socket + 单写线程请求队列；断线退避 1s→2s→5s→10s；全局令牌桶 ≤2 条/秒，超限丢最旧
- `ShellService.java` #BINDSVC：binder 按 pkg+cls 缓存复用（命中直接 transact），空闲 30s 后台 unbind，异常清缓存回退原路径；输出格式不变
- `NavPush.kt`：改走 CarBus，周期保持 2500ms；新增新鲜度闸门（query 往返 >1500ms 丢弃不推）；payload 去重保留
- 退避化：ObdNet 1000ms 基准 / Telemetry 250→2000ms / NetState 400→1000ms，失败均 2s→5s→10s 退避，成功即回基准
- 新增 `#BUSPAUSE <on|off>` 运行时急停：on 时 CarBus 拒发一切命令，各轮询循环只 sleep 不请求；默认 off

## v84 (84) · 2026-10-26 13:21
- `HybridPower.kt` 重写：SOC 跳格一次性修正（±120W/格、总量 ±400W、tau40s 回零），静止 20s 强制收敛；DCDC 基线 −110~−190W
- `MotorSound.kt` v84 电动涡轮音色：70→160Hz 主体 + 2.5× 颤音 + 5× 泛音(6%) + 低通空气声；tau 3s
- `#HYB` 增输出 trim / assist / regen

## v83 (83) · 13:16
- 去掉连续 SOC 反馈（自激振荡源头），改为按格修正
- 音色换成双正弦失谐 + 低八度（去 3 次谐波/噪声）

## v82 (82) · 13:13
- SOC 提升为硬约束；去除虚高怠速补电（<50% 才补，≤400W）；新增 DCDC 基线放电

## v81 (81) · 13:09
- 新增物理反推模型 `HybridPower.kt`（车轮功率/助力/回收/补电 + SOC trim）

## v80 (80) · 13:06
- SOC 20s 不变即功率归零，修掉"平地爬升到 2.6kW"的假功率

## v79 (79) · 13:03
- 主屏控制面板（电机声开关/音量±/拉起第二屏/实时状态行），不再纯透明
- 功率加斜率限速 ±1200W/s、死区 300W；新增 `#HYB`

## v78 (78) · 13:00
- 电机声改低沉背景声（55→195Hz、去噪、16% 底声）

## v77 (77) · 12:58
- 修 `#MOTOR` 参数解析（cmd 被 toUpperCase，参数须 toLowerCase）

## v76 (76) · 12:52
- 新增 `MotorSound.kt`（AudioTrack 合成）+ `#MOTOR on|off|vol|status`
- 48V 功率窗口 2.2s / 斜坡限速；SOC 采样改为变化即采
