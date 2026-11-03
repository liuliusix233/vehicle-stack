#!/usr/bin/env python3
"""Car relay: car app opens an outbound WSS tunnel; operators connect locally
(127.0.0.1:8899) or via WSS role=op. Frames: [type:1][connId:4][payload].
type 1=OPEN 2=DATA 3=CLOSE. Big-endian connId.
"""
import asyncio, os, struct, logging, signal

from aiohttp import web, WSMsgType

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("car-relay")

CAR_TOKEN = os.environ.get("CAR_TOKEN", "")
PI_TOKEN = os.environ.get("PI_TOKEN", "")
PI_PORT = int(os.environ.get("PI_PORT", "8898"))
OP_TOKEN = os.environ.get("OP_TOKEN", "")
BIND = os.environ.get("BIND", "127.0.0.1")
PORT = int(os.environ.get("PORT", "19001"))
OP_PORT = int(os.environ.get("OP_PORT", "8899"))

car_ws = None
conns = {}
next_cid = 1
pi_ws = None
pi_conns = {}


def frame(t: int, cid: int, payload: bytes = b"") -> bytes:
    return bytes([t]) + struct.pack(">I", cid) + payload


class Conn:
    def __init__(self, cid, write, close):
        self.cid = cid
        self.write = write
        self.close = close
        self.done = asyncio.Event()


async def close_all(reason="car disconnected"):
    global conns
    old = list(conns.values())
    conns = {}
    for c in old:
        try:
            await c.close()
        except Exception:
            pass
    if old:
        log.info("closed %d operator conns (%s)", len(old), reason)


async def ws_handler(request: web.Request):
    global car_ws
    role = request.query.get("role", "")
    token = request.query.get("token", "")
    if role == "car":
        if not CAR_TOKEN or token != CAR_TOKEN:
            return web.Response(status=401, text="bad token")
    elif role == "op":
        if not OP_TOKEN or token != OP_TOKEN:
            return web.Response(status=401, text="bad token")
    elif role == "pi":
        if not PI_TOKEN or token != PI_TOKEN:
            return web.Response(status=401, text="bad token")
    else:
        return web.Response(status=403, text="role required")

    ws = web.WebSocketResponse(heartbeat=25, max_msg_size=8 * 1024 * 1024)
    await ws.prepare(request)

    if role == "pi":
        global pi_ws
        old = pi_ws
        pi_ws = ws
        if old is not None and old is not ws and not old.closed:
            try:
                await old.close()
            except Exception:
                pass
        log.info("pi connected from %s", request.remote)
        try:
            async for msg in ws:
                if msg.type != WSMsgType.BINARY:
                    continue
                data = msg.data
                if len(data) < 5:
                    continue
                t = data[0]
                cid = struct.unpack(">I", data[1:5])[0]
                c = pi_conns.get(cid)
                if c is None:
                    continue
                if t == 2:
                    try:
                        await c.write(data[5:])
                    except Exception:
                        pi_conns.pop(cid, None)
                        try:
                            await c.close()
                        except Exception:
                            pass
                elif t == 3:
                    pi_conns.pop(cid, None)
                    c.done.set()
                    try:
                        await c.close()
                    except Exception:
                        pass
        finally:
            if pi_ws is ws:
                pi_ws = None
                for c in list(pi_conns.values()):
                    try:
                        await c.close()
                    except Exception:
                        pass
                pi_conns.clear()
            log.info("pi disconnected")
        return ws

    if role == "car":
        old = car_ws
        car_ws = ws
        if old is not None and old is not ws and not old.closed:
            try:
                await old.close()
            except Exception:
                pass
        log.info("car connected from %s", request.remote)
        try:
            async for msg in ws:
                if msg.type != WSMsgType.BINARY:
                    continue
                data = msg.data
                if len(data) < 5:
                    continue
                t = data[0]
                cid = struct.unpack(">I", data[1:5])[0]
                c = conns.get(cid)
                if c is None:
                    continue
                if t == 2:
                    try:
                        await c.write(data[5:])
                    except Exception:
                        conns.pop(cid, None)
                        try:
                            await c.close()
                        except Exception:
                            pass
                elif t == 3:
                    conns.pop(cid, None)
                    c.done.set()
                    try:
                        await c.close()
                    except Exception:
                        pass
        finally:
            if car_ws is ws:
                car_ws = None
                await close_all()
            log.info("car disconnected")
        return ws

    # operator over WebSocket
    global next_cid
    if car_ws is None or car_ws.closed:
        await ws.send_str("NO CAR ONLINE")
        await ws.close()
        return ws
    cid = next_cid
    next_cid += 1
    closed = False

    async def write(data: bytes):
        if ws.closed:
            return
        await ws.send_bytes(frame(2, cid, data))

    async def close():
        nonlocal closed
        if closed:
            return
        closed = True
        try:
            await ws.close()
        except Exception:
            pass

    conns[cid] = Conn(cid, write, close)
    await ws.send_bytes(frame(1, cid))
    await car_ws.send_bytes(frame(1, cid))
    log.info("op ws cid=%d from %s", cid, request.remote)
    try:
        async for msg in ws:
            if msg.type != WSMsgType.BINARY:
                continue
            data = msg.data
            if len(data) < 5 or data[0] != 2:
                continue
            if car_ws is not None and not car_ws.closed:
                await car_ws.send_bytes(frame(2, cid, data[5:]))
    finally:
        conns.pop(cid, None)
        if car_ws is not None and not car_ws.closed:
            try:
                await car_ws.send_bytes(frame(3, cid))
            except Exception:
                pass
    return ws


