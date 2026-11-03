#!/usr/bin/env python3
"""Create/update the 车载电源 1000mini HA Lovelace dashboard (WS API).

Native `sections` view only — no HACS/custom cards required (this HA has no layout-card).
Usage: ha_arm_dashboard.py [--apply]      # dry-run by default
"""
import json, os, sys, asyncio

HERE = os.path.dirname(os.path.abspath(__file__))
TOKEN = json.load(open(os.path.join(HERE, "..", "tools", "ha_config.json")))["token"]
URL = "http://<TS_IP>:8123"
URL_PATH = "car-power-mini"
TITLE = "车载电源 1000mini"
ICON = "mdi:car-battery"
APPLY = "--apply" in sys.argv

P = "mini_power"


def ent(entity, name, icon):
    return {"type": "entity", "entity": entity, "name": name, "icon": icon}


CARDS_POWER = [
    {"type": "gauge", "entity": f"sensor.{P}_battery", "name": "电量", "unit": "%",
     "needle": False, "severity": {"red": 10, "yellow": 20, "green": 50}},
    ent(f"sensor.{P}_remaining", "剩余可用", "mdi:clock-end"),
    ent(f"sensor.{P}_temperature", "电池温度", "mdi:thermometer"),
    ent(f"sensor.{P}_last_update", "数据更新", "mdi:update"),
    ent(f"sensor.{P}_monitor_status", "监控状态", "mdi:heart-pulse"),
]

CARDS_IO = [
    ent(f"sensor.{P}_discharge_power", "总放电", "mdi:flash-outline"),
    ent(f"sensor.{P}_charge_power", "总充电", "mdi:flash"),
    ent(f"sensor.{P}_sdc_discharge_w", "SDC 放电(出)", "mdi:battery-off"),
    ent(f"sensor.{P}_sdc_charge_w", "SDC 充电(入)", "mdi:battery-charging"),
    ent(f"sensor.{P}_ac_discharge_w", "AC 放电(出)", "mdi:power-plug-off"),
    ent(f"sensor.{P}_ac_charge_w", "AC 充电(入)", "mdi:power-plug"),
    ent(f"sensor.{P}_sdc_type", "连接设备", "mdi:connection"),
    ent(f"sensor.{P}_sdc_voltage", "SDC 电压", "mdi:sine-wave"),
    ent(f"binary_sensor.{P}_sdc_connected", "SDC 连接", "mdi:car-connected"),
]

CARDS_ALERT = [
    ent(f"sensor.{P}_hms_count", "告警数", "mdi:alert-circle-outline"),
    ent(f"sensor.{P}_hms_alert", "告警码", "mdi:alert"),
    ent(f"binary_sensor.{P}_hms_fault", "故障", "mdi:alert-octagon"),
]

CARDS_TREND = [
    {"type": "history-graph", "title": "电量趋势 (24h)",
     "entities": [{"entity": f"sensor.{P}_battery", "name": "电量"}],
     "hours_to_show": 24, "refresh_interval": 60},
    {"type": "history-graph", "title": "放电 / 充电功率 (24h)",
     "entities": [{"entity": f"sensor.{P}_discharge_power", "name": "放电"},
                  {"entity": f"sensor.{P}_charge_power", "name": "充电"}],
     "hours_to_show": 24, "refresh_interval": 60},
]

CONFIG = {
    "views": [
        {
            "title": TITLE,
            "path": "mini",
            "icon": ICON,
            "type": "sections",
            "max_columns": 4,
            "sections": [
                {"type": "grid", "title": "🔋 电量", "cards": CARDS_POWER},
                {"type": "grid", "title": "🔌 输入 / 输出", "cards": CARDS_IO},
                {"type": "grid", "title": "🌡 状态 / 告警", "cards": CARDS_ALERT},
                {"type": "grid", "title": "📈 历史趋势", "column_span": 2, "cards": CARDS_TREND},
            ],
        }
    ]
}


async def main():
    import aiohttp
    async with aiohttp.ClientSession(headers={"Authorization": f"Bearer {TOKEN}"}) as s:
        async with s.ws_connect(f"{URL}/api/websocket") as ws:
            await ws.receive_json()
            await ws.send_json({"type": "auth", "access_token": TOKEN})
            m = await ws.receive_json()
            if m.get("type") != "auth_ok":
                print("AUTH FAIL", m)
                return
            await ws.send_json({"id": 1, "type": "lovelace/dashboards/list"})
            m = await ws.receive_json()
            dash = m.get("result", []) if m.get("success") else []
            existing = {d.get("url_path"): d for d in dash}
            print("dashboards:", list(existing))
            print("cards:", sum(len(x["cards"]) for x in CONFIG["views"][0]["sections"]))
            if not APPLY:
                print("DRY-RUN (pass --apply)")
                return
            if URL_PATH not in existing:
                await ws.send_json({"id": 2, "type": "lovelace/dashboards/create",
                                    "url_path": URL_PATH, "title": TITLE, "icon": ICON,
                                    "show_in_sidebar": True, "require_admin": False})
                m = await ws.receive_json()
                print("CREATE:", m.get("success"), json.dumps(m.get("error"), ensure_ascii=False)[:200] if not m.get("success") else "")
            if existing.get(URL_PATH, {}).get("mode") == "yaml":
                print("REFUSING: yaml-mode dashboard")
                return
            await ws.send_json({"id": 3, "type": "lovelace/config/save",
                                "url_path": URL_PATH, "config": CONFIG})
            m = await ws.receive_json()
            print("SAVE:", m.get("success"), json.dumps(m.get("error"), ensure_ascii=False)[:300] if not m.get("success") else "")
            await ws.send_json({"id": 4, "type": "lovelace/config", "url_path": URL_PATH})
            m = await ws.receive_json()
            if m.get("success"):
                r = m["result"]
                raw = json.dumps(r, ensure_ascii=False)
                print("VERIFY view_type:", r["views"][0].get("type"),
                      "| sections:", len(r["views"][0].get("sections", [])),
                      "| cards:", sum(len(x.get("cards", [])) for x in r["views"][0].get("sections", [])),
                      "| arm_power refs:", raw.count("arm_power"),
                      "| custom cards:", raw.count("custom:"))
            else:
                print("VERIFY FAIL", json.dumps(m.get("error"), ensure_ascii=False)[:200])


asyncio.run(main())
