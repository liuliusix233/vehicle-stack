#!/usr/bin/env python3
"""HUD OBD Proxy v6.0 — Web remote control + persisted ODO baseline."""
import subprocess, json, sys, time, threading, http.server, re, urllib.request, math, os

HOST = "0.0.0.0"
PORT = 8080
HA_URL = "https://nas.<PRIVATE_DOMAIN>:8443"
HA_TOKEN = os.environ.get("HA_TOKEN", "")
ADB_BIN = "/usr/bin/adb"
HUD_ADB_PORT = 5555
HUD_PKG = "com.vox.hud"
HUD_ACTIVITY = ".MainActivity"

ODO_BASE = 30607
FUEL_CORRECTION = -3
HEADING_OFFSET = 208  # Recalibrated in garage: raw yaw=106.7° at actual 315° (NW)
ODO_STATE_FILE = os.path.expanduser("~/.hud_odo_state.json")

first_odo = None
hud_ip = None
adb_connect_fails = 0
last_heading = 0.0
latest = {"connected": 0, "error": ""}
lock = threading.Lock()
scan_running = False
link_result = ""


def load_odo_state():
    global first_odo
    try:
        with open(ODO_STATE_FILE) as f:
            d = json.load(f)
            first_odo = d.get("first_odo")
            if first_odo is not None:
                print("ODO restored: first_odo=" + str(first_odo))
    except:
        pass


def save_odo_state(raw_odo):
    try:
        with open(ODO_STATE_FILE, "w") as f:
            json.dump({"first_odo": raw_odo, "saved_at": time.time()}, f)
    except:
        pass


def find_hud_ip(try_last_first=True):
    """Scan <LAN_IP>-254 for device listening on ADB port 5555.
    If try_last_first is True and a last known IP was saved, try that first."""
    import socket
    last_ip_file = os.path.expanduser("~/.hud_last_ip")

    # Try last known IP first (instant if HUD didn't move)
    if try_last_first:
        try:
            with open(last_ip_file) as f:
                last_ip = f.read().strip()
            if last_ip:
                r = subprocess.run(
                    [ADB_BIN, "-s", last_ip + ":" + str(HUD_ADB_PORT), "shell", "echo", "ok"],
                    capture_output=True, timeout=3
                )
                if r.returncode == 0 and b"ok" in r.stdout:
                    print("HUD found at last IP: " + last_ip)
                    return last_ip
        except:
            pass

    # Full scan
    subprocess.run(["/usr/bin/adb", "disconnect"], capture_output=True, timeout=5)
    for i in range(2, 255):
        ip = "192.168.0." + str(i)
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.settimeout(0.15)
            s.connect((ip, HUD_ADB_PORT))
            s.close()
            r2 = subprocess.run(
                [ADB_BIN, "-s", ip + ":" + str(HUD_ADB_PORT), "shell", "echo", "ok"],
                capture_output=True, timeout=3
            )
            if r2.returncode == 0 and b"ok" in r2.stdout:
                subprocess.run(
                    [ADB_BIN, "connect", ip + ":" + str(HUD_ADB_PORT)],
                    capture_output=True, timeout=5
                )
                print("HUD found at " + ip)
                try:
                    with open(last_ip_file, "w") as f:
                        f.write(ip)
                except:
                    pass
                return ip
        except:
            pass
    return None


def ensure_hud():
    global adb_connect_fails, hud_ip
    if hud_ip is None:
        ip = find_hud_ip()
        if ip is None:
            return False
        hud_ip = ip
        adb_connect_fails = 0
        return True
    try:
        r = subprocess.run([ADB_BIN, "-s", hud_ip + ":" + str(HUD_ADB_PORT), "shell", "echo", "ok"],
                           capture_output=True, timeout=5)
        if r.returncode == 0 and b"ok" in r.stdout:
            adb_connect_fails = 0
            return True
    except:
        pass
    adb_connect_fails += 1
    if adb_connect_fails >= 3:
        print("HUD " + str(hud_ip) + " lost, rescanning...")
        hud_ip = None
        time.sleep(5)
    return False


def set_default_launcher(ip):
    """Set HUD Dashboard as the default home/launcher app on HUD."""
    adb = [ADB_BIN, "-s", ip + ":" + str(HUD_ADB_PORT)]
    try:
        # Clear current default home first
        subprocess.run(adb + ["shell", "cmd", "package", "set-home-activity", "none"],
                       capture_output=True, timeout=5)
        # Set ours
        r = subprocess.run(
            adb + ["shell", "cmd", "package", "set-home-activity",
                   HUD_PKG + "/" + HUD_ACTIVITY],
            capture_output=True, timeout=5
        )
        out = r.stdout.decode("utf-8", errors="replace").strip()
        err = r.stderr.decode("utf-8", errors="replace").strip()
        if r.returncode == 0:
            print("Default launcher set: " + HUD_PKG + HUD_ACTIVITY)
            return "OK: 默认启动器已设为 HUD Dashboard"
        else:
            print("set-home-activity failed: " + err)
            return "失败: " + (err or out or "未知错误")
    except Exception as e:
        print("set-home-activity exception: " + str(e))
        return "异常: " + str(e)


