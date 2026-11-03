import serial, sys
try:
    p = serial.Serial("COM6", 115200, timeout=1)
    print("OPEN OK")
    p.close()
except Exception as e:
    print("OPEN FAIL:", type(e).__name__, e)
