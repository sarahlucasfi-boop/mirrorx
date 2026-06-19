"""
MirrorX v1.5.0 — Unified server entry point
============================================

This is the main server. It can run in two modes:

  * Mirror mode (default, v1.4.3 compatible)
        Streams JPEG screenshots over WebSocket binary on port 9900.
        Accepts binary touch frames (0x10 = touch path, 0x11 = pinch).
        Also accepts click_request JSON.

  * Hermes mode (--hermes, v1.5.0 NEW)
        Listens for JSON mouse/keyboard commands on port 9900.
        No video. Touchpad-only.
        See server_hermes.py for the protocol.

The two modes are mutually exclusive — pick one at startup via the
`--hermes` flag, the ControlPanel checkbox, or the MIRRORX_MODE env var.

Why one binary, two modes:
    The user should be able to choose: see the screen (mirror) OR have
    a low-latency pure-mouse control (hermes). Same network stack, same
    port number — different message types.
"""

from __future__ import annotations
import argparse
import asyncio
import base64
import json
import logging
import os
import struct
import sys
import threading
import time
from typing import Callable, Optional, Set, List

log = logging.getLogger("mirrorx")

VERSION = "1.6.5"
DEFAULT_PORT = 9900


# ---------------------------------------------------------------------------
# Try to import the Hermes server; we may run mirror mode without it but
# the hermes path is just server_hermes.HermesServer.
# ---------------------------------------------------------------------------
try:
    from server_hermes import HermesServer
    HAS_HERMES = True
except ImportError as e:
    log.debug("server_hermes not importable: %s", e)
    HAS_HERMES = False


