#!/usr/bin/env python3
"""终版: 行车发电 vs 驻车消耗 vs 满电停机浪费 + 2kWh 投影"""
import json, sys, datetime, urllib.request, urllib.parse

CFG = json.load(open('/home/<USER>/.openclaw/workspace/jarvis/tools/ha_config.json'))
URL = CFG['url'].rstrip('/'); TOK = CFG['token']
TZ = datetime.timezone(datetime.timedelta(hours=8))
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
DRIVE_V = 12.9      # 发动机/ACC 判定阈值
FULL_SOC = 99.0
CHARGER_W = 400.0   # 车载 DC-DC 满功率
CAP = 1024.0        # 假定额定容量 Wh

def series(ent, start, end):
    q = urllib.parse.urlencode({'filter_entity_id': ent, 'end_time': end,
                                'minimal_response': '', 'no_attributes': ''})
    req = urllib.request.Request(f"{URL}/api/history/period/{start}?{q}",
                                 headers={'Authorization': 'Bearer ' + TOK})
    with opener.open(req, timeout=90) as r: d = json.load(r)
    pts = []
    for p in (d[0] if d else []):
        ts = p.get('last_updated') or p.get('lu') or p.get('last_changed') or p.get('lc')
        st = p.get('state', p.get('s'))
        if not ts: continue
        try:
            t = datetime.datetime.fromisoformat(ts.replace('Z','+00:00')).astimezone(TZ)
            v = float(st)
        except Exception: continue
        pts.append((t, v))
    pts.sort(key=lambda x: x[0]); return pts

def at(pts, t, dv=None):
    lo, hi = 0, len(pts)
    while lo < hi:
        m = (lo+hi)//2
        if pts[m][0] <= t: lo = m+1
        else: hi = m
    return pts[lo-1][1] if lo>0 else dv

now = datetime.datetime.now(TZ)
start = datetime.datetime(2026,9,13,4,0,tzinfo=TZ)
iso = lambda d: d.isoformat()
soc = series('sensor.cadillac_battery', iso(start), iso(now))
chg = series('sensor.cadillac_charge_power', iso(start), iso(now))
dis = series('sensor.cadillac_discharge_power', iso(start), iso(now))
cv  = series('sensor.cadillac_car_voltage', iso(start), iso(now))
odo = series('sensor.onstar_odometer', iso(start), iso(now))

step = datetime.timedelta(seconds=60)
t = start
drv_s = park_s = 0.0
ein_drv = ein_park = 0.0
drv_full_s = 0.0
ein_full = 0.0
out_drv = out_park = 0.0
day = {}
while t < now:
    nt = min(t+step, now); h = (nt-t).total_seconds()/3600.0
    c = at(chg, t, 0.0) or 0.0
    d = at(dis, t, 0.0) or 0.0
    v = at(cv, t, 12.6) or 12.6
    s = at(soc, t, None)
    dd = day.setdefault(t.date(), [0,0,0,0,0,0])  # ein,eout,drv_s,drv_full_s,ein_full
    if v >= DRIVE_V:
        drv_s += h*3600; ein_drv += c*h; out_drv += d*h
        dd[2] += h*3600
        if s is not None and s >= FULL_SOC:
            drv_full_s += h*3600; dd[3] += h*3600
    else:
        park_s += h*3600; ein_park += c*h; out_park += d*h
    if s is not None and s >= FULL_SOC:
        ein_full += c*h; dd[4] += c*h
    dd[0] += c*h; dd[1] += d*h
    t = nt

print(f"## 窗口 {start:%m-%d %H:%M} -> {now:%m-%d %H:%M}  ({(now-start).days} 天, HA 历史仅回溯到这里)")
print(f"行车(engine on, cV>={DRIVE_V}): {drv_s/3600:.1f} h | 驻车: {park_s/3600:.1f} h")
print(f"充入总量 {ein_drv+ein_park:.0f} Wh  (行车段 {ein_drv:.0f} / 驻车段 {ein_park:.0f})")
print(f"放出总量 {out_drv+out_park:.0f} Wh  (行车段 {out_drv:.0f} / 驻车段 {out_park:.0f})")
print(f"行车发电速率: {ein_drv/(drv_s/3600) if drv_s else 0:.0f} W  | 驻车耗电 {out_park/(park_s/3600) if park_s else 0:.1f} W(仅记录输出)")
print(f"满电(SOC>={FULL_SOC})时行车时长: {drv_full_s/3600:.1f} h  |  该时段充入仅 {ein_full:.0f} Wh")
pot = drv_full_s/3600 * CHARGER_W
print(f"=> 满电状态浪费的发电能力 ≈ {pot:.0f} Wh (按车载 {CHARGER_W:.0f}W 满功率, 12天)")

# SOC 衰减 -> 真实日耗
print("\n## 逐日")
print(f"{'date':10s} {'ein':>5s} {'eout':>5s} {'drv_h':>6s} {'full_h':>6s} {'soc末':>5s}")
for d in sorted(day):
    dd = day[d]
    sl = None
    for tt, vv in soc:
        if tt.date() == d: sl = vv
    print(f"{d.isoformat():10s} {dd[0]:5.0f} {dd[1]:5.0f} {dd[2]/3600:6.2f} {dd[3]/3600:6.2f} {sl if sl is not None else -1:5.0f}")

# 驻车纯静置段: SOC 与 dis 对照 -> 真实自耗
print("\n## 纯静置段 (无行车 & 无充电) SOC 衰减 -> 容量/日耗估算")
i = 0
tot_dsoc = 0.0; tot_out = 0.0; tot_h = 0.0
while i < len(soc)-1:
    t0, s0 = soc[i]; t1, s1 = soc[i+1]
    h = (t1-t0).total_seconds()/3600
    if h > 6 or s1 >= s0: 
        i += 1; continue
    c = at(chg, t0, 0.0) or 0.0
    if c > 2:
        i += 1; continue
    # 确认该段无行车
    drv = False
    tt = t0
    while tt < t1:
        if (at(cv, tt, 12.6) or 12.6) >= DRIVE_V: drv = True; break
        tt += datetime.timedelta(minutes=5)
    if drv:
        i += 1; continue
    eo = 0.0; tt = t0
    while tt < t1:
        nt = min(tt+datetime.timedelta(minutes=5), t1)
        eo += (at(dis, tt, 0.0) or 0)*(nt-tt).total_seconds()/3600
        tt = nt
    tot_dsoc += (s0-s1)/100.0; tot_out += eo; tot_h += h
    i += 1
if tot_dsoc > 0:
    print(f"静置样本 {tot_h:.1f} h, SOC 共跌 {tot_dsoc*100:.1f}% , 记录放电 {tot_out:.0f} Wh")
    print(f"=> 隐含容量 {tot_out/tot_dsoc:.0f} Wh (若记录值=全部消耗) ; 真实日耗 {tot_dsoc*100/(tot_h/24):.1f}%/d")
    print(f"=> 按 {CAP:.0f}Wh 额定: 日耗 {tot_dsoc*100/(tot_h/24)/100*CAP:.0f} Wh/d ; 满载续航 {CAP*100/(tot_dsoc*100/(tot_h/24)):.1f} 天")
