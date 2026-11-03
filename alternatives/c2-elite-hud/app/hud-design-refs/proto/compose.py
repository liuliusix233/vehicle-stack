#!/usr/bin/env python3
"""compose.py — build the review assets from the rendered frames."""
import glob, os, sys
from PIL import Image, ImageDraw, ImageFont

D = os.path.dirname(os.path.abspath(__file__))
S = os.path.join(D, "shots")
OUT = os.path.join(D, "review")
os.makedirs(OUT, exist_ok=True)

CJK = None
for p in ("/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc",
          "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
          "/usr/share/fonts/truetype/noto/NotoSansCJK-Bold.ttc"):
    if os.path.exists(p):
        CJK = p
        break
if CJK is None:
    hits = os.popen("fc-list :lang=zh file 2>/dev/null | head -1").read().strip()
    CJK = hits.split(":")[0] or "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"

BG = (10, 14, 18)
FG = (232, 240, 246)
MUT = (140, 158, 172)
ACC = (255, 87, 34)


def font(sz):
    return ImageFont.truetype(CJK, sz)


def up(img, k):
    return img.resize((img.width * k, img.height * k), Image.NEAREST)


def bar(dr, xy, text, sz=22, color=FG, anchor="lt"):
    dr.text(xy, text, font=font(sz), fill=color, anchor=anchor)


# ── 1. hero sheets ───────────────────────────────────────────────
for v, title, bullets in (
    ("s1", "SPORT · S1「GT3 DDU」",
     "换挡灯带 15 段（绿→琥珀→红）｜转速弧 24 段 270° 分区｜档位居中 + 转速数字｜速度 100px"),
    ("s2", "SPORT · S2「速度优先」",
     "速度 104px 居中｜满幅转速条 24 段｜档位 62px + 电压｜底部双能量条"),
):
    hero = Image.open(f"{S}/hero_{v}_2x.png").convert("RGB")
    cv = Image.new("RGB", (960, 60 + 480 + 84), BG)
    cv.paste(hero, (0, 60))
    dr = ImageDraw.Draw(cv)
    bar(dr, (16, 14), title, 30)
    bar(dr, (16, 548), bullets, 19, MUT)
    bar(dr, (16, 576), "480 × 240 px ｜ 纯黑底 ｜ 标签 ≥12px 全部加粗 ｜ 实心笔画 ≥2px ｜ 无 rAF 常驻动画（CSS transition 驱动）", 19, MUT)
    cv.save(f"{OUT}/01_{v}_hero.png")

# ── 2. state sheet (1:1) ─────────────────────────────────────────
states = [("f00.png", "① 启动自检 弧线扫过 0→7000"), ("f12.png", "② 换挡提示 7080rpm 灯带频闪"),
          ("state_lowfuel_2x.png", "③ 低油量 9% 红色脉冲")]
tiles = []
for fn, cap in states:
    im = Image.open(f"{S}/{fn}").convert("RGB")
    if im.width > 480:
        im = im.resize((480, 240), Image.LANCZOS)
    tiles.append((im, cap))
cv = Image.new("RGB", (480 * 3 + 24, 240 + 80), BG)
dr = ImageDraw.Draw(cv)
for i, (im, cap) in enumerate(tiles):
    x = 8 + i * (480 + 4)
    cv.paste(im, (x, 44))
    bar(dr, (x + 8, 12), cap, 21)
cv.save(f"{OUT}/02_states.png")

# ── 3. compare: existing themes vs sport ─────────────────────────
a = Image.open(f"{D}/../hud_cur.png").convert("RGB")
b = Image.open(f"{D}/../hud115_ThemeB_确认_20260823.png").convert("RGB")
new = Image.open(f"{S}/hero_s1_2x.png").convert("RGB")
cv = Image.new("RGB", (960, 240 + 40 + 480 + 90), BG)
dr = ImageDraw.Draw(cv)
cv.paste(a, (0, 36))
cv.paste(b, (480, 36))
bar(dr, (8, 8), "现有 Theme A（线上）", 22)
bar(dr, (488, 8), "现有 Theme B（线上）", 22)
bar(dr, (8, 292), "SPORT · S1（新方案，2× 放大看细节）", 24, ACC)
cv.paste(new, (0, 320))
bar(dr, (8, 810), "同样 480×240：速度提到 100px、标签全部 ≥12px 且加粗、弧线与灯带用 13–16px 实心块", 19, MUT)
bar(dr, (8, 836), "（旧版 1px 刻度线与 1.6–2.5vw 小字在 HUD 合成器上基本看不见）", 19, MUT)
cv.save(f"{OUT}/03_compare.png")

# ── 4. animated demo ─────────────────────────────────────────────
frames = sorted(glob.glob(f"{S}/f[0-9][0-9].png"))
ims = [Image.open(f).convert("RGB") for f in frames]
durs = [200] * 4 + [130] * (len(ims) - 5) + [500]
ims[0].save(f"{OUT}/04_sport_demo.gif", save_all=True, append_images=ims[1:],
            duration=durs, loop=0, optimize=True, disposal=2)
print("sheets ok:", sorted(os.listdir(OUT)))
print("gif frames:", len(ims), "size:", os.path.getsize(f"{OUT}/04_sport_demo.gif"))
