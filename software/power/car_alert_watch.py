#!/usr/bin/env python3
"""车电瓶告警 watcher v2（无模型）：6h 采样状态机 + 云失联 + Pi 失联告警。

采样（2026-11-01 定稿）：默认 mode 1（Auto）；每 6h 切 mode 2（Recharge）静置 10min
读稳态电压（Pi bridge /state 的 auto_ac.volts）→ 切回 mode 1；采样 <12.20V 则切
mode 3（Charge 14V）补电 1h → 切回 mode 1。
告警主源 = 采样值；云值降级为只做 stale/Pi 检查。
"""
import json, os, time, urllib.request, sys, subprocess

BASE = os.path.expanduser("~/.openclaw/workspace/jarvis")
CFG = os.path.join(BASE, "tools/ha_config.json")
STATE_F = os.path.join(BASE, "power_monitor/car_alert_state.json")
LAST_F = "/tmp/car_alert_last.json"
PI_BASE = "http://<TS_IP>:8095"
PI_HEALTH = PI_BASE + "/health"
INTERVAL = 60

V_WARN = 12.20
V_ALERT = 12.40          # 采样真值警戒线（盒子 2026-11-01）
TREND_RATE = -0.03       # V/天；低于此斜率视为缓慢下滑
TREND_NEAR = 12.70       # 只在接近警戒线时报趋势，避免满电噪声
STALE_S = 900
PI_FAILS = 3

SAMPLE_EVERY = 6 * 3600
SETTLE_S = 600
CHARGE_S = 3600

OP = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def ha(cfg, ent):
    req = urllib.request.Request(cfg["url"].rstrip("/") + "/api/states/" + ent,
                                 headers={"Authorization": "Bearer " + cfg["token"]})
    with OP.open(req, timeout=8) as r:
        return json.loads(r.read().decode())


def ha_set(cfg, ent, state, attrs):
    payload = json.dumps({"state": state, "attributes": attrs}).encode()
    req = urllib.request.Request(
        cfg["url"].rstrip("/") + "/api/states/" + ent, data=payload, method="POST",
        headers={"Authorization": "Bearer " + cfg["token"],
                 "Content-Type": "application/json"})
    try:
        with OP.open(req, timeout=8) as r:
            return r.status
    except Exception as ex:
        sys.stderr.write("ha_set %s failed: %r" % (ent, ex) + chr(10))
        return None


def push_sample(cfg, st):
    sm = st.get("sm") or {}
    v = sm.get("last_v")
    attrs = {"unit_of_measurement": "V", "friendly_name": "车电瓶电压·采样真值",
             "threshold_v": V_WARN, "source": "BLE mode2 settle",
             "phase": sm.get("phase")}
    if sm.get("last_ts"):
        attrs["last_sample"] = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(sm["last_ts"]))
    if sm.get("next_due"):
        attrs["next_due"] = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(sm["next_due"]))
    ha_set(cfg, "sensor.cadillac_car_voltage_sample",
           ("%.2f" % v) if v is not None else "unknown", attrs)


def ha_post(cfg, path, payload):
    req = urllib.request.Request(
        cfg["url"].rstrip("/") + "/api/" + path,
        data=json.dumps(payload).encode(), method="POST",
        headers={"Authorization": ("B" + "earer " + cfg["token"]),
                 "Content-Type": "application/json"})
    try:
        with OP.open(req, timeout=8) as r:
            return r.status
    except Exception as ex:
        sys.stderr.write("ha_post %s failed: %r" % (path, ex) + chr(10))
        return None


def ha_notify(cfg, title, message, nid):
    ha_post(cfg, "services/persistent_notification/create",
            {"title": title, "message": message, "notification_id": nid})
    for target in ("mobile_app_sm_s9180",):
        ha_post(cfg, "services/notify/" + target, {"title": title, "message": message})