# ---------------------------------------------------------------------------
# Mirror mode — the v1.4.3 fallback.
# This is a lean reimplementation that focuses on what v1.5.0 needs:
#   - Screen capture
#   - JPEG encoding
#   - WebSocket broadcast of JPEG frames
#   - Touch input handling (binary 0x10, 0x11, plus click_request JSON)
#   - Settings: quality, scale, fps target
#   - A simple stats dict for the control panel
# ---------------------------------------------------------------------------
class MirrorMode:
    """v1.4.3-compatible mirror server.

    Compared to the original v1.4.3 server this is intentionally leaner
    (no MotionInterpolator, no cursor broadcasting, no GhostCursor
    code-paths, no touch-path collector). Those are v1.5.0 niceties
    already; for the fallback we just need: capture → encode → broadcast
    → receive touch → inject.
    """

    DEFAULTS = {
        "scale": 1.0,        # v1.6.0: 100% — crisp, no downscaling
        "quality": 34,       # v1.6.0: fast encode, low bandwidth (~200 KB/frame)
        "target_fps": 30,
    }

    def __init__(self, port: int = DEFAULT_PORT, host: str = "0.0.0.0"):
        self.host = host
        self.port = port
        self.clients: Set = set()
        self.running = False
        self._capture = None
        self._encoder = None
        self._screen_size = None
        # Settings
        self.scale = self.DEFAULTS["scale"]
        self.quality = self.DEFAULTS["quality"]
        self.target_fps = self.DEFAULTS["target_fps"]
        # Stats
        self.stats = {
            "fps": 0.0,
            "clients": 0,
            "frame_bytes": 0,
            "frames_sent": 0,
            "bytes_sent": 0,
            "touches": 0,
            "started_at": time.time(),
        }

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------
    async def start(self):
        # v1.6.0: retry capture init up to 3x in case dxcam failed to
        # acquire the device (happens sometimes on hot-reload, screen
        # lock state, or when an exclusive fullscreen app is running).
        for attempt in range(1, 4):
            self._init_capture()
            self._init_encoder()
            if self._screen_size is not None:
                break
            log.warning("[Mirror] capture init failed (attempt %d/3), retrying...",
                        attempt)
            await asyncio.sleep(2)
        if self._screen_size is None:
            log.error("[Mirror] capture init failed after 3 attempts — giving up")
            return
        log.info("[Mirror] starting on ws://%s:%d", self.host, self.port)
        import websockets
        async with websockets.serve(
            self._on_client, self.host, self.port,
            max_size=10_000, ping_interval=15, ping_timeout=30,
        ):
            self.running = True
            log.info("[Mirror] listening — %dx%d @ scale %.2f q=%d",
                     self._screen_size[0], self._screen_size[1],
                     self.scale, self.quality)
            # v1.6.0: use a wrapper that auto-restarts on crash
            self.broadcast_task = asyncio.create_task(self._broadcast_loop_supervised())
            try:
                await asyncio.Future()
            finally:
                self.running = False
                self.broadcast_task.cancel()
                try:
                    await self.broadcast_task
                except (asyncio.CancelledError, Exception):
                    pass

    async def _broadcast_loop_supervised(self):
        """Wrap _broadcast_loop so a crash here doesn't kill the server.
        Restarts after 1s if the loop dies unexpectedly."""
        while self.running:
            try:
                await self._broadcast_loop()
            except asyncio.CancelledError:
                raise
            except Exception as e:
                log.exception("[Mirror] broadcast_loop crashed, restarting in 1s: %s", e)
                await asyncio.sleep(1.0)
            else:
                # Loop exited normally (self.running went False)
                break

    # ------------------------------------------------------------------
    # Capture / encoder init
    # ------------------------------------------------------------------
    def _init_capture(self):
        """Try dxcam → mss → PIL.ImageGrab in that order."""
        try:
            import dxcam
            cap = dxcam.create()
            frame = cap.grab()
            if frame is not None:
                self._capture = ("dxcam", cap)
                self._screen_size = (frame.shape[1], frame.shape[0])  # (W, H)
                log.info("[Mirror] using dxcam capture")
                return
        except Exception as e:
            log.debug("[Mirror] dxcam not available: %s", e)
        try:
            import mss
            with mss.mss() as sct:
                m = sct.monitors[1]  # primary
                self._capture = ("mss", mss.mss())
                self._screen_size = (m["width"], m["height"])
                log.info("[Mirror] using mss capture (%dx%d)",
                         m["width"], m["height"])
                return
        except Exception as e:
            log.debug("[Mirror] mss not available: %s", e)
        try:
            from PIL import ImageGrab
            img = ImageGrab.grab()
            self._capture = ("pil", ImageGrab)
            self._screen_size = img.size  # (W, H)
            log.info("[Mirror] using PIL.ImageGrab")
        except Exception as e:
            log.error("[Mirror] no capture backend available: %s", e)
            self._screen_size = (1920, 1080)  # last-resort default

    def _init_encoder(self):
        """Try OpenCV → Pillow. We avoid TurboJPEG to keep the dep list small."""
        try:
            import cv2
            self._encoder = ("cv2", cv2)
            log.info("[Mirror] using OpenCV encoder")
            return
        except Exception as e:
            log.debug("[Mirror] OpenCV not available: %s", e)
        try:
            from PIL import Image
            self._encoder = ("pil", Image)
            log.info("[Mirror] using Pillow encoder (slower)")
        except Exception as e:
            log.error("[Mirror] no encoder available: %s", e)

    # ------------------------------------------------------------------
    # Frame grab + encode
    # ------------------------------------------------------------------
    def _grab_frame(self):
        """Return a numpy BGR array of the screen, scaled to self.scale.
        
        Note: all capture backends return RGB, but cv2.imencode expects
        BGR. We convert here so the JPEG ends up with correct colors on
        the client side (BitmapFactory doesn't do channel swaps).
        
        v1.6.0: returns None on any capture exception (instead of
        crashing the broadcast loop) so the supervisor can recover.
        """
        try:
            import cv2
            import numpy as np
            backend, cap = self._capture
            if backend == "dxcam":
                arr = cap.grab()
                if arr is None:
                    return None
            elif backend == "mss":
                with cap as sct:
                    m = sct.monitors[1]
                    img = sct.grab(m)
                    arr = np.frombuffer(img.rgb, dtype=np.uint8).reshape(
                        img.height, img.width, 3)
            elif backend == "pil":
                from PIL import ImageGrab
                img = ImageGrab.grab()
                arr = np.array(img)[..., :3]  # drop alpha
            else:
                return None
            # Convert RGB → BGR for cv2.imencode (all backends give RGB)
            arr = cv2.cvtColor(arr, cv2.COLOR_RGB2BGR)
            return self._resize(arr)
        except Exception as e:
            # v1.6.0: never let a capture exception kill the broadcast loop.
            # The supervisor wraps _broadcast_loop and restarts it on crash,
            # so we want to return None (skip frame) and let it keep running.
            log.warning("[Mirror] _grab_frame error: %s", e)
            return None

    def _resize(self, arr):
        """Scale down by self.scale. arr is already BGR."""
        if self.scale >= 0.999:
            return arr
        import cv2
        w = int(arr.shape[1] * self.scale)
        h = int(arr.shape[0] * self.scale)
        return cv2.resize(arr, (w, h), interpolation=cv2.INTER_AREA)

    def _encode_jpeg(self, arr) -> Optional[bytes]:
        """Encode a BGR numpy array to JPEG bytes."""
        try:
            if self._encoder[0] == "cv2":
                import cv2
                ok, buf = cv2.imencode(
                    ".jpg", arr,
                    [int(cv2.IMWRITE_JPEG_QUALITY), int(self.quality)],
                )
                return buf.tobytes() if ok else None
            else:
                from PIL import Image
                from io import BytesIO
                import cv2
                # Pillow expects RGB; convert back from BGR
                rgb = cv2.cvtColor(arr, cv2.COLOR_BGR2RGB)
                img = Image.fromarray(rgb)
                buf = BytesIO()
                img.save(buf, format="JPEG", quality=int(self.quality))
                return buf.getvalue()
        except Exception as e:
            log.debug("[Mirror] encode failed: %s", e)
            return None

    # ------------------------------------------------------------------
    # Broadcast loop
    # ------------------------------------------------------------------
    async def _broadcast_loop(self):
        frame_interval = 1.0 / max(1, self.target_fps)
        last_fps_check = time.time()
        frames_in_window = 0
        while self.running:
            t0 = time.time()
            try:
                arr = await asyncio.to_thread(self._grab_frame)
                if arr is None:
                    await asyncio.sleep(0.05)
                    continue
                jpeg = await asyncio.to_thread(self._encode_jpeg, arr)
                if jpeg is None:
                    await asyncio.sleep(0.05)
                    continue
                # 11-byte frame header (v1.2): type(1) + jpeg_len(4)
                # + mouse_x(2) + mouse_y(2) + cursor_visible(1) + reserved(1)
                mx, my, cur_vis = self._get_cursor_state()
                mx = max(0, min(65535, int(mx)))
                my = max(0, min(65535, int(my)))
                header = struct.pack(">BIHHBB",
                                     0x01,            # type 1 = frame
                                     len(jpeg),
                                     mx, my,
                                     1 if cur_vis else 0,
                                     0)
                # Pack into one binary frame
                payload = header + jpeg
                if self.clients:
                    await asyncio.gather(
                        *(self._safe_send(c, payload) for c in list(self.clients)),
                        return_exceptions=True,
                    )
                    self.stats["frames_sent"] += 1
                    self.stats["bytes_sent"] += len(payload)
                    self.stats["frame_bytes"] = len(jpeg)
                frames_in_window += 1
            except Exception as e:
                log.exception("[Mirror] broadcast error: %s", e)
            # FPS update (1 Hz)
            now = time.time()
            if now - last_fps_check >= 1.0:
                self.stats["fps"] = frames_in_window / (now - last_fps_check)
                self.stats["clients"] = len(self.clients)
                frames_in_window = 0
                last_fps_check = now
            # Sleep until next frame slot
            elapsed = time.time() - t0
            sleep_for = max(0, frame_interval - elapsed)
            if sleep_for > 0:
                await asyncio.sleep(sleep_for)

    def _get_cursor_state(self):
        # v1.6.5: ALWAYS return True for cursor_visible so the tablet 
        # overlay always draws the PC cursor. The server has no way to
        # know whether a stylus is being used — that's a tablet-side
        # decision. Force it ON.
        try:
            import pyautogui
            x, y = pyautogui.position()
            return (min(65535, max(0, int(x))),
                    min(65535, max(0, int(y))),
                    True)
        except Exception:
            return (0, 0, True)  # even on error, say visible

    async def _safe_send(self, ws, payload):
        try:
            await ws.send(payload)
        except Exception:
            pass

    # ------------------------------------------------------------------
    # Client connection
    # ------------------------------------------------------------------
    async def _on_client(self, ws, path: str = ""):
        addr = "?"
        try:
            addr = "%s:%d" % ws.remote_address[:2]
        except Exception:
            pass
        log.info("[Mirror] client connected: %s", addr)
        self.clients.add(ws)
        # Send a one-time hello + screen info
        try:
            await ws.send(json.dumps({
                "type": "hello",
                "version": VERSION,
                "mode": "mirror",
                "screen": {
                    "width": self._screen_size[0] if self._screen_size else 0,
                    "height": self._screen_size[1] if self._screen_size else 0,
                    "scale": self.scale,
                    "quality": self.quality,
                    "target_fps": self.target_fps,
                },
            }))
        except Exception:
            pass
        try:
            async for raw in ws:
                await self._handle_input(raw, ws)
        except Exception:
            pass
        finally:
            log.info("[Mirror] client disconnected: %s", addr)
            self.clients.discard(ws)

    # ------------------------------------------------------------------
    # Input router
    # ------------------------------------------------------------------
    async def _handle_input(self, raw, ws=None):
        if isinstance(raw, (bytes, bytearray)):
            if not raw:
                return
            t = raw[0]
            if t == 0x10:  # touch path binary
                await asyncio.to_thread(self._handle_touch_path_binary, raw)
            elif t == 0x11:  # pinch
                await asyncio.to_thread(self._handle_pinch, raw)
            else:
                log.debug("[Mirror] unknown binary op %d", t)
            self.stats["touches"] += 1
            return
        # JSON
        try:
            obj = json.loads(raw)
        except Exception:
            return
        # Hermes JSON uses "t" as the key; Mirror-style uses "type".
        # Accept whichever is present so the same socket carries both.
        t = obj.get("type") or obj.get("t")
        if t == "m":
            # Hermes relative mouse move
            await asyncio.to_thread(
                self._do_move_rel, int(obj.get("x", 0)), int(obj.get("y", 0))
            )
            return
        if t == "c":
            await asyncio.to_thread(self._do_click_button, int(obj.get("b", 0)))
            return
        if t == "s":
            await asyncio.to_thread(self._do_scroll_v, int(obj.get("v", 0)))
            return
        if t == "k":
            await asyncio.to_thread(
                self._do_key, str(obj.get("k", "")), bool(obj.get("p", True))
            )
            return
        # legacy Mirror commands still use "type"
        t = obj.get("type")
        if t == "mirror_config":
            # v1.5.8: in-app config from Hermes APK
            key = str(obj.get("key", ""))
            val = obj.get("value")
            if key == "quality":
                self.quality = int(val)
                log.info("[Mirror] config quality=%d", self.quality)
            elif key == "scale":
                self.scale = float(val)
                log.info("[Mirror] config scale=%.2f", self.scale)
            elif key == "fps":
                self.target_fps = int(val)
                log.info("[Mirror] config target_fps=%d", self.target_fps)
            elif key == "auto_adjust":
                self.auto_adjust = bool(int(val))
                log.info("[Mirror] config auto_adjust=%s", self.auto_adjust)
            elif key == "encoder":
                log.info("[Mirror] config encoder=%s (no-op, auto-selected)", val)
            return
        if t == "click_request":
            await asyncio.to_thread(self._do_click_request, obj.get("button", "left"))
        elif t == "cursor":
            self._set_remote_cursor_visible(bool(obj.get("visible", True)))
        elif t == "ping":
            pass
        elif t == "touch":
            # v1.6.0: process touch events so MirrorScreen touch mode
            # works without flooding the server with unknown messages.
            await asyncio.to_thread(self._handle_touch_json, raw)
        else:
            log.debug("[Mirror] unknown msg type %r", t)

    # ------------------------------------------------------------------
    # v1.6.0: handle JSON touch events (from old MirrorScreen touch mode).
    # The Android client sends {"type":"touch","x":...,"y":...,"action":...}
    # for each finger event. We process them the same as hermes-style moves
    # and clicks — pyautogui.moveRel for "move" and pyautogui.click for
    # "down"/"up"/"click". This stops the "unknown message" flood when
    # the user switches to Cursor/Caneta/Desenhar touch mode.
    # ------------------------------------------------------------------
    async def _handle_touch_json(self, raw):
        """Parse a {"type":"touch","x":...,"y":...,"action":...,...} packet.
        Coordinates are in *normalised* [0..1] screen space from the
        Android TouchHandler. We convert back to absolute pixels using
        the known screen size."""
        try:
            obj = json.loads(raw)
        except Exception:
            return
        action = obj.get("action", "")
        nx = float(obj.get("x", 0))
        ny = float(obj.get("y", 0))
        # Normalised coords → absolute pixels
        sw, sh = self._screen_size or (1920, 1080)
        abs_x = int(nx * sw)
        abs_y = int(ny * sh)
        try:
            import pyautogui
        except ImportError:
            return
        if action in ("down", "click"):
            pyautogui.click(abs_x, abs_y, _pause=False)
        elif action == "up":
            pass  # no-op: mouse button was already released virtually
        elif action == "move":
            # For a single move point, jump directly (no interpolator here;
            # the client already rate-limits moves).
            pyautogui.moveTo(abs_x, abs_y, _pause=False)
        elif action == "drag":
            pyautogui.dragTo(abs_x, abs_y, button="left", _pause=False)
        # "click" already handled above; if any other action, ignore.

    # ------------------------------------------------------------------

    def _handle_touch_path_binary(self, raw):
        """0x10 [count:u8] [(x:u16 LE)(y:u16 LE) ...]"""
        if len(raw) < 2:
            return
        count = raw[1]
        expected = 2 + count * 4
        if len(raw) < expected:
            return
        try:
            import pyautogui
        except ImportError:
            return
        for i in range(count):
            off = 2 + i * 4
            x, y = struct.unpack("<HH", raw[off:off + 4])
            pyautogui.moveRel(int(x), int(y), _pause=False)

    def _handle_pinch(self, raw):
        """0x11 [scale:f32 LE][cx:f32 LE][cy:f32 LE]"""
        if len(raw) < 13:
            return
        scale, cx, cy = struct.unpack("<fff", raw[1:13])
        try:
            import pyautogui
        except ImportError:
            return
        if scale == 0:
            return
        # Move to center first
        sw, sh = self._screen_size or (1920, 1080)
        tx = int(cx * sw)
        ty = int(cy * sh)
        pyautogui.moveTo(tx, ty, _pause=False)
        # Each 5% of pinch = 1 scroll notch
        delta_pct = (scale - 1.0) * 100.0
        notches = int(delta_pct / 5.0)
        if notches != 0:
            with pyautogui.hold("ctrl"):
                pyautogui.scroll(-notches, _pause=False)

    def _do_click_request(self, button: str):
        try:
            import pyautogui
        except ImportError:
            return
        x, y = pyautogui.position()
        if button == "double":
            pyautogui.doubleClick(x, y, _pause=False)
        elif button == "right":
            pyautogui.click(button="right", _pause=False)
        elif button == "middle":
            pyautogui.click(button="middle", _pause=False)
        else:
            pyautogui.click(button="left", _pause=False)
        log.info("[Mirror] click_request %s @ (%d,%d)", button, x, y)

    def _set_remote_cursor_visible(self, visible: bool):
        # Stored on the server only for stats; the per-frame header carries
        # this flag to the client.
        self._cursor_visible = visible

    # ------------------------------------------------------------------
    # v1.5.5 Hybrid Hermes command handlers
    # ------------------------------------------------------------------
    def _do_move_rel(self, dx: int, dy: int):
        """Relative mouse move (from Hermes 'm' packet)."""
        try:
            import pyautogui
        except ImportError:
            return
        if dx or dy:
            pyautogui.moveRel(int(dx), int(dy), _pause=False)

    def _do_click_button(self, button_num: int):
        """Click by numeric id (Hermes convention): 0=L, 1=R, 2=M, 3=double."""
        try:
            import pyautogui
        except ImportError:
            return
        if button_num == 3:
            pyautogui.doubleClick(_pause=False)
        elif button_num == 1:
            pyautogui.click(button="right", _pause=False)
        elif button_num == 2:
            pyautogui.click(button="middle", _pause=False)
        else:
            pyautogui.click(_pause=False)

    def _do_scroll_v(self, v: int):
        """Vertical scroll (Hermes 's' packet)."""
        try:
            import pyautogui
        except ImportError:
            return
        if v:
            pyautogui.scroll(int(v), _pause=False)

    def _do_key(self, key: str, press: bool):
        """Key press/release (Hermes 'k' packet)."""
        if not key:
            return
        try:
            import pyautogui
        except ImportError:
            return
        if press:
            pyautogui.keyDown(key)
        else:
            pyautogui.keyUp(key)