# ── Background scan thread ──

def scan_hud():
    global hud_ip, adb_connect_fails, scan_running
    with lock:
        if scan_running:
            return
        scan_running = True
    try:
        found = find_hud_ip()
        with lock:
            if found:
                hud_ip = found
                adb_connect_fails = 0
                print("Scan found HUD at " + found)
    finally:
        with lock:
            scan_running = False


# ── Background link thread ──

def link_hud():
    global hud_ip, link_result, scan_running
    with lock:
        scan_result_ip = hud_ip
    steps = []
    # Step 1: scan if no IP
    if not scan_result_ip:
        steps.append("扫描中...")
        found = None
        try:
            with lock:
                scan_running = True
            found = find_hud_ip()
        finally:
            with lock:
                scan_running = False
        if found:
            with lock:
                hud_ip = found
            scan_result_ip = found
            steps.append("找到 HUD: " + found)
        else:
            link_result = " | ".join(steps) + " | ❌ 未找到 HUD"
            return
    else:
        steps.append("HUD: " + scan_result_ip)

    # Step 2: ADB connect
    ip = scan_result_ip
    try:
        r = subprocess.run(
            [ADB_BIN, "connect", ip + ":" + str(HUD_ADB_PORT)],
            capture_output=True, timeout=5
        )
        out = r.stdout.decode("utf-8", errors="replace").strip()
        steps.append("ADB: " + ("✅" if "connected" in out.lower() else out))
    except Exception as e:
        steps.append("ADB异常: " + str(e))

    # Step 3: Install APK if available
    apk_path = os.path.expanduser("~/.hud_update.apk")
    if os.path.exists(apk_path):
        try:
            r = subprocess.run(
                [ADB_BIN, "-s", ip + ":" + str(HUD_ADB_PORT), "install", "-r", apk_path],
                capture_output=True, timeout=30
            )
            out = r.stdout.decode("utf-8", errors="replace").strip()
            steps.append("安装: ✅" if "Success" in out else ("安装: " + out[:60]))
        except Exception as e:
            steps.append("安装异常: " + str(e))

    # Step 4: Set default launcher
    launcher_result = set_default_launcher(ip)
    steps.append("启动器: " + launcher_result)

    link_result = " | ".join(steps)


def get_heading():
    global last_heading
    if hud_ip is None:
        return last_heading
    try:
        raw = adb_run(["dumpsys", "sensorservice"], timeout=5)
        if not raw:
            return last_heading
        m = re.search(r'Rotation Vector Sensor: last \d+ events\n((?:\s+\d+.*\n)+)', raw)
        if m:
            lines = m.group(1).strip().split("\n")
            if lines:
                parts = lines[-1].strip().split()
                if len(parts) >= 8:
                    x = float(parts[3].rstrip(","))
                    y = float(parts[4].rstrip(","))
                    z = float(parts[5].rstrip(","))
                    w = float(parts[6].rstrip(","))
                    yaw = math.atan2(2.0 * (w * z + x * y), 1.0 - 2.0 * (y * y + z * z))
                    heading = (math.degrees(yaw) + HEADING_OFFSET) % 360.0
                    heading = last_heading * 0.3 + heading * 0.7
                    last_heading = heading
                    return round(heading, 1)
        m2 = re.search(r'q=< ([-0-9.eE+]+), ([-0-9.eE+]+), ([-0-9.eE+]+), ([-0-9.eE+]+) >', raw)
        if m2:
            x = float(m2.group(1)); y = float(m2.group(2))
            z = float(m2.group(3)); w = float(m2.group(4))
            yaw = math.atan2(2.0 * (w * z + x * y), 1.0 - 2.0 * (y * y + z * z))
            heading = (math.degrees(yaw) + HEADING_OFFSET) % 360.0
            heading = last_heading * 0.3 + heading * 0.7
            last_heading = heading
            return round(heading, 1)
    except:
        pass
    return last_heading


def adb_run(cmd_args, timeout=10):
    if hud_ip is None:
        return None
    try:
        r = subprocess.run([ADB_BIN, "-s", hud_ip + ":" + str(HUD_ADB_PORT), "shell"] + cmd_args,
                           capture_output=True, timeout=timeout)
        return r.stdout.decode("utf-8", errors="replace")
    except:
        return None


