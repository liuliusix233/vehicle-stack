#!/usr/bin/env python3
"""
Lightweight HA proxy for HUD PiDataProvider.
Runs on Pi at <LAN_IP>:8080, serves /obd endpoint.
"""
import json, os, http.server, urllib.request

HA_URL = "https://nas.<PRIVATE_DOMAIN>:8443"
HA_TOKEN = os.environ.get("HA_TOKEN", "")

def ha_state(entity):
    try:
        req = urllib.request.Request(
            f"{HA_URL}/api/states/{entity}",
            headers={"Authorization": f"Bearer {HA_TOKEN}"}
        )
        with urllib.request.urlopen(req, timeout=3) as r:
            data = json.loads(r.read())
        s = data.get("state", "unavailable")
        if s in ("unknown", "unavailable", ""): return None
        return s
    except: return None

class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/obd":
            self.send_response(404)
            self.end_headers()
            return

        fill = {"connected": 1}

        # Odometer from OnStar
        odo = ha_state("sensor.onstar_odometer")
        if odo: fill["odo"] = float(odo)

        # Fuel from OnStar
        fuel = ha_state("sensor.onstar_fuel_level")
        if fuel: fill["fuelLevel"] = int(float(fuel))

        # DJI power
        power = ha_state("sensor.cadillac_discharge_power")
        if power: fill["power"] = int(float(power))

        body = json.dumps(fill).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", len(body))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        pass  # quiet

if __name__ == "__main__":
    srv = http.server.HTTPServer(("0.0.0.0", 8080), Handler)
    srv.serve_forever()
