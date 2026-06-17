"""
MirrorX v1.0.5 — PC Screen Mirroring to Tablet
Captures screen via DXGI, streams via WebSocket, receives touch input.

v1.0.5 changes:
  - Interactive Tkinter control panel (FPS, quality, scale, live stats)
  - Mouse cursor tracking — position is sent with each frame so the
    tablet can overlay a cursor on the captured image
  - Frame format: type(1) + jpeg_len(4) + mouse_x(2) + mouse_y(2) + jpeg
  - Settings can be changed live from the panel (no restart needed)
  - Removed aggressive auto-quality (user is in control now)
  - Unbuffered output, non-blocking send, dynamic quality floor raised
"""
import asyncio
import struct
import json
import time
import socket
import sys
import os
import threading
import tkinter as tk
from tkinter import ttk
from pathlib import Path

# Force unbuffered output so we see logs in real-time
# (stdout is None when running as --windowed .exe)
if sys.stdout is not None:
    sys.stdout.reconfigure(line_buffering=True)
if sys.stderr is not None:
    sys.stderr.reconfigure(line_buffering=True)

import dxcam
import cv2
import numpy as np
import websockets
import pyautogui

# --- Config ---
PORT = 9900
HTTP_PORT = 8080

# Defaults — overridden by the ControlPanel at startup
DEFAULTS = {
    "fps": 30,
    "quality": 75,
    "scale": 0.75,
    "encoding": "JPEG",   # future: "WebP"
}

# Bounds
FPS_OPTIONS = [15, 24, 30, 45, 60, 90]
SCALE_OPTIONS = [0.25, 0.50, 0.75, 1.00]
QUALITY_MIN, QUALITY_MAX = 20, 95

# Disable pyautogui fail-safe (corner throw)
pyautogui.FAILSAFE = False


def log(msg):
    """Print with immediate flush (safe for --windowed exe)."""
    try:
        print(msg, flush=True)
    except Exception:
        pass


# --- Live settings (shared between asyncio loop and Tkinter panel) ---

class Settings:
    """Thread-safe live settings shared by server + control panel."""

    def __init__(self):
        self._lock = threading.Lock()
        self._vals = dict(DEFAULTS)
        self.auto_adjust = True
        self.low_fps_threshold = 20
        self.high_fps_threshold = 28

    def get(self, key):
        with self._lock:
            return self._vals.get(key, DEFAULTS.get(key))

    def set(self, key, value):
        with self._lock:
            if key in DEFAULTS:
                self._vals[key] = value

    def all(self):
        with self._lock:
            return dict(self._vals)


# --- Mouse position cache (read by capture loop, updated by touch handler) ---

class MouseTracker:
    """Tracks the most recent mouse position. Updated by:
       - pyautogui queries in the capture loop (passive, every frame)
       - touch handler (active, on every tablet touch)

    Read by the capture loop to include position in each frame header.
    """

    def __init__(self):
        self._x = 0
        self._y = 0
        self._lock = threading.Lock()

    def set(self, x: int, y: int):
        with self._lock:
            self._x, self._y = x, y

    def get(self) -> tuple[int, int]:
        with self._lock:
            return (self._x, self._y)


# --- Tkinter control panel ---