async def handle_op(reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
    global next_cid
    if car_ws is None or car_ws.closed:
        try:
            writer.write(b"NO CAR ONLINE\n")
            await writer.drain()
        except Exception:
            pass
        writer.close()
        return
    cid = next_cid
    next_cid += 1
    peer = writer.get_extra_info("peername")

    async def write(data: bytes):
        writer.write(data)
        await writer.drain()

    async def close():
        try:
            writer.close()
        except Exception:
            pass

    conns[cid] = Conn(cid, write, close)
    try:
        await car_ws.send_bytes(frame(1, cid))
    except Exception:
        writer.close()
        return
    log.info("op tcp cid=%d from %s", cid, peer)
    try:
        while True:
            data = await reader.read(65536)
            if not data:
                break
            if car_ws is None or car_ws.closed:
                break
            await car_ws.send_bytes(frame(2, cid, data))
    except Exception:
        pass
    finally:
        # 操作端 EOF 只代表“命令已发完”。必须等车机处理完并回 CLOSE，
        # 否则连续分块会并发/乱序写入，文件被截断。
        try:
            await asyncio.wait_for(c.done.wait(), timeout=20)
        except Exception:
            pass
        conns.pop(cid, None)
        try:
            writer.close()
        except Exception:
            pass
        log.info("op tcp cid=%d done", cid)


async def handle_pi_op(reader, writer):
    """本地 TCP -> Pi 隧道（Pi 侧默认转到 :8077）。"""
    global next_cid
    if pi_ws is None or pi_ws.closed:
        writer.close()
        return
    cid = next_cid
    next_cid += 1
    peer = writer.get_extra_info("peername")

    async def write(data):
        writer.write(data)
        await writer.drain()

    async def close():
        writer.close()

    pi_conns[cid] = Conn(cid, write, close)
    try:
        await pi_ws.send_bytes(frame(1, cid))
    except Exception:
        writer.close()
        return
    log.info("pi-op tcp cid=%d from %s", cid, peer)
    try:
        while True:
            data = await reader.read(65536)
            if not data:
                break
            if pi_ws is None or pi_ws.closed:
                break
            await pi_ws.send_bytes(frame(2, cid, data))
    except Exception:
        pass
    finally:
        try:
            await asyncio.wait_for(pi_conns[cid].done.wait(), timeout=10)
        except Exception:
            pass
        pi_conns.pop(cid, None)
        try:
            writer.close()
        except Exception:
            pass
        log.info("pi-op tcp cid=%d done", cid)


async def health(request):
    return web.json_response({
        "ok": True,
        "car_online": car_ws is not None and not car_ws.closed,
        "pi_online": pi_ws is not None and not pi_ws.closed,
        "operator_conns": len(conns),
    })


async def main():
    app = web.Application()
    app.router.add_get("/car-tunnel", ws_handler)
    app.router.add_get("/car-health", health)
    runner = web.AppRunner(app, access_log=None)
    await runner.setup()
    site = web.TCPSite(runner, BIND, PORT)
    await site.start()
    server = await asyncio.start_server(handle_op, "127.0.0.1", OP_PORT)
    await asyncio.start_server(handle_pi_op, "127.0.0.1", PI_PORT)
    log.info("relay %s:%d ws, operator 127.0.0.1:%d, pi-op 127.0.0.1:%d", BIND, PORT, OP_PORT, PI_PORT)
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, stop.set)
        except NotImplementedError:
            pass
    await stop.wait()
    server.close()


if __name__ == "__main__":
    asyncio.run(main())