# ---------------------------------------------------------------------------
# Combined launcher
# ---------------------------------------------------------------------------
def parse_args():
    p = argparse.ArgumentParser(description=f"MirrorX v{VERSION}")
    p.add_argument("--hermes", action="store_true",
                   help="Run in Hermes mode (mouse-only, no video).")
    p.add_argument("--port", type=int, default=DEFAULT_PORT,
                   help=f"WebSocket port (default {DEFAULT_PORT}).")
    p.add_argument("--host", default="0.0.0.0",
                   help="Bind host (default 0.0.0.0).")
    p.add_argument("--no-panel", action="store_true",
                   help="Disable Tkinter control panel (enabled by default).")
    p.add_argument("--no-encoding", action="store_true",
                   help="Disable UTF-8 fix (not recommended).")
    return p.parse_args()


def setup_logging(verbose: bool = False):
    level = logging.DEBUG if verbose else logging.INFO
    logging.basicConfig(
        level=level,
        format="%(asctime)s [%(name)s] %(levelname)s %(message)s",
        datefmt="%H:%M:%S",
    )
    # Force UTF-8 in stdout so emoji/logs don't crash on Windows
    if hasattr(sys.stdout, "reconfigure"):
        try:
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
            sys.stderr.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass
    os.environ.setdefault("PYTHONIOENCODING", "utf-8")