class ControlPanel:
    """Dark-themed control window. Runs on the main thread; reads/writes
    Settings and displays live stats from the server. Closes cleanly on
    window close (also quits the asyncio loop)."""

    def __init__(self, settings: Settings, server: "MirrorServer"):
        self.settings = settings
        self.server = server

        self.root = tk.Tk()
        self.root.title("MirrorX v1.0.5 — Painel de Controle")
        self.root.geometry("440x620")
        self.root.configure(bg="#0A0A0B")
        self.root.minsize(420, 580)

        # Try to load the same icon used by the .exe (if present)
        try:
            ico = Path(__file__).parent / "assets" / "icons" / "mirrorx.ico"
            if ico.exists():
                self.root.iconbitmap(str(ico))
        except Exception:
            pass

        self._build_ui()

        # Closing the window also stops the server
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)

        # Schedule the stats refresh (every 500ms)
        self._tick()

    # ---------- UI construction ----------

    def _build_ui(self):
        BG = "#0A0A0B"
        SURFACE = "#141416"
        BORDER = "#25252D"
        TEXT = "#E8E8ED"
        DIM = "#A0A0B0"
        ACCENT = "#6366F1"
        GREEN = "#22C55E"
        RED = "#EF4444"
        YELLOW = "#EAB308"

        # Style the ttk widgets
        style = ttk.Style()
        style.theme_use("clam")
        style.configure("TS.Horizontal.TScale", troughcolor=BORDER,
                        background=ACCENT, darkcolor=ACCENT, lightcolor=ACCENT)
        style.configure("TCombobox", fieldbackground=SURFACE, background=SURFACE,
                        foreground=TEXT, arrowcolor=ACCENT, bordercolor=BORDER)
        style.configure("TCheckbutton", background=SURFACE, foreground=TEXT)
        style.map("TCheckbutton", background=[("active", SURFACE)])

        # Outer container
        outer = tk.Frame(self.root, bg=BG, padx=20, pady=20)
        outer.pack(fill=tk.BOTH, expand=True)

        # Title
        tk.Label(outer, text="MirrorX", font=("Segoe UI", 22, "bold"),
                 fg=ACCENT, bg=BG).pack(anchor=tk.W)
        tk.Label(outer, text="v1.0.5 — Painel interativo", font=("Segoe UI", 10),
                 fg=DIM, bg=BG).pack(anchor=tk.W, pady=(0, 12))

        # --- Network card ---
        self._card(outer, "Rede").pack(fill=tk.X, pady=(0, 10))
        net = self._card_body(outer.winfo_children()[-1])
        self.ip_label = tk.Label(net, text="--", font=("Consolas", 14, "bold"),
                                 fg=GREEN, bg=SURFACE, anchor=tk.W)
        self.ip_label.pack(fill=tk.X, pady=(0, 4))
        self.url_label = tk.Label(net, text="--", font=("Consolas", 10),
                                  fg=DIM, bg=SURFACE, anchor=tk.W)
        self.url_label.pack(fill=tk.X)

        # --- Stream card (FPS / Quality / Scale) ---
        self._card(outer, "Stream").pack(fill=tk.X, pady=(0, 10))
        stream = self._card_body(outer.winfo_children()[-1])

        # FPS
        tk.Label(stream, text="FPS alvo", font=("Segoe UI", 10),
                 fg=TEXT, bg=SURFACE).pack(anchor=tk.W)
        self.fps_var = tk.IntVar(value=self.settings.get("fps"))
        fps_frame = tk.Frame(stream, bg=SURFACE); fps_frame.pack(fill=tk.X, pady=(2, 8))
        self.fps_combo = ttk.Combobox(fps_frame, textvariable=self.fps_var,
                                      values=FPS_OPTIONS, state="readonly", width=10)
        self.fps_combo.pack(side=tk.LEFT)
        self.fps_combo.bind("<<ComboboxSelected>>", self._on_fps_change)
        tk.Label(fps_frame, text="(15 = leve / 60 = fluido / 90+ = gamer)",
                 font=("Segoe UI", 9), fg=DIM, bg=SURFACE).pack(side=tk.LEFT, padx=10)

        # Quality
        tk.Label(stream, text=f"Qualidade JPEG: {self.settings.get('quality')}%",
                 font=("Segoe UI", 10), fg=TEXT, bg=SURFACE).pack(anchor=tk.W)
        self.quality_var = tk.IntVar(value=self.settings.get("quality"))
        q_frame = tk.Frame(stream, bg=SURFACE); q_frame.pack(fill=tk.X, pady=(2, 8))
        self.quality_scale = ttk.Scale(q_frame, from_=QUALITY_MIN, to=QUALITY_MAX,
                                       orient=tk.HORIZONTAL, variable=self.quality_var,
                                       style="TS.Horizontal.TScale",
                                       command=self._on_quality_change)
        self.quality_scale.pack(side=tk.LEFT, fill=tk.X, expand=True)
        self.quality_readout = tk.Label(q_frame, text=f"{self.quality_var.get()}%",
                                        font=("Consolas", 10, "bold"),
                                        fg=ACCENT, bg=SURFACE, width=5)
        self.quality_readout.pack(side=tk.LEFT, padx=(8, 0))
        tk.Label(stream, text="(maior = mais nítido, menor = mais leve)",
                 font=("Segoe UI", 9), fg=DIM, bg=SURFACE).pack(anchor=tk.W)

        # Scale
        tk.Label(stream, text="Escala da captura", font=("Segoe UI", 10),
                 fg=TEXT, bg=SURFACE).pack(anchor=tk.W, pady=(8, 0))
        self.scale_var = tk.DoubleVar(value=self.settings.get("scale"))
        s_frame = tk.Frame(stream, bg=SURFACE); s_frame.pack(fill=tk.X, pady=(2, 8))
        self.scale_combo = ttk.Combobox(s_frame, textvariable=self.scale_var,
                                        values=[f"{s*100:.0f}%" for s in SCALE_OPTIONS],
                                        state="readonly", width=10)
        self.scale_combo.pack(side=tk.LEFT)
        self.scale_combo.bind("<<ComboboxSelected>>", self._on_scale_change)
        tk.Label(s_frame, text="(25% = super leve / 75% = ideal / 100% = nativo)",
                 font=("Segoe UI", 9), fg=DIM, bg=SURFACE).pack(side=tk.LEFT, padx=10)

        # Auto-adjust toggle
        self.auto_var = tk.BooleanVar(value=self.settings.auto_adjust)
        ttk.Checkbutton(stream, text="Ajuste automático baseado no FPS",
                        variable=self.auto_var, command=self._on_auto_change,
                        style="TCheckbutton").pack(anchor=tk.W, pady=(4, 0))

        # --- Live stats card ---
        self._card(outer, "Status em tempo real").pack(fill=tk.X, pady=(0, 10))
        stats = self._card_body(outer.winfo_children()[-1])

        self.fps_live = self._stat_row(stats, "FPS atual", "--", ACCENT)
        self.clients_live = self._stat_row(stats, "Clientes conectados", "0", ACCENT)
        self.screen_live = self._stat_row(stats, "Tela (PC)", "--", TEXT)
        self.stream_live = self._stat_row(stats, "Stream (enviado)", "--", TEXT)
        self.frame_size_live = self._stat_row(stats, "Tamanho médio do frame", "--", TEXT)
        self.bandwidth_live = self._stat_row(stats, "Banda estimada", "--", TEXT)
        self.cursor_live = self._stat_row(stats, "Cursor (PC)", "--", DIM)

        # --- Action buttons ---
        btn_frame = tk.Frame(outer, bg=BG)
        btn_frame.pack(fill=tk.X, pady=(8, 0))
        self.stop_btn = tk.Button(btn_frame, text="Parar servidor",
                                  font=("Segoe UI", 10, "bold"),
                                  bg=RED, fg="white", activebackground="#DC2626",
                                  activeforeground="white", relief=tk.FLAT,
                                  padx=16, pady=8, command=self._on_close)
        self.stop_btn.pack(side=tk.RIGHT)

    def _card(self, parent, title):
        BG = "#0A0A0B"
        SURFACE = "#141416"
        BORDER = "#25252D"
        TEXT = "#E8E8ED"
        c = tk.Frame(parent, bg=SURFACE, bd=0, highlightthickness=1,
                     highlightbackground=BORDER, highlightcolor=BORDER)
        tk.Label(c, text=title, font=("Segoe UI", 11, "bold"),
                 fg=TEXT, bg=SURFACE, anchor=tk.W).pack(anchor=tk.W, padx=14, pady=(12, 0))
        return c

    def _card_body(self, card):
        SURFACE = "#141416"
        body = tk.Frame(card, bg=SURFACE)
        body.pack(fill=tk.X, padx=14, pady=(4, 14))
        return body

    def _stat_row(self, parent, label, value, value_color):
        SURFACE = "#141416"
        DIM = "#A0A0B0"
        TEXT = "#E8E8ED"
        row = tk.Frame(parent, bg=SURFACE)
        row.pack(fill=tk.X, pady=2)
        tk.Label(row, text=label, font=("Segoe UI", 10), fg=DIM,
                 bg=SURFACE, anchor=tk.W).pack(side=tk.LEFT)
        v = tk.Label(row, text=value, font=("Consolas", 10, "bold"),
                     fg=value_color, bg=SURFACE, anchor=tk.E)
        v.pack(side=tk.RIGHT)
        return v

    # ---------- Event handlers ----------

    def _on_fps_change(self, *_):
        v = int(self.fps_var.get())
        self.settings.set("fps", v)
        log(f"[MirrorX] [panel] FPS alvo -> {v}")

    def _on_quality_change(self, *_):
        v = int(self.quality_var.get())
        self.quality_readout.config(text=f"{v}%")
        self.settings.set("quality", v)
        # Live update the parent label too
        for child in self.root.winfo_children():
            pass  # no-op; the readout is enough

    def _on_scale_change(self, *_):
        sel = self.scale_var.get()
        # sel is the string label like "75%"; map to the float
        try:
            pct = float(str(sel).rstrip("%"))
            v = pct / 100.0
        except ValueError:
            return
        self.settings.set("scale", v)
        log(f"[MirrorX] [panel] Escala -> {v*100:.0f}%")

    def _on_auto_change(self):
        self.settings.auto_adjust = self.auto_var.get()
        log(f"[MirrorX] [panel] Auto-ajuste -> {'ON' if self.settings.auto_adjust else 'OFF'}")

    def _on_close(self):
        log("[MirrorX] Painel fechado — parando servidor")
        try:
            self.root.destroy()
        except Exception:
            pass
        # Stop the asyncio loop from another thread
        try:
            self.server.stop()
        except Exception:
            pass

    # ---------- Periodic UI refresh ----------

    def _tick(self):
        try:
            self._refresh_stats()
        except Exception:
            pass
        self.root.after(500, self._tick)

    def _refresh_stats(self):
        s = self.server.stats()
        # Top of the network card
        self.ip_label.config(text=f"PC: {s.get('local_ip', '--')}")
        self.url_label.config(
            text=f"Tablet: abra http://{s.get('local_ip', '--')}:{HTTP_PORT} no Chrome"
        )
        # Live stats
        fps = s.get("fps", 0)
        self.fps_live.config(
            text=f"{fps:.0f} FPS",
            fg=("#22C55E" if fps >= 25 else "#EAB308" if fps >= 15 else "#EF4444")
        )
        self.clients_live.config(text=str(s.get("clients", 0)))
        self.screen_live.config(text=f"{s.get('screen_w', 0)}x{s.get('screen_h', 0)}")
        self.stream_live.config(text=f"{s.get('stream_w', 0)}x{s.get('stream_h', 0)} @ {s.get('scale', 0)*100:.0f}%")
        fs = s.get("frame_size_kb", 0)
        self.frame_size_live.config(text=f"{fs:.1f} KB" if fs else "--")
        bw = s.get("bandwidth_mbps", 0)
        self.bandwidth_live.config(text=f"{bw:.1f} Mbps" if bw else "--")
        self.cursor_live.config(text=f"({s.get('mouse_x', 0)}, {s.get('mouse_y', 0)})")

    def run(self):
        self.root.mainloop()


