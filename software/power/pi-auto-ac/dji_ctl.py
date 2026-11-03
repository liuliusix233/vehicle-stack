#!/usr/bin/env python3
"""BLE control CLI for the DJI Power station (runs on the Pi).

  dji_ctl.py state
  dji_ctl.py ac on|off
  dji_ctl.py port <type> <seq> on|off
  dji_ctl.py noports            # write each row back unchanged (link proof, no state change)
"""
import asyncio
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from bleak import BleakScanner  # noqa: E402
from dji_power_ble.device import DjiPowerDevice  # noqa: E402

NAMES = {2: "AC", 3: "USB-A", 4: "USB-C", 5: "SDC", 6: "SDC-Lite", 7: "CAR"}


async def find(addr, name):
    devs = await BleakScanner.discover(timeout=15, return_adv=True)
    for d, adv in devs.values():
        if d.address.upper() == addr.upper() or (name and (adv.local_name or "") == name):
            return d
    return None


def show_rows(tag, data):
    rows = data.get("power_switches") or []
    txt = ", ".join(f"{NAMES.get(r['type'], r['type'])}{r['seq']}={'ON' if r['sw'] == 1 else 'off'}" for r in rows)
    print(f"{tag}: {txt}")


async def main():
    args = sys.argv[1:]
    mode = args[0] if args else "state"
    spec = json.load(open(os.path.join(HERE, "pair.json")))
    dev = await find(spec["ble_address"], spec.get("ble_name") or "")
    if dev is None:
        print("RESULT=NOT_FOUND")
        return 2
    st = DjiPowerDevice(dev, spec["pair_key"], name=spec.get("ble_name") or "DJI Power",
                        model="DJI Power 2000", serial_number=spec["sn"])
    await st.connect()
    print("RESULT=CONNECTED")
    show_rows("before", st.data)
    rc = 0
    if mode == "ac":
        want = args[1] == "on"
        await st.set_ac(want)
        print(f"RESULT=SET_AC_{'ON' if want else 'OFF'}_OK")
    elif mode == "port":
        t, s_, v = int(args[1]), int(args[2]), args[3] == "on"
        await st.set_port_switch(t, s_, v)
        print(f"RESULT=SET_PORT_{t}_{s_}_{'ON' if v else 'OFF'}_OK")
    elif mode == "noports":
        for r in list(st.data.get("power_switches") or []):
            if (r["type"], r["seq"]) == (4, 3):
                print("skip type4/seq3 (loaded: Pi power)")
                continue
            await st.set_port_switch(r["type"], r["seq"], r["sw"] == 1)
            print(f"RESULT=NOWRITE_OK {NAMES.get(r['type'])}{r['seq']}")
    elif mode == "sweep":
        for r in list(st.data.get("power_switches") or []):
            t, sq = r["type"], r["seq"]
            if (t, sq) == (4, 3):
                print("SKIP type4/seq3 (loaded: Pi power)")
                continue
            tag = f"{NAMES.get(t, t)}{sq}"
            for v in (False, True):
                try:
                    await st.set_port_switch(t, sq, v)
                    print(f"RESULT=SWEEP_{tag}_{'ON' if v else 'OFF'}_OK")
                except Exception as e:  # noqa
                    print(f"RESULT=SWEEP_{tag}_{'ON' if v else 'OFF'}_FAIL {type(e).__name__}: {e}")
                await asyncio.sleep(1.5)
    await asyncio.sleep(2)
    show_rows("after", st.data)
    try:
        await st.disconnect()
    except Exception:  # noqa
        pass
    print("RESULT=DONE")
    return rc


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
