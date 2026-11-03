#!/usr/bin/env python3
"""近 N 天 车载电源 + 驾驶记录 聚合分析 (HA history, 禁用代理)."""
import json, sys, datetime, urllib.request, urllib.parse, subprocess

CFG = json.load(open('/home/<USER>/.openclaw/workspace/jarvis/tools/ha_config.json'))
URL = CFG['url'].rstrip('/')
TOK = CFG['token']
TZ = datetime.timezone(datetime.timedelta(hours=8))
DAYS = int(sys.argv[1]) if len(sys.argv) > 1 else 30

opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

def fetch(ent, start, end):
    q = urllib.parse.urlencode({'filter_entity_id': ent, 'end_time': end,
                                'minimal_response': '', 'no_attributes': ''})
    req = urllib.request.Request(f"{URL}/api/history/period/{start}?{q}",
                                 headers={'Authorization': 'Bearer ' + TOK})
    with opener.open(req, timeout=90) as r:
        d = json.load(r)
    return d[0] if d else []

def parse_ts(s):
    try:
        return datetime.datetime.fromisoformat(s.replace('Z', '+00:00')).astimezone(TZ)
    except Exception:
        return None

def series(ent, start, end):
    out = []
    for p in fetch(ent, start, end):
        st = p.get('state', p.get('s'))
        ts = p.get('last_updated') or p.get('last_changed') or p.get('lu') or p.get('lc')
        t = parse_ts(ts) if ts else None
        if t is None:
            continue
        try:
            v = float(st)
        except (TypeError, ValueError):
            continue
        out.append((t, v))
    out.sort(key=lambda x: x[0])
    # dedupe consecutive equal values to keep memory sane
    ded = []
    for t, v in out:
        if ded and ded[-1][1] == v:
            continue
        ded.append((t, v))
    return ded

def integrate(sr):
    """Wh from W samples (forward diff), also returns time span hours."""
    wh = 0.0
    for i in range(1, len(sr)):
        dt = (sr[i][0] - sr[i-1][0]).total_seconds() / 3600.0
        if dt <= 0 or dt > 6:   # 跳过长时间断档
            continue
        wh += sr[i-1][1] * dt
    return wh

now = datetime.datetime.now(TZ)
start = now - datetime.timedelta(days=DAYS)
iso = lambda d: d.isoformat()

E = {
 'odo':    'sensor.onstar_odometer',
 'chg':    'sensor.cadillac_charge_power',
 'dis':    'sensor.cadillac_discharge_power',
 'ac_chg': 'sensor.cadillac_ac_charge_w',
 'ac_dis': 'sensor.cadillac_ac_discharge_w',
 'sdc_chg':'sensor.cadillac_sdc_charge_w',
 'sdc_dis':'sensor.cadillac_sdc_discharge_w',
 'soc':    'sensor.cadillac_battery',
 'cvolt':  'sensor.cadillac_car_voltage',
 'sdcv':   'sensor.cadillac_sdc_voltage',
 'sdct':   'sensor.cadillac_sdc_type',
 'temp':   'sensor.cadillac_temperature',
}

print(f"### window {start:%Y-%m-%d %H:%M} -> {now:%Y-%m-%d %H:%M} (last {DAYS}d)")
S = {}
for k, ent in E.items():
    try:
        S[k] = series(ent, iso(start), iso(now))
    except Exception as e:
        S[k] = []
        print(f"# {k}: ERR {type(e).__name__} {str(e)[:50]}")
    if S[k]:
        print(f"# {k:8s} n={len(S[k]):5d} {S[k][0][0]:%m-%d %H:%M} -> {S[k][-1][0]:%m-%d %H:%M} range {min(v for _,v in S[k]):.1f}..{max(v for _,v in S[k]):.1f}")
    else:
        print(f"# {k:8s} (empty)")

if not S['odo']:
    sys.exit(0)

# ---- 逐日 ----
print("\n## 逐日 (km / 充电Wh / 放电Wh / SOC末值 / 车电压均值)")
days = {}
for t, v in S['odo']:
    days.setdefault(t.date(), []).append((t, v))
drv_km = {}
for d, pts in days.items():
    km = 0.0
    for i in range(1, len(pts)):
        dv = pts[i][1] - pts[i-1][1]
        dt = (pts[i][0] - pts[i-1][0]).total_seconds() / 3600.0
        if 0 < dv < 300 and dt < 3:
            km += dv
    drv_km[d] = km

def daily_energy(sr):
    out = {}
    for i in range(1, len(sr)):
        dt = (sr[i][0] - sr[i-1][0]).total_seconds() / 3600.0
        if dt <= 0 or dt > 6:
            continue
        out[sr[i-1][0].date()] = out.get(sr[i-1][0].date(), 0.0) + sr[i-1][1] * dt
    return out

chg_d = daily_energy(S['chg']); dis_d = daily_energy(S['dis'])
soc_last = {}
for t, v in S['soc']:
    soc_last[t.date()] = v
cvolt_d = {}
for t, v in S['cvolt']:
    cvolt_d.setdefault(t.date(), []).append(v)

alld = sorted(set(list(drv_km) + list(chg_d) + list(dis_d)))
print(f"{'date':10s} {'km':>6s} {'chg_Wh':>8s} {'dis_Wh':>8s} {'SOC末':>6s} {'cV均值':>7s}")
for d in alld:
    cv = cvolt_d.get(d)
    cvm = sum(cv)/len(cv) if cv else 0
    print(f"{d.isoformat():10s} {drv_km.get(d,0):6.1f} {chg_d.get(d,0):8.0f} {dis_d.get(d,0):8.0f} "
          f"{soc_last.get(d,float('nan')):6.0f} {cvm:7.2f}")

n = len(alld) or 1
print(f"\n## 合计/均值 ({n} 天)")
print(f"驾驶总里程      : {sum(drv_km.values()):8.1f} km  (日均 {sum(drv_km.values())/n:.1f})")
print(f"充电总能量(in)  : {sum(chg_d.values()):8.0f} Wh (日均 {sum(chg_d.values())/n:.0f})")
print(f"放电总能量(out) : {sum(dis_d.values()):8.0f} Wh (日均 {sum(dis_d.values())/n:.0f})")
print(f"净平衡          : {sum(chg_d.values())-sum(dis_d.values()):8.0f} Wh")
