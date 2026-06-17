"""
MirrorX Server — PC Screen Mirroring to Tablet
Captures screen via DXGI, streams via WebSocket, receives touch input.
Optimized: dynamic quality, aspect ratio preservation, fast JPEG encoding.
"""
import asyncio
import struct
import json
import time
import socket
from pathlib import Path

import dxcam
import cv2
import numpy as np
import websockets
import pyautogui

# --- Config ---
PORT = 9900
HTTP_PORT = 8080
BASE_QUALITY = 75          # Starting JPEG quality — higher for text readability
MIN_QUALITY = 25           # Minimum quality floor
MAX_QUALITY = 85           # Maximum quality ceiling
TARGET_FPS = 30
FRAME_TIME = 1.0 / TARGET_FPS
SCALE_FACTOR = 0.75        # 75% — preserves text legibility
MIN_SCALE = 0.25           # Minimum downscale
JPEG_PARAMS = [cv2.IMWRITE_JPEG_QUALITY, BASE_QUALITY,
               cv2.IMWRITE_JPEG_OPTIMIZE, 0,      # No Huffman opt = faster
               cv2.IMWRITE_JPEG_RST_INTERVAL, 0]  # No restart markers
MAX_SCALE = 1.0            # Maximum (native)

# Dynamic quality control
QUALITY_STEP = 5           # Adjust by this much per step
ADJUST_INTERVAL = 1.0      # Seconds between adjustments
LOW_FPS_THRESHOLD = 20     # If FPS drops below this, reduce quality
HIGH_FPS_THRESHOLD = 28    # If FPS above this, increase quality

# Disable pyautogui fail-safe (corner throw)
pyautogui.FAILSAFE = False


