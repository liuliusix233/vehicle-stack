#!/usr/bin/env python3
"""DJI Power (BLE) <-> Home Assistant bridge.  Runs on the car Pi.

Owns the BLE link to the station, then:
  * mirrors state into HA sensors/nbinary_sensor (`sensor.pelite_*`)
  * mirrors HA helper switches (`input_boolean.pelite_*`) down to the station
  * serves a tiny HTTP API on :8095   (GET /state, /health, /set?key=..&on=1,
    /carmode?mode=N)

Safety: the port that powers this Pi (USB-C3) is refused for OFF via the
bridge; pass force=1 to override.
"""
import asyncio
import json
import os
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from datetime import datetime

from bleak import BleakScanner  # noqa: E402
from dji_power_ble.device import DjiPowerDevice  # noqa: E402
from dji_power_ble.duml import CAR_CHARGERS_KEY  # noqa: E402

PORT = int(os.environ.get("DJI_BRIDGE_PORT", "8095"))
HA = json.load(open(os.path.join(HERE, "ha_config.json")))
HA_URL = HA.get("url", "http://<TS_IP>:8123").rstrip("/")
HA_TOKEN = HA["token"]

# (type, seq) -> (helper_id_suffix, label)
PORTS = {
    (2, 1): ("ac", "AC 220V"),
    (4, 1): ("usb_c1", "USB-C1"),
    (4, 2): ("usb_c2", "USB-C2"),
    (4, 3): ("usb_c3", "USB-C3(本机供电)"),
    (3, 1): ("usb_a1", "USB-A1"),
    (3, 2): ("usb_a2", "USB-A2"),
    (5, 1): ("sdc1", "SDC1"),
    (5, 2): ("sdc2", "SDC2"),
}
PROTECTED = set()       # from policy.json protected_ports; none by default now
HELPER_PREFIX = "input_boolean.pelite_"
SENSOR_PREFIX = "sensor.pelite_"
RECONNECT_DELAY = 15
HELPER_POLL = 3.0
PUSH_EVERY = 20.0
POLICY_FILE = os.path.join(HERE, "policy.json")
DEFAULT_POLICY = {
    "enabled": True,
    "sensor": "sensor.cadillac_sdc_voltage",
    "on_v": 12.8,          # car running -> AC on
    "off_v": 12.7,         # car off    -> AC off
    "hold_on_s": 12,       # must hold this long before acting
    "hold_off_s": 15,
    "arm_switch": "input_boolean.pelite_auto_ac",
    "min_interval_s": 30,    # hard anti-flap: min seconds between policy AC writes
    "ignore_off_after_on_s": 60,   # once ON, ride out short dips (his ask)
    "ignore_on_after_off_s": 30,   # once OFF, ignore brief spikes
    "rail_guard": True,      # 车轨压保护：低于阈值报警/降载
    "rail_warn_v": 11.8,
    "rail_warn_s": 20,
    "rail_cut_v": 11.5,
    "rail_cut_s": 60,
    "protected_ports": [],
    "manual_hold_s": 900,   # 手动拨 AC 后的优先窗口（秒），到期自动恢复自动策略
    "soc_sensor": "sensor.cadillac_battery",  # 2000E 电量实体(%)
    "soc_min_pct": 10,      # 电量下限：低于此值禁止自动开 AC（不拿 2000E 给 1000mini 供电）
    "soc_resume_pct": 12,   # 回升到此值以上才重新允许自动开（迟滞，防抖）
}

log_lock = asyncio.Lock()


def log(*a):
    print(time.strftime("[%F %T]"), *a, flush=True)


# ---------------------------------------------------------------- HA REST
def _ha(method, path, payload=None, raw=False):
    url = f"{HA_URL}/api/{path}"
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {HA_TOKEN}")
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            body = r.read().decode()
            if raw:
                return body
            return json.loads(body) if body else None
    except urllib.error.HTTPError as e:
        return {"_error": f"HTTP {e.code} {e.read()[:120]!r}"}
    except json.JSONDecodeError:
        return {"_error": "non-json response"} if not raw else body
    except Exception as e:  # noqa
        return {"_error": f"{type(e).__name__}: {e}"}


async def ha(method, path, payload=None, raw=False):
    return await asyncio.to_thread(_ha, method, path, payload, raw)


