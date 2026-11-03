#!/usr/bin/env python3
"""
HUD ttyMT0 串口双向抓包器（部署到 HUD 或用 Nano 转发）
- 打开 /dev/ttyMT0
- 持续读取：把原始字节 + 7E 分帧打印
- 可选: 回放 aicore 的请求帧 7E8004000600010000000101000300807E 看是否有响应

依赖: HUD 上无 python，此脚本跑在 Nano 上，通过 adb forward 转发串口？
替代: 直接 adb shell + busybox 方案更实际（见 sniff_obd.sh）
此文件保留用于离线分析捕获的 bin 文件。
"""
import sys, time

def analyze(path):
    data = open(path, 'rb').read()
    print(f"total {len(data)} bytes")
    # 按 7E 分帧
    frames = data.split(b'\x7e')
    print(f"{len(frames)-1} potential frames (0x7e-separated)")
    for i, f in enumerate(frames[:20]):
        if f:
            print(f"[{i}] {f.hex()}")

if __name__ == '__main__':
    analyze(sys.argv[1])