def _get_local_ip():
    """Discover the primary LAN IP of this machine."""
    try:
        import socket
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def _mirror_snapshot(mirror) -> dict:
    """Build a stats dict for the v1.5.2 control panel from MirrorMode."""
    st = mirror.stats or {}
    # Bandwidth (bytes/s → bits/s)
    total_sec = max(0.001, time.time() - st.get("started_at", time.time()))
    bandwidth_bps = st.get("bytes_sent", 0) / total_sec
    # Cursor position
    cursor = (0, 0)
    try:
        import pyautogui
        cursor = pyautogui.position()
    except Exception:
        pass
    return {
        "fps": float(st.get("fps", 0.0)),
        "clients": int(st.get("clients", 0)),
        "screen_size": mirror._screen_size,
        "scale": float(mirror.scale),
        "quality": int(mirror.quality),
        "frame_bytes": int(st.get("frame_bytes", 0)),
        "bandwidth_bps": float(bandwidth_bps),
        "cursor": cursor,
        "encoder": getattr(mirror, "_encoder_name", "—"),
    }


def run_panel_v152(mode: str, server_obj, port: int, host: str,
                   on_stop: Callable[[], None]):
    """Modern v1.5.2 control panel (customtkinter).

    Replaces run_panel() from v1.4.3-era code. Supports both mirror and
    hermes modes. The panel Tk mainloop MUST run on the main thread
    (customtkinter crashes if not). The asyncio loop should run in a
    daemon thread that pushes stats via panel.update_stats() / log_event().
    """
    from panel_ui import ControlPanel  # local import, panel_ui.py
    return ControlPanel(
        mode=mode,
        server_obj=server_obj,
        port=port,
        host=host,
        on_stop=on_stop,
        version=VERSION,
    )


