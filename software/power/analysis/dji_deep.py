#!/usr/bin/env python3
"""深度分析: 驾驶/充电/放电/满电浪费 + 容量估算 + 2kWh 投影"""
import json, sys, datetime, urllib.request, urllib.parse

CFG = json.load(open('/home/<USER>/.openclaw/workspace/jarvis/tools/ha_config.json'))
URL = CFG['url'].rstrip('/'); TOK = CFG['token']
TZ = datetime.timezone(datetime.timedelta(hours=8))
DAYS = int(sys.argv[1]) if len(sys.argv) > 1 else 12
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

def series(ent, start, end):
    q = urllib.parse.urlencode({'filter_entity_id': ent, 'end_time': end,
                                'minimal_response': '', 'no_attributes': ''})
    req = urllib.request.Request(f"{URL}/api/history/period/{start}?{q}",
                                 headers={'Authorization': 'Bearer ' + TOK})
    with opener.open(req, timeout=90) as r:
        d = json.load(r)
    pts = []
    for p in (d[0] if d else []):
        st = p.get('state', p.get('s'))
        ts = p.get('last_updated') or p.get('lu') or p.get('last_changed') or p.get('lc')
        if not ts: continue
        try:
            t = datetime.datetime.fromisoformat(ts.replace('Z', '+00:00')).astimezone(TZ)
            v = float(st)
        except Exception:
            continue
        pts.append((t, v))
    pts.sort(key=lambda x: x[0])
    return pts

def sample_at(pts, t, default=None):
    """last value <= t (binary search)"""
    lo, hi = 0, len(pts)
    while lo < hi:
        mid = (lo + hi) // 2
        if pts[mid][0] <= t: lo = mid + 1
        else: hi = mid
    return pts[lo-1][1] if lo > 0 else default

now = datetime.datetime.now(TZ)
start = now - datetime.timedelta(days=DAYS)
iso = lambda d: d.isoformat()
soc  = series('sensor.cadillac_battery', iso(start), iso(now))
chg  = series('sensor.cadillac_charge_power', iso(start), iso(now))
dis  = series('sensor.cadillac_discharge_power', iso(start), iso(now))
cvol = series('sensor.cadillac_car_voltage', iso(start), iso(now))
odo  = series('sensor.onstar_odometer', iso(start), iso(now))

# ---- 主积分 (以 cvolt 采样时间轴为基准，1min 插值) ----
def build_grid():
    grid = []
    t = start
    step = datetime.timedelta(seconds=60)
    while t < now:
        grid.append(t); t += step
    return grid
grid = build_grid()
SOC_LOW = 99.0
E = {'in': 0.0, 'out': 0.0, 'waste': 0.0, 'drive_s': 0.0, 'park_s': 0.0}
in_full = 0.0
for i in range(1, len(grid)):
    t0, t1 = grid[i-1], grid[i]
    dt = (t1 - t0).total_seconds() / 3600.0
    c = sample_at(chg, t0, 0.0) or 0.0
    d = sample_at(dis, t0, 0.0) or 0.0
    v = sample_at(cvol, t0, 12.6)
    s = sample_at(soc, t0, None)
    E['in'] += c * dt
    E['out'] += d * dt
    if v is not None and v >= 13.6:
        E['drive_s'] += dt * 3600
    else:
        E['park_s'] += dt * 3600
    if c > 5 and s is not None and s >= SOC_LOW:
        E['waste'] += c * dt

print(f"## 汇总 ({DAYS}d, {start:%m-%d %H:%M} -> {now:%m-%d %H:%M})")
print(f"总充入 {E['in']:.0f} Wh | 总放出 {E['out']:.0f} Wh | 净 {E['in']-E['out']:+.0f} Wh")
print(f"行车时长 {E['drive_s']/3600:.1f} h | 驻车时长 {E['park_s']/3600:.1f} h")
print(f"满电(SOC>=99)时仍充入 = 浪费 {E['waste']:.0f} Wh")

# ---- 容量估算 ----
cap_est = []
for i in range(1, len(soc)):
    t0, s0 = soc[i-1]; t1, s1 = soc[i]
    dt = (t1 - t0).total_seconds() / 3600.0
    dsoc = (s1 - s0) / 100.0
    if abs(dsoc) < 0.05 or dt > 6: continue
    ein = eout = 0.0
    for j in range(1, len(grid)):
        if grid[j] <= t0 or grid[j-1] >= t1: continue
        h = (grid[j] - grid[j-1]).total_seconds() / 3600.0
        ein += (sample_at(chg, grid[j-1], 0.0) or 0) * h
        eout += (sample_at(dis, grid[j-1], 0.0) or 0) * h
    net = ein - eout
    if dsoc > 0:
        cap_est.append(net / dsoc)
    else:
        cap_est.append((eout - ein) / (-dsoc))
cap_est = [c for c in cap_est if 100 < c < 5000]
cap_est.sort()
med = cap_est[len(cap_est)//2] if cap_est else 0
print(f"容量估算 n={len(cap_est)} 中位 {med:.0f} Wh  (P25 {cap_est[len(cap_est)//4] if cap_est else 0:.0f} / P75 {cap_est[3*len(cap_est)//4] if cap_est else 0:.0f})")

# ---- 放电(静态损耗) ----
park_wh = 0.0
for i in range(1, len(grid)):
    t0 = grid[i-1]
    h = (grid[i] - t0).total_seconds() / 3600.0
    if sample_at(cvol, t0, 12.6) < 13.6:
        park_wh += (sample_at(dis, t0, 0.0) or 0) * h
print(f"驻车放电 {park_wh:.0f} Wh / {E['park_s']/3600:.1f} h = {park_wh/(E['park_s']/3600) if E['park_s'] else 0:.1f} W  -> 日均(按24h) {park_wh/max(E['park_s']/3600,1e-9)*24:.0f} Wh/d")

# ---- 驾驶会话 (cvolt>=13.6 分组, 间隔>20min 切分) ----
sessions = []
cur = None
for i in range(1, len(grid)):
    t0 = grid[i-1]; h = (grid[i]-t0).total_seconds()/3600.0
    v = sample_at(cvol, t0, 12.6)
    if v >= 13.6:
        if cur is None:
            cur = [t0, t0, 0.0, 0.0, 0.0]  # start,end,inWh,wasteWh,outWh
        cur[1] = t0
        c = sample_at(chg, t0, 0.0) or 0.0
        cur[2] += c * h
        s = sample_at(soc, t0, None)
        if c > 5 and s is not None and s >= SOC_LOW: cur[3] += c*h
        cur[4] += (sample_at(dis, t0, 0.0) or 0)*h
    else:
        if cur is not None:
            if (t0 - cur[1]).total_seconds() > 1200:
                sessions.append(cur); cur = None
            else:
                pass
if cur: sessions.append(cur)
sessions = [s for s in sessions if (s[1]-s[0]).total_seconds() >= 300]
print(f"\n## 驾驶会话 (>=5min) n={len(sessions)}")
print(f"{'start':16s} {'min':>5s} {'in_Wh':>6s} {'waste_Wh':>8s} {'out_Wh':>6s}")
for s in sessions[-25:]:
    mins = (s[1]-s[0]).total_seconds()/60
    print(f"{s[0]:%m-%d %H:%M}    {mins:5.0f} {s[2]:6.0f} {s[3]:8.0f} {s[4]:6.0f}")
tin = sum(s[2] for s in sessions); tw = sum(s[3] for s in sessions)
print(f"合计 充入 {tin:.0f} Wh, 其中满电浪费 {tw:.0f} Wh ({tw/tin*100 if tin else 0:.0f}%)")
