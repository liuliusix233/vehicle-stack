#!/usr/bin/env python3
"""
HUD GPS Bridge — Pi Side
========================
Reads HUD GPS from ADB logcat (HudGPS tag), calculates cumulative
driving odometer via Haversine, and pushes to HA REST API every 30s.

Data flow:
  HUD GPS → logcat HudGPS
      ↓ (adb logcat -d -s HudGPS:*)
  hud_gps_bridge.py (Pi)
      ↓ (Haversine + 30s batch)
  HA REST API → sensor.hud_gps_*

Sensors created:
  sensor.hud_gps_latitude
  sensor.hud_gps_longitude
  sensor.hud_gps_accuracy
  sensor.hud_gps_speed        (GPS groundspeed, km/h)
  sensor.hud_gps_bearing
  sensor.hud_gps_altitude
  sensor.hud_driving_odometer  (cumulative km, persisted)
  sensor.hud_gps_timestamp

Author: Vox ⚙️
"""

import json
import math
import os
import re
import subprocess
import sys
import time
import urllib.request

# ── Config ──
HUD_ADB = "<TS_IP>:5555"       # Tailscale stable
HUD_ADB_FALLBACK = "<LAN_IP>:5555"  # car WiFi DHCP (scanned)
ADB_TIMEOUT = 5                         # seconds per adb call
POLL_INTERVAL = 1.0                     # seconds between logcat polls
HA_PUSH_INTERVAL = 30                   # seconds between HA pushes
STATE_FILE = "/var/tmp/hud_gps_state.json"
HA_CONFIG = os.path.expanduser("~/ha_config.json")  # Pi's HA config

# HA API
HA_URL = None
HA_TOKEN = None


def load_ha_config():
    """Load HA connection details from config file."""
    global HA_URL, HA_TOKEN
    try:
        with open(HA_CONFIG) as f:
            cfg = json.load(f)
        HA_URL = cfg.get("url", "http://<TS_IP>:8123").rstrip("/")
        HA_TOKEN = cfg.get("token", "")
        return True
    except Exception as e:
        print(f"[ERROR] Failed to load HA config: {e}")
        return False


def ha_set_state(entity_id, state, attrs=None):
    """Set HA sensor state via REST API."""
    if not HA_URL or not HA_TOKEN:
        return False
    data = {"state": str(state), "attributes": attrs or {}}
    req = urllib.request.Request(
        f"{HA_URL}/api/states/{entity_id}",
        data=json.dumps(data).encode(),
        headers={
            "Authorization": f"Bearer {HA_TOKEN}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            return resp.status == 200 or resp.status == 201
    except Exception as e:
        print(f"[WARN] HA set_state {entity_id} failed: {e}")
        return False


def ha_create_sensor(entity_id, name, unit=None, icon=None):
    """Register a sensor entity in HA (create via state set)."""
    attrs = {"friendly_name": name}
    if unit:
        attrs["unit_of_measurement"] = unit
    if icon:
        attrs["icon"] = icon
    ha_set_state(entity_id, "unknown", attrs)


def find_hud_adb():
    """Try to find HUD ADB endpoint. Returns working address or None."""
    addrs = [HUD_ADB, HUD_ADB_FALLBACK]
    for addr in addrs:
        try:
            r = subprocess.run(
                ["timeout", str(ADB_TIMEOUT), "adb", "connect", addr],
                capture_output=True, text=True, timeout=ADB_TIMEOUT + 2
            )
            if "connected" in r.stdout.lower():
                print(f"[INFO] HUD ADB connected: {addr}")
                return addr
        except Exception:
            continue
    # Try scanning car WiFi for port 5555
    try:
        scan = subprocess.run(
            ["sudo", "nmap", "-sS", "-p", "5555", "--open", "<LAN_IP>/24",
             "-oG", "-"],
            capture_output=True, text=True, timeout=30
        )
        for line in scan.stdout.split("\n"):
            if "/open/" in line and "5555" in line:
                parts = line.split()
                ip = parts[1] if len(parts) > 1 else None
                if ip and ip != "bogon":
                    addr = f"{ip}:5555"
                    r = subprocess.run(
                        ["timeout", "3", "adb", "connect", addr],
                        capture_output=True, text=True, timeout=5
                    )
                    if "connected" in r.stdout.lower():
                        print(f"[INFO] HUD ADB scanned: {addr}")
                        return addr
    except Exception:
        pass
    return None


def read_gps_logcat(hud_addr):
    """Read HudGPS logcat lines and return latest GPS data dict."""
    try:
        r = subprocess.run(
            ["timeout", "3", "adb", "-s", hud_addr, "shell",
             "logcat", "-d", "-s", "HudGPS:*", "*:S"],
            capture_output=True, text=True, timeout=5
        )
        lines = r.stdout.strip().split("\n")
        latest = None
        for line in lines:
            # Logcat format: 07-24 13:20:01.123  1234  5678 D HudGPS: lat=...
            m = re.search(r"D\s+HudGPS\s*:\s*(.+)", line)
            if m:
                data_str = m.group(1)
                # Parse: <COORD>,<COORD>,acc=1.5,speed=0.0,bear=22.1,alt=17.6,ts=1784870401
                parts = data_str.split(",")
                parsed = {}
                for p in parts:
                    kv = p.split("=", 1)
                    if len(kv) == 2:
                        key = kv[0].strip()
                        val = kv[1].strip()
                        try:
                            parsed[key] = float(val)
                        except ValueError:
                            parsed[key] = val
                if "lat" in parsed and "lon" in parsed:
                    if parsed["lat"] != 0.0 or parsed["lon"] != 0.0:
                        latest = parsed
        return latest
    except Exception as e:
        print(f"[WARN] logcat read error: {e}")
        return None


def haversine(lat1, lon1, lat2, lon2):
    """Haversine distance in meters between two GPS points."""
    R = 6371000  # Earth radius in meters
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = (math.sin(dlat / 2) ** 2 +
         math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) *
         math.sin(dlon / 2) ** 2)
    c = 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))
    return R * c


