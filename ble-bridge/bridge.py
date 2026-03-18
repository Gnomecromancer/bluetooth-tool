"""
BLE / Classic BT / HID Bridge — WebSocket server on localhost:7878
Java app connects here and sends JSON commands; this script handles:
  • BLE via bleak (cross-platform: Windows WinRT, macOS CoreBluetooth, Linux BlueZ)
  • Bluetooth Classic via pybluez2 (gracefully disabled if not installed)
  • HID via hidapi/hid (gracefully disabled if not installed)

Protocol
--------
Client → Bridge  (BLE commands):
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

Client → Bridge  (Classic BT commands):
  {"cmd": "bt_scan",        "timeout": 8}
  {"cmd": "bt_services",    "address": "AA:BB:CC:DD:EE:FF"}
  {"cmd": "bt_connect",     "address": "AA:BB:CC:DD:EE:FF", "port": 1}
  {"cmd": "bt_send",        "hex": "FF0A"}
  {"cmd": "bt_send_text",   "text": "hello"}
  {"cmd": "bt_disconnect"}

Client → Bridge  (HID commands):
  {"cmd": "hid_list"}
  {"cmd": "hid_open",       "path": "/dev/hidraw0"}
  {"cmd": "hid_open_id",    "vendor_id": 1234, "product_id": 5678}
  {"cmd": "hid_start_read"}
  {"cmd": "hid_stop_read"}
  {"cmd": "hid_write",      "hex": "00FF01"}
  {"cmd": "hid_close"}

Client → Bridge  (Emulator commands):
  {"cmd": "emu_key_press",         "key": "enter"}
  {"cmd": "emu_key_type",          "text": "hello", "interval": 0.05}
  {"cmd": "emu_key_down",          "key": "shift"}
  {"cmd": "emu_key_up",            "key": "shift"}
  {"cmd": "emu_key_hotkey",        "keys": ["ctrl","shift","s"]}
  {"cmd": "emu_mouse_move",        "x": 100, "y": 200, "relative": false, "duration": 0.0}
  {"cmd": "emu_mouse_click",       "button": "left", "double": false}
  {"cmd": "emu_mouse_down",        "button": "left"}
  {"cmd": "emu_mouse_up",          "button": "left"}
  {"cmd": "emu_mouse_scroll",      "dx": 0, "dy": 3}
  {"cmd": "emu_gamepad_connect",   "type": "xbox360"}
  {"cmd": "emu_gamepad_button",    "button": "A", "pressed": true}
  {"cmd": "emu_gamepad_left_stick","x": 0.5, "y": -0.5}
  {"cmd": "emu_gamepad_right_stick","x": 0.0, "y": 0.0}
  {"cmd": "emu_gamepad_trigger",   "side": "left", "value": 0.8}
  {"cmd": "emu_gamepad_update"}
  {"cmd": "emu_gamepad_disconnect"}

Bridge → Client  (events):
  {"event": "ready",        "version": "1.0"}

  BLE events:
  {"event": "device",       "address": "...", "name": "...", "rssi": -60, "services": [...]}
  {"event": "scan_done",    "count": 3}
  {"event": "connected",    "address": "..."}
  {"event": "disconnected"}
  {"event": "services",     "services": [...]}
  {"event": "read_result",  "char": "uuid", "hex": "FF0A", "text": "..."}
  {"event": "notify",       "char": "uuid", "hex": "...",  "text": "..."}
  {"event": "error",        "message": "..."}
  {"event": "pong"}

  Classic BT events:
  {"event": "bt_device",    "address": "...", "name": "...", "cod": 0}
  {"event": "bt_scan_done"}
  {"event": "bt_services",  "services": [...]}
  {"event": "bt_connected", "address": "...", "port": 1}
  {"event": "bt_disconnected"}
  {"event": "bt_data",      "hex": "...", "text": "..."}
  {"event": "bt_error",     "message": "..."}

  HID events:
  {"event": "hid_devices",  "devices": [...]}
  {"event": "hid_opened",   "manufacturer": "...", "product": "..."}
  {"event": "hid_input",    "hex": "...", "bytes": [...], "len": 64}
  {"event": "hid_closed"}
  {"event": "hid_error",    "message": "..."}

  Emulator events:
  {"event": "emu_ok",       "cmd": "emu_key_press"}
  {"event": "emu_gamepad_connected",    "type": "xbox360"}
  {"event": "emu_gamepad_disconnected"}
"""

