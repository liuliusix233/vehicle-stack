# DIY HUD（240x960 条屏）

Raspberry Pi 直写 `/dev/fb0` 的仪表盘：车速、导航箭头、海拔、电源状态。无 GUI 栈，延迟 <50ms。

## 安装

```bash
./install.sh                 # 装依赖 + systemd 服务
systemctl status diyhud      # active (running)
```

## 主体

- `fb_dash.py`：主渲染循环（PIL → RGB565/32bpp → fb0）
- `fonts/`：CJK 字体（缺失会画成方块）
- `pi/`：kiosk 与 bridge 辅助脚本
- 旧方案（C2-Elite HUD）已移到 [`alternatives/c2-elite-hud/`](../../alternatives/c2-elite-hud/)

## 坑

- 写 fb0 需要 root 或 `video` 组。
- 熄火前必须清屏，否则残影。
- 屏幕方向/偏移在 `fb_dash.py` 顶部常量里改。