def load_state():
    """Load persisted state (cumulative odometer, last GPS fix)."""
    default = {
        "odometer_km": 0.0,
        "last_lat": None,
        "last_lon": None,
        "last_update": 0,
        "total_samples": 0,
    }
    try:
        with open(STATE_FILE) as f:
            state = json.load(f)
            for k in default:
                state.setdefault(k, default[k])
            return state
    except (FileNotFoundError, json.JSONDecodeError):
        return dict(default)


def save_state(state):
    """Persist state to file."""
    try:
        os.makedirs(os.path.dirname(STATE_FILE), exist_ok=True)
        with open(STATE_FILE, "w") as f:
            json.dump(state, f)
    except Exception as e:
        print(f"[WARN] save_state failed: {e}")


def register_ha_sensors():
    """Create all GPS sensor entities in HA (idempotent)."""
    sensors = [
        ("sensor.hud_gps_latitude", "HUD GPS Latitude", "°", "mdi:latitude"),
        ("sensor.hud_gps_longitude", "HUD GPS Longitude", "°", "mdi:longitude"),
        ("sensor.hud_gps_accuracy", "HUD GPS Accuracy", "m", "mdi:crosshairs-gps"),
        ("sensor.hud_gps_speed", "HUD GPS Speed", "km/h", "mdi:speedometer"),
        ("sensor.hud_gps_bearing", "HUD GPS Bearing", "°", "mdi:compass"),
        ("sensor.hud_gps_altitude", "HUD GPS Altitude", "m", "mdi:signal"),
        ("sensor.hud_driving_odometer", "HUD Driving Odometer", "km", "mdi:counter"),
        ("sensor.hud_gps_timestamp", "HUD GPS Timestamp", None, "mdi:clock-outline"),
    ]
    for eid, name, unit, icon in sensors:
        ha_create_sensor(eid, name, unit, icon)
    print("[INFO] HA sensors registered")