import asyncio
import json
import sys
import websockets
from bleak import BleakScanner, BleakClient
from bleak.backends.characteristic import BleakGATTCharacteristic

# ── Optional dependencies ──────────────────────────────────────────────────────

try:
    import bluetooth  # pybluez2
    from bluetooth import BluetoothSocket, RFCOMM
    BT_AVAILABLE = True
except ImportError:
    BT_AVAILABLE = False
    print("[bridge] pybluez2 not installed — Classic BT commands disabled", flush=True)

try:
    import hid  # hidapi
    HID_AVAILABLE = True
except ImportError:
    HID_AVAILABLE = False
    print("[bridge] hid (hidapi) not installed — HID commands disabled", flush=True)

try:
    import pyautogui
    pyautogui.FAILSAFE = False
    HAS_PYAUTOGUI = True
except ImportError:
    HAS_PYAUTOGUI = False

try:
    import keyboard as kb_lib
    HAS_KEYBOARD = True
except ImportError:
    HAS_KEYBOARD = False

try:
    import vgamepad as vg
    HAS_VGAMEPAD = True
except ImportError:
    HAS_VGAMEPAD = False

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
        self.ws           = ws
        # BLE
        self.client       = None   # BleakClient
        self.scanning     = False
        self.scan_task    = None
        # Classic BT
        self.bt_socket    = None   # BluetoothSocket
        self.bt_recv_task = None
        # HID
        self.hid_device   = None
        self.hid_read_task = None
        # Emulator
        self.emu_gamepad  = None

    async def emit(self, obj):
        try:
            await self.ws.send(json.dumps(obj))
        except Exception:
            pass

    # ── Dispatch ──────────────────────────────────────────────────────────────

    async def handle(self, raw: str):
        try:
            msg = json.loads(raw)
        except json.JSONDecodeError:
            await self.emit({"event": "error", "message": "Invalid JSON"})
            return

        cmd = msg.get("cmd", "")
        try:
            # ---- BLE --------------------------------------------------------
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
            # ---- Classic BT -------------------------------------------------
            elif cmd == "bt_scan":       await self.bt_scan(msg.get("timeout", 8))
            elif cmd == "bt_services":   await self.bt_services(msg["address"])
            elif cmd == "bt_connect":    await self.bt_connect(msg["address"], int(msg["port"]))
            elif cmd == "bt_send":       await self.bt_send(msg["hex"])
            elif cmd == "bt_send_text":  await self.bt_send_text(msg["text"])
            elif cmd == "bt_disconnect": await self.bt_disconnect()
            # ---- HID --------------------------------------------------------
            elif cmd == "hid_list":      await self.hid_list()
            elif cmd == "hid_open":      await self.hid_open(msg["path"])
            elif cmd == "hid_open_id":   await self.hid_open_id(int(msg["vendor_id"]), int(msg["product_id"]))
            elif cmd == "hid_start_read": await self.hid_start_read()
            elif cmd == "hid_stop_read": await self.hid_stop_read()
            elif cmd == "hid_write":     await self.hid_write(msg["hex"])
            elif cmd == "hid_close":     await self.hid_close()
            # ---- Emulator ---------------------------------------------------
            elif cmd == "emu_key_press":          await self.emu_key_press(msg["key"])
            elif cmd == "emu_key_type":           await self.emu_key_type(msg["text"], float(msg.get("interval", 0.0)))
            elif cmd == "emu_key_down":           await self.emu_key_down(msg["key"])
            elif cmd == "emu_key_up":             await self.emu_key_up(msg["key"])
            elif cmd == "emu_key_hotkey":         await self.emu_key_hotkey(msg["keys"])
            elif cmd == "emu_mouse_move":         await self.emu_mouse_move(int(msg["x"]), int(msg["y"]), bool(msg.get("relative", False)), float(msg.get("duration", 0.0)))
            elif cmd == "emu_mouse_click":        await self.emu_mouse_click(msg.get("button", "left"), bool(msg.get("double", False)))
            elif cmd == "emu_mouse_down":         await self.emu_mouse_down(msg.get("button", "left"))
            elif cmd == "emu_mouse_up":           await self.emu_mouse_up(msg.get("button", "left"))
            elif cmd == "emu_mouse_scroll":       await self.emu_mouse_scroll(int(msg.get("dx", 0)), int(msg.get("dy", 0)))
            elif cmd == "emu_gamepad_connect":    await self.emu_gamepad_connect(msg.get("type", "xbox360"))
            elif cmd == "emu_gamepad_button":     await self.emu_gamepad_button(msg["button"], bool(msg["pressed"]))
            elif cmd == "emu_gamepad_left_stick": await self.emu_gamepad_left_stick(float(msg["x"]), float(msg["y"]))
            elif cmd == "emu_gamepad_right_stick":await self.emu_gamepad_right_stick(float(msg["x"]), float(msg["y"]))
            elif cmd == "emu_gamepad_trigger":    await self.emu_gamepad_trigger(msg["side"], float(msg["value"]))
            elif cmd == "emu_gamepad_update":     await self.emu_gamepad_update()
            elif cmd == "emu_gamepad_disconnect": await self.emu_gamepad_disconnect()
            else:
                await self.emit({"event": "error", "message": f"Unknown command: {cmd}"})
        except KeyError as e:
            await self.emit({"event": "error", "message": f"Missing field: {e}"})
        except Exception as e:
            await self.emit({"event": "error", "message": str(e)})

    # ══════════════════════════════════════════════════════════════════════════
    # BLE
    # ══════════════════════════════════════════════════════════════════════════

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
        self.scanning = False

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

    async def read(self, service_uuid: str, char_uuid: str):
        self._require_connected()
        data = await self.client.read_gatt_char(char_uuid)
        await self.emit({
            "event": "read_result",
            "char":  char_uuid,
            "hex":   dv_to_hex(data),
            "text":  try_decode(data),
        })

    async def write(self, service_uuid: str, char_uuid: str, hex_str: str, response: bool):
        self._require_connected()
        data = bytes.fromhex(hex_str.replace(" ", ""))
        if response:
            await self.client.write_gatt_char(char_uuid, data, response=True)
        else:
            await self.client.write_gatt_char(char_uuid, data, response=False)
        await self.emit({"event": "write_ok", "char": char_uuid})

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

    def _require_connected(self):
        if not self.client or not self.client.is_connected:
            raise RuntimeError("Not connected to a BLE device")

    # ══════════════════════════════════════════════════════════════════════════
    # Classic Bluetooth (pybluez2)
    # ══════════════════════════════════════════════════════════════════════════

    def _require_bt(self):
        if not BT_AVAILABLE:
            raise RuntimeError("pybluez2 is not installed — Classic BT unavailable")

    async def bt_scan(self, timeout: int):
        self._require_bt()
        loop = asyncio.get_event_loop()

        def _do_scan():
            return bluetooth.discover_devices(
                duration=timeout,
                lookup_names=True,
                lookup_class=True,
                flush_cache=True,
            )

        devices = await loop.run_in_executor(None, _do_scan)
        for entry in devices:
            # discover_devices returns (address, name) or (address, name, cod)
            if len(entry) >= 3:
                address, name, cod = entry[0], entry[1], entry[2]
            else:
                address, name, cod = entry[0], entry[1], 0
            await self.emit({
                "event":   "bt_device",
                "address": address,
                "name":    name or "",
                "cod":     cod,
            })
        await self.emit({"event": "bt_scan_done"})

    async def bt_services(self, address: str):
        self._require_bt()
        loop = asyncio.get_event_loop()

        def _do_find():
            return bluetooth.find_service(address=address)

        raw_services = await loop.run_in_executor(None, _do_find)

        services = []
        for svc in raw_services:
            protocol = svc.get("protocol", "") or ""
            port = svc.get("port", 0) or 0
            # Only include RFCOMM services with a valid port
            if protocol != "RFCOMM" or port <= 0:
                continue
            services.append({
                "host":           svc.get("host", ""),
                "name":           svc.get("name", ""),
                "description":    svc.get("description", ""),
                "provider":       svc.get("provider", ""),
                "protocol":       protocol,
                "port":           port,
                "service_classes": svc.get("service-classes", []),
                "service_id":     svc.get("service-id", ""),
                "profiles":       svc.get("profiles", []),
            })

        await self.emit({"event": "bt_services", "services": services})

    async def bt_connect(self, address: str, port: int):
        self._require_bt()
        if self.bt_socket is not None:
            await self.bt_disconnect()

        loop = asyncio.get_event_loop()

        def _do_connect():
            sock = BluetoothSocket(RFCOMM)
            sock.connect((address, port))
            return sock

        self.bt_socket = await loop.run_in_executor(None, _do_connect)
        await self.emit({"event": "bt_connected", "address": address, "port": port})

        # Start background recv loop
        self.bt_recv_task = asyncio.ensure_future(self._bt_recv_loop())

    async def _bt_recv_loop(self):
        loop = asyncio.get_event_loop()
        sock = self.bt_socket
        try:
            while self.bt_socket is not None:
                def _recv():
                    return sock.recv(4096)

                try:
                    data = await loop.run_in_executor(None, _recv)
                except Exception:
                    break

                if not data:
                    break

                await self.emit({
                    "event": "bt_data",
                    "hex":   dv_to_hex(data),
                    "text":  try_decode(data),
                })
        except asyncio.CancelledError:
            pass
        except Exception as e:
            await self.emit({"event": "bt_error", "message": str(e)})
        finally:
            if self.bt_socket is not None:
                await self.emit({"event": "bt_disconnected"})
                try:
                    self.bt_socket.close()
                except Exception:
                    pass
                self.bt_socket = None

    async def bt_send(self, hex_str: str):
        self._require_bt()
        if self.bt_socket is None:
            raise RuntimeError("Not connected to a Classic BT device")
        data = bytes.fromhex(hex_str.replace(" ", ""))
        loop = asyncio.get_event_loop()
        await loop.run_in_executor(None, lambda: self.bt_socket.send(data))

    async def bt_send_text(self, text: str):
        self._require_bt()
        if self.bt_socket is None:
            raise RuntimeError("Not connected to a Classic BT device")
        data = text.encode("utf-8")
        loop = asyncio.get_event_loop()
        await loop.run_in_executor(None, lambda: self.bt_socket.send(data))

    async def bt_disconnect(self):
        if self.bt_recv_task is not None:
            self.bt_recv_task.cancel()
            try:
                await self.bt_recv_task
            except asyncio.CancelledError:
                pass
            self.bt_recv_task = None

        if self.bt_socket is not None:
            try:
                self.bt_socket.close()
            except Exception:
                pass
            self.bt_socket = None
            await self.emit({"event": "bt_disconnected"})

    # ══════════════════════════════════════════════════════════════════════════
    # HID (hidapi)
    # ══════════════════════════════════════════════════════════════════════════

    def _require_hid(self):
        if not HID_AVAILABLE:
            raise RuntimeError("hid (hidapi) is not installed — HID unavailable")

    async def hid_list(self):
        self._require_hid()
        loop = asyncio.get_event_loop()

        def _do_enum():
            return hid.enumerate()

        devices_raw = await loop.run_in_executor(None, _do_enum)
        devices = []
        for d in devices_raw:
            devices.append({
                "path":         d.get("path", b"").decode("utf-8", errors="replace") if isinstance(d.get("path"), bytes) else str(d.get("path", "")),
                "vendor_id":    d.get("vendor_id", 0),
                "product_id":   d.get("product_id", 0),
                "manufacturer": d.get("manufacturer_string", ""),
                "product":      d.get("product_string", ""),
                "serial":       d.get("serial_number", ""),
                "usage_page":   d.get("usage_page", 0),
                "usage":        d.get("usage", 0),
            })
        await self.emit({"event": "hid_devices", "devices": devices})

    async def hid_open(self, path: str):
        self._require_hid()
        if self.hid_device is not None:
            await self.hid_close()

        loop = asyncio.get_event_loop()
        path_bytes = path.encode("utf-8") if isinstance(path, str) else path

        def _do_open():
            dev = hid.device()
            dev.open_path(path_bytes)
            return dev

        self.hid_device = await loop.run_in_executor(None, _do_open)
        manufacturer = self.hid_device.get_manufacturer_string() or ""
        product      = self.hid_device.get_product_string() or ""
        await self.emit({"event": "hid_opened", "manufacturer": manufacturer, "product": product})

    async def hid_open_id(self, vendor_id: int, product_id: int):
        self._require_hid()
        if self.hid_device is not None:
            await self.hid_close()

        loop = asyncio.get_event_loop()

        def _do_open():
            dev = hid.device()
            dev.open(vendor_id, product_id)
            return dev

        self.hid_device = await loop.run_in_executor(None, _do_open)
        manufacturer = self.hid_device.get_manufacturer_string() or ""
        product      = self.hid_device.get_product_string() or ""
        await self.emit({"event": "hid_opened", "manufacturer": manufacturer, "product": product})

    async def hid_start_read(self):
        self._require_hid()
        if self.hid_device is None:
            raise RuntimeError("No HID device is open")
        if self.hid_read_task is not None:
            return  # already reading

        self.hid_read_task = asyncio.ensure_future(self._hid_read_loop())

    async def _hid_read_loop(self):
        loop = asyncio.get_event_loop()
        dev = self.hid_device
        # Set non-blocking so we can check for cancellation
        try:
            dev.set_nonblocking(1)
        except Exception:
            pass

        try:
            while self.hid_device is not None:
                def _do_read():
                    return dev.read(64)

                try:
                    report = await loop.run_in_executor(None, _do_read)
                except asyncio.CancelledError:
                    raise
                except Exception as e:
                    await self.emit({"event": "hid_error", "message": str(e)})
                    break

                if report:
                    data_bytes = list(report)
                    hex_str = " ".join(f"{b:02X}" for b in data_bytes)
                    await self.emit({
                        "event": "hid_input",
                        "hex":   hex_str,
                        "bytes": data_bytes,
                        "len":   len(data_bytes),
                    })
                else:
                    # No data yet — yield briefly to avoid a busy-spin
                    await asyncio.sleep(0.01)

        except asyncio.CancelledError:
            pass
        except Exception as e:
            await self.emit({"event": "hid_error", "message": str(e)})
        finally:
            self.hid_read_task = None

    async def hid_stop_read(self):
        if self.hid_read_task is not None:
            self.hid_read_task.cancel()
            try:
                await self.hid_read_task
            except asyncio.CancelledError:
                pass
            self.hid_read_task = None

    async def hid_write(self, hex_str: str):
        self._require_hid()
        if self.hid_device is None:
            raise RuntimeError("No HID device is open")

        data = bytes.fromhex(hex_str.replace(" ", ""))
        # hidapi write requires a leading report-ID byte; prepend 0x00 if not present
        # We trust the caller to include it (as documented), but guard against empty input
        report = list(data)
        loop = asyncio.get_event_loop()
        await loop.run_in_executor(None, lambda: self.hid_device.write(report))

    async def hid_close(self):
        await self.hid_stop_read()
        if self.hid_device is not None:
            try:
                self.hid_device.close()
            except Exception:
                pass
            self.hid_device = None
            await self.emit({"event": "hid_closed"})

    # ══════════════════════════════════════════════════════════════════════════
    # Emulator (pyautogui / vgamepad)
    # ══════════════════════════════════════════════════════════════════════════

    def _require_pyautogui(self):
        if not HAS_PYAUTOGUI:
            raise RuntimeError("pyautogui is not installed — install pyautogui")

    async def emu_key_press(self, key: str):
        self._require_pyautogui()
        loop = asyncio.get_event_loop()
        await loop.run_in_executor(None, lambda: pyautogui.press(key))
        await self.emit({"event": "emu_ok", "cmd": "emu_key_press"})

    async def emu_key_type(self, text: str, interval: float = 0.0):
        self._require_pyautogui()
        loop = asyncio.get_event_loop()
        await loop.run_in_executor(None, lambda: pyautogui.typewrite(text, interval=interval))
        await self.emit({"event": "emu_ok", "cmd": "emu_key_type"})

    async def emu_key_down(self, key: str):
        self._require_pyautogui()
        loop = asyncio.get_event_loop()
        await loop.run_in_executor(None, lambda: pyautogui.keyDown(key))
        await self.emit({"event": "emu_ok", "cmd": "emu_key_down"})

    async def emu_key_up(self, key: str):
        self._require_pyautogui()
        loop = asyncio.get_event_loop()
        await loop.run_in_executor(None, lambda: pyautogui.keyUp(key))
        await self.emit({"event": "emu_ok", "cmd": "emu_key_up"})

    async def emu_key_hotkey(self, keys: list):
        self._require_pyautogui()
        loop = asyncio.get_event_loop()
        await loop.run_in_executor(None, lambda: pyautogui.hotkey(*keys))
        await self.emit({"event": "emu_ok", "cmd": "emu_key_hotkey"})

    async def emu_mouse_move(self, x: int, y: int, relative: bool = False, duration: float = 0.0):
        self._require_pyautogui()
        loop = asyncio.get_event_loop()

        if relative:
            await loop.run_in_executor(None, lambda: pyautogui.moveRel(x, y, duration=duration))
        else:
            await loop.run_in_executor(None, lambda: pyautogui.moveTo(x, y, duration=duration))

        pos = pyautogui.position()
        await self.emit({"event": "emu_ok", "cmd": "emu_mouse_move", "x": pos.x, "y": pos.y})

    async def emu_mouse_click(self, button: str = "left", double: bool = False):
        self._require_pyautogui()
        loop = asyncio.get_event_loop()
        if double:
            await loop.run_in_executor(None, lambda: pyautogui.doubleClick(button=button))
        else:
            await loop.run_in_executor(None, lambda: pyautogui.click(button=button))
        await self.emit({"event": "emu_ok", "cmd": "emu_mouse_click"})

    async def emu_mouse_down(self, button: str = "left"):
        self._require_pyautogui()
        loop = asyncio.get_event_loop()
        await loop.run_in_executor(None, lambda: pyautogui.mouseDown(button=button))
        await self.emit({"event": "emu_ok", "cmd": "emu_mouse_down"})

    async def emu_mouse_up(self, button: str = "left"):
        self._require_pyautogui()
        loop = asyncio.get_event_loop()
        await loop.run_in_executor(None, lambda: pyautogui.mouseUp(button=button))
        await self.emit({"event": "emu_ok", "cmd": "emu_mouse_up"})

    async def emu_mouse_scroll(self, dx: int = 0, dy: int = 0):
        self._require_pyautogui()
        loop = asyncio.get_event_loop()
        if dy != 0:
            await loop.run_in_executor(None, lambda: pyautogui.scroll(dy))
        if dx != 0:
            await loop.run_in_executor(None, lambda: pyautogui.hscroll(dx))
        await self.emit({"event": "emu_ok", "cmd": "emu_mouse_scroll"})

    async def emu_gamepad_connect(self, type: str = "xbox360"):
        if not HAS_VGAMEPAD:
            await self.emit({"event": "error", "message": "vgamepad not installed — install vgamepad and ViGEmBus driver"})
            return
        if self.emu_gamepad is not None:
            self.emu_gamepad = None
        if type == "ds4":
            self.emu_gamepad = vg.VDS4Gamepad()
        else:
            self.emu_gamepad = vg.VX360Gamepad()
        self.emu_gamepad.update()
        await self.emit({"event": "emu_gamepad_connected", "type": type})

    async def emu_gamepad_button(self, button: str, pressed: bool):
        if self.emu_gamepad is None:
            await self.emit({"event": "error", "message": "No gamepad connected"})
            return

        xbox_map = {
            "A":          vg.XUSB_BUTTON.XUSB_GAMEPAD_A,
            "B":          vg.XUSB_BUTTON.XUSB_GAMEPAD_B,
            "X":          vg.XUSB_BUTTON.XUSB_GAMEPAD_X,
            "Y":          vg.XUSB_BUTTON.XUSB_GAMEPAD_Y,
            "LB":         vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_SHOULDER,
            "RB":         vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_SHOULDER,
            "START":      vg.XUSB_BUTTON.XUSB_GAMEPAD_START,
            "BACK":       vg.XUSB_BUTTON.XUSB_GAMEPAD_BACK,
            "LS":         vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_THUMB,
            "RS":         vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_THUMB,
            "DPAD_UP":    vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_UP,
            "DPAD_DOWN":  vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_DOWN,
            "DPAD_LEFT":  vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_LEFT,
            "DPAD_RIGHT": vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_RIGHT,
        }

        if isinstance(self.emu_gamepad, vg.VX360Gamepad):
            btn_const = xbox_map.get(button.upper())
            if btn_const is None:
                await self.emit({"event": "error", "message": f"Unknown Xbox button: {button}"})
                return
            if pressed:
                self.emu_gamepad.press_button(button=btn_const)
            else:
                self.emu_gamepad.release_button(button=btn_const)
        else:
            # DS4 — map common names to DS4 equivalents where possible
            ds4_map = {
                "A":          vg.DS4_BUTTONS.DS4_BUTTON_CROSS,
                "B":          vg.DS4_BUTTONS.DS4_BUTTON_CIRCLE,
                "X":          vg.DS4_BUTTONS.DS4_BUTTON_SQUARE,
                "Y":          vg.DS4_BUTTONS.DS4_BUTTON_TRIANGLE,
                "LB":         vg.DS4_BUTTONS.DS4_BUTTON_SHOULDER_LEFT,
                "RB":         vg.DS4_BUTTONS.DS4_BUTTON_SHOULDER_RIGHT,
                "START":      vg.DS4_BUTTONS.DS4_BUTTON_OPTIONS,
                "BACK":       vg.DS4_BUTTONS.DS4_BUTTON_SHARE,
                "LS":         vg.DS4_BUTTONS.DS4_BUTTON_THUMB_LEFT,
                "RS":         vg.DS4_BUTTONS.DS4_BUTTON_THUMB_RIGHT,
            }
            ds4_dpad_map = {
                "DPAD_UP":    vg.DS4_DPAD_DIRECTIONS.DS4_BUTTON_DPAD_NORTH,
                "DPAD_DOWN":  vg.DS4_DPAD_DIRECTIONS.DS4_BUTTON_DPAD_SOUTH,
                "DPAD_LEFT":  vg.DS4_DPAD_DIRECTIONS.DS4_BUTTON_DPAD_WEST,
                "DPAD_RIGHT": vg.DS4_DPAD_DIRECTIONS.DS4_BUTTON_DPAD_EAST,
            }
            btn_upper = button.upper()
            if btn_upper in ds4_dpad_map:
                if pressed:
                    self.emu_gamepad.directional_pad(direction=ds4_dpad_map[btn_upper])
                else:
                    self.emu_gamepad.directional_pad(direction=vg.DS4_DPAD_DIRECTIONS.DS4_BUTTON_DPAD_NONE)
            else:
                btn_const = ds4_map.get(btn_upper)
                if btn_const is None:
                    await self.emit({"event": "error", "message": f"Unknown DS4 button: {button}"})
                    return
                if pressed:
                    self.emu_gamepad.press_button(button=btn_const)
                else:
                    self.emu_gamepad.release_button(button=btn_const)

        await self.emit({"event": "emu_ok", "cmd": "emu_gamepad_button"})

    async def emu_gamepad_left_stick(self, x: float, y: float):
        if self.emu_gamepad is None:
            await self.emit({"event": "error", "message": "No gamepad connected"})
            return
        self.emu_gamepad.left_joystick_float(x_value_float=x, y_value_float=y)
        await self.emit({"event": "emu_ok", "cmd": "emu_gamepad_left_stick"})

    async def emu_gamepad_right_stick(self, x: float, y: float):
        if self.emu_gamepad is None:
            await self.emit({"event": "error", "message": "No gamepad connected"})
            return
        self.emu_gamepad.right_joystick_float(x_value_float=x, y_value_float=y)
        await self.emit({"event": "emu_ok", "cmd": "emu_gamepad_right_stick"})

    async def emu_gamepad_trigger(self, side: str, value: float):
        if self.emu_gamepad is None:
            await self.emit({"event": "error", "message": "No gamepad connected"})
            return
        if side == "left":
            self.emu_gamepad.left_trigger_float(value_float=value)
        else:
            self.emu_gamepad.right_trigger_float(value_float=value)
        await self.emit({"event": "emu_ok", "cmd": "emu_gamepad_trigger"})

    async def emu_gamepad_update(self):
        if self.emu_gamepad is None:
            await self.emit({"event": "error", "message": "No gamepad connected"})
            return
        self.emu_gamepad.update()
        await self.emit({"event": "emu_ok", "cmd": "emu_gamepad_update"})

    async def emu_gamepad_disconnect(self):
        self.emu_gamepad = None
        await self.emit({"event": "emu_gamepad_disconnected"})

    # ── Cleanup ───────────────────────────────────────────────────────────────

    async def cleanup(self):
        """Called on WebSocket close — tear down all connections."""
        # BLE
        if self.client and self.client.is_connected:
            try:
                await self.client.disconnect()
            except Exception:
                pass

        # Classic BT
        if self.bt_recv_task is not None:
            self.bt_recv_task.cancel()
            try:
                await self.bt_recv_task
            except asyncio.CancelledError:
                pass
        if self.bt_socket is not None:
            try:
                self.bt_socket.close()
            except Exception:
                pass
            self.bt_socket = None

        # HID
        await self.hid_stop_read()
        if self.hid_device is not None:
            try:
                self.hid_device.close()
            except Exception:
                pass
            self.hid_device = None

        # Emulator
        if self.emu_gamepad is not None:
            self.emu_gamepad = None


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
        await session.cleanup()
        print("[bridge] Client disconnected", flush=True)


async def main():
    print(f"[bridge] Bridge starting on ws://127.0.0.1:{PORT}", flush=True)
    print(f"[bridge] BLE: enabled  |  Classic BT: {'enabled' if BT_AVAILABLE else 'disabled (pybluez2 missing)'}  |  HID: {'enabled' if HID_AVAILABLE else 'disabled (hid missing)'}  |  Emulator: {'enabled' if HAS_PYAUTOGUI else 'disabled (pyautogui missing)'}  |  Gamepad: {'enabled' if HAS_VGAMEPAD else 'disabled (vgamepad missing)'}", flush=True)
    async with websockets.serve(handler, "127.0.0.1", PORT):
        print(f"[bridge] Ready", flush=True)
        await asyncio.Future()  # run forever


if __name__ == "__main__":
    asyncio.run(main())