# --- Mirror server ---

class MirrorServer:
    def __init__(self, settings: Settings, mouse: MouseTracker):
        self.settings = settings
        self.mouse = mouse
        self.camera = None
        self.clients: set = set()
        self.running = False
        self.screen_w = 1920
        self.screen_h = 1080
        self.stream_w = 0
        self.stream_h = 0

        # Cached JPEG encode params (updated when quality changes)
        self._jpeg_params = [int(cv2.IMWRITE_JPEG_QUALITY), int(self.settings.get("quality")),
                             int(cv2.IMWRITE_JPEG_OPTIMIZE), 0,
                             int(cv2.IMWRITE_JPEG_RST_INTERVAL), 0]
        self._last_quality = self.settings.get("quality")

        # FPS tracking
        self.sent_frame_count = 0
        self.bytes_sent = 0
        # Lifetime accumulators (never reset) for accurate KB/frame stat
        self.total_frames = 0
        self.total_bytes = 0
        self.last_fps_time = time.monotonic()
        self.current_fps = 0.0

        # Per-client send state (non-blocking)
        self._sending: dict = {}

    # ---------- Public API (called by ControlPanel) ----------

    def stats(self) -> dict:
        with self.mouse._lock:
            mx, my = self.mouse._x, self.mouse._y
        # KB/frame uses lifetime accumulators so the average is correct
        # regardless of the 1s/3s reset windows.
        avg_frame_kb = (self.total_bytes / 1024) / max(self.total_frames, 1) if self.total_frames else 0
        return {
            "local_ip": getattr(self, "_local_ip", "?"),
            "fps": self.current_fps,
            "clients": len(self.clients),
            "screen_w": self.screen_w,
            "screen_h": self.screen_h,
            "stream_w": self.stream_w,
            "stream_h": self.stream_h,
            "scale": self.settings.get("scale"),
            "quality": self.settings.get("quality"),
            "frame_size_kb": avg_frame_kb,
            "bandwidth_mbps": (avg_frame_kb * 1024 * 8 * self.current_fps) / 1_000_000 if self.current_fps else 0,
            "mouse_x": mx,
            "mouse_y": my,
        }

    def stop(self):
        self.running = False
        # Schedule loop cancellation from any thread
        try:
            loop = asyncio.get_event_loop()
            loop.call_soon_threadsafe(loop.stop)
        except Exception:
            pass

    # ---------- Internals ----------

    def get_local_ip(self) -> str:
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
        self.camera = dxcam.create(output_color="RGB")
        if self.camera is None:
            raise RuntimeError("dxcam failed to initialize. GPU with DXGI required.")
        self.screen_w = self.camera.width
        self.screen_h = self.camera.height
        self._update_stream_dims()
        target_fps = int(self.settings.get("fps"))
        self.camera.start(target_fps=target_fps)
        log(f"[MirrorX] Screen: {self.screen_w}x{self.screen_h}")
        log(f"[MirrorX] Streaming at: {self.stream_w}x{self.stream_h} ({self.settings.get('scale')*100:.0f}%)")
        log(f"[MirrorX] Quality: {self.settings.get('quality')}%  FPS target: {target_fps}")
        log(f"[MirrorX] Capture: continuous mode")

    def _update_stream_dims(self):
        s = self.settings.get("scale")
        self.stream_w = int(self.screen_w * s)
        self.stream_h = int(self.screen_h * s)

    def _maybe_apply_settings(self):
        """Apply settings that may have changed in the panel."""
        q = int(self.settings.get("quality"))
        if q != self._last_quality:
            self._jpeg_params[1] = q
            self._last_quality = q
        # Scale may have changed too
        s = self.settings.get("scale")
        if s != self._last_scale:
            self._last_scale = s
            self._update_stream_dims()
            log(f"[MirrorX] Scale changed -> {self.stream_w}x{self.stream_h}")

    def capture_frame(self) -> bytes | None:
        frame = self.camera.get_latest_frame()
        if frame is None:
            return None
        s = self.settings.get("scale")
        if s < 1.0:
            self.stream_w = int(self.screen_w * s)
            self.stream_h = int(self.screen_h * s)
            frame = cv2.resize(frame, (self.stream_w, self.stream_h),
                             interpolation=cv2.INTER_AREA)
        success, encoded = cv2.imencode('.jpg', frame, self._jpeg_params)
        if not success:
            return None
        return encoded.tobytes()

    def _handle_key(self, key: str):
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
            log(f"[MirrorX] Key error: {key} -> {e}")

    def handle_touch(self, data: dict):
        try:
            x_ratio = data.get("x", 0.5)
            y_ratio = data.get("y", 0.5)
            action = data.get("action", "move")
            target_x = int(x_ratio * self.screen_w)
            target_y = int(y_ratio * self.screen_h)

            # Update mouse cache (so next frame's cursor overlay is correct)
            self.mouse.set(target_x, target_y)

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
            log(f"[MirrorX] Touch error: {e}")

    def handle_config(self, data: dict):
        cmd = data.get("cmd", "")
        if cmd == "set_quality":
            self.settings.set("quality", max(QUALITY_MIN, min(QUALITY_MAX, int(data.get("value", DEFAULTS["quality"])))))
        elif cmd == "set_scale":
            self.settings.set("scale", max(0.25, min(1.0, float(data.get("value", DEFAULTS["scale"])))))
        return None

    async def handler(self, websocket):
        self.clients.add(websocket)
        self._sending[websocket] = False
        remote = websocket.remote_address
        log(f"[MirrorX] Client connected: {remote}")
        try:
            await websocket.send(json.dumps({
                "type": "screen_info",
                "width": self.screen_w,
                "height": self.screen_h,
                "stream_width": self.stream_w,
                "stream_height": self.stream_h,
                "aspect_ratio": self.screen_w / self.screen_h,
                "version": "1.0.5",
            }))
        except Exception as e:
            log(f"[MirrorX] Failed to send screen_info: {e}")
            self.clients.discard(websocket)
            return
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
                        self.handle_config(data)
                except json.JSONDecodeError:
                    pass
        except websockets.exceptions.ConnectionClosed:
            pass
        finally:
            self.clients.discard(websocket)
            self._sending.pop(websocket, None)
            log(f"[MirrorX] Client disconnected: {remote}")

    async def _send_to_client(self, client, frame_msg):
        if self._sending.get(client, False):
            return
        self._sending[client] = True
        try:
            await asyncio.wait_for(client.send(frame_msg), timeout=2.0)
        except (websockets.exceptions.ConnectionClosed, asyncio.TimeoutError):
            self.clients.discard(client)
            self._sending.pop(client, None)
        except Exception as e:
            log(f"[MirrorX] Send error: {e}")
            self.clients.discard(client)
            self._sending.pop(client, None)
        else:
            self._sending[client] = False

    async def stream_loop(self):
        self.start_capture()
        self.running = True
        self._local_ip = self.get_local_ip()
        self._last_scale = self.settings.get("scale")
        log(f"[MirrorX] Server started on {self._local_ip}")
        log(f"[MirrorX] Tablet: open http://{self._local_ip}:{HTTP_PORT} in Chrome")

        last_fps_log = time.monotonic()
        target_fps = max(1, int(self.settings.get("fps")))
        frame_time = 1.0 / target_fps
        last_frame_time = 0

        while self.running:
            # Apply any settings changes
            self._maybe_apply_settings()

            now = time.monotonic()
            elapsed = now - last_frame_time
            if elapsed < frame_time:
                await asyncio.sleep(frame_time - elapsed)
            last_frame_time = time.monotonic()

            if not self.clients:
                await asyncio.sleep(0.1)
                # Still poll the mouse so the cache stays warm
                try:
                    px, py = pyautogui.position()
                    self.mouse.set(px, py)
                except Exception:
                    pass
                continue

            jpeg_bytes = self.capture_frame()
            if jpeg_bytes is None:
                continue

            # Refresh mouse position before sending (cheap)
            try:
                px, py = pyautogui.position()
                self.mouse.set(px, py)
            except Exception:
                px, py = self.mouse.get()
            mx, my = self.mouse.get()

            # Frame header: type(1) + jpeg_len(4) + mouse_x(2) + mouse_y(2)
            header = struct.pack('>B I H H', 0, len(jpeg_bytes), mx, my)
            frame_msg = header + jpeg_bytes
            self.sent_frame_count += 1
            self.bytes_sent += len(jpeg_bytes)
            self.total_frames += 1
            self.total_bytes += len(jpeg_bytes)

            for client in list(self.clients):
                asyncio.ensure_future(self._send_to_client(client, frame_msg))

            if now - self.last_fps_time >= 1.0:
                self.current_fps = self.sent_frame_count / (now - self.last_fps_time)
                # Reset for the next window — but KEEP bytes_sent for stats
                self.sent_frame_count = 0
                self.last_fps_time = now
                if now - last_fps_log >= 3.0:
                    avg_kb = (self.total_bytes / 1024) / max(self.total_frames, 1) if self.total_frames else 0
                    log(f"[MirrorX] FPS: {self.current_fps:.0f} | Q: {self.settings.get('quality')}% | "
                        f"S: {self.settings.get('scale')*100:.0f}% | "
                        f"Frame: {avg_kb:.0f}KB avg | "
                        f"Clients: {len(self.clients)}")
                    self.bytes_sent = 0
                    last_fps_log = now

        # Cleanup on exit
        try:
            self.camera.stop()
        except Exception:
            pass

    async def run(self):
        async with websockets.serve(
            self.handler, "0.0.0.0", PORT,
            max_size=2_000_000, ping_interval=30, ping_timeout=10,
        ):
            log(f"[MirrorX] WebSocket server listening on :{PORT}")
            await self.stream_loop()