def _spawn_stats_pumper(get_stats, panel, interval_s: float = 0.5):
    """Background thread that calls panel.update_stats(get_stats()) every
    `interval_s` seconds. Used to keep the UI live without coupling the
    asyncio loop to Tk."""
    import threading
    stopped = threading.Event()

    def loop():
        while not stopped.is_set():
            try:
                snap = get_stats()
                panel.update_stats(snap)
            except Exception as e:
                log.warning("stats pumper error: %s", e)
            stopped.wait(interval_s)

    t = threading.Thread(target=loop, name="stats-pump", daemon=True)
    t.start()
    return stopped.set  # call to stop


def run_panel(mirror: MirrorMode, port: int):
    """Tkinter control panel — always shown by default in mirror mode.
    
    v1.5.0: matches the v1.4.3 panel with:
    - Network card: PC IP + tablet URL
    - Stream card: FPS combobox, Quality slider, Scale combobox
    - Live stats: FPS, clients, screen info, frame size, bandwidth, cursor
    - Auto-adjust toggle
    - Stop server button
    """
    try:
        import tkinter as tk
        from tkinter import ttk, messagebox
    except ImportError:
        log.warning("Tkinter not available — control panel disabled")
        return None

    BG = "#1a1a2e"
    FG = "#e0e0e0"
    ACCENT = "#e94560"
    DIM = "#8892b0"
    CARD_BG = "#16213e"
    CARD_BORDER = "#0f3460"

    FPS_OPTIONS = [15, 24, 30, 45, 60, 90]
    SCALE_OPTIONS = [0.50, 0.75, 1.00]

    root = tk.Tk()
    root.title(f"MirrorX v{VERSION}")
    root.geometry("440x680")
    root.configure(bg=BG)
    root.resizable(False, False)

    style = ttk.Style()
    style.theme_use("clam")
    style.configure(".", background=BG, foreground=FG,
                    fieldbackground=CARD_BG)
    style.configure("TLabel", background=BG, foreground=FG,
                    font=("Segoe UI", 10))
    style.configure("TFrame", background=BG)
    style.configure("Card.TFrame", background=CARD_BG)
    style.configure("Card.TLabel", background=CARD_BG, foreground=FG,
                    font=("Consolas", 10))
    style.configure("CardTitle.TLabel", background=CARD_BG,
                    foreground=ACCENT, font=("Segoe UI", 11, "bold"))
    style.configure("Header.TLabel", font=("Segoe UI", 16, "bold"),
                    background=BG, foreground=ACCENT)
    style.configure("Subheader.TLabel", font=("Segoe UI", 9),
                    background=BG, foreground=DIM)
    style.configure("Stop.TButton", background="#c0392b", foreground="white",
                    font=("Segoe UI", 10, "bold"))
    style.map("Stop.TButton",
              background=[("active", "#e74c3c")])
    style.configure("TCheckbutton", background=BG, foreground=FG,
                    font=("Segoe UI", 9))
    style.map("TCheckbutton", background=[("active", BG)])

    # ── Header ──
    ttk.Label(root, text=f"MirrorX v{VERSION}", style="Header.TLabel").pack(
        pady=(12, 2))
    ttk.Label(root, text=f"Mode: mirror (v1.4.3 fallback)",
              style="Subheader.TLabel").pack()

    local_ip = _get_local_ip()
    tablet_url = f"http://{local_ip}:{port}"

    # ── Network Card ──
    net_card = tk.Frame(root, bg=CARD_BG, highlightbackground=CARD_BORDER,
                        highlightthickness=1)
    net_card.pack(fill="x", padx=12, pady=(10, 4))
    ttk.Label(net_card, text="Rede", style="CardTitle.TLabel").pack(
        anchor="w", padx=10, pady=(6, 2))
    net_info = tk.Frame(net_card, bg=CARD_BG)
    net_info.pack(fill="x", padx=10, pady=(0, 6))

    tk.Label(net_info, text=f"PC IP:  {local_ip}", bg=CARD_BG, fg=FG,
             font=("Consolas", 11), anchor="w").pack(fill="x")
    tk.Label(net_info, text=f"Tablet: {tablet_url}", bg=CARD_BG, fg="#00d4aa",
             font=("Consolas", 11), anchor="w").pack(fill="x")

    # ── Stream Card ──
    stream_card = tk.Frame(root, bg=CARD_BG, highlightbackground=CARD_BORDER,
                           highlightthickness=1)
    stream_card.pack(fill="x", padx=12, pady=4)
    ttk.Label(stream_card, text="Stream", style="CardTitle.TLabel").pack(
        anchor="w", padx=10, pady=(6, 2))

    inner = tk.Frame(stream_card, bg=CARD_BG)
    inner.pack(fill="x", padx=10, pady=(0, 6))

    # FPS
    fps_row = tk.Frame(inner, bg=CARD_BG)
    fps_row.pack(fill="x", pady=2)
    tk.Label(fps_row, text="FPS:", bg=CARD_BG, fg=FG,
             font=("Segoe UI", 10), width=8, anchor="w").pack(side="left")
    fps_var = tk.IntVar(value=mirror.target_fps)
    fps_combo = ttk.Combobox(fps_row, values=FPS_OPTIONS, width=5,
                             textvariable=fps_var, state="readonly")
    fps_combo.pack(side="left")
    def on_fps_change(*_):
        mirror.target_fps = fps_var.get()
    fps_combo.bind("<<ComboboxSelected>>", on_fps_change)

    # Quality
    qual_row = tk.Frame(inner, bg=CARD_BG)
    qual_row.pack(fill="x", pady=2)
    tk.Label(qual_row, text="Quality:", bg=CARD_BG, fg=FG,
             font=("Segoe UI", 10), width=8, anchor="w").pack(side="left")
    qual_var = tk.IntVar(value=int(mirror.quality))
    qual_display = tk.Label(qual_row, text=f"{qual_var.get()}%",
                            bg=CARD_BG, fg=FG, font=("Consolas", 10), width=4)
    qual_display.pack(side="right")
    qual_scale = tk.Scale(qual_row, from_=20, to=95, orient="horizontal",
                          variable=qual_var, bg=CARD_BG, fg=FG,
                          highlightthickness=0, troughcolor=CARD_BORDER,
                          command=lambda v: (
                              setattr(mirror, 'quality', int(float(v))),
                              qual_display.config(text=f"{int(float(v))}%")
                          ))
    qual_scale.pack(side="left", fill="x", expand=True)

    # Scale
    scale_row = tk.Frame(inner, bg=CARD_BG)
    scale_row.pack(fill="x", pady=2)
    tk.Label(scale_row, text="Scale:", bg=CARD_BG, fg=FG,
             font=("Segoe UI", 10), width=8, anchor="w").pack(side="left")
    scale_var = tk.DoubleVar(value=mirror.scale)
    scale_combo = ttk.Combobox(scale_row,
                               values=[f"{s:.0%}" for s in SCALE_OPTIONS],
                               width=5, state="readonly")
    scale_combo.current(SCALE_OPTIONS.index(mirror.scale)
                        if mirror.scale in SCALE_OPTIONS else 1)
    scale_combo.pack(side="left")
    def on_scale_change(*_):
        idx = scale_combo.current()
        mirror.scale = SCALE_OPTIONS[idx]
    scale_combo.bind("<<ComboboxSelected>>", on_scale_change)

    # Auto-adjust
    auto_var = tk.BooleanVar(value=False)
    ttk.Checkbutton(inner, text="Ajuste automático baseado no FPS",
                    variable=auto_var).pack(anchor="w", pady=(4, 2))

    # ── Stats Card ──
    stats_card = tk.Frame(root, bg=CARD_BG, highlightbackground=CARD_BORDER,
                          highlightthickness=1)
    stats_card.pack(fill="x", padx=12, pady=4)
    ttk.Label(stats_card, text="Status em tempo real",
              style="CardTitle.TLabel").pack(anchor="w", padx=10, pady=(6, 2))

    stats_inner = tk.Frame(stats_card, bg=CARD_BG)
    stats_inner.pack(fill="x", padx=10, pady=(0, 6))

    def make_label(parent, text, fg=FG):
        lbl = tk.Label(parent, text=text, bg=CARD_BG, fg=fg,
                       font=("Consolas", 10), anchor="w")
        lbl.pack(fill="x")
        return lbl

    lbl_fps = make_label(stats_inner, "FPS: --")
    lbl_clients = make_label(stats_inner, "Clientes: 0")
    lbl_screen = make_label(stats_inner, "Tela: -- x --")
    lbl_stream = make_label(stats_inner, "Stream: -- x --")
    lbl_frame = make_label(stats_inner, "Tamanho médio: -- KB")
    lbl_banda = make_label(stats_inner, "Banda: -- KB/s")
    lbl_cursor = make_label(stats_inner, "Cursor: (0, 0)")
    lbl_mode = make_label(stats_inner, "Modo: mirror", fg="#00d4aa")

    def update_stats():
        st = mirror.stats
        fps = st['fps']
        clients = st['clients']
        frame_kb = st['frame_bytes'] / 1024 if st['frame_bytes'] else 0
        total_sec = max(1, time.time() - st['started_at'])
        band_kbs = (st['bytes_sent'] / 1024) / total_sec

        lbl_fps.config(text=f"FPS: {fps:.1f}",
                       fg="white" if fps >= 25 else ACCENT)
        lbl_clients.config(text=f"Clientes: {clients}")

        sw, sh = mirror._screen_size or (0, 0)
        lbl_screen.config(text=f"Tela: {sw} x {sh}")

        if sw and sh:
            ssw, ssh = int(sw * mirror.scale), int(sh * mirror.scale)
            lbl_stream.config(text=f"Stream: {ssw} x {ssh} @ "
                                   f"scale {mirror.scale:.0%} q={int(mirror.quality)}")
        lbl_frame.config(text=f"Tamanho médio: {frame_kb:.1f} KB")
        lbl_banda.config(text=f"Banda: {band_kbs:.0f} KB/s")

        try:
            import pyautogui
            x, y = pyautogui.position()
            lbl_cursor.config(text=f"Cursor: ({x}, {y})")
        except Exception:
            pass

        lbl_mode.config(text=f"Modo: mirror v{VERSION}", fg="#00d4aa")

        root.after(500, update_stats)

    root.after(500, update_stats)

    # ── Stop Button ──
    stop_btn = ttk.Button(root, text="Parar servidor", style="Stop.TButton",
                          command=lambda: (
                              setattr(mirror, 'running', False),
                              root.destroy()
                          ))
    stop_btn.pack(pady=(12, 8))

    return root


