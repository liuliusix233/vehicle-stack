#!/usr/bin/env python3
"""预热离线瓦片：围绕中心点在高德瓦片坐标里抓一圈，走本机 tile_server 落盘缓存。
用法: python3 pi/warm_tiles.py [lat] [lon] [radius_km] [zmin] [zmax]
默认：无锡锡山区，3km，z13-16
"""
import math, sys, urllib.request

LAT = float(sys.argv[1]) if len(sys.argv) > 1 else 31.5896
LON = float(sys.argv[2]) if len(sys.argv) > 2 else 120.3607
RADIUS_KM = float(sys.argv[3]) if len(sys.argv) > 3 else 3.0
ZMIN = int(sys.argv[4]) if len(sys.argv) > 4 else 13
ZMAX = int(sys.argv[5]) if len(sys.argv) > 5 else 16
BASE = 'http://127.0.0.1:8477/t'

# WGS84 -> GCJ02（高德瓦片用的是 GCJ02）
PI = math.pi
AXIS, EE = 6378245.0, 0.00669342162296594323


def _tlat(x, y):
    r = -100 + 2 * x + 3 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * math.sqrt(abs(x))
    r += (20 * math.sin(6 * x * PI) + 20 * math.sin(2 * x * PI)) * 2 / 3
    r += (20 * math.sin(y * PI) + 40 * math.sin(y / 3 * PI)) * 2 / 3
    r += (160 * math.sin(y / 12 * PI) + 320 * math.sin(y * PI / 30)) * 2 / 3
    return r


def _tlon(x, y):
    r = 300 + x + 2 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * math.sqrt(abs(x))
    r += (20 * math.sin(6 * x * PI) + 20 * math.sin(2 * x * PI)) * 2 / 3
    r += (20 * math.sin(x * PI) + 40 * math.sin(x / 3 * PI)) * 2 / 3
    r += (150 * math.sin(x / 12 * PI) + 300 * math.sin(x / 30 * PI)) * 2 / 3
    return r


def wgs2gcj(lon, lat):
    dlat = _tlat(lon - 105, lat - 35)
    dlon = _tlon(lon - 105, lat - 35)
    rad = lat / 180 * PI
    m = 1 - EE * math.sin(rad) ** 2
    sq = math.sqrt(m)
    dlat = (dlat * 180) / ((AXIS * (1 - EE)) / (m * sq) * PI)
    dlon = (dlon * 180) / (AXIS / sq * math.cos(rad) * PI)
    return lon + dlon, lat + dlat


glon, glat = wgs2gcj(LON, LAT)
total = ok = 0
for z in range(ZMIN, ZMAX + 1):
    ppm = 156543.03392 * math.cos(glat * PI / 180) / (2 ** z)   # 米/像素
    half = RADIUS_KM * 1000 / (ppm * 256)                        # 半径（瓦片数）
    n = 2 ** z
    cx = (glon + 180) / 360 * n
    sy = math.sin(glat * PI / 180)
    cy = (0.5 - math.log((1 + sy) / (1 - sy)) / (4 * PI)) * n
    x0, x1 = int(cx - half) - 1, int(cx + half) + 1
    y0, y1 = int(cy - half) - 1, int(cy + half) + 1
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            total += 1
            try:
                urllib.request.urlopen('%s/%d/%d/%d.png' % (BASE, z, x, y), timeout=8).read()
                ok += 1
            except Exception:
                pass
    print('z=%d tiles=%d ok=%d' % (z, (x1 - x0 + 1) * (y1 - y0 + 1), ok))
print('done total=%d ok=%d' % (total, ok))
