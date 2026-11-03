#!/usr/bin/env python3
"""Car button (Xiaomi Smart Button, BLE) -> toggle the DJI Power AC.

The button's press advertisements are AES-encrypted, so instead of decrypting we
detect the press by the plaintext frame counter: idle keeps the same counter for
minutes, while a press bumps it and produces a burst of identical advertisements.

Runs on the car Pi next to the DJI bridge; toggles through the bridge HTTP API so
it is NOT subject to the car-running policy lockouts.
"""
import asyncio
import json
import os
import sys
import time
import urllib.request
from collections import deque

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bleak import BleakScanner  # noqa: E402

TARGET = "<MAC>"          # Xiaomi Smart Button (car)
SERVICE = "0000fe95-0000-1000-8000-00805f9b34fb"
BRIDGE = "http://127.0.0.1:8095"
MIN_BURST = 3          # sightings of the new counter that make a press
BURST_WINDOW = 4.0     # seconds to collect them
ACTION_DEBOUNCE = 5.0  # ignore anything this long after acting

seen = deque(maxlen=200)     # (ts, counter)
last_counter = None
acted_counter = None
last_action = 0.0


def log(s):
    print(time.strftime("[%F %T]"), s, flush=True)


def api(path):
    with urllib.request.urlopen(BRIDGE + path, timeout=10) as r:
        return json.loads(r.read().decode())


def cb(device, adv):
    global last_counter
    if device.address.upper() != TARGET:
        return
    data = (adv.service_data or {}).get(SERVICE)
    if not data:
        for k, v in (adv.service_data or {}).items():
            if "fe95" in str(k).lower():
                data = v
                break
    if not data or len(data) < 8:
        return
    b = bytes(data)
    if b[0] != 0x58 or b[1] != 0x59:
        return
    ctr = b[4]
    if ctr != last_counter:
        log(f"counter {last_counter} -> {ctr} (rssi={adv.rssi})")
        last_counter = ctr
    seen.append((time.monotonic(), ctr))


async def ticker():
    global acted_counter, last_action
    while True:
        await asyncio.sleep(0.5)
        now = time.monotonic()
        if now - last_action < ACTION_DEBOUNCE:
            continue
        # group recent sightings
        recent = [c for t, c in seen if now - t <= BURST_WINDOW]
        if not recent:
            continue
        ctr = recent[-1]
        if ctr == acted_counter:
            continue
        if recent.count(ctr) >= MIN_BURST:
            acted_counter = ctr
            last_action = now
            try:
                st = api("/state")
                cur = (st.get("ports") or {}).get("2-1")
                want = not bool(cur)
                res = api(f"/set?key=***&on={1 if want else 0}")
                log(f"PRESS -> AC {'ON' if want else 'OFF'} {res}")
            except Exception as e:  # noqa
                log("toggle failed:", type(e).__name__, e)


async def main():
    log(f"button watch start (target {TARGET})")
    await asyncio.gather(
        BleakScanner.discover(timeout=0, return_adv=True, detection_callback=cb),
        ticker(),
    )


if __name__ == "__main__":
    asyncio.run(main())