class MirrorServer:
    def __init__(self):
        self.camera = None
        self.clients: set = set()
        self.running = False
        self.screen_w = 1920
        self.screen_h = 1080
        self.stream_w = 0
        self.stream_h = 0

        # Dynamic quality state
        self.current_quality = BASE_QUALITY
        self.current_scale = SCALE_FACTOR
        self.frame_count = 0
        self.last_fps_time = time.monotonic()
        self.current_fps = 0
        self.last_adjust_time = time.monotonic()

        # Pre-allocated numpy array for resize target
        self._resize_target = None
        # Cached JPEG encode params
        self._jpeg_params = list(JPEG_PARAMS)
        # Latest-frame buffer (solution 4: drop old frames)
        self.latest_frame: bytes | None = None
        self.latest_frame_ready = asyncio.Event()
        # Per-client send lock (solution 5: non-blocking broadcast)
        self._sending: set = set()

    def get_local_ip(self) -> str:
        """Get the local network IP for the tablet to connect to."""
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(('192.168.100.17', 1))
            ip = s.getsockname()[0]
        except Exception:
            ip = '127.0.0.1'
        finally:
            s.close()
        return ip

    def start_capture(self):
        """Initialize DXGI screen capture."""
        self.camera = dxcam.create(output_color="RGB")
        if self.camera is None:
            raise RuntimeError(
                "dxcam failed to initialize. Make sure you have a GPU with DXGI support."
            )

        # Get screen info
        self.screen_w = self.camera.width
        self.screen_h = self.camera.height
        self._update_stream_dims()

        print(f"[MirrorX] Screen: {self.screen_w}x{self.screen_h}")
        print(f"[MirrorX] Streaming at: {self.stream_w}x{self.stream_h} ({self.current_scale*100:.0f}%)")
        print(f"[MirrorX] Quality: {self.current_quality}%")

    def _update_stream_dims(self):
        """Update stream dimensions preserving aspect ratio."""
        self.stream_w = int(self.screen_w * self.current_scale)
        self.stream_h = int(self.screen_h * self.current_scale)
        # Pre-allocate resize target
        self._resize_target = None  # Will be allocated on first use

    def _adjust_quality(self):
        """Dynamically adjust quality/scale based on FPS."""
        now = time.monotonic()
        if now - self.last_adjust_time < ADJUST_INTERVAL:
            return
 
        self.last_adjust_time = now
        changed = False
 
        if self.current_fps < LOW_FPS_THRESHOLD:
            # FPS too low - reduce quality first, then scale
            if self.current_quality > MIN_QUALITY:
                self.current_quality = max(MIN_QUALITY, self.current_quality - QUALITY_STEP)
                self._jpeg_params[1] = self.current_quality
                changed = True
                print(f"[MirrorX] FPS {self.current_fps} -> Quality down to {self.current_quality}%")
            elif self.current_scale > MIN_SCALE:
                self.current_scale = max(MIN_SCALE, self.current_scale - 0.05)
                self._update_stream_dims()
                changed = True
                print(f"[MirrorX] FPS {self.current_fps} -> Scale down to {self.current_scale*100:.0f}%")
 
        elif self.current_fps > HIGH_FPS_THRESHOLD:
            # FPS has headroom - increase quality first, then scale
            if self.current_quality < MAX_QUALITY:
                self.current_quality = min(MAX_QUALITY, self.current_quality + QUALITY_STEP)
                self._jpeg_params[1] = self.current_quality
                changed = True
                print(f"[MirrorX] FPS {self.current_fps} -> Quality up to {self.current_quality}%")
            elif self.current_scale < MAX_SCALE:
                self.current_scale = min(MAX_SCALE, self.current_scale + 0.05)
                self._update_stream_dims()
                changed = True
                print(f"[MirrorX] FPS {self.current_fps} -> Scale up to {self.current_scale*100:.0f}%")

    def capture_frame(self) -> bytes | None:
        """Capture one frame from screen, return JPEG bytes (optimized)."""
        frame = self.camera.grab()
        if frame is None:
            return None
 
        # Downscale using cv2 (much faster than Pillow)
        if self.current_scale < 1.0:
            frame = cv2.resize(frame, (self.stream_w, self.stream_h),
                             interpolation=cv2.INTER_AREA)  # INTER_AREA = fastest for downscale
 
        # Encode as JPEG via OpenCV (5-10x faster than Pillow)
        success, encoded = cv2.imencode('.jpg', frame, self._jpeg_params)
        if not success:
             return None
        return encoded.tobytes()

    def _handle_key(self, key: str):
        """Simulate keyboard key press."""
        key_map = {
            "escape": "esc", "enter": "enter", "space": "space",
            "tab": "tab", "win": "winleft", "backspace": "backspace",
            "delete": "delete", "up": "up", "down": "down",
            "left": "left", "right": "right",
        }
        pyautogui_key = key_map.get(key, key)
        try:
            pyautogui.press(pyautogui_key)
        except Exception as e:
            print(f"[MirrorX] Key error: {key} -> {e}")

    def handle_touch(self, data: dict):
        """Convert tablet touch to Windows mouse events."""
        try:
            x_ratio = data.get("x", 0.5)
            y_ratio = data.get("y", 0.5)
            action = data.get("action", "move")

            target_x = int(x_ratio * self.screen_w)
            target_y = int(y_ratio * self.screen_h)

            if action == "down":
                pyautogui.moveTo(target_x, target_y)
                pyautogui.mouseDown()
            elif action == "up":
                pyautogui.moveTo(target_x, target_y)
                pyautogui.mouseUp()
            elif action == "click":
                pyautogui.click(target_x, target_y)
            elif action == "right_click":
                pyautogui.rightClick(target_x, target_y)
            elif action == "move":
                pyautogui.moveTo(target_x, target_y)
            elif action == "scroll":
                amount = data.get("amount", 0)
                pyautogui.scroll(amount)
            elif action == "drag":
                pyautogui.dragTo(target_x, target_y, duration=0.05)
            elif action == "key":
                key = data.get("key", "")
                self._handle_key(key)
        except Exception as e:
            print(f"[MirrorX] Touch error: {e}")

    def handle_config(self, data: dict):
        """Handle config commands from client (quality, scale, etc)."""
        cmd = data.get("cmd", "")
        if cmd == "get_stats":
            return {
                "type": "stats",
                "fps": self.current_fps,
                "quality": self.current_quality,
                "scale": self.current_scale,
                "stream_w": self.stream_w,
                "stream_h": self.stream_h,
                "screen_w": self.screen_w,
                "screen_h": self.screen_h,
            }
        elif cmd == "set_quality":
            self.current_quality = max(MIN_QUALITY, min(MAX_QUALITY, int(data.get("value", BASE_QUALITY))))
            self._jpeg_params[1] = self.current_quality
            print(f"[MirrorX] Quality set to {self.current_quality}%")
        elif cmd == "set_scale":
            self.current_scale = max(MIN_SCALE, min(MAX_SCALE, float(data.get("value", SCALE_FACTOR))))
            self._update_stream_dims()
            print(f"[MirrorX] Scale set to {self.current_scale*100:.0f}%")
        elif cmd == "reset_auto":
            self.current_quality = BASE_QUALITY
            self.current_scale = SCALE_FACTOR
            self._update_stream_dims()
            print("[MirrorX] Auto quality/scale reset")
        return None

    async def handler(self, websocket):
        """WebSocket connection handler."""
        self.clients.add(websocket)
        remote = websocket.remote_address
        print(f"[MirrorX] Client connected: {remote}")

        # Send screen info
        await websocket.send(json.dumps({
            "type": "screen_info",
            "width": self.screen_w,
            "height": self.screen_h,
            "stream_width": self.stream_w,
            "stream_height": self.stream_h,
            "aspect_ratio": self.screen_w / self.screen_h,
        }))

        try:
            async for message in websocket:
                try:
                    data = json.loads(message)
                    msg_type = data.get("type", "")

                    if msg_type == "touch":
                        self.handle_touch(data)
                    elif msg_type == "ping":
                        await websocket.send(json.dumps({"type": "pong"}))
                    elif msg_type == "config":
                        response = self.handle_config(data)
                        if response:
                            await websocket.send(json.dumps(response))
                except json.JSONDecodeError:
                    pass
        except websockets.exceptions.ConnectionClosed:
            pass
        finally:
            self.clients.discard(websocket)
            print(f"[MirrorX] Client disconnected: {remote}")

    async def _send_to_client(self, client, frame_msg):
        """Send frame to one client. Skip if still sending previous frame."""
        if client in self._sending:
            return  # Solution 5: drop frame for slow client
        self._sending.add(client)
        try:
            await client.send(frame_msg)
        except websockets.exceptions.ConnectionClosed:
            self.clients.discard(client)
        finally:
            self._sending.discard(client)

    async def stream_loop(self):
        """Main capture and broadcast loop — latest-frame buffer pattern."""
        self.start_capture()
        self.running = True
        print(f"[MirrorX] Server started on {self.get_local_ip()}")
        print(f"[MirrorX] Tablet: open http://{self.get_local_ip()}:{HTTP_PORT} in Chrome")
        print(f"[MirrorX] Dynamic quality: ON (target {TARGET_FPS} FPS)")

        last_frame_time = 0

        while self.running:
            # Frame rate limiting
            now = time.monotonic()
            elapsed = now - last_frame_time
            if elapsed < FRAME_TIME:
                await asyncio.sleep(FRAME_TIME - elapsed)

            last_frame_time = time.monotonic()
            self.frame_count += 1

            # Calculate FPS every second
            if now - self.last_fps_time >= 1.0:
                self.current_fps = self.frame_count / (now - self.last_fps_time)
                self.frame_count = 0
                self.last_fps_time = now
                self._adjust_quality()

            # Skip if no clients
            if not self.clients:
                await asyncio.sleep(0.5)
                continue

            # Capture frame (solution 4: latest-frame buffer)
            jpeg_bytes = self.capture_frame()
            if jpeg_bytes is None:
                continue

            # Store latest frame (old frame is automatically dropped)
            self.latest_frame = jpeg_bytes
            frame_msg = struct.pack('>BI', 0, len(jpeg_bytes)) + jpeg_bytes

            # Solution 5: fire-and-forget sends (non-blocking broadcast)
            for client in list(self.clients):
                asyncio.ensure_future(self._send_to_client(client, frame_msg))

    async def run(self):
        """Start the WebSocket server."""
        async with websockets.serve(
            self.handler,
            "0.0.0.0",
            PORT,
            max_size=2_000_000,
            ping_interval=30,
            ping_timeout=10,
        ):
            print(f"[MirrorX] WebSocket server listening on :{PORT}")
            await self.stream_loop()


def start_http_server(port: int = HTTP_PORT):
    """Start HTTP server to serve the web client to tablet."""
    import http.server
    import os
    import sys
    import threading

    if getattr(sys, 'frozen', False):
        client_dir = os.path.join(sys._MEIPASS, "client")
    else:
        client_dir = str(Path(__file__).parent / "client")

    os.chdir(client_dir)

    handler = http.server.SimpleHTTPRequestHandler
    httpd = http.server.HTTPServer(("0.0.0.0", port), handler)
    print(f"[MirrorX] HTTP server on http://0.0.0.0:{port}")
    httpd.serve_forever()


def main():
    import threading

    http_thread = threading.Thread(target=start_http_server, args=(HTTP_PORT,), daemon=True)
    http_thread.start()

    server = MirrorServer()
    try:
        asyncio.run(server.run())
    except KeyboardInterrupt:
        print("\n[MirrorX] Server stopped.")


if __name__ == "__main__":
    main()