def sample_trend(sm):
    """最近 48h 内的采样斜率（V/天）；跨度不足 0.4 天返回 None。"""
    hist = sm.get("history") or []
    if len(hist) < 2:
        return None
    now = time.time()
    recent = [p for p in hist if now - p["ts"] <= 48 * 3600]
    if len(recent) < 2:
        return None
    span = (recent[-1]["ts"] - recent[0]["ts"]) / 86400.0
    if span < 0.4:
        return None
    rate = (recent[-1]["v"] - recent[0]["v"]) / span
    return {"rate": rate, "span": span, "v0": recent[0]["v"], "v1": recent[-1]["v"]}


def pi_get(path, timeout=10):
    with OP.open(PI_BASE + path, timeout=timeout) as r:
        return json.loads(r.read().decode())


def pi_carmode(mode):
    try:
        r = pi_get("/carmode?mode=%d" % mode, timeout=30)
        return r if isinstance(r, dict) else {"ok": False, "error": "bad response"}
    except Exception as ex:
        return {"ok": False, "error": repr(ex)}


def pi_volts():
    try:
        v = (pi_get("/state", timeout=8).get("auto_ac") or {}).get("volts")
        return float(v) if v is not None else None
    except Exception:
        return None


def load(p, d):
    try:
        return json.load(open(p))
    except Exception:
        return dict(d)


def save(p, o):
    with open(p, "w") as f:
        json.dump(o, f)


def tg(text):
    try:
        r = subprocess.run(["openclaw", "message", "send", "--channel", "telegram",
                            "--target", "5302210480", "--message", text],
                           timeout=30, capture_output=True, text=True)
        if r.returncode != 0:
            sys.stderr.write("tg failed: " + (r.stderr or r.stdout)[:200] + chr(10))
    except Exception as ex:
        sys.stderr.write("tg exception: %r" % (ex,) + chr(10))


def tg_throttled(sm, key, text):
    n = sm.get(key, 0) + 1
    sm[key] = n
    if n == 1 or n % 6 == 0:
        tg(text)


def fire(kind, text, tg_push=True):
    save(LAST_F, {"ts": time.time(), "iso": time.strftime("%F %T"), "kind": kind, "text": text})
    print(json.dumps({"fire": True, "kind": kind, "text": text}, ensure_ascii=False), flush=True)
    # 双通道：cron 负责微信；这里额外直发 Telegram（微信通道故障时仍能收到）
    if tg_push and os.environ.get("ALERT_TELEGRAM", "1") == "1":
        try:
            r = subprocess.run(["openclaw", "message", "send", "--channel", "telegram",
                                "--target", "5302210480", "--message", text],
                               timeout=30, capture_output=True, text=True)
            if r.returncode != 0:
                sys.stderr.write("telegram push failed: " + (r.stderr or r.stdout)[:200] + "\n")
        except Exception as ex:
            sys.stderr.write("telegram push exception: %r\n" % (ex,))


