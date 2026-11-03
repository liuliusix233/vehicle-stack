#!/usr/bin/env python3
"""Car truth puller —— 只读车机 ShellBridge (Tailscale <TS_IP>:8888)，
把导航 TBT / 车机 GPS / 红绿灯喂给 Pi 的 pi_nav_listener (:8899)。

不碰 ELM327：OBD 链路唯一持有者仍是 obd_http.py (:8077)。

数据流：
  car :8888 #BINDSVC(29) -> TBT JSON -> POST :8899/nav
  car :8888 #GPS         -> /tmp/car_gps.json (bridge.py 合并)
  car :8888 #ADUDUMP     -> 解码 UTF-16 -> POST :8899/debug (解析红绿灯)
"""
import json, os, re, socket, sys, tempfile, time, urllib.request

CAR = os.environ.get("CAR_HOST", "<TS_IP>")
PORT = int(os.environ.get("CAR_PORT", "8888"))
NAV = os.environ.get("NAV_BASE", "http://127.0.0.1:8899")
GPSF = os.environ.get("GPS_FILE", "/tmp/car_gps.json")

CMD_NAV = ("#BINDSVC com.sgm.navi.hmi com.sgm.navi.exportservice.MapService 4 "
           "s:com.vox.castsink s:NAVI_AUTO_API -- 29 s:com.vox.castsink")

_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def ask(cmd, timeout=2.0):
    s = socket.socket()
    s.settimeout(1.2)
    try:
        s.connect((CAR, PORT))
        s.sendall((cmd + "\n").encode())
        buf = b""
        end = time.time() + timeout
        while time.time() < end:
            try:
                b = s.recv(8192)
            except socket.timeout:
                break
            if not b:
                break
            buf += b
            t = buf.decode("utf-8", "ignore")
            if "-> Int:" in t or "total_lines=" in t and t.count("\n") > 1 or "Altitude:" in t:
                break
        return buf.decode("utf-8", "ignore")
    except Exception as e:
        return ""
    finally:
        try:
            s.close()
        except Exception:
            pass


def post(path, body):
    try:
        req = urllib.request.Request(NAV + path, data=body.encode("utf-8"), method="POST")
        with _OPENER.open(req, timeout=1.2) as r:
            return r.read(20)
    except Exception:
        return b""


def atomic_write(path, obj):
    d = os.path.dirname(os.path.abspath(path)) or "."
    fd, tmp = tempfile.mkstemp(dir=d)
    with os.fdopen(fd, "w") as f:
        json.dump(obj, f, ensure_ascii=False)
    os.replace(tmp, path)


def read_gps():
    t = ask("#GPS")
    m = re.search(r"Lat:\s*(-?[\d.]+).*?Lon:\s*(-?[\d.]+)", t, re.S)
    if not m:
        return
    alt = re.search(r"Altitude:\s*(-?[\d.]+)", t)
    acc = re.search(r"Accuracy:\s*(-?[\d.]+)", t)
    atomic_write(GPSF, {"lat": float(m.group(1)), "lon": float(m.group(2)),
                        "alt": float(alt.group(1)) if alt else None,
                        "acc": float(acc.group(1)) if acc else None,
                        "ts": round(time.time(), 3)})


def push_nav():
    t = ask(CMD_NAV, 2.5)
    i = t.find("-> String: ")
    if i < 0:
        return
    raw = t[i + len("-> String: "):].split("\n")[0].strip()
    if not raw.startswith("{"):
        return
    try:
        o = json.loads(raw)
    except Exception:
        return
    g = {}
    try:
        with open(GPSF) as f:
            g = json.load(f)
        if time.time() - g.get("ts", 0) > 600:   # 停车丢星时沿用最近一次定位
            g = {}
    except Exception:
        g = {}
    # 车机 TBT 里 lat/lon 可能是 null，必须补上（原来只看 key 是否存在，导致一直是 None）
    cur_lat = o.get("lat")
    if (not isinstance(cur_lat, (int, float)) or abs(cur_lat) < 1) and \
            isinstance(g.get("lat"), (int, float)) and abs(g["lat"]) > 1:
        o["lat"], o["lon"] = g["lat"], g["lon"]
    post("/nav", json.dumps(o, ensure_ascii=False))
    try:
        atomic_write("/tmp/nav_raw.json", o)   # 保留原始字段，便于核红绿灯/动作映射
    except Exception:
        pass
    return o


def push_light():
    t = ask("#ADUDUMP 4", 2.0)
    for ln in t.split("\n"):
        if "hex=" not in ln:
            continue
        try:
            hx = ln.split("hex=", 1)[1].split(";")[0]
            hx = "".join(c for c in hx if c in "0123456789abcdef")
            vals = [int(hx[i:i + 8], 16) for i in range(0, len(hx) - 7, 8)]
            txt = b"".join(v.to_bytes(4, "little") for v in vals).decode("utf-16-le", "ignore")
        except Exception:
            continue
        if "mLightInfo" in txt or "CountDownLight" in txt:
            post("/debug", txt + " light")


def main():
    last_arm = 0.0
    last_light = 0.0
    last_nav = 0.0
    ok = False
    while True:
        loop = time.time()
        try:
            if loop - last_arm > 1500:
                r = ask("#ADALISTENBG 1800", 1.5)
                if "started" in r:
                    log("ADALISTENBG armed")
                last_arm = loop
            read_gps()
            if loop - last_nav > 1.0:
                last_nav = loop
                o = push_nav()
                if o and not ok:
                    ok = True
                    log("nav ok:", (o.get("mCurRouteName") or "")[:20], o.get("mRemainDist"))
            if loop - last_light > 2.0:
                last_light = loop
                push_light()
        except Exception as e:
            log("err:", e)
        sleep = 1.0 - (time.time() - loop)
        if sleep > 0:
            time.sleep(sleep)


if __name__ == "__main__":
    main()