async def ha_set_state(entity_id, state, attrs=None):
    return await ha("POST", f"states/{entity_id}", {"state": state, "attributes": attrs or {}})


async def ha_call(domain, service, payload):
    return await ha("POST", f"services/{domain}/{service}", payload)


KEYS = [k for _ts, (k, _l) in sorted(PORTS.items())]
TEMPLATE = ("{% for k in " + str(KEYS).replace("'", '"') + " %}"
            "{{ k }}={{ states('input_boolean.pelite_' ~ k) }}\n{% endfor %}")


# ---------------------------------------------------------------- station
class Bridge:
    def __init__(self):
        spec = json.load(open(os.path.join(HERE, "pair.json")))
        self.spec = spec
        self.station = None
        self.connected = False
        self.last_error = ""
        self.last_push = 0.0
        self.pending = {}          # (type,seq) -> desired bool
        self.tickn = 0
        self._mirror = {}
        self.policy = dict(DEFAULT_POLICY)
        try:
            self.policy.update(json.load(open(POLICY_FILE)))
        except Exception as e:  # noqa
            log("policy.json unreadable, using defaults:", e)
        self.protected = {tuple(p) for p in self.policy.get("protected_ports") or []}
        self._volts = None
        self.mini_battery = None
        self.cloud_battery = None   # 云值(与 HUD 同源)；BLE 站点值作兜底
        self._v_dir = 0
        self._v_since = time.monotonic()
        self._auto_state = "init"
        self._auto_acted = ""
        self._ac_seen = None
        self._soc = None
        self._soc_blocked = False
        self._last_ac_write = 0.0
        self._last_ac_was = None
        self._rail_low_since = None
        self._rail_warned = False

    # ---- connection -------------------------------------------------
    async def find(self):
        devs = await BleakScanner.discover(timeout=15, return_adv=True)
        for d, adv in devs.values():
            if d.address.upper() == self.spec["ble_address"].upper():
                return d
            if (adv.local_name or "") == self.spec.get("ble_name"):
                return d
        return None

    def clear_stale_link(self):
        """A killed client can leave BlueZ holding the link -> the station stops
        advertising -> we can never reconnect.  Drop it explicitly."""
        try:
            subprocess.run(["bluetoothctl", "disconnect", self.spec["ble_address"]],
                           timeout=15, capture_output=True)
            log("cleared stale BLE link")
        except Exception as e:  # noqa
            log("clear_stale_link failed:", e)

    async def run(self):
        miss = 0
        while True:
            try:
                dev = await self.find()
                if dev is None:
                    self.connected = False
                    self.last_error = "device not advertising"
                    miss += 1
                    log(f"scan: not found (#{miss})")
                    if miss % 4 == 0:
                        self.clear_stale_link()
                    await asyncio.sleep(RECONNECT_DELAY)
                    continue
                miss = 0
                st = DjiPowerDevice(dev, self.spec["pair_key"],
                                    name=self.spec.get("ble_name") or "DJI Power",
                                    model="DJI Power 2000",
                                    serial_number=self.spec["sn"])
                await asyncio.wait_for(st.connect(), timeout=90)
                self.station, self.connected = st, True
                self.last_error = ""
                log("BLE connected + authenticated")
                await self.push_state(force=True)
            except Exception as e:  # noqa
                self.connected = False
                self.last_error = f"{type(e).__name__}: {e}"
                log("BLE connect failed:", self.last_error)
                await asyncio.sleep(RECONNECT_DELAY)
                continue

            # keep the link alive; if it drops, reconnect
            _last_rm = None
            _last_rm_at = time.time()
            while self.connected:
                await asyncio.sleep(HELPER_POLL)
                try:
                    await self.tick()
                    _rm = (self.station.data or {}).get("runtime_min") if self.station else None
                    if _rm is not None and _rm != _last_rm:
                        _last_rm = _rm
                        _last_rm_at = time.time()
                    elif time.time() - _last_rm_at > 180:
                        log("STALE_RECONNECT runtime_min frozen", _rm)
                        self.connected = False
                        break
                except Exception as e:  # noqa
                    self.connected = False
                    self.last_error = f"{type(e).__name__}: {e}"
                    log("link lost:", self.last_error)
            try:
                await self.station.disconnect()
            except Exception:  # noqa
                pass
            self.station = None
            await asyncio.sleep(5)

    # ---- state ------------------------------------------------------
    def rows(self):
        d = (self.station.data if self.station else {}) or {}
        out = {}
        for r in d.get("power_switches") or []:
            out[(r["type"], r["seq"])] = r["sw"] == 1
        return out

    def snapshot(self):
        d = (self.station.data if self.station else {}) or {}
        return {
            "connected": self.connected,
            "error": self.last_error,
            "sn": self.spec["sn"],
            "battery": d.get("primary_battery_percent"),
            "mini_battery": self.mini_battery,
            "vehicle_total_pct": self.vehicle_total(),
            "temperature": d.get("temperature"),
            "runtime_min": d.get("runtime_min"),
            "input_w": d.get("input_w"),
            "sdc_in_w": d.get("sdc_input_w"),
            "output_w": d.get("output_w"),
            "usb_c3_w": d.get("usb_c_3_output_w"),
            "firmware": d.get("firmware"),
            "ports": {f"{k[0]}-{k[1]}": v for k, v in sorted(self.rows().items())},
            "car_chargers": d.get("car_chargers"),
            "auto_ac": {"state": self._auto_state, "volts": self._volts,
                        "last_action": self._auto_acted,
                        "on_v": self.policy.get("on_v"), "off_v": self.policy.get("off_v"),
                        "enabled": self.policy.get("enabled")},
        }

    def vehicle_total(self):
        """车辆电池总电量 = (2000E 2048Wh + 1000mini 1008Wh) 合计 / 3056Wh"""
        s = self.snapshot_battery()
        p2000, p1000 = s
        if p2000 is None or p1000 is None:
            return None
        return (2048.0 * p2000 + 1008.0 * p1000) / 3056.0

    def snapshot_battery(self):
        # 2026-10-12: 优先用云值(与 HUD/看板同源)；BLE 站点值只在云值缺失时兜底
        # （BLE 链路断时 primary_battery_percent 会冻住 -> 总电量曾卡在 37.7% 达半小时）
        d = (self.station.data if self.station else {}) or {}
        p2000 = self.cloud_battery if self.cloud_battery is not None else d.get("primary_battery_percent")
        return p2000, self.mini_battery

    async def push_state(self, force=False):
        now = time.monotonic()
        if not force and now - self.last_push < PUSH_EVERY:
            return
        self.last_push = now
        s = self.snapshot()
        await ha_set_state(SENSOR_PREFIX + "battery", s["battery"], {"unit_of_measurement": "%", "friendly_name": "电源·电量"})
        await ha_set_state(SENSOR_PREFIX + "output_w", s["output_w"], {"unit_of_measurement": "W", "friendly_name": "电源·总输出"})
        await ha_set_state(SENSOR_PREFIX + "input_w", s["input_w"], {"unit_of_measurement": "W", "friendly_name": "电源·总输入"})
        await ha_set_state(SENSOR_PREFIX + "temperature", s["temperature"], {"unit_of_measurement": "°C", "friendly_name": "电源·温度"})
        await ha_set_state(SENSOR_PREFIX + "runtime_min", s["runtime_min"], {"unit_of_measurement": "min", "friendly_name": "电源·剩余续航"})
        await ha_set_state(SENSOR_PREFIX + "usb_c3_w", ("unknown" if s["usb_c3_w"] is None else s["usb_c3_w"]), {"unit_of_measurement": "W", "friendly_name": "电源·USB-C3(Pi)负载"})
        await ha_set_state(SENSOR_PREFIX + "firmware", s["firmware"], {"friendly_name": "电源·固件"})
        await ha_set_state(
            SENSOR_PREFIX + "switch_summary",
            " ".join(f"{PORTS.get(k, (str(k), ''))[1].split('(')[0]}={'ON' if v else 'off'}"
                     for k, v in sorted(self.rows().items())),
            {"friendly_name": "电源·端口状态", "ports": s["ports"], "error": s["error"]},
        )
        await ha_set_state(SENSOR_PREFIX + "ble_result", "on" if self.connected else "off",
                           {"friendly_name": "电源·BLE 桥", "error": self.last_error})
        # ── 车辆电池总电量（两机合计 / 3056Wh），HA 实体 ──
        try:
            mb = await ha("GET", "states/sensor.mini_power_battery")
            self.mini_battery = float((mb or {}).get("state"))
        except (TypeError, ValueError):
            self.mini_battery = None
        try:
            cb = await ha("GET", "states/" + (self.policy.get("soc_sensor") or "sensor.cadillac_battery"))
            self.cloud_battery = float((cb or {}).get("state"))
        except (TypeError, ValueError):
            self.cloud_battery = None
        total = self.vehicle_total()
        p2000, p1000 = self.snapshot_battery()
        if total is None:
            await ha_set_state("sensor.vehicle_battery_total", "unknown",
                               {"friendly_name": "车辆电池总电量", "unit_of_measurement": "%",
                                "icon": "mdi:battery-heart-variant", "capacity_wh": 3056})
        else:
            await ha_set_state("sensor.vehicle_battery_total", f"{total:.1f}",
                               {"friendly_name": "车辆电池总电量", "unit_of_measurement": "%",
                                "state_class": "measurement",
                                "icon": "mdi:battery-heart-variant", "capacity_wh": 3056,
                                "battery_2000e": p2000, "battery_1000mini": p1000})
        await ha_set_state(SENSOR_PREFIX + "car_voltage", self._volts,
                           {"unit_of_measurement": "V", "friendly_name": "电源·车电瓶电压"})
        await ha_set_state(SENSOR_PREFIX + "auto_ac", self._auto_state,
                           {"friendly_name": "电源·自动 AC 策略",
                            "volts": self._volts, "on_v": self.policy.get("on_v"),
                            "off_v": self.policy.get("off_v"),
                            "enabled": self.policy.get("enabled"),
                            "last_action": self._auto_acted,
                            "soc": self._soc,
                            "soc_min_pct": self.policy.get("soc_min_pct"),
                            "soc_blocked": self._soc_blocked,
                            "icon": "mdi:car"})

    # ---- control ----------------------------------------------------
    async def write_port(self, t, sq, want, tries=3):
        """A single, settled port write with retries on flaky readback."""
        last = ""
        for i in range(tries):
            try:
                await self.station.set_port_switch(t, sq, want)
                await asyncio.sleep(1.0)
                return True, ""
            except Exception as e:  # noqa
                last = f"{type(e).__name__}: {e}"
                log(f"write {t}/{sq}={want} try{i + 1} failed: {last}")
                await asyncio.sleep(3)
        try:
            await self.station.refresh_config()
        except Exception as e:  # noqa
            log("refresh after failed write:", type(e).__name__, e)
        return False, last

    async def set_port(self, key, want, force=False):
        """key = helper suffix (ac / usb_c1 / sdc2 ...)."""
        match = next((ts for ts, (k, _) in PORTS.items() if k == key), None)
        if match is None:
            return {"ok": False, "error": f"unknown port {key}"}
        if not self.connected or self.station is None:
            return {"ok": False, "error": "not connected"}
        if match in self.protected and not want and not force:
            log(f"refused OFF for protected port {match} (use force=1)")
            return {"ok": False, "error": "protected port refused (force=1 to override)"}
        ok, err = await self.write_port(match[0], match[1], want)
        if not ok:
            return {"ok": False, "port": key, "error": err}
        now_rows = self.rows()
        return {"ok": True, "port": key, "sw": now_rows.get(match)}

    async def set_carmode(self, mode):
        """Switch the car-charger mode (1=Auto 2=Recharge 3=Charge) via BLE.

        Uses the first reported car_chargers row; enables the charger first
        when it is off, since mode can only change while enabled.
        """
        if not self.connected or self.station is None:
            return {"ok": False, "error": "not connected"}
        if mode not in (1, 2, 3):
            return {"ok": False, "error": f"bad mode {mode} (1=Auto 2=Recharge 3=Charge)"}
        try:
            rows = (self.station.data or {}).get("car_chargers") or []
            if not rows:
                upd = await self.station._read_accessory_config(CAR_CHARGERS_KEY, "car_chargers")
                rows = upd.get("car_chargers") or []
            if not rows:
                return {"ok": False, "error": "station reports no car_chargers rows"}
            row = rows[0]
            it, sq, at = row["interface_type"], row["seq"], row["type"]
            if row.get("sw") != 1:
                await self.station.set_car_charger(it, sq, at, enabled=True)
            await self.station.set_car_charger(it, sq, at, mode=mode)
        except Exception as e:  # noqa
            return {"ok": False, "error": f"{type(e).__name__}: {e}"}
        await self.push_state(force=True)
        return {"ok": True, "mode": mode, "interface": f"{it}-{sq}", "accessory_type": at}

    async def fetch_desired(self):
        r = await ha("POST", "template", {"template": TEMPLATE}, raw=True)
        if not isinstance(r, str) or not r.strip():
            log("fetch_desired failed:", str(r)[:120])
            return None
        out = {}
        for line in r.strip().splitlines():
            if "=" in line:
                k, v = line.split("=", 1)
                out[k.strip()] = v.strip() == "on"
        return out or None

    async def sync_helpers(self):
        """HA helper -> station."""
        desired = await self.fetch_desired()
        if not desired:
            return
        rows = self.rows()
        for ts, (key, _label) in PORTS.items():
            if key not in desired or key not in self._mirror:
                continue
            if key == "ac" and self._policy_owns_ac():
                # 2026-09-30: 手动拨动 AC = 限时接管（到点自动交还策略，不会永久关闭自动）
                if desired.get("ac") == self._mirror.get("ac"):
                    continue    # 没有手动动作，AC 仍归自动策略管
                hold = float(self.policy.get("manual_hold_s", 900))
                self._manual_until = time.monotonic() + hold
                log(f"ac: 手动接管 -> 自动策略暂停 {int(hold)}s 后自动恢复, set AC={desired.get('ac')}")
                # 落到下面的通用写入分支执行
            want, actual = desired[key], rows.get(ts)
            if actual is None or want == actual:
                continue
            if ts in self.protected and not want:
                log("helper wants a protected port off -> refused, mirroring back")
                await ha_call("input_boolean", "turn_on", {"entity_id": f"{HELPER_PREFIX}{key}"})
                continue
            log(f"{key}: helper={want} device={actual} -> writing")
            await self.write_port(ts[0], ts[1], want)

    async def mirror_helpers(self):
        """station -> HA helper (keep the dashboard truthful)."""
        rows = self.rows()
        for ts, (key, _label) in PORTS.items():
            actual = rows.get(ts)
            if actual is None or self._mirror.get(key) == actual:
                continue
            await ha_call("input_boolean", "turn_on" if actual else "turn_off",
                          {"entity_id": f"{HELPER_PREFIX}{key}"})
            self._mirror[key] = actual

    # ---- car-running auto AC policy ---------------------------------
    async def poll_voltage(self, sensor):
        """Vehicle 12 V rail as reported by the cloud monitor (fresh only)."""
        st = await ha("GET", f"states/{sensor}")
        if not isinstance(st, dict) or "_error" in st:
            return None
        raw = st.get("state")
        try:
            v = float(raw)
        except (TypeError, ValueError):
            return None
        try:
            age = time.time() - datetime.fromisoformat(
                (st.get("last_updated") or "").replace("Z", "+00:00")).timestamp()
        except Exception:  # noqa
            age = 0
        if age > self.policy.get("stale_s", 900):
            log(f"auto-ac: {sensor} stale {age:.0f}s -> skip")
            return None
        return v

    def _policy_owns_ac(self):
        p = self.policy
        if time.monotonic() < getattr(self, "_manual_until", 0.0):
            return False        # 手动优先窗口内，AC 归手动
        return bool(p.get("enabled")) and self._auto_state.startswith(("ac=", "wait("))

    async def auto_ac(self):
        """Engine on (rail > on_v) -> AC on; engine off (< off_v) -> AC off.

        手动拨 AC = 限时接管（manual_hold_s，默认 900s），到期自动交还策略。
        """
        p = self.policy
        if not p.get("enabled"):
            self._auto_state = "disabled"
            return
        arm = p.get("arm_switch")
        if arm:
            a = await ha("GET", f"states/{arm}")
            if isinstance(a, dict) and a.get("state") == "off":
                self._auto_state = "paused(HA)"
                return
        if time.monotonic() < getattr(self, "_manual_until", 0.0):
            left = int(self._manual_until - time.monotonic())
            self._auto_state = f"manual({left}s)"
            self._v_dir = 0
            self._v_since = time.monotonic()
            return
        v = await self.poll_voltage(p["sensor"])
        self._volts = v
        # any external change of the AC row restarts the hold (anti-flap)
        now_ac = self.rows().get((2, 1))
        if self._ac_seen is not None and now_ac != self._ac_seen:
            log(f"auto-ac: AC changed externally ({self._ac_seen}->{now_ac}) -> re-arm hold")
            self._v_since = time.monotonic()
            self._v_dir = 0
        self._ac_seen = now_ac
        if v is None:
            self._auto_state = "no_data"
            self._v_dir = 0
            return
        # ---- 电量守卫（盒子 2026-10-12）：2000E 低于 soc_min_pct 不给 1000mini 供电 ----
        soc_sensor = p.get("soc_sensor")
        soc = await self.poll_voltage(soc_sensor) if soc_sensor else None
        self._soc = soc
        soc_min = float(p.get("soc_min_pct", 0) or 0)
        soc_resume = float(p.get("soc_resume_pct", 0) or 0) or (soc_min + 2)
        if soc_min <= 0 or soc is None:
            self._soc_blocked = False   # 未启用/无数据 -> 不拦（2000E 自身有 5% 硬件下限兜底）
        elif self._soc_blocked:
            if soc >= soc_resume:
                self._soc_blocked = False
                log(f"auto-ac: soc {soc:.0f}% >= {soc_resume:.0f}% -> 电量守卫解除")
        elif soc < soc_min:
            self._soc_blocked = True
            log(f"auto-ac: soc {soc:.0f}% < {soc_min:.0f}% -> 禁止自动开 AC（保护 2000E）")
        if self._soc_blocked:
            if self.rows().get((2, 1)) is True:
                log(f"auto-ac: soc {soc:.0f}% 低 -> AC OFF")
                ok, err = await self.write_port(2, 1, False)
                self._last_ac_write = time.monotonic()
                if ok:
                    self._last_ac_was = False
                    self._ac_seen = False
                    self._mirror["ac"] = False
                    await ha_call("input_boolean", "turn_off",
                                  {"entity_id": "input_boolean.pelite_ac"})
            self._auto_state = f"soc-low({soc:.0f}%)" if soc is not None else "soc-low(no_data)"
            self._v_dir = 0
            self._v_since = time.monotonic()
            return
        if v >= p["on_v"]:
            d = 1
        elif v <= p["off_v"]:
            d = -1
        else:
            d = 0
        if d != self._v_dir:
            self._v_dir, self._v_since = d, time.monotonic()
            self._auto_state = f"wait({v:.2f}V)"
            return
        hold = p["hold_on_s"] if d == 1 else p["hold_off_s"]
        if d == 0 or time.monotonic() - self._v_since < hold:
            self._auto_state = f"wait({v:.2f}V)"
            return
        want = d == 1
        if self.rows().get((2, 1)) == want:
            self._auto_state = f"ac={'on' if want else 'off'}({v:.2f}V)"
            return
        cool = p.get("min_interval_s", 120)
        if time.monotonic() - self._last_ac_write < cool:
            self._auto_state = f"cool({v:.2f}V)"
            return
        if self._last_ac_was is not None:
            since = time.monotonic() - self._last_ac_write
            if (want is False and self._last_ac_was is True
                    and since < p.get("ignore_off_after_on_s", 60)):
                self._auto_state = f"keep-on({v:.2f}V)"
                return
            if (want is True and self._last_ac_was is False
                    and since < p.get("ignore_on_after_off_s", 30)):
                self._auto_state = f"keep-off({v:.2f}V)"
                return
        log(f"auto-ac: {v:.2f}V -> AC {'ON' if want else 'OFF'}")
        ok, err = await self.write_port(2, 1, want)
        self._last_ac_write = time.monotonic()
        if ok:
            self._last_ac_was = want
            self._ac_seen = want
            self._auto_acted = f"{'on' if want else 'off'}@{v:.2f}V {time.strftime('%H:%M:%S')}"
            self._auto_state = f"ac={'on' if want else 'off'}({v:.2f}V)"
            # keep the HA helper in step immediately so the helper sync can't fight back
            await ha_call("input_boolean", "turn_on" if want else "turn_off",
                          {"entity_id": "input_boolean.pelite_ac"})
            self._mirror["ac"] = want
        else:
            self._auto_state = f"write_fail:{err[:40]}"

    async def rail_guard(self):
        """车 12V 轨压保护：持续偏低 -> HA 通知 + 必要时卸掉 AC 负载。"""
        p = self.policy
        if not p.get("rail_guard", True):
            return
        v = self._volts
        if v is None:
            return
        now = time.monotonic()
        warn_v = p.get("rail_warn_v", 11.8)
        if v < warn_v:
            if self._rail_low_since is None:
                self._rail_low_since = now
            low = now - self._rail_low_since
            if low >= p.get("rail_warn_s", 20) and not self._rail_warned:
                self._rail_warned = True
                log(f"rail guard: LOW {v:.2f}V for {low:.0f}s")
                await ha("POST", "services/persistent_notification/create",
                         {"title": "⚠ 车电瓶轨压偏低",
                          "message": f"SDC 电压 {v:.2f}V 持续 {low:.0f}s（阈值 {warn_v}V）。"
                                     f"行车中若刚上 800W 负载，请查线/保险与 DC-DC 余量。"})
                await ha_set_state(SENSOR_PREFIX + "car_voltage_alert", "low",
                                   {"friendly_name": "电源·轨压告警", "volts": v,
                                    "threshold": warn_v, "icon": "mdi:alert"})
            if (low >= p.get("rail_cut_s", 60) and self.rows().get((2, 1))
                    and v < p.get("rail_cut_v", 11.5)):
                log("rail guard: cutting AC load")
                await self.write_port(2, 1, False)
        else:
            self._rail_low_since = None
            if self._rail_warned and v > warn_v + 0.2:
                self._rail_warned = False
                await ha_set_state(SENSOR_PREFIX + "car_voltage_alert", "ok",
                                   {"friendly_name": "电源·轨压告警", "volts": v,
                                    "icon": "mdi:check-circle"})

    async def tick(self):
        self.tickn += 1
        if self.tickn % 20 == 1:      # pick up policy.json edits without a restart
            try:
                self.policy.update(json.load(open(POLICY_FILE)))
                self.protected = {tuple(p) for p in self.policy.get("protected_ports") or []}
            except Exception as e:  # noqa
                log("policy reload failed:", e)
        if self.tickn % 5 == 0:
            try:
                await self.station._read_accessory_config(0x0D, "power_switches")
            except Exception as e:  # noqa
                log("periodic refresh:", type(e).__name__, e)
        try:
            await self.auto_ac()
        except Exception as e:  # noqa
            log("auto_ac error:", type(e).__name__, e)
        try:
            await self.rail_guard()
        except Exception as e:  # noqa
            log("rail_guard error:", type(e).__name__, e)
        await self.sync_helpers()
        await self.mirror_helpers()
        await self.push_state()