def main():
    args = parse_args()
    setup_logging()

    print("=" * 60)
    print(f"  MirrorX v{VERSION} — "
          f"{'Hermes (mouse-only)' if args.hermes else 'Mirror (v1.4.3)'}")
    print(f"  Listening on ws://{args.host}:{args.port}/")
    print("=" * 60)

    if args.hermes:
        if not HAS_HERMES:
            print("ERROR: server_hermes.py not found", file=sys.stderr)
            return 1
        hermes = HermesServer(port=args.port, host=args.host)
        # Build the panel FIRST (customtkinter requires main thread for
        # its mainloop). If --no-panel is set, fall back to console mode.
        if not args.no_panel:
            panel = run_panel_v152(
                mode="hermes", server_obj=hermes,
                port=args.port, host=args.host,
                on_stop=lambda: setattr(hermes, "_stop_requested", True),
            )
            panel.log_event("MirrorX v1.5.2 — Hermes pronto", "ok")
            panel.log_event(f"WebSocket escutando em :{args.port}", "info")
            # Start the asyncio WS server in a daemon thread.
            import threading

            stop_evt = threading.Event()

            def _server_thread():
                try:
                    asyncio.run(hermes.start())
                except Exception as e:
                    log.exception("[Hermes] server crashed: %s", e)
                    try:
                        panel.log_event(f"Servidor crashou: {e}", "bad")
                    except Exception:
                        pass

            t = threading.Thread(target=_server_thread, name="hermes-loop",
                                 daemon=True)
            t.start()
            # Stats pumper (background thread → panel)
            _spawn_stats_pumper(hermes.snapshot, panel, interval_s=0.5)
            # Panel mainloop on main thread (blocks until window closed)
            try:
                panel.start()
            except KeyboardInterrupt:
                pass
            stop_evt.set()
            return 0
        # No-panel branch: plain asyncio
        try:
            asyncio.run(hermes.start())
        except KeyboardInterrupt:
            print("\n[Hermes] stopped by user")
        return 0

    # ── Mirror mode ──────────────────────────────────────────────────
    mirror = MirrorMode(port=args.port, host=args.host)
    if not args.no_panel:
        panel = run_panel_v152(
            mode="mirror", server_obj=mirror,
            port=args.port, host=args.host,
            on_stop=lambda: setattr(mirror, "running", False),
        )
        panel.log_event("MirrorX v1.5.2 — Mirror pronto", "ok")
        panel.log_event(f"WebSocket escutando em :{args.port}", "info")
        import threading

        def _server_thread():
            try:
                asyncio.run(mirror.start())
            except Exception as e:
                log.exception("[Mirror] server crashed: %s", e)
                try:
                    panel.log_event(f"Servidor crashou: {e}", "bad")
                except Exception:
                    pass

        t = threading.Thread(target=_server_thread, name="mirror-loop",
                             daemon=True)
        t.start()
        _spawn_stats_pumper(
            lambda: _mirror_snapshot(mirror), panel, interval_s=0.5)
        try:
            panel.start()
        except KeyboardInterrupt:
            mirror.running = False
        return 0
    # No-panel mirror: original behavior
    try:
        asyncio.run(mirror.start())
    except KeyboardInterrupt:
        print("\n[Mirror] stopped by user")
    return 0


if __name__ == "__main__":
    sys.exit(main())