def sample_tick(st, cfg, force=False):
    """6h 采样状态机：idle -> settle -> (charge) -> idle。"""
    sm = st.setdefault("sm", {"phase": "idle", "phase_ts": 0, "next_due": 0,
                              "last_v": None, "last_ts": 0, "retry_at": 0})
    now = time.time()
    if force and sm["phase"] == "idle":
        sm["next_due"] = 0
    if now < sm.get("retry_at", 0):
        return
    if sm["phase"] == "idle":
        if now < sm.get("next_due", 0):
            return
        r = pi_carmode(2)
        if not r.get("ok"):
            sm["retry_at"] = now + 600
            tg_throttled(sm, "tg_start_fail", "⚠️ 采样启动失败：切 mode2 失败（%s），10 分钟后重试。" % str(r.get("error"))[:80])
            sys.stderr.write("sample: mode2 failed: %s\n" % str(r.get("error"))[:120])
            return
        sm.update(phase="settle", phase_ts=now)
        sm.pop("tg_start_fail", None)
    elif sm["phase"] == "settle":
        if now - sm["phase_ts"] < SETTLE_S:
            return
        v = pi_volts()
        r = pi_carmode(1)
        if v is None:
            sm.update(phase="idle", phase_ts=now, next_due=now + 600)
            tg("⚠️ 采样失败：mode2 静置后读不到电压（桥未连接或云值过期），已回 mode1，10 分钟后重试。")
            sys.stderr.write("sample: no volts, retry in 10min (mode1=%s)\n" % r.get("ok"))
            return
        hist = sm.setdefault("history", [])
        if not hist and sm.get("last_ts") and sm.get("last_v") is not None:
            hist.append({"ts": sm["last_ts"], "v": sm["last_v"]})
        hist.append({"ts": now, "v": v})
        sm["history"] = hist[-40:]
        sm.update(last_v=v, last_ts=now)
        push_sample(cfg, st)
        tr = sample_trend(sm)
        if tr:
            eta = (v - V_ALERT) / (-tr["rate"]) if tr["rate"] < 0 else None
            ha_set(cfg, "sensor.cadillac_car_voltage_trend", "%.3f" % tr["rate"],
                   {"unit_of_measurement": "V/天", "friendly_name": "车电瓶电压·趋势",
                    "span_days": round(tr["span"], 2), "v0": tr["v0"], "v1": tr["v1"],
                    "eta_days_1240": (round(eta, 1) if eta is not None else None)})
        if v < V_WARN:
            fire("low", "采样电压偏低：%.2fV（警戒 %.2fV），切 mode 3 给车电瓶补电 1h。" % (v, V_WARN), tg_push=False)
            ha_notify(cfg, "⚠ 车电瓶采样真值临界",
                      "采样真值 %.2fV，已低于紧急线 %.2fV，已切 mode 3 补电（%s）。" % (v, V_WARN, time.strftime("%F %T")),
                      "sdc_voltage_low")
            tg("🔴 采样完成：%.2fV，低于紧急线 %.2fV，已切 mode3 补电 1h（%s）。" % (v, V_WARN, time.strftime("%F %T")))
            r3 = pi_carmode(3)
            if r3.get("ok"):
                sm.update(phase="charge", phase_ts=now)
                sm.pop("tg_chg_fail", None)
            else:
                sm.update(phase="idle", next_due=now + 1800)
                tg_throttled(sm, "tg_chg_fail", "⚠️ 低压补电启动失败：切 mode3 失败（%s），30 分钟后重试。" % str(r3.get("error"))[:80])
                sys.stderr.write("sample: mode3 failed: %s\n" % str(r3.get("error"))[:120])
        else:
            if v < V_ALERT:
                fire("low", "采样真值偏低：%.2fV（警戒 %.2fV）。建议补电或检查熄火后是否仍有负载。" % (v, V_ALERT), tg_push=False)
                ha_notify(cfg, "⚠ 车电瓶采样真值偏低",
                          "采样真值 %.2fV，低于警戒线 %.2fV（%s）。" % (v, V_ALERT, time.strftime("%F %T")),
                          "sdc_voltage_low")
            if tr and tr["rate"] <= TREND_RATE and V_ALERT <= v < TREND_NEAR:
                eta = (v - V_ALERT) / (-tr["rate"]) if tr["rate"] < 0 else None
                fire("trend", "采样真值缓慢下滑：%.3f V/天（%.2f→%.2fV / %.1f天），预计 %.1f 天跌破 %.2fV。" % (tr["rate"], tr["v0"], tr["v1"], tr["span"], eta if eta is not None else -1, V_ALERT), tg_push=False)
                ha_notify(cfg, "⚠ 车电瓶采样真值缓慢下滑",
                          "最近 %.1f 天 %.2f→%.2fV（%.3f V/天），按此速度预计 %.1f 天跌破 %.2fV。" % (tr["span"], tr["v0"], tr["v1"], tr["rate"], eta if eta is not None else -1, V_ALERT),
                          "sdc_voltage_trend")
            nd = time.strftime("%H:%M", time.localtime(now + SAMPLE_EVERY))
            if v < V_ALERT:
                tg("⚠️ 采样完成：%.2fV，低于 12.40V 警戒线（%s）。下次 %s。" % (v, time.strftime("%F %T"), nd))
            elif tr and tr["rate"] <= TREND_RATE and V_ALERT <= v < TREND_NEAR:
                tg("✅ 采样完成：%.2fV，趋势 %.3f V/天（下滑），下次 %s。" % (v, tr["rate"], nd))
            else:
                tg("✅ 采样完成：%.2fV（%s），下次 %s。" % (v, time.strftime("%F %T"), nd))
            sm.update(phase="idle", phase_ts=now, next_due=now + SAMPLE_EVERY)
    elif sm["phase"] == "charge":
        if now - sm["phase_ts"] < CHARGE_S:
            return
        r = pi_carmode(1)
        if r.get("ok"):
            sm.update(phase="idle", phase_ts=now, next_due=now + SAMPLE_EVERY)
            push_sample(cfg, st)
            sm.pop("tg_back_fail", None)
            tg("🔋 补电结束：已切回 mode1，下次采样 %s。" % time.strftime("%H:%M", time.localtime(now + SAMPLE_EVERY)))
        else:
            sm["retry_at"] = now + 300
            tg_throttled(sm, "tg_back_fail", "⚠️ 补电结束失败：切回 mode1 失败（%s），5 分钟后重试。" % str(r.get("error"))[:80])
            sys.stderr.write("sample: mode1 restore failed: %s\n" % str(r.get("error"))[:120])


