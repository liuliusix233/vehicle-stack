#!/usr/bin/env python3
"""家庭电费每日更新 - 无需模型，直接通过 CDP 抓取"""
import json, urllib.request, websocket, time, re, subprocess, os

# Config
HA_TOKEN_FILE = "/home/<USER>/.openclaw/workspace/jarvis/tools/ha_config.json"
LOG_FILE = "/home/<USER>/.openclaw/workspace/jarvis/power_monitor/elec_update.log"

def log(msg):
    with open(LOG_FILE, "a") as f:
        f.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')} {msg}\n")
    print(msg)

def get_ha_token():
    with open(HA_TOKEN_FILE) as f:
        return json.load(f)["token"]

def push_ha(token, entity, state, attrs=None):
    data = {"state": str(state)}
    if attrs: data["attributes"] = attrs
    req = urllib.request.Request(
        f"http://<TS_IP>:8123/api/states/{entity}",
        data=json.dumps(data).encode(),
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
        method="POST"
    )
    resp = urllib.request.urlopen(req)
    return resp.status

### Step 1: Setup SSH tunnel ###
def setup_tunnel():
    # Kill old tunnel
    os.system("pkill -f 'ssh.*9222' 2>/dev/null")
    time.sleep(1)
    # Start new tunnel
    ret = os.system(
        "ssh -L 9222:127.0.0.1:9222 -N -f -o StrictHostKeyChecking=no "
        "-o ExitOnForwardFailure=yes <USER>@<TS_IP> -p 2222 2>/dev/null"
    )
    time.sleep(3)
    return ret == 0

### Step 2: Connect to Edge CDP ###
def get_edge_tab():
    try:
        tabs = json.loads(urllib.request.urlopen("http://127.0.0.1:9222/json", timeout=5).read())
        for t in tabs:
            if "95598" in t["url"] or "sgcc" in t["url"]:
                return t["id"], t["url"]
        log("No 95598 tab found")
        return None, None
    except Exception as e:
        log(f"Edge not accessible: {e}")
        return None, None

def navigate_tab(tab_id, url):
    ws_url = f"ws://127.0.0.1:9222/devtools/page/{tab_id}"
    ws = websocket.create_connection(ws_url, timeout=10, header={"Origin": "http://127.0.0.1:9222"})
    ws.send(json.dumps({"id": 1, "method": "Page.navigate", "params": {"url": url}}))
    ws.recv()
    ws.close()
    time.sleep(5)

def get_page_text(tab_id):
    ws_url = f"ws://127.0.0.1:9222/devtools/page/{tab_id}"
    ws = websocket.create_connection(ws_url, timeout=10, header={"Origin": "http://127.0.0.1:9222"})
    ws.send(json.dumps({"id": 1, "method": "Runtime.evaluate", 
                        "params": {"expression": "document.body ? document.body.innerText : ''"}}))
    resp = json.loads(ws.recv())
    ws.close()
    return resp.get("result", {}).get("result", {}).get("value", "")

def click_daily_tab(tab_id):
    ws_url = f"ws://127.0.0.1:9222/devtools/page/{tab_id}"
    ws = websocket.create_connection(ws_url, timeout=10, header={"Origin": "http://127.0.0.1:9222"})
    ws.send(json.dumps({"id": 1, "method": "Runtime.evaluate",
                        "params": {"expression": "document.getElementById('tab-second').click()"}}))
    ws.recv()
    ws.close()
    time.sleep(3)

### Step 3: Parse data ###
def parse_balance(text):
    m = re.search(r'账户余额为：([\d.]+)元', text)
    return m.group(1) if m else None

def parse_electricity(text):
    result = {}
    # Year-to-date
    m = re.search(r'年电量累计（千瓦时）\s*(\d+)', text)
    if m: result["ytd_kwh"] = m.group(1)
    m = re.search(r'年电费累计（元）\s*(\d+)', text)
    if m: result["ytd_cost"] = m.group(1)
    # Current month (last line with 月 before data)
    months = re.findall(r'(\d+)月\d+日-\d+月\d+日\s*\n\s*(\d+)\s*\n\s*(\d+)', text)
    if months:
        last = months[-1]
        result["month_kwh"] = last[1]
        result["month_cost"] = last[2]
    return result

def parse_daily(text):
    # Daily usage - last lines before "小贴士"
    daily = re.findall(r'(\d{4}-\d{2}-\d{2})\s*\n\s*([\d.]+)', text)
    if daily:
        # Most recent day
        result = {"today_kwh": daily[-1][1]}
        # Total
        m = re.search(r'([\d.]+)\s*合计电量', text)
        if m: result["7day_total"] = m.group(1)
        return result
    return {}

### Main ###
log("=== Electricity Bill Update Started ===")

# Setup
if not setup_tunnel():
    log("Tunnel setup failed")
    exit(1)

tab_id, _ = get_edge_tab()
if not tab_id:
    log("Edge debug not available")
    exit(1)

token = get_ha_token()

try:
    # 1. Get balance
    navigate_tab(tab_id, "https://www.95598.cn/osgweb/userAcc?partNo=P02021704")
    text = get_page_text(tab_id)
    balance = parse_balance(text)
    if balance:
        push_ha(token, "sensor.elec_balance", balance,
                {"unit_of_measurement": "CNY", "friendly_name": "电费账户余额", "icon": "mdi:wallet"})
        log(f"Balance: {balance} CNY")
    else:
        log("Balance page unavailable (maybe maintenance)")
except Exception as e:
    log(f"Balance error: {e}")

try:
    # 2. Get electricity data (monthly + daily)
    navigate_tab(tab_id, "https://www.95598.cn/osgweb/electricityCharge?partNo=P02021703")
    text = get_page_text(tab_id)
    elec = parse_electricity(text)
    
    if elec.get("month_kwh"):
        push_ha(token, "sensor.elec_monthly_usage", elec["month_kwh"],
                {"unit_of_measurement": "kWh", "friendly_name": "本月用电", "icon": "mdi:lightning-bolt",
                 "state_class": "total_increasing", "device_class": "energy"})
        log(f"Monthly: {elec['month_kwh']} kWh, ¥{elec.get('month_cost','?')}")
    if elec.get("ytd_kwh"):
        push_ha(token, "sensor.elec_ytd_usage", elec["ytd_kwh"],
                {"unit_of_measurement": "kWh", "friendly_name": "年累计用电", "icon": "mdi:lightning-bolt-outline"})
        push_ha(token, "sensor.elec_ytd_cost", elec["ytd_cost"],
                {"unit_of_measurement": "CNY", "friendly_name": "年累计电费", "icon": "mdi:currency-cny"})
        log(f"YTD: {elec['ytd_kwh']} kWh, ¥{elec.get('ytd_cost','?')}")
    
    # 3. Get daily data
    click_daily_tab(tab_id)
    text = get_page_text(tab_id)
    daily = parse_daily(text)
    if daily.get("today_kwh"):
        push_ha(token, "sensor.elec_today_usage", daily["today_kwh"],
                {"unit_of_measurement": "kWh", "friendly_name": "今日用电", "icon": "mdi:lightning-bolt",
                 "device_class": "energy"})
        log(f"Today: {daily['today_kwh']} kWh")
    if daily.get("7day_total"):
        push_ha(token, "sensor.elec_7day_total", daily["7day_total"],
                {"unit_of_measurement": "kWh", "friendly_name": "近7天用电合计", "icon": "mdi:calendar-week"})
        log(f"7day total: {daily['7day_total']} kWh")
except Exception as e:
    log(f"Electricity data error: {e}")

log("=== Update Complete ===")
