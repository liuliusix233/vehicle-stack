#!/usr/bin/env python3
"""HUD 瓦片服务：/t/{z}/{x}/{y}.png = 高德路网叠加层（透明底）单色化；其余走静态文件"""
import io, os, sys, urllib.request
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from PIL import Image

ROOT = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(ROOT, 'tilecache')
UA = 'Mozilla/5.0 (X11; Linux x86_64) HudBench/1.0'
COLOR = (126, 240, 224)


def fetch(z, x, y):
    sub = (x + y) % 4 + 1
    u = ('https://webst0%d.is.autonavi.com/appmaptile?style=8&x=%d&y=%d&z=%d' % (sub, x, y, z))
    req = urllib.request.Request(u, headers={'User-Agent': UA, 'Referer': 'https://www.amap.com/'})
    return urllib.request.urlopen(req, timeout=4).read()


def tint(data):
    im = Image.open(io.BytesIO(data)).convert('RGBA')
    w, h = im.size
    src = im.load()
    out = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    dst = out.load()
    for j in range(h):
        for i in range(w):
            a = src[i, j][3]
            if a:
                dst[i, j] = (255, 255, 255, a)
    return out

_BLANK = None


def blank():
    """离线兜底：返回全透明瓦片，避免页面等网络超时"""
    global _BLANK
    if _BLANK is None:
        buf = io.BytesIO()
        Image.new('RGBA', (256, 256), (0, 0, 0, 0)).save(buf, 'PNG')
        _BLANK = buf.getvalue()
    return _BLANK


def tile(z, x, y):
    p = os.path.join(CACHE, str(z), str(x), '%d.png' % y)
    if os.path.exists(p):
        return open(p, 'rb').read()
    try:
        img = tint(fetch(z, x, y))
    except Exception:
        return blank()          # 无网：立刻回透明，下次有网再补
    os.makedirs(os.path.dirname(p), exist_ok=True)
    img.save(p, 'PNG', optimize=True)
    return open(p, 'rb').read()


class H(SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=ROOT, **kw)

    def do_GET(self):
        if self.path.startswith('/t/'):
            try:
                z, x, y = self.path[3:].split('?')[0].replace('.png', '').split('/')
                body = tile(int(z), int(x), int(y))
                self.send_response(200)
                self.send_header('Content-Type', 'image/png')
                self.send_header('Content-Length', str(len(body)))
                self.send_header('Cache-Control', 'max-age=86400')
                self.end_headers()
                self.wfile.write(body)
            except Exception as e:
                self.send_error(502, str(e)[:80])
            return
        super().do_GET()

    def end_headers(self):
        if self.path.endswith('.html') or self.path in ('/', ''):
            self.send_header('Cache-Control', 'no-store, no-cache, must-revalidate')
            self.send_header('Pragma', 'no-cache')
        super().end_headers()

    def log_message(self, *a):
        pass


if __name__ == '__main__':
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8477
    os.makedirs(CACHE, exist_ok=True)
    ThreadingHTTPServer(('0.0.0.0', port), H).serve_forever()
