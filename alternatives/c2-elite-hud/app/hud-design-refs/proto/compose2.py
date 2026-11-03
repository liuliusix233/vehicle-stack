#!/usr/bin/env python3
"""compose2.py — v2 review assets."""
import glob, os
from PIL import Image, ImageDraw, ImageFont

D = os.path.dirname(os.path.abspath(__file__))
S, OUT = D + "/shots", D + "/review"
os.makedirs(OUT, exist_ok=True)
CJK = None
for p in ("/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc",
          "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"):
    if os.path.exists(p):
        CJK = p; break
BG, FG, MUT, ACC = (10, 14, 18), (232, 240, 246), (140, 158, 172), (255, 87, 34)
f = lambda s: ImageFont.truetype(CJK, s)


def hero(src, dst, title, b1, b2):
    im = Image.open(src).convert("RGB")
    cv = Image.new("RGB", (960, 60 + im.height + 84), BG)
    cv.paste(im, (0, 60))
    dr = ImageDraw.Draw(cv)
    dr.text((16, 14), title, font=f(30), fill=FG)
    dr.text((16, 60 + im.height + 8), b1, font=f(19), fill=MUT)
    dr.text((16, 60 + im.height + 36), b2, font=f(19), fill=MUT)
    cv.save(dst)


hero(f"{S}/v2_s1_2x.png", f"{OUT}/20_s1_v2_hero.png", "SPORT · S1「GT3 DDU」v2 — Rajdhani Bold",
     "上：换挡灯带 14 段（22px 高）｜罗盘右下角；中：速度 112px / 转速弧 22 段 270°(13px) + 档位居中 / 能量条 2 行；下：ODO·状态·电压·时钟（4 组，20px 间距）",
     "外层留白 14px，列间距 20px 起；已去掉 SRV/EH 等非驾驶项（留在 A/B 主题）")
hero(f"{S}/v2_s2_2x.png", f"{OUT}/21_s2_v2_hero.png", "SPORT · S2「速度优先」v2 — Rajdhani Bold",
     "上：灯带 + 罗盘；中：档位 68px / 速度 104px 居中 / 转速 46px；转速满幅条 22 段（20px）在下方独立成带",
     "垂直方向分成 5 条带（灯带 / 中区 / 转速条 / 底栏），互不挤压")

# 排版前后对比
old = Image.open(f"{S}/hero_s1_2x.png").convert("RGB")
new = Image.open(f"{S}/v2_s1_2x.png").convert("RGB")
cv = Image.new("RGB", (960, 40 + 480 + 40 + 480 + 40), BG)
dr = ImageDraw.Draw(cv)
dr.text((8, 8), "v1（你说太挤）：4 组信息塞进顶栏、右侧 3 行堆叠、底栏 5 组、RPM 数字压弧", font=f(22), fill=MUT)
cv.paste(old, (0, 40))
dr.text((8, 528), "v2（重构）：顶栏 2 组 / 中区 3 组 / 底栏 4 组，留白 14→20px，字号大一档", font=f(22), fill=ACC)
cv.paste(new, (0, 560))
cv.save(f"{OUT}/22_spacing.png")

# 字体在版式中的对比（4 种 × 1:1）
variants = [("v2_s1_2x.png", "Rajdhani Bold（推荐）"), ("v2_s1_font_saira.png", "Saira Condensed Bold"),
            ("v2_s1_font_chakra.png", "Chakra Petch Bold"), ("v2_s1_font_barlow.png", "Barlow Semi Condensed Bold")]
cv = Image.new("RGB", (960, 4 * (240 + 34) + 36), BG)
dr = ImageDraw.Draw(cv)
dr.text((8, 8), "同一版式换字体（1:1 真机尺寸）", font=f(24), fill=FG)
for i, (fn, name) in enumerate(variants):
    im = Image.open(f"{S}/{fn}").convert("RGB")
    if im.width > 480:
        im = im.resize((480, 240), Image.LANCZOS)
    y = 36 + i * (240 + 34)
    cv.paste(im, (8, y))
    dr.text((500, y + 8), name, font=f(22), fill=FG)
cv.save(f"{OUT}/23_font_variants.png")

# GIF
frames = sorted(glob.glob(f"{S}/g[0-9][0-9].png"))
ims = [Image.open(x).convert("RGB") for x in frames]
durs = [200] * 4 + [130] * (len(ims) - 5) + [500]
ims[0].save(f"{OUT}/24_sport_v2_demo.gif", save_all=True, append_images=ims[1:],
            duration=durs, loop=0, optimize=True, disposal=2)
print("ok", sorted(os.listdir(OUT)))
