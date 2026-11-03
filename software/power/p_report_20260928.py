#!/usr/bin/env python3
"""P (self parasitic loss) report for DJI Power 2000 — 2026-09-28.

Window: 2026-09-26T12:45 -> now (default 2026-09-28T20:00), CST.
Rule: keep minutes with sdc_charge_w < 30W; drop >=30W minutes AND their dt.

Also: clean-segment analysis (contiguous kept minutes) so the SOC gain of
dropped high-power minutes does not leak into the residual.

Usage: p_report_20260928.py <start_iso> <end_iso> [--refresh]
"""
import json, os, sys, urllib.parse, urllib.request
from datetime import datetime, timedelta, timezone

TOKEN = os.environ["HA_TOKEN"]
URL = "http://<TS_IP>:8123"
CST = timezone(timedelta(hours=8))
START = datetime.fromisoformat(sys.argv[1])
END = datetime.fromisoformat(sys.argv[2])
REFRESH = "--refresh" in sys.argv
HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, ".cache_preport.json")
CAP = 2048.0
KW = 30.0
NEED = 0.35          # min baseline power to count as "sdc active" (<30W band)

ENTS = ["sensor.cadillac_sdc_charge_w", "sensor.cadillac_charge_power",
        "sensor.cadillac_discharge_power", "sensor.cadillac_sdc_discharge_w",
        "sensor.cadillac_battery", "sensor.cadillac_car_voltage"]

merged = None
if os.path.exists(CACHE) and not REFRESH:
    with open(CACHE) as f:
        c = json.load(f)
    if c.get("start") == START.isoformat() and c.get("end") == END.isoformat():
        merged = c["data"]
        print(f"[cache] {CACHE}")
if merged is None:
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    merged = {}
    cur = START
    while cur < END:
        nxt = min(cur + timedelta(hours=3), END)
        qs = urllib.parse.urlencode({"filter_entity_id": ",".join(ENTS),
                                     "minimal_response": "1",
                                     "end_time": nxt.isoformat(timespec="seconds")})
        req = urllib.request.Request(
            f"{URL}/api/history/period/{cur.isoformat(timespec='seconds')}?{qs}")
        req.add_header("Authorization", "Bearer " + TOKEN)
        try:
            for s in json.loads(opener.open(req, timeout=180).read()):
                if s:
                    merged.setdefault(s[0].get("entity_id", "?"), []).extend(s)
        except Exception as e:
            print(f"  page {cur:%m-%d %H:%M} failed: {e}")
        cur = nxt
    with open(CACHE, "w") as f:
        json.dump({"start": START.isoformat(), "end": END.isoformat(), "data": merged}, f)
    print(f"[fetched] -> {CACHE}")


def pts(eid):
    seen, out = set(), []
    for r in merged.get(eid, []):
        k = (r.get("last_changed"), r.get("state"))
        if k in seen:
            continue
        seen.add(k)
        try:
            v = float(r["state"])
        except (KeyError, ValueError, TypeError):
            continue
        out.append((datetime.fromisoformat(r["last_changed"]).astimezone(CST), v))
    out.sort(key=lambda x: x[0])
    return out


def at(p, t):
    """last value at or before t (None if series starts after t)"""
    if not p or t < p[0][0]:
        return None
    lo, hi = 0, len(p) - 1
    while lo < hi:
        m = (lo + hi + 1) // 2
        if p[m][0] <= t:
            lo = m
        else:
            hi = m - 1
    return p[lo][1]


charge = pts("sensor.cadillac_sdc_charge_w")
cpow = pts("sensor.cadillac_charge_power")
disch = pts("sensor.cadillac_discharge_power")
sdis = pts("sensor.cadillac_sdc_discharge_w")
soc = pts("sensor.cadillac_battery")
volt = pts("sensor.cadillac_car_voltage")

print(f"\n=== window {START:%m-%d %H:%M} -> {END:%m-%d %H:%M} "
      f"({(END-START).total_seconds()/3600:.2f}h) ===")
for nm, p in (("sdc_charge_w", charge), ("charge_power", cpow), ("discharge_power", disch),
              ("sdc_discharge_w", sdis), ("battery", soc), ("car_voltage", volt)):
    if p:
        print(f"  {nm:16s} n={len(p):5d}  first={p[0][0]:%m-%d %H:%M}={p[0][1]:.2f}  last={p[-1][0]:%m-%d %H:%M}={p[-1][1]:.2f}")
    else:
        print(f"  {nm:16s} NO DATA")
print("  SOC samples:", "  ".join(f"{t:%m-%d %H:%M}={v:.0f}" for t, v in soc))

