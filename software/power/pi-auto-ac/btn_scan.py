#!/usr/bin/env python3
"""Listen for the Xiaomi Smart Button's BLE advertisements (run on the car Pi).

usage: btn_scan.py [seconds]      # appends sightings to btn_scan.log
"""
import asyncio
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bleak import BleakScanner  # noqa: E402

TARGET = "<MAC>"
DUR = int(sys.argv[1]) if len(sys.argv) > 1 else 600
LOG = os.path.join(os.path.dirname(os.path.abspath(__file__)), "btn_scan.log")


def logline(s):
    with open(LOG, "a") as fh:
        fh.write(s + "\n")
    print(s, flush=True)


def cb(device, adv):
    if device.address.upper() != TARGET:
        return
    mfr = {k: bytes(v).hex() for k, v in (adv.manufacturer_data or {}).items()}
    svc = {str(k): bytes(v).hex() for k, v in (adv.service_data or {}).items()}
    logline(f"{time.strftime('%F %T')} rssi={adv.rssi} name={adv.local_name!r} "
            f"mfr={mfr} svc={svc}")


async def main():
    logline(f"--- scan start {time.strftime('%F %T')} dur={DUR}s target={TARGET}")
    devs = await BleakScanner.discover(timeout=DUR, return_adv=True, detection_callback=cb)
    seen = [d.address for d in devs.values()]
    logline(f"--- scan done, {len(seen)} devices seen, target_seen={TARGET in [a.upper() for a in seen]}")


asyncio.run(main())
