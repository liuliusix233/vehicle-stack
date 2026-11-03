# Vehicle Stack — field notes from an SA8155P head unit

This is **not** a one-click replication template. It is a record of what actually worked (and what did not)
when turning a stock 2025 Cadillac XT5 head unit into a programmable terminal.

**For AI agents**: read `AGENTS.md` first. Use the conclusions and interfaces; do not treat the commands
as an install script — we never validated a clean-room build.

## What is inside

| Capability | Module |
| --- | --- |
| Replace adb: shell, file read, APK install over TCP | `software/shellbridge/` |
| Receive casts on the car screen | `software/castsink/` |
| Vehicle data via VHAL (22 properties) | `software/headunit-vhal/` |
| 240x960 HUD, direct framebuffer | `software/hud/` |
| Portable power monitor (BLE only) | `software/power/` |
| Auto AC by rail voltage (13.2V on / 13.0V off) | `software/power/pi-auto-ac/` |
| Pi relay / tunnel / OBD bridge | `software/relay/` |
| Parking monitor | COTS DVR (no source) |

## Hard findings

- The head unit **cannot enable ADB**; APKs are installed from a USB stick.
- VHAL exposes 22 properties (speed, gear, fuel, range). Gear is a bitmask.
- Engine RPM is **not** in VHAL; 12s subscription on all properties yielded 0 events. Use an OBD dongle.
- Odometer requires signature|privileged permission — not accessible.
- The factory cluster UI cannot be modified by third parties (platform signature required).
- HUD writes to `/dev/fb0` at 240x960/16bpp, <50ms latency.

## Links

- Full retrospective (Chinese): `docs/HOW-WE-BUILT-IT.md`
- Agent guide: `AGENTS.md`
- Gallery with captions: `docs/GALLERY.md`

## License

MIT. All credentials stripped; every IP/SN/token is a `<PLACEHOLDER>`.