def parse_obd_lines(lines):
    result = {}
    for line in lines:
        eq = line.find("=")
        if eq < 0: continue
        hash_idx = line.find("##")
        if hash_idx < 0: continue
        key = line[hash_idx + 2:eq].strip()
        val = line[eq + 1:].strip()
        result[key] = val
    return result


def map_to_snapshot(d):
    g = lambda *keys: next((int(d[k]) for k in keys if k in d and d[k].lstrip("-").isdigit()), -1)
    gf = lambda *keys: next((float(d[k]) for k in keys if k in d), -1.0)
    return {
        "speed": g("车辆速度"),
        "rpm": g("发动机转速"),
        "coolantTemp": g("水箱温度"),
        "voltage": gf("电瓶电压"),
        "fuelLevel": g("剩余油量"),
        "engineLoad": g("发动机负荷"),
        "throttlePos": g("绝对节气门位置传感器"),
        "intakeTemp": g("进气口温度", "进气温度"),
        "oilTemp": g("机油温度"),
        "odo": g("行驶里程"),
        "ambientTemp": g("车辆环境温度", "车外温度"),
        "fuelCons": gf("瞬时油耗"),
        "connected": 1,
    }


def fetch_ha(entity):
    try:
        req = urllib.request.Request(HA_URL + "/api/states/" + entity,
                                     headers={"Authorization": "Bearer " + HA_TOKEN})
        with urllib.request.urlopen(req, timeout=5) as r:
            return float(json.loads(r.read()).get("state", -1))
    except:
        return -1


def ha_data():
    return {"power": fetch_ha("sensor.cadillac_battery"),
            "cadillacWh": fetch_ha("sensor.cadillac_remaining")}


def poll_loop():
    global first_odo, hud_ip
    ensure_hud()
    load_odo_state()
    ha_tick = 0
    while True:
        heading = get_heading()
        ha_tick += 1
        raw = None
        hud_ok = ensure_hud()
        if hud_ok:
            raw = adb_run(["logcat", "-d", "-t", "100", "-s", "AIOSLOG:*"], timeout=10)
        with lock:
            if raw is not None:
                lines = raw.strip().split("\n")
                obd = [l for l in lines if "AIOBD##" in l and "=" in l]
                if obd:
                    snapshot = map_to_snapshot(parse_obd_lines(obd))
                    snapshot["heading"] = heading
                    snapshot["wifiOn"] = 1 if hud_ip else 0
                    raw_odo = snapshot.get("odo", -1)
                    if raw_odo > 0:
                        if first_odo is None:
                            first_odo = raw_odo
                            save_odo_state(raw_odo)
                            print("ODO baseline: raw=" + str(raw_odo) + " base=" + str(ODO_BASE))
                        # Only reset baseline if raw DECREASED (HUD counter reset), not on proxy restart
                        if raw_odo < first_odo:
                            print("ODO counter reset: " + str(first_odo) + "→" + str(raw_odo) + ", re-baselining")
                            first_odo = raw_odo
                            save_odo_state(raw_odo)
                        snapshot["odo"] = ODO_BASE + int((raw_odo - first_odo) / 1000)
                    raw_fuel = snapshot.get("fuelLevel", -1)
                    if raw_fuel > 0:
                        snapshot["fuelLevel"] = max(0, min(100, raw_fuel + FUEL_CORRECTION))
                    ha_extra = ha_data()
                    snapshot.update(ha_extra)
                    latest.clear()
                    latest.update(snapshot)
                    latest["error"] = ""
                else:
                    latest["connected"] = 0
            else:
                latest["connected"] = 0
                latest["error"] = "no-hud"
            if ha_tick >= 15:
                ha_tick = 0
                ha_extra = ha_data()
                latest.update(ha_extra)
        time.sleep(1)


# ── Web Remote Control HTML ──

