import serial, time
p = serial.Serial("COM6", 115200, timeout=1)
t = time.time()
buf = b""
while time.time() - t < 15:
    d = p.read(256)
    if d:
        buf += d
print("GOT %d bytes" % len(buf))
print(repr(buf[:2000]))