# ---------------------------------------------------------------- HTTP
async def handle(reader, writer, br):
    try:
        head = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), timeout=10)
    except Exception:
        writer.close()
        return
    line = head.split(b"\r\n", 1)[0].decode("latin1")
    parts = line.split()
    path = parts[1] if len(parts) > 1 else "/"
    query = {}
    if "?" in path:
        path, qs = path.split("?", 1)
        for kv in qs.split("&"):
            if "=" in kv:
                k, v = kv.split("=", 1)
                query[k] = v
    try:
        if path == "/health":
            body = json.dumps({"ok": True, "connected": br.connected})
        elif path == "/state":
            body = json.dumps(br.snapshot(), ensure_ascii=False)
        elif path == "/set":
            key = query.get("key", "")
            want = query.get("on", "1") in ("1", "on", "true")
            force = query.get("force", "0") in ("1", "on", "true")
            res = await br.set_port(key, want, force)
            await br.push_state(force=True)
            body = json.dumps(res, ensure_ascii=False)
        elif path == "/carmode":
            try:
                mode = int(query.get("mode", "0"))
            except ValueError:
                mode = 0
            res = await br.set_carmode(mode)
            body = json.dumps(res, ensure_ascii=False)
        else:
            body = json.dumps({"error": "not found",
                               "usage": "/state /health /set?key=ac&on=1 /carmode?mode=N"})
    except Exception as e:  # noqa
        body = json.dumps({"error": f"{type(e).__name__}: {e}"})
    payload = body.encode()
    writer.write(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                 + str(len(payload)).encode() + b"\r\nConnection: close\r\n\r\n" + payload)
    await writer.drain()
    writer.close()


async def main():
    br = Bridge()
    server = await asyncio.start_server(lambda r, w: handle(r, w, br), "0.0.0.0", PORT)
    log(f"HTTP listening on :{PORT}")
    try:
        async with server:
            await asyncio.gather(server.serve_forever(), br.run())
    finally:
        pass


if __name__ == "__main__":
    asyncio.run(main())
