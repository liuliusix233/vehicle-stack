#!/usr/bin/env python3
"""Read-only: connect over BLE and dump the decoded (non-raw) state.

Prints everything except key_XX raw hex blobs, so per-port loads are visible.
"""
import asyncio
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from bleak import BleakScanner  # noqa: E402
from dji_power_ble.device import DjiPowerDevice  # noqa: E402


def brief(v):
    if isinstance(v, (list, dict)):
        s = json.dumps(v, ensure_ascii=False, default=str)
    else:
        s = str(v)
    return s[:240]


async def find(addr, name):
    devs = await BleakScanner.discover(timeout=15, return_adv=True)
    for d, adv in devs.values():
        if d.address.upper() == addr.upper():
            return d
        if name and (adv.local_name or "") == name:
            return d
    return None


async def main():
    spec = json.load(open(os.path.join(HERE, "pair.json")))
    dev = await find(spec["ble_address"], spec.get("ble_name") or "")
    if dev is None:
        print("RESULT=NOT_FOUND")
        return 2
    st = DjiPowerDevice(
        dev, spec["pair_key"], name=spec.get("ble_name") or "DJI Power",
        model="DJI Power 2000", serial_number=spec["sn"],
    )
    await st.connect()
    print("RESULT=CONNECTED")
    data = st.data
    for k in sorted(data):
        if k.startswith("key_"):
            continue
        print(f"{k} = {brief(data[k])}")
    print("--- raw keys present:", ",".join(sorted(k for k in data if k.startswith("key_"))))
    try:
        await st.disconnect()
    except Exception:  # noqa
        pass
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
