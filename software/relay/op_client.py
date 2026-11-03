#!/usr/bin/env python3
"""Operator client for the car relay.
Usage:
  python3 op_client.py '#HELP'          # one-shot command
  python3 op_client.py                  # interactive stdin
  python3 op_client.py --host HOST --port 8731 ...
"""
import argparse, asyncio, os, struct, sys, threading
import websockets

DEFAULT_TOKEN_FILE = "/home/<USER>/.openclaw/workspace/jarvis/secrets/car-relay.env"


def load_token(path, key="OP_TOKEN"):
    try:
        for line in open(path):
            if line.startswith(key + "="):
                return line.split("=", 1)[1].strip()
    except OSError:
        pass
    return os.environ.get(key, "")


def data_frame(cid, payload):
    return bytes([2]) + struct.pack(">I", cid) + payload


async def run(args):
    token = args.token or load_token(args.token_file)
    url = f"wss://{args.host}:{args.port}/car-tunnel?role=op&token={token}"
    async with websockets.connect(url, max_size=8 * 1024 * 1024) as ws:
        first = await ws.recv()
        if isinstance(first, str):
            print(first, file=sys.stderr)
            return 2
        if not isinstance(first, (bytes, bytearray)) or len(first) < 5 or first[0] != 1:
            print("unexpected relay frame", file=sys.stderr)
            return 2
        cid = struct.unpack(">I", first[1:5])[0]

        async def reader():
            async for m in ws:
                if isinstance(m, (bytes, bytearray)) and len(m) >= 5:
                    if m[0] == 2:
                        sys.stdout.buffer.write(m[5:])
                        sys.stdout.buffer.flush()
                    elif m[0] == 3:
                        return

        async def writer():
            if args.command is not None:
                await ws.send(data_frame(cid, args.command.encode() + b"\n"))
                return
            loop = asyncio.get_running_loop()
            while True:
                line = await loop.run_in_executor(None, sys.stdin.buffer.readline)
                if not line:
                    break
                await ws.send(data_frame(cid, line))
            try:
                await ws.send(bytes([3]) + struct.pack(">I", cid))
            except Exception:
                pass

        rt = asyncio.create_task(reader())
        wt = asyncio.create_task(writer())
        try:
            await asyncio.wait_for(rt, timeout=args.timeout)
        except asyncio.TimeoutError:
            pass
        finally:
            wt.cancel()
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("command", nargs="?", default=None)
    ap.add_argument("--host", default="nas.<PRIVATE_DOMAIN>")
    ap.add_argument("--port", type=int, default=8731)
    ap.add_argument("--token", default="")
    ap.add_argument("--token-file", default=DEFAULT_TOKEN_FILE)
    ap.add_argument("--timeout", type=float, default=15.0)
    args = ap.parse_args()
    if args.command is None and sys.stdin.isatty():
        print("interactive: type commands, Ctrl-D to exit", file=sys.stderr)
    raise SystemExit(asyncio.run(run(args)))


if __name__ == "__main__":
    main()
