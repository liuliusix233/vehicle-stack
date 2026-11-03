#!/usr/bin/env python3
"""轻量 diyHUD 仪表：PIL 直接画 /dev/fb0，不跑 WebKit。
数据源：http://127.0.0.1:8477/state.json（tile_server），2 FPS。
目标屏：/dev/fb0（实测面板 240x960，16bpp）；内容按 960x240 横屏绘制后旋转。
"""
import json, os, time, mmap, urllib.request
import numpy as np
from PIL import Image, ImageDraw, ImageFont

FB = os.environ.get("FB", "/dev/fb0")
W, H = 960, 240
ROT = int(os.environ.get("ROT", "90"))      # 90 = 顺时针
FLIPY = os.environ.get("FLIPY", "1") == "1"
DX = int(os.environ.get("DX", "60"))
DY = int(os.environ.get("DY", "0"))
STATE_URL = os.environ.get("STATE_URL", "http://127.0.0.1:8477/state.json")
FPS = float(os.environ.get("FPS", "2"))

INK = (255, 255, 255)
MUTED = (170, 185, 190)  # 次要信息也提亮
ACC = (255, 255, 255)   # 亮度拉满：箭头/路名也用纯白
RED = (240, 92, 92)
AMB = (240, 190, 90)
BLUE = (90, 170, 240)
BG = (6, 10, 12)

FONT_CANDIDATES = [
    "/home/<USER>/fonts/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/home/<USER>/fonts/DroidSansFallbackFull.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
]

def has_cjk(s):
    return any('\u4e00' <= ch <= '\u9fff' for ch in s)

def font_cjk(sz):
    for p in ("/home/<USER>/fonts/DroidSansFallbackFull.ttf",
              "/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf",
              "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
              "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"):
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, sz)
            except Exception:
                pass
    return font(sz)

def font(sz, bold=True):
    for p in FONT_CANDIDATES:
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, sz)
            except Exception:
                pass
    return ImageFont.load_default()

def fmt(v, nd=0, unit=""):
    if v is None or v < 0:
        return "--" + unit
    return ("%.*f" % (nd, v)) + unit

def fetch():
    try:
        with urllib.request.urlopen(STATE_URL, timeout=1.5) as r:
            return json.loads(r.read().decode())
    except Exception:
        return None

