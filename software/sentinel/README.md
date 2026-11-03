# Sentinel（哨兵）

> 本模块**没有自研代码**：采用开箱即用的成品 4CH AHD DVR（见 `hardware/BOM.md`），装上即用。

## 它做什么

- 四路 360 环视录像（前 / 后 / 左 / 右）
- 停车监控（碰撞唤醒 / 移动侦测）
- 4G + Wi-Fi 远程回放
- 独立 12V 供电，不依赖车机与 Pi

## 安装

见 `hardware/INSTALL.md` 与 `docs/gallery/sentinel-dvr-trunk.jpg`（后备箱侧壁固定位）。

## 与软件栈的关系

哨兵是**独立系统**：它不读 VHAL、不连 HA、不参与本仓库代码。
车上真正由本仓库驱动的部分只有：CastSink / ShellBridge / HUD / Power Monitor / Relay。
