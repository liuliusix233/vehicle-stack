# 第三方代码与许可

本仓库主体为 MIT（见 `LICENSE`）。以下部分来自第三方项目，按原许可使用：

## dji_power_ble（BLE 协议栈）

| 项 | 内容 |
| --- | --- |
| 上游 | https://github.com/zuyan9/ha-dji-power-ble |
| 许可 | **0BSD**（允许商用、修改、再分发，无需署名） |
| 本仓库位置 | `software/power/pi-auto-ac/dji_power_ble/` |
| 改动 | 移除 Home Assistant 组件层，保留协议编解码与 BLE 客户端，供独立脚本调用 |

0BSD 不要求署名，此处署名是出于尊重，也不改变原许可条款。
该目录内保留 `NOTICE` 说明来源。

## 商标声明

DJI、Cadillac、GM 等名称与商标归各自所有者。本项目与上述公司**无任何关联**，
未获其授权或背书。所有接口均来自公开逆向与协议观察，仅供个人自有设备使用。

## 不包含的内容

- 任何厂商 APK、反编译产物或固件
- 任何云端 API 的凭证提取工具
- 任何凭证 / token / 序列号（全部为 `<PLACEHOLDER>`）