def draw_bar(d, x, y, w, h, frac, color):
    d.rounded_rectangle([x, y, x + w, y + h], radius=h // 2, fill=(28, 36, 40))
    if frac > 0:
        fw = max(2, int(w * min(1.0, frac)))
        d.rounded_rectangle([x, y, x + fw, y + h], radius=h // 2, fill=color)

def render_old(st, now):
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    f_big, f_mid, f_sm = font(118), font(34), font(22)

    ok = bool(st) and st.get("src_ok")
    age = None
    if st and st.get("ts"):
        age = max(0.0, now - float(st["ts"]))
    fresh = ok and age is not None and age < 5.0

    sp = st.get("speed") if st else None
    rpm = st.get("rpm") if st else None
    gear = (st.get("gear") or "--") if st else "--"
    if not fresh:
        sp = rpm = None
        gear = "--"

    # 左：车速 + 档位 + 转速
    txt = fmt(sp, 0)
    bb = d.textbbox((0, 0), txt, font=f_big)
    d.text((24, 60 - bb[1]), txt, font=f_big, fill=INK)
    d.text((24 + (bb[2] - bb[0]) + 14, 96), "km/h", font=f_sm, fill=MUTED)
    d.text((28, 18), "GEAR " + str(gear), font=f_mid, fill=ACC)
    frac = 0.0 if not rpm or rpm < 0 else min(1.0, rpm / 6500.0)
    draw_bar(d, 24, 190, 260, 14, frac, ACC)
    d.text((24, 206), "RPM " + fmt(rpm, 0), font=f_sm, fill=MUTED)

    # 中：导航
    d.line([(320, 16), (320, 224)], fill=(26, 34, 38), width=2)
    nav = (st or {}).get("nav") or {}
    nav_live = bool((st or {}).get("navLive")) and nav.get("distance") is not None
    act = (nav.get("action") or "").strip()
    road = (nav.get("road") or "").strip()
    dist = nav.get("distance")
    if not nav_live:
        act, road, dist = "", "", None
    d.text((344, 18), act[:14] or "—", font=font_cjk(34) if has_cjk(act) else f_mid, fill=ACC)
    d.text((344, 62), (str(int(dist)) + " m") if isinstance(dist, (int, float)) else "—", font=f_big if False else f_mid, fill=INK)
    d.text((344, 104), road[:16] or "—", font=f_sm, fill=MUTED)
    remain = nav.get("remain")
    d.text((344, 136), (("%.1f km" % remain) if isinstance(remain, (int, float)) else "—"), font=f_sm, fill=MUTED)
    d.text((344, 164), (nav.get("eta") or ""), font=f_sm, fill=MUTED)

    # 右：红绿灯 + 车辆信息
    d.line([(636, 16), (636, 224)], fill=(26, 34, 38), width=2)
    lig = (st or {}).get("light") or {}
    color, sec = lig.get("color"), lig.get("sec")
    if not (isinstance(sec, (int, float)) and 0 < sec <= 60):
        color, sec = None, None
    cx, cy, r = 690, 60, 26
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(RED if color == "red" else AMB if color == "yellow" else (86, 220, 140) if color == "green" else (26, 34, 38)))
    d.text((cx + 40, cy - 22), (str(int(sec)) + "s") if sec else "--", font=f_mid, fill=INK)

    rows = [
        ("temp", fmt((st or {}).get("cool"), 0, "C")),
        ("volt", fmt((st or {}).get("volt"), 1, "V")),
        ("odo", fmt((st or {}).get("odo"), 0, "km")),
        ("soc", fmt((st or {}).get("soc"), 0, "%")),
    ]
    y = 108
    for k, v in rows:
        d.text((668, y), k, font=f_sm, fill=MUTED)
        d.text((740, y), v, font=f_sm, fill=INK)
        y += 29

    if st is None or (age is not None and age > 30):
        d.text((700, 200), "NO DATA", font=f_sm, fill=RED)
    return img

MAN_CN = {
    "直行": "straight", "左转": "left", "右转": "right",
    "左前方": "sleft", "右前方": "sright", "左后方": "sleft", "右后方": "sright",
    "掉头": "uturn", "进入环岛": "round", "驶出环岛": "round",
    "逆时针环岛": "round", "顺时针环岛": "round", "进入匝道": "sright",
    "驶出匝道": "sleft", "目的地": "arrive", "途经点": "straight",
}

_MISS = None
_GCACHE = {}

def _glyph_ok(f, ch):
    """判断字体里是否真有该字形的位图（缺字形时 PIL 画 .notdef 方框）。"""
    global _MISS
    key = (id(f), ch)
    if key in _GCACHE:
        return _GCACHE[key]
    try:
        if _MISS is None:
            _MISS = bytes(f.getmask("\ue000"))
        ok = bytes(f.getmask(ch)) != _MISS
    except Exception:
        ok = True
    _GCACHE[key] = ok
    return ok

def _allowed(ch):
    """白名单：ASCII 可见字符 + 常用 CJK + 全角标点。Droid 回退字体没有拉丁区，
    乱码（Latin-1 尾巴）必须挡掉，否则整行变方框。"""
    o = ord(ch)
    if 0x20 <= o <= 0x7E:
        return True
    if 0x4E00 <= o <= 0x9FFF or 0x3400 <= o <= 0x4DBF:
        return True
    if 0x3000 <= o <= 0x303F or 0xFF01 <= o <= 0xFF5E:
        return True
    return ch.isspace()

def clean_text(f, s):
    """按字符集白名单净化，避免方框与乱码上屏。"""
    if not s:
        return s
    out = []
    for ch in s:
        if _allowed(ch) and _glyph_ok(f, ch):
            out.append(ch)
        else:
            out.append("·")
    t = "".join(out).strip("·").strip()
    while "··" in t:
        t = t.replace("··", "·")
    return t

def draw_maneuver(d, kind, cx, cy, size):
    """几何绘制转向图标：杆用矩形、箭头用等边三角，接缝同色不叠加毛边。
    支持 straight/left/right/sleft/sright/uturn/round/arrive。"""
    a = (kind or "").strip()
    if a not in ("straight", "left", "right", "sleft", "sright", "uturn", "round", "arrive"):
        a = MAN_CN.get(a) or a.lower()
    s = float(size)
    col = ACC
    w = max(14, int(s * 0.17))      # 杆宽
    h = w // 2
    hw = int(w * 2.0)               # 箭头底宽
    hl = int(w * 1.7)               # 箭头长
    top = int(cy - s / 2 + 2)
    bot = int(cy + s / 2 - 2)
    left = int(cx - s / 2 + 2)
    right = int(cx + s / 2 - 2)

    def stem(x0, y0, x1, y1):
        d.rectangle([x0, y0, x1, y1], fill=col)

    if a == "arrive":
        stem(cx - h, top, cx + h, bot)
        d.polygon([(cx + h, top), (cx + h + int(s * 0.30), top + int(s * 0.11)), (cx + h, top + int(s * 0.22))], fill=col)
        return
    if a == "round":
        r = int(s * 0.32)
        d.arc([cx - r, cy - r, cx + r, cy + r], 40, 320, fill=col, width=w)
        import math
        ang = math.radians(40)
        px, py = cx + r * math.cos(ang), cy - r * math.sin(ang)
        d.polygon([(px - hw * 0.55, py - hw * 0.55), (px + hw * 0.55, py - hw * 0.45), (px - hw * 0.15, py + hw * 0.75)], fill=col)
        return
    if a == "uturn":
        r = int(s * 0.20)
        d.arc([cx - r - h, top, cx + r + h, top + 2 * r], 180, 360, fill=col, width=w)
        stem(cx + r - h, top + r - h, cx + r + h, bot)          # 右腿到底
        stem(cx - r - h, top + r - h, cx - r + h, cy)           # 左腿止于中线
        d.polygon([(cx - r, cy + hl), (cx - r - hw // 2, cy), (cx - r + hw // 2, cy)], fill=col)
        return
    if a in ("left", "right", "sleft", "sright"):
        t = -1 if a in ("left", "sleft") else 1
        jy = int(cy - s * 0.04)
        stem(cx - h, jy, cx + h, bot)                 # 竖杆
        if t > 0:
            d.rectangle([cx - h, jy - h, right - hl, jy + h], fill=col)   # 横臂
            d.polygon([(right, jy), (right - hl, jy - hw // 2), (right - hl, jy + hw // 2)], fill=col)
        else:
            d.rectangle([left + hl, jy - h, cx + h, jy + h], fill=col)
            d.polygon([(left, jy), (left + hl, jy - hw // 2), (left + hl, jy + hw // 2)], fill=col)
        return
    stem(cx - h, top + hl, cx + h, bot)
    d.polygon([(cx, top), (cx - hw // 2, top + hl), (cx + hw // 2, top + hl)], fill=col)


GATE_URL = os.environ.get("GATE_URL", "http://127.0.0.1:8077/")
_gate_cache = {"t": 0.0, "v": None}

def gate_now():
    """熄火门控状态：asleep/blind 时 HUD 显示黑图层（与浏览器版同款行为）。"""
    now = time.time()
    if now - _gate_cache["t"] < 2.0:
        return _gate_cache["v"]
    _gate_cache["t"] = now
    try:
        with urllib.request.urlopen(GATE_URL, timeout=0.8) as r:
            j = json.loads(r.read().decode())
        _gate_cache["v"] = j.get("gate")
    except Exception:
        _gate_cache["v"] = None
    return _gate_cache["v"]

def fit_font(d, text, target_h, cjk=False, hi=260, max_w=None):
    fn = font_cjk if cjk else font
    lo, best = 8, 8
    while lo <= hi:
        mid = (lo + hi) // 2
        bb = d.textbbox((0, 0), text, font=fn(mid))
        ok = (bb[3] - bb[1]) <= target_h
        if ok and max_w is not None and (bb[2] - bb[0]) > max_w:
            ok = False
        if ok:
            best, lo = mid, mid + 1
        else:
            hi = mid - 1
    return fn(best)

def render(st, now):
    """v2 版式：960x240，上下各 20px 安全框；左=动作图标+距离/路名上下叠放，右=时速顶天立地。"""
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)

    if gate_now() in ("asleep", "blind"):
        return img   # 熄火黑图层：整屏纯背景色，打火自动恢复

    ok = bool(st) and st.get("src_ok")
    age = None
    if st and st.get("ts"):
        age = max(0.0, now - float(st["ts"]))
    fresh = ok and age is not None and age < 5.0

    sp = (st or {}).get("speed") if fresh else None
    nav = (st or {}).get("nav") or {}
    _nts = float(nav.get("ts") or 0)
    nav_live = nav.get("distance") is not None and (_nts == 0 or (now - _nts) < 180)
    act = (nav.get("action") or "").strip() if nav_live else ""
    road = clean_text(font_cjk(36), (nav.get("road") or "").strip()) if nav_live else ""
    dist = nav.get("distance") if nav_live else None

    TOP, BOT = 20, 220
    CY = (TOP + BOT) // 2

    stxt = ("%d" % sp) if isinstance(sp, (int, float)) else "--"
    fs = 200
    while fs > 80:
        f_sp = font(fs)
        sb = d.textbbox((0, 0), stxt, font=f_sp)
        if (sb[3] - sb[1]) <= (BOT - TOP) - 2 and (sb[2] - sb[0]) <= 300:
            break
        fs -= 4
    f_sp = font(fs)
    sb = d.textbbox((0, 0), stxt, font=f_sp)
    ww = sb[2] - sb[0]
    hh = sb[3] - sb[1]
    R = 940
    d.text((R - ww, CY - hh // 2 - sb[1]), stxt, font=f_sp, fill=INK)

    f_unit = font(26)
    ub = d.textbbox((0, 0), "km/h", font=f_unit)
    uw = ub[2] - ub[0]
    ux = R - ww - uw - 10
    d.text((ux, CY + hh // 2 - (ub[3] - ub[1]) - 4), "km/h", font=f_unit, fill=MUTED)

    # 左侧导航组：箭头与文字块整体高度都等于车速数字高度
    if isinstance(dist, (int, float)):
        dtxt = ("%.1fkm" % (dist / 1000.0)) if dist >= 1000 else ("%dm" % int(dist))
    else:
        dtxt = "--"
    target = max(120, hh)
    gap = max(4, int(target * 0.05))
    road_target = int(target * 0.26)
    dist_target = target - gap - road_target

    IS = int(target)
    tx = 20 + IS + 16
    maxw = max(120, ux - 24 - tx)
    draw_maneuver(d, (st or {}).get("maneuver") or act, 20 + IS // 2, CY, IS)

    if road:
        f_road = fit_font(d, road, road_target, cjk=True, max_w=maxw)
        rb = d.textbbox((0, 0), road, font=f_road)
        rh = rb[3] - rb[1]
    else:
        f_road = None
        rb = (0, 0, 0, 0)
        rh = 0
    f_dist = fit_font(d, dtxt, dist_target, max_w=maxw)
    db = d.textbbox((0, 0), dtxt, font=f_dist)
    dh = db[3] - db[1]

    block = dh + gap + rh
    ty = CY - block // 2
    d.text((tx, ty - db[1]), dtxt, font=f_dist, fill=INK)

    maxw = max(80, ux - 24 - tx)
    rtxt = road
    while rtxt and d.textbbox((0, 0), rtxt, font=f_road)[2] > maxw:
        rtxt = rtxt[:-1]
    if rtxt and rtxt != road:
        rtxt = rtxt[:-1] + "…"
    if f_road is not None and rtxt:
        d.text((tx, ty + dh + gap - rb[1]), rtxt, font=f_road, fill=ACC)
    return img


def to_portrait(img):
    p = img.rotate(-ROT, expand=True) if ROT else img
    if FLIPY:
        p = p.transpose(Image.FLIP_TOP_BOTTOM)
    return p

def fb_geom():
    try:
        with open("/sys/class/graphics/fb0/virtual_size") as fh:
            w, h = [int(x) for x in fh.read().strip().split(",")]
        with open("/sys/class/graphics/fb0/bits_per_pixel") as fh:
            bpp = int(fh.read().strip())
        return w, h, w * h * bpp // 8
    except Exception:
        return 360, 960, 360 * 960 * 2

def main():
    f = open(FB, "r+b", buffering=0)
    fbw, fbh, size = fb_geom()
    mm = mmap.mmap(f.fileno(), size)
    while True:
        st = fetch()
        img = to_portrait(render(st, time.time()))
        canvas = Image.new("RGB", (fbw, fbh), BG)
        canvas.paste(img, (DX, DY))
        a = np.asarray(canvas.convert("RGB"), dtype=np.uint16)
        buf = (((a[:, :, 0] >> 3) << 11) | ((a[:, :, 1] >> 2) << 5) | (a[:, :, 2] >> 3)).astype("<u2").tobytes()
        mm.seek(0)
        mm.write(buf[:size])
        time.sleep(1.0 / FPS)

if __name__ == "__main__":
    main()
