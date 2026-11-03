#!/usr/bin/env python3
"""One-shot proof: connect to DJI Power Auro 2000 Elite over BLE, show AC state,
optionally set AC, then read back.  Usage: ac_probe.py [on|off|readonly]"""
import asyncio
import json
import logging
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from bleak import BleakScanner  # noqa: E402
from dji_power_ble.device import DjiPowerDevice  # noqa: E402
from dji_power_ble.duml import parse_power_switches  # noqa: E402

WANT = (sys.argv[1] if len(sys.argv) > 1 else "on").lower()
SHOW_KEYS = (
    "battery_level", "power_sw", "key_0d", "key_05", "key_00", "key_0e",
    "ac_output_power", "total_output_power", "soc", "station_rules",
)


def dump(tag, data):
    print(f"--- {tag} (data keys={len(data)})")
    for k in sorted(data):
        if k in SHOW_KEYS or k.startswith("key_0d"):
            v = data[k]
            s = repr(v)
            print(f"    {k} = {s[:400]}")
    for k in ("power_sw", "key_0d"):
        v = data.get(k)
        if isinstance(v, str):
            try:
                rows = parse_power_switches(bytes.fromhex(v))
                print(f"    [{k}] rows: {rows}")
            except Exception as e:  # noqa
                print(f"    [{k}] parse err {e}")


async def find(addr, name):
    devs = await BleakScanner.discover(timeout=15, return_adv=True)
    for d, adv in devs.values():
        if d.address.upper() == addr.upper():
            return d
        if name and (adv.local_name or "") == name:
            return d
    return None


async def main():
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s %(message)s")
    spec = json.load(open(os.path.join(HERE, "pair.json")))
    addr, name = spec["ble_address"], spec.get("ble_name") or ""
    print(f"target {spec['name']} sn={spec['sn']} addr={addr}")
    dev = await find(addr, name)
    if dev is None:
        print("RESULT=NOT_FOUND")
        return 2
    print(f"found {dev.address} rssi(n/a) name={dev.name!r}")
    st = DjiPowerDevice(
        dev, spec["pair_key"], name=name or "DJI Power",
        model="DJI Power 2000", serial_number=spec["sn"],
    )
    st.add_state_listener(lambda d: None)
    try:
        await asyncio.wait_for(st.connect(), timeout=90)
    except Exception as e:  # noqa
        print(f"RESULT=CONNECT_FAIL {type(e).__name__}: {e}")
        return 3
    print("RESULT=CONNECTED")
    dump("after connect", st.data)
    if WANT in ("on", "off"):
        try:
            await asyncio.wait_for(st.set_ac(WANT == "on"), timeout=90)
            print(f"RESULT=SET_AC_{WANT.upper()}_OK")
        except Exception as e:  # noqa
            print(f"RESULT=SET_AC_FAIL {type(e).__name__}: {e}")
        await asyncio.sleep(2)
        dump("after set", st.data)
    try:
        await st.disconnect()
    except Exception:  # noqa
        pass
    print("RESULT=DONE")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