PAGE = r"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<title>HUD 遥控器</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:#0d1117;color:#c9d1d9;padding:16px;max-width:480px;margin:auto}
h1{font-size:20px;margin:12px 0;color:#58a6ff;text-align:center}
.status{background:#161b22;border:1px solid #30363d;border-radius:12px;padding:16px;margin:12px 0}
.status .row{display:flex;justify-content:space-between;padding:6px 0;font-size:14px}
.status .lab{color:#8b949e}
.status .val{font-weight:600}
.online{color:#3fb950}
.offline{color:#f85149}
.scanning{color:#ffa657}
.btn-group{display:flex;flex-direction:column;gap:10px;margin:16px 0}
.btn{display:flex;align-items:center;justify-content:center;gap:8px;padding:14px;border-radius:10px;font-size:16px;font-weight:600;border:none;cursor:pointer;transition:opacity 0.2s}
.btn:active{opacity:0.7}
.btn-scan{background:#1f6feb;color:#fff}
.btn-link{background:#238636;color:#fff}
.btn:disabled{opacity:0.4;cursor:not-allowed}
.log{background:#0d1117;border:1px solid #30363d;border-radius:8px;padding:10px;font-size:12px;font-family:monospace;color:#8b949e;min-height:40px;white-space:pre-wrap;word-break:break-all;margin-top:8px}
</style>
</head>
<body>
<h1>🚗 HUD 遥控器</h1>
<div class="status" id="status">
  <div class="row"><span class="lab">HUD IP</span><span class="val" id="hudIp">--</span></div>
  <div class="row"><span class="lab">ADB</span><span class="val" id="adbStatus">--</span></div>
  <div class="row"><span class="lab">OBD</span><span class="val" id="obdStatus">--</span></div>
  <div class="row"><span class="lab">扫描中</span><span class="val" id="scanStatus">--</span></div>
</div>
<div class="btn-group">
  <button class="btn btn-scan" id="scanBtn" onclick="doScan()">🔍 扫描局域网</button>
  <button class="btn btn-link" id="linkBtn" onclick="doLink()">🔗 连接 HUD</button>
</div>
<div class="log" id="log">等待操作...</div>
<script>
function $(id){return document.getElementById(id)}
function poll(){
  fetch('/health').then(r=>r.json()).then(d=>{
    $('hudIp').textContent=d.hud_ip||'--';
    $('hudIp').className='val'+(d.hud_ip?' online':' offline');
    $('adbStatus').textContent=d.hud_ip?'✅ 已连接':'❌ 未连接';
    $('adbStatus').className='val'+(d.hud_ip?' online':' offline');
    $('obdStatus').textContent=d.connected?'✅ 运行中':'⏸️ 停止';
    $('obdStatus').className='val'+(d.connected?' online':' offline');
    $('scanStatus').textContent=d.scanning?'🔍 扫描中...':(d.hud_ip?'✅ 已找到':'--');
    $('scanStatus').className='val'+(d.scanning?' scanning':(d.hud_ip?' online':''));
    $('scanBtn').disabled=d.scanning;
    $('linkBtn').disabled=d.scanning;
  }).catch(()=>{})
}
function doScan(){
  $('log').textContent='🔍 正在扫描 <LAN_IP>-125... (约8秒)';
  $('scanBtn').disabled=true;
  fetch('/scan',{method:'POST'}).then(r=>r.json()).then(d=>{
    $('log').textContent=d.message;
    setTimeout(poll,500);
  })
}
function doLink(){
  $('log').textContent='🔗 正在连接...';
  $('linkBtn').disabled=true;
  fetch('/link',{method:'POST'}).then(r=>r.json()).then(d=>{
    $('log').textContent=d.message;
    setTimeout(poll,500);
  })
}
setInterval(poll,2000);poll();
</script>
</body>
</html>"""


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/":
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()
            self.wfile.write(PAGE.encode("utf-8"))
        elif self.path == "/obd":
            with lock:
                body = json.dumps(latest, ensure_ascii=False)
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(body.encode("utf-8"))
        elif self.path == "/health":
            with lock:
                body = json.dumps({
                    "connected": latest.get("connected", 0),
                    "error": latest.get("error", ""),
                    "hud_ip": hud_ip or "",
                    "scanning": scan_running,
                })
            self.send_response(200)
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(body.encode("utf-8"))
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        if self.path == "/scan":
            with lock:
                if scan_running:
                    self.json_resp({"status": "busy", "message": "扫描已在运行中"})
                    return
            t = threading.Thread(target=scan_hud, daemon=True)
            t.start()
            self.json_resp({"status": "scanning", "message": "扫描已启动，约8秒..."})
        elif self.path == "/link":
            global link_result
            link_result = ""
            t = threading.Thread(target=link_hud, daemon=True)
            t.start()
            # Wait up to 60s for result (link is slow)
            for _ in range(120):
                if link_result:
                    break
                time.sleep(0.5)
            self.json_resp({"status": "done" if link_result else "timeout",
                            "message": link_result if link_result else "连接超时"})
        else:
            self.send_response(404)
            self.end_headers()

    def json_resp(self, data):
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(json.dumps(data, ensure_ascii=False).encode("utf-8"))

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    ip = find_hud_ip()
    if ip:
        hud_ip = ip
        print("Proxy ready, HUD=" + ip + " ODO base=" + str(ODO_BASE))
    else:
        print("Proxy started, scanning for HUD...")
    t = threading.Thread(target=poll_loop, daemon=True)
    t.start()
    http.server.HTTPServer((HOST, PORT), Handler).serve_forever()