def main():
    once = "--once" in sys.argv
    force = "--force" in sys.argv or os.path.exists("/tmp/car_alert_force")
    cfg = json.load(open(CFG))
    st = load(STATE_F, {"cloud": False, "pi": False, "pi_fail": 0})
    while True:
        try:
            ms = ha(cfg, "sensor.cadillac_monitor_status")["state"]
        except Exception:
            ms = "unknown"
        try:
            lu = ha(cfg, "sensor.cadillac_last_update")["state"].strip()
            ts = None
            for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M", "%Y-%m-%dT%H:%M:%S%z"):
                try:
                    ts = time.mktime(time.strptime(lu, fmt))
                    break
                except Exception:
                    continue
            age = (time.time() - ts) if ts else 10**6
        except Exception:
            lu = "unknown"
            age = 10**6
        pi_ok = False
        try:
            with OP.open(PI_HEALTH, timeout=4) as r:
                pi_ok = b'"ok"' in r.read()
        except Exception:
            pi_ok = False

        if force:
            try:
                os.remove("/tmp/car_alert_force")
            except Exception:
                pass
            fire("test", "【端到端测试】车电瓶告警通道已接通（%s）。收到这条说明推送正常。" % time.strftime("%H:%M"))
            if not once:
                return

        # 6h 采样状态机（告警主源 = 采样值）
        try:
            sample_tick(st, cfg, force=force)
        except Exception as ex:
            sys.stderr.write("sample_tick error: %r\n" % (ex,))

        bad_cloud = (ms != "live") or (age > STALE_S)
        if bad_cloud:
            st["cloud_n"] = st.get("cloud_n", 0) + 1
        else:
            st["cloud_n"] = 0
        coolc = (time.time() - st.get("cloud_last", 0)) > 1800
        if st["cloud_n"] >= 2 and not st["cloud"] and coolc:
            st["cloud"] = True
            st["cloud_last"] = time.time()
            fire("cloud", "大疆云数据中断：状态=%s，最后更新 %s（%.0f 分钟前）。" % (ms, lu, age / 60))
        elif not bad_cloud and st["cloud"]:
            st["cloud"] = False
            st["cloud_n"] = 0

        if not pi_ok:
            st["pi_fail"] = st.get("pi_fail", 0) + 1
        else:
            st["pi_fail"] = 0
        if st["pi_fail"] >= PI_FAILS and not st["pi"]:
            st["pi"] = True
            fire("pi", "车载 Pi / 2000E 蓝牙桥失联（连续 %d 次探测失败）。" % st["pi_fail"])
        elif pi_ok and st["pi"]:
            st["pi"] = False

        save(STATE_F, st)
        if once:
            return
        time.sleep(INTERVAL)


if __name__ == "__main__":
    main()
