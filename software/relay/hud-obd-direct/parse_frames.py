#!/usr/bin/env python3
"""
HUD 车萝卜私有 OBD 协议解析器（原型）
协议逆向完成 2026-08-16，见 PROTOCOL.md

用法:
  python3 parse_frames.py capture.bin     # 解析抓包文件
  python3 parse_frames.py --live /dev/ttyMT0 115200  # 直接读串口(linux)
"""
import sys, time, struct

STX = 0x7e
ESC = 0x7d

def unescape(data: bytes) -> bytes:
    """还原转义: 7D 01 → 7E, 7D 02 → 7D"""
    out = bytearray()
    i = 0
    while i < len(data):
        b = data[i]
        if b == ESC and i + 1 < len(data):
            nxt = data[i+1]
            if nxt == 0x01:
                out.append(STX); i += 2; continue
            elif nxt == 0x02:
                out.append(ESC); i += 2; continue
        out.append(b); i += 1
    return bytes(out)

def parse_frame(body: bytes):
    """解析原始帧: [cmd 2B][len 2B][count 2B][0000 2B][data N B][xor 1B]"""
    if len(body) < 9:
        return None
    cmd, ln, count, res = struct.unpack(">HHHH", body[:8])
    data = body[8:-1]
    cs = body[-1]
    calc = 0
    for b in body[:-1]:
        calc ^= b
    if len(data) != ln:
        return {"cmd": hex(cmd), "len": ln, "count": count, "data": data.hex(),
                "cs_ok": cs == calc, "len_mismatch": True}
    return {"cmd": hex(cmd), "len": ln, "count": count, "res": hex(res),
            "data": data.hex(), "cs_ok": cs == calc}

def extract_frames(raw: bytes):
    """按 7E 分帧（简化：未处理帧内转义 7D 01）"""
    frames = []
    cur = bytearray()
    for b in raw:
        if b == STX:
            if cur:
                frames.append(bytes(cur))
            cur = bytearray()
        else:
            cur.append(b)
    if cur:
        frames.append(bytes(cur))
    return frames

def main():
    if len(sys.argv) >= 2 and sys.argv[1] == "--live":
        import serial
        port = sys.argv[2]
        baud = int(sys.argv[3]) if len(sys.argv) > 3 else 19200
        s = serial.Serial(port, baud, timeout=1)
        print(f"listening {port} @ {baud}")
        buf = b""
        while True:
            chunk = s.read(64)
            if chunk:
                buf += chunk
                for f in extract_frames(buf):
                    p = parse_frame(f)
                    if p and p["cmd"] != "0x8004":
                        print(time.strftime("%H:%M:%S"), p)
                buf = buf[-256:]
    else:
        raw = open(sys.argv[1], "rb").read()
        print(f"total {len(raw)} bytes")
        for f in extract_frames(raw):
            p = parse_frame(f)
            if p:
                print(p)

if __name__ == "__main__":
    main()
