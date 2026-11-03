#!/usr/bin/env python3
"""Remove cards referencing a given entity from an HA Lovelace dashboard (WS API).

Usage:  lovelace_strip_entity.py <url_path|-> <entity_id> [--apply]
        url_path '-' = default dashboard (no url_path param)
Dry-run by default: prints what would change, writes a backup, does not save.
"""
import json, os, sys, asyncio, time

HERE = os.path.dirname(os.path.abspath(__file__))
TOKEN = json.load(open(os.path.join(HERE, "..", "tools", "ha_config.json")))["token"]
URL = "http://<TS_IP>:8123"

URL_PATH = sys.argv[1]
TARGET = sys.argv[2]
APPLY = "--apply" in sys.argv
TS = time.strftime("%Y%m%d-%H%M%S")
removed_cards = []


def has_kids(n):
    return isinstance(n, dict) and any(isinstance(n.get(k), list) for k in ("cards", "entities"))


def clean(node):
    """Recurse into container nodes; drop only leaf entries referencing TARGET.
    A container (horizontal-stack / entities card with children) is never dropped
    wholesale — only the offending leaf inside it."""
    if isinstance(node, dict):
        for k in ("cards", "entities"):
            if isinstance(node.get(k), list):
                kept = []
                for x in node[k]:
                    if TARGET in json.dumps(x, ensure_ascii=False) and not has_kids(x):
                        removed_cards.append(x)
                        continue
                    kept.append(clean(x))
                node[k] = kept
    return node


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
            req = {"id": 1, "type": "lovelace/config"}
            if URL_PATH != "-":
                req["url_path"] = URL_PATH
            await ws.send_json(req)
            m = await ws.receive_json()
            if not m.get("success"):
                print("GET FAIL:", json.dumps(m.get("error"), ensure_ascii=False)[:200])
                return
            cfg = m["result"]
            views = cfg.get("views", []) if isinstance(cfg, dict) else []
            print("dashboard:", URL_PATH, "| views:", [v.get("title") or v.get("path") for v in views])
            raw = json.dumps(cfg, ensure_ascii=False)
            if TARGET not in raw:
                print(f"OK: {TARGET} not referenced anywhere -> nothing to do")
                return
            bak = os.path.join(HERE, f"lovelace_{URL_PATH.replace('/','_')}.bak-{TS}.json")
            with open(bak, "w") as f:
                json.dump(cfg, f, ensure_ascii=False, indent=1)
            print("backup ->", bak)
            before_t = raw.count(TARGET)
            for v in views:
                clean(v)
            after = json.dumps(cfg, ensure_ascii=False)
            print("removed entries:", json.dumps(removed_cards, ensure_ascii=False)[:400])
            print(f"target refs before={before_t} after={after.count(TARGET)}")
            print("siblings kept:", [e for e in ("sensor.cadillac_battery\"", "cadillac_charge_power", "cadillac_discharge_power", "cadillac_remaining") if e.split('"')[0] in after])
            if not APPLY:
                print("DRY-RUN (pass --apply to save)")
                return
            req = {"id": 2, "type": "lovelace/config/save", "config": cfg}
            if URL_PATH != "-":
                req["url_path"] = URL_PATH
            await ws.send_json(req)
            m = await ws.receive_json()
            print("SAVE:", m.get("success"), json.dumps(m.get("error"), ensure_ascii=False)[:200] if not m.get("success") else "")
            # verify
            req = {"id": 3, "type": "lovelace/config"}
            if URL_PATH != "-":
                req["url_path"] = URL_PATH
            await ws.send_json(req)
            m = await ws.receive_json()
            if m.get("success"):
                n = json.dumps(m["result"], ensure_ascii=False).count(TARGET)
                print("VERIFY targets left after save:", n)
                print("VERIFY 电量 gauge kept:", "sensor.cadillac_battery" in json.dumps(m["result"], ensure_ascii=False))


asyncio.run(main())
