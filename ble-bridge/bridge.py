"""
BLE Bridge — WebSocket server on localhost:7878
Java app connects here and sends JSON commands; this script handles all BLE
via bleak (cross-platform: Windows WinRT, macOS CoreBluetooth, Linux BlueZ).

Protocol
--------
Client → Bridge  (commands):
  {"cmd": "scan_start", "timeout": 10}
  {"cmd": "scan_stop"}
  {"cmd": "connect",        "address": "AA:BB:CC:DD:EE:FF"}
  {"cmd": "disconnect"}
  {"cmd": "services"}
  {"cmd": "read",           "service": "uuid", "char": "uuid"}
  {"cmd": "write",          "service": "uuid", "char": "uuid", "hex": "FF0A", "response": true}
  {"cmd": "notify_start",   "service": "uuid", "char": "uuid"}
  {"cmd": "notify_stop",    "service": "uuid", "char": "uuid"}
  {"cmd": "ping"}

Bridge → Client  (events):
  {"event": "ready",        "version": "1.0"}
  {"event": "device",       "address": "...", "name": "...", "rssi": -60, "services": [...]}
  {"event": "scan_done",    "count": 3}
  {"event": "connected",    "address": "..."}
  {"event": "disconnected"}
  {"event": "services",     "services": [...]}
  {"event": "read_result",  "char": "uuid", "hex": "FF0A", "text": "..."}
  {"event": "notify",       "char": "uuid", "hex": "...",  "text": "..."}
  {"event": "error",        "message": "..."}
  {"event": "pong"}
"""

import asyncio
import json
import sys
import websockets
from bleak import BleakScanner, BleakClient
from bleak.backends.characteristic import BleakGATTCharacteristic

PORT = 7878

# ── Helpers ───────────────────────────────────────────────────────────────────

def dv_to_hex(data: bytes) -> str:
    return data.hex().upper()

def try_decode(data: bytes) -> str:
    try:
        text = data.decode("utf-8")
        if all(32 <= ord(c) < 127 for c in text):
            return text
    except Exception:
        pass
    return ""

def send(ws, obj):
    """Fire-and-forget send (schedules on the current event loop)."""
    asyncio.ensure_future(ws.send(json.dumps(obj)))

# ── Session state (one client at a time) ──────────────────────────────────────

