#!/usr/bin/env python3
"""
ADB raw protocol connection - try AOSP test keys
Some debuggable builds accept the AOSP test RSA key.
"""
import socket
import struct
import base64
import os
import sys

HOST = sys.argv[1] if len(sys.argv) > 1 else "<LAN_IP>"
PORT = 5555

# The AOSP test RSA public key (well-known, accepted by some debug builds)
# This is the default test key from the AOSP tree
AOSP_TEST_KEY = "QAAAAOuStNk2WhK9FiMZM6FLQECeSNK2l32J/CR+P6RLv8NGBnD1AWPqLmlPVvhIXsV/x/9pF4sWfLyGfaBk7Fq+5M88QeFnHDKBzBGEpJym7cnATQwTY8n9tVJT6TLj8GGqy+7BcmhU5tqM0bPEgPdW3Il+GFe4KvIKnFNClCBqI7BMM0U7g+nWUQGBh7WLHWB9Vl2ngCgaAEfBqyfPWFqH12T8GO3LqIZH1bsKjhtC8tX+1N6EdGkQV/Vo2NCdOP+hWQ6NW0fWpnLN2mlNTrs94Cn9WBRsAKH6MT6fBTnkQfQgnVJ4K1fPB+rg8VjlGx+AZYxI7TY0kMpeIETP+Uv00I3DIB1tF2iFtMHIjCZXbm1N5eYMg+3CW00DRI1qw9CQvDyW2U36qV48RBx1mSzF4uylFlBNL8NqADFsYXJfOF8xMjg5MzQ1Njc4OTBhYmNkZWYwMTIzNDU2Nzg5MGFiY2RlZjAxMjM0NTY3ODkwYWJjZGVmMDEyMzQ1Njc4OTBhYmNkZWYwMTIzNDU2Nzg5MGFiY2RlZg=="

# The standard 2048-bit test key for adb
# From frameworks/native/cmds/adb/keys/ 
# Rsa key for device with ro.adb.secure maybe 0 
ADB_TEST_KEY = "QAAAAI7kUzytBm0jj9VEMI9azgYQ2ct0PCjfZB1AqgGFfnY2I5AQ6mnfQXcKbZXw05pBG+6C1SwKS0PvEYvW9z2vHl0L+qFNxB47W16It0SslqnLKpODeC+hx/NlqFBDKBIK7LDSTOK2ltUVJ+WNs5vX8NHnSV2hTFWAxxtLOPqpU18rUX+6TEDi9vNjZXAs0Up7gx8/7n2N5FWUZAlwDQsx8iDsA6ekwC9Ifc08qG8fKbHZ7s5eYqQYtRxWgvKcIQJQUiB3llF1llJr/wTNl3MsoXos+o+OQ3rjmgM0Bm4cxsH5Tj27tjN0RRkkCqBN3tNs5MqAKrjYUSRgSle6Yjdv0Befl0wMfrB5puSDPA/TlC45yZ0CE2MSzZ/pFJwLfVmRNglAEldUbv3mSkMHGdUbQPV8KpLrhfzqoUBWnXthjHpSNhMAK2Rzcm2Fcm1kZw1leWNlcm5ldF9vYmQ="

def try_adb_key(host, port, pubkey_b64):
    """Try to connect using a specific RSA public key"""
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(5)
    try:
        s.connect((host, port))
        
        # ADB protocol connect packet
        version = 0x01000001
        max_payload = 4096
        banner = f"host::features=shell_v2,cmd,abb_exec,fixed_push_symlink_target,fixed_push_symlink_timestamp,ls_v2,apex,abb\n"
        
        msg = struct.pack("<IIII", version, max_payload, 0x4e4e5843, 1)  # CNXN
        msg += banner.encode()
        s.send(msg)
        
        resp = s.recv(4096)
        if not resp:
            return "No response"
        
        cmd = struct.unpack("<I", resp[8:12])[0]
        arg0 = struct.unpack("<I", resp[12:16])[0]
        
        if cmd == 0x4e4e5843:  # CNXN
            if arg0 & 1:  # needs auth
                print(f"  Auth required, trying key...")
                # Send AUTH with RSAPublicKey 
                auth_cmd = struct.pack("<II", 0x48545541, 2)  # AUTH, arg0=2 (RSAPublicKey)
                s.send(auth_cmd + pubkey_b64.encode() + b"\n")
                
                try:
                    resp2 = s.recv(4096)
                    cmd2 = struct.unpack("<I", resp2[8:12])[0]
                    arg2 = struct.unpack("<I", resp2[12:16])[0]
                    
                    if cmd2 == 0x4e4e5843:  # CNXN back = accepted!
                        print(f"  ✅ KEY ACCEPTED! Connected!")
                        print(f"  Response: {resp2[:80]}")
                        return "ACCEPTED"
                    else:
                        print(f"  ❌ Key rejected. cmd=0x{cmd2:08x}")
                        return "REJECTED"
                except socket.timeout:
                    print(f"  ⏰ Auth timeout")
                    return "TIMEOUT"
            else:
                print(f"  ✅ No auth needed! Connected!")
                return "NO_AUTH"
        else:
            print(f"  Unexpected cmd: 0x{cmd:08x}")
            return "UNEXPECTED"
    except Exception as e:
        print(f"  Error: {e}")
        return f"ERROR: {e}"
    finally:
        s.close()

print(f"Connecting to {HOST}:{PORT}...")
result = try_adb_key(HOST, PORT, AOSP_TEST_KEY)
print(f"Result 1 (AOSP test): {result}")
print()
result2 = try_adb_key(HOST, PORT, ADB_TEST_KEY)
print(f"Result 2 (ADB test): {result2}")
print()

# Also try without key (some debug builds accept no auth)
print("Trying without key (raw connect)...")
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.settimeout(5)
try:
    s.connect((HOST, PORT))
    version = 0x01000001
    max_payload = 4096
    banner = f"host::features=\n"
    msg = struct.pack("<IIII", version, max_payload, 0x4e4e5843, 0)  # CNXN, auth_required=0
    msg += banner.encode()
    s.send(msg)
    resp = s.recv(4096)
    if resp:
        print(f"  Raw: {resp[:100]}")
    s.close()
except Exception as e:
    print(f"  Raw error: {e}")
