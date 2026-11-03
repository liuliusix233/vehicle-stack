#!/usr/bin/env python3
"""Pi 侧反向隧道客户端：连 wss://nas.<PRIVATE_DOMAIN>:8731/car-tunnel?role=pi，
每条入站连接转到本地固定端口（默认 8077），用于 Tailscale 失效时的远程访问。"""
import asyncio, os, struct

import websockets

WS = os.environ.get("PI_WS", "wss://nas.<PRIVATE_DOMAIN>:8731/car-tunnel")
TOKEN = os.environ.get("PI_TOKEN", "")
TARGET_HOST = os.environ.get("PI_TARGET_HOST", "127.0.0.1")
TARGET_PORT = int(os.environ.get("PI_TARGET_PORT", "8077"))


def frame(t, cid, payload=b""):
    return bytes([t]) + struct.pack(">I", cid) + payload


async def run_once():
    url = "%s?role=pi&token=%s" % (WS, TOKEN)
    async with websockets.connect(url, ping_interval=20, ping_timeout=20, max_size=8 * 1024 * 1024) as ws:
        print("PI_TUNNEL connected", flush=True)
        writers, tasks = {}, {}

        async def pipe(cid, reader, writer):
            try:
                while True:
                    data = await reader.read(65536)
                    if not data:
                        break
                    await ws.send(frame(2, cid, data))
            except Exception:
                pass
            finally:
                try:
                    await ws.send(frame(3, cid))
                except Exception:
                    pass
                try:
                    writer.close()
                except Exception:
                    pass
                writers.pop(cid, None)
                tasks.pop(cid, None)

        try:
            async for msg in ws:
                if not isinstance(msg, (bytes, bytearray)) or len(msg) < 5:
                    continue
                t = msg[0]
                cid = struct.unpack(">I", msg[1:5])[0]
                if t == 1:
                    try:
                        reader, writer = await asyncio.open_connection(TARGET_HOST, TARGET_PORT)
                    except Exception:
                        await ws.send(frame(3, cid))
                        continue
                    writers[cid] = writer
                    tasks[cid] = asyncio.create_task(pipe(cid, reader, writer))
                elif t == 2:
                    w = writers.get(cid)
                    if w:
                        try:
                            w.write(msg[5:])
                            await w.drain()
                        except Exception:
                            pass
                elif t == 3:
                    task = tasks.pop(cid, None)
                    if task:
                        task.cancel()
                    w = writers.pop(cid, None)
                    if w:
                        try:
                            w.close()
                        except Exception:
                            pass
        finally:
            for task in tasks.values():
                task.cancel()


async def main():
    while True:
        try:
            await run_once()
        except Exception as e:
            print("tunnel error:", repr(e), flush=True)
        await asyncio.sleep(10)


if __name__ == "__main__":
    asyncio.run(main())