class Session:
    def __init__(self, ws):
        self.ws        = ws
        self.client    = None   # BleakClient
        self.scanning  = False
        self.scan_task = None

    async def emit(self, obj):
        try:
            await self.ws.send(json.dumps(obj))
        except Exception:
            pass

    # ── Commands ──────────────────────────────────────────────────────────────

    async def handle(self, raw: str):
        try:
            msg = json.loads(raw)
        except json.JSONDecodeError:
            await self.emit({"event": "error", "message": "Invalid JSON"})
            return

        cmd = msg.get("cmd", "")
        try:
            if   cmd == "ping":          await self.emit({"event": "pong"})
            elif cmd == "scan_start":    await self.scan_start(msg.get("timeout", 10))
            elif cmd == "scan_stop":     await self.scan_stop()
            elif cmd == "connect":       await self.connect(msg["address"])
            elif cmd == "disconnect":    await self.disconnect()
            elif cmd == "services":      await self.list_services()
            elif cmd == "read":          await self.read(msg["service"], msg["char"])
            elif cmd == "write":         await self.write(msg["service"], msg["char"],
                                                          msg["hex"], msg.get("response", True))
            elif cmd == "notify_start":  await self.notify_start(msg["service"], msg["char"])
            elif cmd == "notify_stop":   await self.notify_stop(msg["service"], msg["char"])
            else:
                await self.emit({"event": "error", "message": f"Unknown command: {cmd}"})
        except KeyError as e:
            await self.emit({"event": "error", "message": f"Missing field: {e}"})
        except Exception as e:
            await self.emit({"event": "error", "message": str(e)})

    # ── Scan ──────────────────────────────────────────────────────────────────

    async def scan_start(self, timeout: float):
        if self.scanning:
            return
        self.scanning = True
        found = {}

        def on_device(device, adv_data):
            if device.address in found:
                return
            found[device.address] = True
            services = list(adv_data.service_uuids) if adv_data.service_uuids else []
            asyncio.ensure_future(self.emit({
                "event":    "device",
                "address":  device.address,
                "name":     device.name or "",
                "rssi":     adv_data.rssi,
                "services": services,
            }))

        scanner = BleakScanner(on_device)
        await scanner.start()
        await asyncio.sleep(timeout)
        await scanner.stop()
        self.scanning = False
        await self.emit({"event": "scan_done", "count": len(found)})

    async def scan_stop(self):
        self.scanning = False  # will cause scan_start to stop at next check

    # ── Connect / disconnect ──────────────────────────────────────────────────

    async def connect(self, address: str):
        if self.client and self.client.is_connected:
            await self.client.disconnect()

        self.client = BleakClient(address, disconnected_callback=self._on_disconnected)
        await self.client.connect()
        await self.emit({"event": "connected", "address": address})

    def _on_disconnected(self, client: BleakClient):
        asyncio.ensure_future(self.emit({"event": "disconnected"}))

    async def disconnect(self):
        if self.client:
            await self.client.disconnect()
            self.client = None

    # ── Services ──────────────────────────────────────────────────────────────

    async def list_services(self):
        self._require_connected()
        services = []
        for svc in self.client.services:
            chars = []
            for c in svc.characteristics:
                chars.append({
                    "uuid":  c.uuid,
                    "props": c.properties,
                    "desc":  c.description,
                })
            services.append({"uuid": svc.uuid, "desc": svc.description, "chars": chars})
        await self.emit({"event": "services", "services": services})

    # ── Read ──────────────────────────────────────────────────────────────────

    async def read(self, service_uuid: str, char_uuid: str):
        self._require_connected()
        data = await self.client.read_gatt_char(char_uuid)
        await self.emit({
            "event": "read_result",
            "char":  char_uuid,
            "hex":   dv_to_hex(data),
            "text":  try_decode(data),
        })

    # ── Write ─────────────────────────────────────────────────────────────────

    async def write(self, service_uuid: str, char_uuid: str, hex_str: str, response: bool):
        self._require_connected()
        data = bytes.fromhex(hex_str.replace(" ", ""))
        if response:
            await self.client.write_gatt_char(char_uuid, data, response=True)
        else:
            await self.client.write_gatt_char(char_uuid, data, response=False)
        await self.emit({"event": "write_ok", "char": char_uuid})

    # ── Notify ────────────────────────────────────────────────────────────────

    async def notify_start(self, service_uuid: str, char_uuid: str):
        self._require_connected()

        def callback(char: BleakGATTCharacteristic, data: bytearray):
            asyncio.ensure_future(self.emit({
                "event": "notify",
                "char":  char.uuid,
                "hex":   dv_to_hex(bytes(data)),
                "text":  try_decode(bytes(data)),
            }))

        await self.client.start_notify(char_uuid, callback)
        await self.emit({"event": "notify_started", "char": char_uuid})

    async def notify_stop(self, service_uuid: str, char_uuid: str):
        self._require_connected()
        await self.client.stop_notify(char_uuid)
        await self.emit({"event": "notify_stopped", "char": char_uuid})

    # ── Util ──────────────────────────────────────────────────────────────────

    def _require_connected(self):
        if not self.client or not self.client.is_connected:
            raise RuntimeError("Not connected to a BLE device")


# ── WebSocket handler ─────────────────────────────────────────────────────────

async def handler(ws):
    session = Session(ws)
    await session.emit({"event": "ready", "version": "1.0"})
    print(f"[bridge] Client connected", flush=True)
    try:
        async for message in ws:
            await session.handle(message)
    except websockets.exceptions.ConnectionClosedOK:
        pass
    except Exception as e:
        print(f"[bridge] Error: {e}", flush=True)
    finally:
        if session.client and session.client.is_connected:
            await session.client.disconnect()
        print("[bridge] Client disconnected", flush=True)


async def main():
    print(f"[bridge] BLE bridge starting on ws://127.0.0.1:{PORT}", flush=True)
    async with websockets.serve(handler, "127.0.0.1", PORT):
        print(f"[bridge] Ready", flush=True)
        await asyncio.Future()  # run forever


if __name__ == "__main__":
    asyncio.run(main())