# ---------- minute grid ----------
NMIN = int((END - START).total_seconds() // 60)
grid = []
for i in range(NMIN):
    t = START + timedelta(minutes=i)
    c = at(charge, t)
    d = at(disch, t)
    dv = at(disch, t)
    c = 0.0 if c is None else c
    d = 0.0 if d is None else d
    grid.append((t, c, d))

n_excl = sum(1 for _, c, _ in grid if c >= KW)
n_keep = NMIN - n_excl
E_in_all = sum(c / 60.0 for _, c, _ in grid)
E_out_all = sum(d / 60.0 for _, c, _ in grid)
E_in_kept = sum(c / 60.0 for _, c, _ in grid if c < KW)
E_out_kept = sum(d / 60.0 for _, c, _ in grid if c < KW)
E_in_excl = E_in_all - E_in_kept
print(f"\n--- exclusion rule (<{KW:.0f}W) ---")
print(f"minutes all={NMIN} kept={n_keep} ({n_keep/NMIN*100:.1f}%) excluded={n_excl} ({n_excl/NMIN*100:.1f}%)")
print(f"E_in_all={E_in_all:.1f}Wh  kept={E_in_kept:.1f}Wh  excluded={E_in_excl:.1f}Wh ({E_in_excl/max(E_in_all,1e-9)*100:.1f}% of input)")
print(f"E_out_all={E_out_all:.1f}Wh  kept={E_out_kept:.1f}Wh")
dt_keep = n_keep / 60.0
print(f"kept dt={dt_keep:.2f}h   avg discharge over kept = {E_out_kept/max(dt_keep,1e-9):.2f}W")

# ---------- clean contiguous kept segments ----------
segs = []
cur = None
for t, c, d in grid:
    keep = c < KW
    if keep:
        if cur is None:
            cur = [t, t, 0.0, 0.0, 0]
        cur[1] = t
        cur[2] += c / 60.0
        cur[3] += d / 60.0
        cur[4] += 1
    else:
        if cur is not None:
            segs.append(cur)
            cur = None
if cur is not None:
    segs.append(cur)

print(f"\n--- clean segments (contiguous <{KW:.0f}W minutes) ---")
print(f"{'start':>16} {'end':>16} {'hrs':>6} {'E_in':>8} {'E_out':>8} {'dSOC':>5} {'P':>8} {'P±1%':>8}")
rows = []
for s in segs:
    hrs = (s[1] - s[0]).total_seconds() / 3600 + 1 / 60
    s0, s1 = at(soc, s[0]), at(soc, s[1])
    if hrs >= 0.5 and s0 is not None and s1 is not None:
        ds = s1 - s0
        Es = ds / 100.0 * CAP
        P = (s[2] - s[3] - Es) / hrs
        P_lo = (s[2] - s[3] - (ds + 1) / 100.0 * CAP) / hrs
        P_hi = (s[2] - s[3] - (ds - 1) / 100.0 * CAP) / hrs
        rows.append((s[0], s[1], hrs, s[2], s[3], ds, P, P_lo, P_hi))
        print(f"{s[0]:%m-%d %H:%M} {s[1]:%m-%d %H:%M} {hrs:6.2f} {s[2]:8.1f} {s[3]:8.1f} "
              f"{ds:+5.0f} {P:8.2f} {min(P_lo,P_hi):7.2f}/{max(P_lo,P_hi):.2f}")

# long clean segments only (>=3h) = the usable ones
print("\n--- long clean segments (>=3h) residue ---")
tot_in = tot_out = tot_hrs = 0.0
for s in segs:
    hrs = (s[1] - s[0]).total_seconds() / 3600 + 1 / 60
    if hrs < 3:
        continue
    s0, s1 = at(soc, s[0]), at(soc, s[1])
    if s0 is None or s1 is None:
        continue
    ds = s1 - s0
    Es = ds / 100.0 * CAP
    P = (s[2] - s[3] - Es) / hrs
    tot_in += s[2]
    tot_out += s[3]
    tot_hrs += hrs
    print(f"  {s[0]:%m-%d %H:%M}->{s[1]:%m-%d %H:%M} {hrs:.1f}h  in={s[2]:.1f} out={s[3]:.1f} dSOC={ds:+.0f} P={P:.2f}W")
if tot_hrs:
    print(f"  SUM: {tot_hrs:.1f}h in={tot_in:.1f} out={tot_out:.1f} -> avg non-SOC drift={(tot_in-tot_out)/tot_hrs:.2f}W")

# ---------- baseline discharge TWA ----------
print("\n--- baseline: discharge_power TWA over whole window ---")
for nm, p in (("discharge_power", disch), ("sdc_discharge_w", sdis)):
    if not p:
        continue
    tot = span = 0.0
    hist = {}
    for i, (t, v) in enumerate(p):
        t2 = p[i + 1][0] if i + 1 < len(p) else END if END > t else t + timedelta(minutes=1)
        d = (t2 - t).total_seconds()
        if d <= 0:
            continue
        tot += v * d
        span += d
        hist[round(v)] = hist.get(round(v), 0) + d
    print(f"  {nm}: TWA={tot/span:.2f}W over {span/3600:.2f}h")
    print("    share:", {f"{k}W": round(v / span * 100) for k, v in sorted(hist.items())})

# ---------- per-day ----------
print("\n--- per-day (CST) ---")
for day in sorted({t.strftime("%m-%d") for t, _, _ in grid}):
    rows_d = [(t, c2, d2) for t, c2, d2 in grid if t.strftime("%m-%d") == day]
    dd_in = sum(c2 / 60.0 for _, c2, _ in rows_d)
    dd_out = sum(d2 / 60.0 for _, _, d2 in rows_d)
    dd_ex = sum(1 for _, c, _ in rows_d if c >= KW)
    d0, d1 = rows_d[0][0], rows_d[-1][0]
    s0, s1 = at(soc, d0), at(soc, d1)
    soc_txt = f"{s0:.0f}->{s1:.0f} ({(s1-s0)/100*CAP:+.0f}Wh)" if s0 is not None and s1 is not None else "n/a"
    print(f"  {day} {len(rows_d)}min excl={dd_ex} in={dd_in:.1f} out={dd_out:.1f} Wh  SOC {soc_txt}")
