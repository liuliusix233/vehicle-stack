#!/usr/bin/env python3
"""cdp_shot.py — deterministic HTML -> PNG rendering via headless Chromium + CDP.

Why: this host's exec tool imposes a 2GB RLIMIT_AS (hard), so Chromium cannot be
launched from a normal shell — and Chromium's --window-size does NOT control the
layout viewport in --headless=new (measured: viewport stays 500x153). Driving CDP
with Emulation.setDeviceMetricsOverride gives an exact 480x240 viewport for the
HUD C2-Elite panel, at any device scale factor.

usage: cdp_shot.py jobs.json      (run this under `systemd-run --user`)
jobs.json: [{"out":"/path/a.png","url":"file:///...","w":480,"h":240,"scale":1}, ...]
"""
import base64, json, os, shutil, subprocess, sys, tempfile, time

import requests
import websocket  # websocket-client

CHROME = "/home/<USER>/.cache/ms-playwright/chromium-1208/chrome-linux64/chrome"
PORT = 9223


def wait_devtools(timeout=30):
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            r = requests.get(f"http://127.0.0.1:{PORT}/json/version", timeout=1)
            if r.ok:
                return r.json()
        except Exception:
            pass
        time.sleep(0.2)
    raise RuntimeError("devtools endpoint never came up")


class Tab:
    def __init__(self, ws_url):
        self.ws = websocket.create_connection(ws_url, suppress_origin=True, timeout=30)
        self.i = 0

    def send(self, method, params=None, timeout=30):
        self.i += 1
        mid = self.i
        self.ws.settimeout(timeout)
        self.ws.send(json.dumps({"id": mid, "method": method, "params": params or {}}))
        while True:
            msg = json.loads(self.ws.recv())
            if msg.get("id") == mid:
                if "error" in msg:
                    raise RuntimeError(f"{method}: {msg['error']}")
                return msg.get("result", {})

    def close(self):
        try:
            self.ws.close()
        except Exception:
            pass


def render(tab, job):
    w, h, scale = job["w"], job["h"], job.get("scale", 1)
    tab.send("Page.enable")
    tab.send("Emulation.setDeviceMetricsOverride",
             {"width": w, "height": h, "deviceScaleFactor": scale, "mobile": False})
    tab.send("Page.navigate", {"url": job["url"]})
    # wait for load + webfonts
    tab.send("Runtime.evaluate", {"expression": "document.readyState", "awaitPromise": False})
    t0 = time.time()
    while time.time() - t0 < 20:
        r = tab.send("Runtime.evaluate", {"expression": "document.readyState"})
        if r.get("result", {}).get("value") == "complete":
            break
        time.sleep(0.1)
    tab.send("Runtime.evaluate",
             {"expression": "document.fonts.ready.then(()=>true)",
              "awaitPromise": True, "returnByValue": True}, timeout=20)
    time.sleep(job.get("settle", 0.35))
    res = tab.send("Page.captureScreenshot",
                   {"format": "png", "captureBeyondViewport": False}, timeout=60)
    data = base64.b64decode(res["data"])
    os.makedirs(os.path.dirname(job["out"]), exist_ok=True)
    with open(job["out"], "wb") as f:
        f.write(data)
    return len(data)


def main():
    jobs = json.load(open(sys.argv[1]))
    profile = tempfile.mkdtemp(prefix="cdpshot-")
    proc = subprocess.Popen([
        CHROME, "--headless=new", "--no-sandbox", "--disable-gpu",
        "--disable-dev-shm-usage", "--hide-scrollbars", "--mute-audio",
        "--disable-extensions", "--no-first-run", "--disable-background-networking",
        f"--remote-debugging-port={PORT}", f"--user-data-dir={profile}",
        "--remote-allow-origins=*", "about:blank",
    ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    ok = fail = 0
    try:
        wait_devtools()
        tab = Tab(requests.get(f"http://127.0.0.1:{PORT}/json/list", timeout=5).json()[0]["webSocketDebuggerUrl"])
        for job in jobs:
            try:
                n = render(tab, job)
                print(f"OK   {job['out']} {n}")
                ok += 1
            except Exception as e:
                print(f"FAIL {job['out']} {e}")
                fail += 1
        tab.close()
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except Exception:
            proc.kill()
        shutil.rmtree(profile, ignore_errors=True)
    print(f"DONE ok={ok} fail={fail}")
    return 0 if fail == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