def main():
    print("=" * 60)
    print("  HUD GPS Bridge v1.0")
    print("  Reading GPS from HUD → HA every 30s")
    print("=" * 60)

    # Load HA config
    if not load_ha_config():
        print("[FATAL] Cannot load HA config")
        sys.exit(1)

    # Find HUD ADB
    hud = find_hud_adb()
    if not hud:
        print("[ERROR] HUD not reachable via ADB")
        sys.exit(1)

    # Register HA sensors
    register_ha_sensors()
    time.sleep(1)

    # Load and reset odometer (starts from 0, will grow)
    state = load_state()
    if state["odometer_km"] > 0:
        print(f"[INFO] Resuming odometer: {state['odometer_km']:.2f} km")
    else:
        print("[INFO] Starting new odometer from 0 km")

    last_push = 0
    idle_count = 0

    print(f"[INFO] Polling HUD GPS every {POLL_INTERVAL}s, pushing every {HA_PUSH_INTERVAL}s")
    print()

    while True:
        try:
            gps = read_gps_logcat(hud)
            now = time.time()

            if gps:
                lat = gps.get("lat")
                lon = gps.get("lon")
                acc = gps.get("acc", -1)
                speed = gps.get("speed", -1)
                bear = gps.get("bear", -1)
                alt = gps.get("alt", -1)
                ts = gps.get("ts", int(now))

                # Accumulate odometer
                if (lat and lon and
                        state["last_lat"] is not None and
                        state["last_lon"] is not None):
                    dist = haversine(state["last_lat"], state["last_lon"],
                                     lat, lon)
                    # Filter noise: ignore jumps > 500m (GPS glitch) and
                    # tiny movements < 1m (GPS jitter when stationary)
                    if 1.0 <= dist <= 500.0:
                        state["odometer_km"] += dist / 1000.0
                        state["total_samples"] += 1
                    elif dist > 500.0:
                        print(f"[WARN] GPS jump {dist:.0f}m — skipping")

                state["last_lat"] = lat
                state["last_lon"] = lon
                state["last_update"] = int(now)
                idle_count = 0

                # Push to HA every HA_PUSH_INTERVAL seconds
                if now - last_push >= HA_PUSH_INTERVAL:
                    ts_dt = time.strftime(
                        "%Y-%m-%d %H:%M:%S", time.localtime(ts)
                    )

                    ha_set_state("sensor.hud_gps_latitude", f"{lat:.6f}")
                    ha_set_state("sensor.hud_gps_longitude", f"{lon:.6f}")
                    ha_set_state("sensor.hud_gps_accuracy",
                                 f"{acc:.1f}" if acc >= 0 else "unknown",
                                 {"unit_of_measurement": "m"})
                    ha_set_state("sensor.hud_gps_speed",
                                 f"{speed:.1f}" if speed >= 0 else "0.0",
                                 {"unit_of_measurement": "km/h"})
                    ha_set_state("sensor.hud_gps_bearing",
                                 f"{bear:.0f}" if bear >= 0 else "unknown",
                                 {"unit_of_measurement": "°"})
                    ha_set_state("sensor.hud_gps_altitude",
                                 f"{alt:.1f}" if alt >= 0 else "unknown",
                                 {"unit_of_measurement": "m"})
                    ha_set_state("sensor.hud_driving_odometer",
                                 f"{state['odometer_km']:.2f}",
                                 {"unit_of_measurement": "km"})
                    ha_set_state("sensor.hud_gps_timestamp", ts_dt)

                    print(f"[PUSH] lat={lat:.4f} lon={lon:.4f} "
                          f"acc={acc:.0f}m speed={speed:.0f}km/h "
                          f"odo={state['odometer_km']:.2f}km "
                          f"pts={state['total_samples']}")
                    last_push = now

                    # Persist state
                    save_state(state)
            else:
                # Push "no GPS" state if idle for 2+ push cycles
                idle_count += 1
                if idle_count >= 2 and now - last_push >= HA_PUSH_INTERVAL:
                    ha_set_state("sensor.hud_gps_latitude", "unavailable")
                    ha_set_state("sensor.hud_gps_longitude", "unavailable")
                    ha_set_state("sensor.hud_gps_timestamp",
                                 time.strftime("%Y-%m-%d %H:%M:%S"))
                    last_push = now

        except KeyboardInterrupt:
            save_state(state)
            print("\n[INFO] Stopped")
            break
        except Exception as e:
            print(f"[ERROR] Loop exception: {e}")
            # Try to reconnect ADB
            try:
                subprocess.run(
                    ["timeout", "5", "adb", "connect", hud],
                    capture_output=True, timeout=6
                )
            except Exception:
                pass

        time.sleep(POLL_INTERVAL)


if __name__ == "__main__":
    main()
