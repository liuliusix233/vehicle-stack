#!/usr/bin/env python3
"""Quick read-only HA WS checks: dashboards, lovelace resources, card types used by a dashboard."""
import json, os, sys, asyncio

HERE = os.path.dirname(os.path.abspath(__file__))
TOKEN = json.load(open(os.path.join(HERE, "..", "tools", "ha_config.json")))["token"]
URL = "http://<TS_IP>:8123"
TARGET = sys.argv[1] if len(sys.argv) > 1 else "car-data"


async def main():
    import aiohttp
    async with aiohttp.ClientSession(headers={"Authorization": f"Bearer {TOKEN}"}) as s:
        async with s.ws_connect(f"{URL}/api/websocket") as ws:
            await ws.receive_json()
            await ws.send_json({"type": "auth", "access_token": TOKEN})
            m = await ws.receive_json()
            print("auth:", m.get("type"))
            for i, req in enumerate([
                {"id": 10, "type": "lovelace/dashboards/list"},
                {"id": 11, "type": "lovelace/resources"},
                {"id": 12, "type": "lovelace/config", "url_path": TARGET},
            ]):
                await ws.send_json(req)
                m = await ws.receive_json()
                if not m.get("success"):
                    print(req["type"], "FAIL", json.dumps(m.get("error"), ensure_ascii=False)[:200])
                    continue
                r = m["result"]
                if req["type"] == "lovelace/dashboards/list":
                    print("dashboards:", [(d.get("url_path"), d.get("title")) for d in r])
                elif req["type"] == "lovelace/resources":
                    print("resources:", [(x.get("url", "")[-45:], x.get("type")) for x in (r or [])])
                else:
                    raw = json.dumps(r, ensure_ascii=False)
                    types = {}
                    for t in ("custom:grid-layout", "custom:layout-card", "horizontal-stack", "gauge", "history-graph"):
                        if t in raw:
                            types[t] = raw.count(t)
                    print(f"{TARGET} views:", [v.get("title") for v in r.get("views", [])], "| types:", types)


asyncio.run(main())
