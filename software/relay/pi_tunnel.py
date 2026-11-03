
import base64, hashlib, os, socket, ssl, struct, sys, threading, time

HOST = os.environ.get("PI_WS_HOST", "nas.<PRIVATE_DOMAIN>")
PORT = int(os.environ.get("PI_WS_PORT", "8731"))
PATH = os.environ.get("PI_WS_PATH", "/car-tunnel")
TARGET_HOST = os.environ.get("PI_TARGET_HOST", "127.0.0.1")
TARGET_PORT = int(os.environ.get("PI_TARGET_PORT", "8077"))
TOKEN_FILE = os.environ.get("PI_TOKEN_FILE", "/etc/pi-tunnel.token")


def read_token():
    try:
        with open(TOKEN_FILE) as f:
            return f.read().strip()
    except Exception:
        return ""


def frame(t, cid, payload=b""):
    return bytes([t]) + struct.pack(">I", cid) + payload


def ws_send(sock, data, opcode=0x2):
    mask = os.urandom(4)
    n = len(data)
    hdr = bytes([0x80 | opcode])
    if n < 126:
        hdr += bytes([0x80 | n])
    elif n < 65536:
        hdr += bytes([0x80 | 126]) + struct.pack(">H", n)
    else:
        hdr += bytes([0x80 | 127]) + struct.pack(">Q", n)
    hdr += mask
    masked = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
    sock.sendall(hdr + masked)


def _recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("closed")
        buf += chunk
    return buf


def ws_recv(sock):
    b1, b2 = _recv_exact(sock, 2)
    opcode = b1 & 0x0F
    masked = b2 & 0x80
    n = b2 & 0x7F
    if n == 126:
        n = struct.unpack(">H", _recv_exact(sock, 2))[0]
    elif n == 127:
        n = struct.unpack(">Q", _recv_exact(sock, 8))[0]
    mask = _recv_exact(sock, 4) if masked else None
    data = _recv_exact(sock, n) if n else b""
    if mask:
        data = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
    return opcode, data


def ws_connect():
    tok = read_token()
    raw = socket.create_connection((HOST, PORT), timeout=20)
    ctx = ssl.create_default_context()
    sock = ctx.wrap_socket(raw, server_hostname=HOST)
    key = base64.b64encode(os.urandom(16)).decode()
    q = "role=pi&" + "token=" + tok
    req = ("GET " + PATH + "?" + q + " HTTP/1.1\r\n"
           "Host: " + HOST + ":" + str(PORT) + "\r\n"
           "Upgrade: websocket\r\n"
           "Connection: Upgrade\r\n"
           "Sec-WebSocket-Key: " + key + "\r\n"
           "Sec-WebSocket-Version: 13\r\n\r\n")
    sock.sendall(req.encode())
    resp = b""
    while b"\r\n\r\n" not in resp:
        chunk = sock.recv(4096)
        if not chunk:
            raise ConnectionError("handshake closed")
        resp += chunk
    if b"101" not in resp.split(b"\r\n")[0]:
        raise ConnectionError(resp.split(b"\r\n")[0].decode("latin1"))
    return sock


writers = {}


def handle_open(cid, sock):
    try:
        conn = socket.create_connection((TARGET_HOST, TARGET_PORT), timeout=10)
        print("OPEN cid=%d dial %s:%d ok" % (cid, TARGET_HOST, TARGET_PORT), flush=True)
    except Exception as e:
        print("OPEN cid=%d dial FAIL %r" % (cid, e), flush=True)
        ws_send(sock, frame(3, cid))
        return

    def pump():
        try:
            while True:
                data = conn.recv(65536)
                if not data:
                    break
                print("RECV cid=%d %d bytes" % (cid, len(data)), flush=True)
                ws_send(sock, frame(2, cid, data))
        except Exception:
            pass
        finally:
            try:
                ws_send(sock, frame(3, cid))
            except Exception:
                pass
            try:
                conn.close()
            except Exception:
                pass

    threading.Thread(target=pump, daemon=True).start()
    writers[cid] = conn


def run():
    sock = ws_connect()
    print("PI_TUNNEL connected", flush=True)
    while True:
        op, data = ws_recv(sock)
        if op == 0x8:
            break
        if op == 0x9:
            ws_send(sock, data, opcode=0xA)
            continue
        if op != 0x2 or len(data) < 5:
            continue
        t, cid = data[0], struct.unpack(">I", data[1:5])[0]
        print("FRAME t=%d cid=%d len=%d" % (t, cid, len(data)), flush=True)
        if t == 1:
            handle_open(cid, sock)
        elif t == 2:
            w = writers.get(cid)
            if w:
                try:
                    w.sendall(data[5:])
                except Exception:
                    pass
        elif t == 3:
            w = writers.pop(cid, None)
            if w:
                try:
                    w.close()
                except Exception:
                    pass


if __name__ == "__main__":
    while True:
        try:
            run()
        except Exception as e:
            print("tunnel error:", repr(e), flush=True)
        time.sleep(10)