def start_http_server(port: int = HTTP_PORT):
    import http.server
    if getattr(sys, 'frozen', False):
        client_dir = os.path.join(sys._MEIPASS, "client")
    else:
        client_dir = str(Path(__file__).parent / "client")
    os.chdir(client_dir)
    handler = http.server.SimpleHTTPRequestHandler
    httpd = http.server.HTTPServer(("0.0.0.0", port), handler)
    log(f"[MirrorX] HTTP server on http://0.0.0.0:{port}")
    httpd.serve_forever()


def run_server_in_thread(settings, mouse, ready_event, server_holder):
    """Run the asyncio server in a background thread. Registers the
    MirrorServer instance in server_holder[0] so the panel can read
    live stats from it."""
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    server = MirrorServer(settings, mouse)
    server_holder[0] = server
    ready_event.set()
    try:
        loop.run_until_complete(server.run())
    except Exception as e:
        log(f"[MirrorX] Server thread error: {e}")
    finally:
        try:
            loop.close()
        except Exception:
            pass


def main():
    log("[MirrorX] v1.0.5 starting...")
    log(f"[MirrorX] Python: {sys.version}")

    settings = Settings()
    mouse = MouseTracker()
    server_holder: list = [None]  # MirrorServer ref for the panel

    # HTTP server thread
    http_thread = threading.Thread(target=start_http_server, args=(HTTP_PORT,), daemon=True)
    http_thread.start()

    # Mirror server thread (so the Tkinter panel can run on the main thread)
    server_ready = threading.Event()
    server_thread = threading.Thread(
        target=run_server_in_thread,
        args=(settings, mouse, server_ready, server_holder),
        daemon=True,
    )
    server_thread.start()
    server_ready.wait(timeout=3)

    # Open the control panel on the main thread (Tkinter requirement)
    server = server_holder[0]
    if server is None:
        log("[MirrorX] FATAL: server failed to start")
        return
    try:
        panel = ControlPanel(settings, server)
        panel.run()
    except Exception as e:
        log(f"[MirrorX] Painel error: {e}")
        import traceback
        traceback.print_exc()


if __name__ == "__main__":
    main()
