"""
MirrorX v1.4.2 — PC Screen Mirroring to Tablet
Captures screen via DXGI, streams via WebSocket, receives touch input.

v1.3.0 changes:
  - MotionInterpolator: smooth human-like cursor movement with min-jerk
    trajectory (cursor glides instead of teleporting like a real touchpad)
  - Adaptive JPEG encoder: auto-detects best available encoder
    (TurboJPEG > OpenCV > Pillow) at startup
  - Panel shows active encoder: "TurboJPEG @ Q75" or "OpenCV @ Q75"
  - Quality steps incluem Q40, Q30, Q20, Q15 para adaptacao agressiva
  - TouchPathCollector no APK: trajetorias comprimidas ao inves de
    pontos individuais — cursor mais suave, 60%% menos trafego de rede
"""
import asyncio
import struct
import json
import time
import socket
import sys
import os
import threading
import math
import tkinter as tk
from tkinter import ttk
from pathlib import Path
import locale

# Force UTF-8 on stdout/stderr so accented chars render correctly
# in the console, log files and any captured output. --windowed PyInstaller
# builds default to cp1252 which mangles non-ASCII text.
try:
    if sys.stdout is not None and hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace", line_buffering=True)
    if sys.stderr is not None and hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace", line_buffering=True)
except Exception:
    pass
try:
    locale.setlocale(locale.LC_ALL, "")
except Exception:
    pass
os.environ.setdefault("PYTHONIOENCODING", "utf-8")

import dxcam
import cv2
import numpy as np
import websockets
import pyautogui

# --- v1.3.0: Adaptive encoder auto-detection ---
_ENCODER_NAME = "OpenCV"
_ENCODE_FN = None

def _init_encoder():
    """Auto-detect the best available JPEG encoder. Called once at startup.

    Order: TurboJPEG (libjpeg-turbo) > OpenCV > Pillow.
    When multiple encoders are available we run a micro-benchmark on a sample
    frame (1440x810) and pick the fastest one on this machine."""
    global _ENCODER_NAME, _ENCODE_FN

    candidates = []

    # 1) TurboJPEG
    try:
        from turbojpeg import TurboJPEG, TJPF_RGB, TJFLAG_FASTDCT
        lib_candidates = []
        if getattr(sys, 'frozen', False):
            lib_candidates.append(os.path.join(sys._MEIPASS, 'turbojpeg.dll'))
        lib_candidates.append(
            os.path.join(os.path.dirname(os.path.abspath(__file__)), 'turbojpeg.dll'))
        lib_candidates.append(None)  # auto-discover
        for cand in lib_candidates:
            try:
                tj = TurboJPEG(lib_path=cand) if cand else TurboJPEG()
                fn = (lambda _tj: lambda f, q: _tj.encode(f, quality=q,
                    pixel_format=TJPF_RGB, flags=TJFLAG_FASTDCT))(tj)
                candidates.append(("TurboJPEG", fn))
                break
            except Exception:
                continue
    except Exception:
        pass

    # 2) OpenCV
    try:
        fn = (lambda: (
            lambda f, q: (lambda ok, enc: enc.tobytes() if ok else None)(
                *cv2.imencode('.jpg', f, [int(cv2.IMWRITE_JPEG_QUALITY), int(q),
                    int(cv2.IMWRITE_JPEG_OPTIMIZE), 0,
                    int(cv2.IMWRITE_JPEG_RST_INTERVAL), 0]))))()
        candidates.append(("OpenCV", fn))
    except Exception:
        pass

    # 3) Pillow
    try:
        import io
        from PIL import Image as PILImage
        def _pil_encode(f, q):
            buf = io.BytesIO()
            PILImage.fromarray(f).save(buf, format='JPEG', quality=int(q), optimize=True)
            return buf.getvalue()
        candidates.append(("Pillow", _pil_encode))
    except Exception:
        pass

    if not candidates:
        raise RuntimeError("No JPEG encoder available! Install OpenCV (pip install opencv-python)")

    # Benchmark if we have more than one candidate
    if len(candidates) > 1:
        import numpy as _np, time as _time
        sample = _np.random.RandomState(42).randint(0, 255, (810, 1440, 3), dtype=_np.uint8)
        best = (None, 1e9)
        for name, fn in candidates:
            try:
                # warm-up
                for _ in range(3):
                    _ = fn(sample, 75)
                t0 = _time.perf_counter()
                for _ in range(20):
                    _ = fn(sample, 75)
                t1 = _time.perf_counter()
                ms = (t1 - t0) / 20 * 1000
                if ms < best[1]:
                    best = (name, ms)
                log(f"[MirrorX] Encoder benchmark: {name} = {ms:.2f}ms")
            except Exception as e:
                log(f"[MirrorX] Encoder benchmark failed for {name}: {e}")
        # If benchmark failed for all, fall back to the first candidate
        if best[0] is None:
            best = (candidates[0][0], 0)
        _ENCODER_NAME, _ENCODE_FN = next((n, f) for n, f in candidates if n == best[0])
    else:
        _ENCODER_NAME, _ENCODE_FN = candidates[0]
    log(f"[MirrorX] Encoder: {_ENCODER_NAME} (selecionado por benchmark)")


# --- v1.3.0: Motion Interpolator (smooth human-like cursor) ---

class MotionInterpolator:
    """Emulates human-like cursor movement using minimum-jerk trajectory.
    Replaces pyautogui.moveTo() teleports with smooth acceleration/deceleration.

    Based on Flash & Hogan (1985): x(t) = 10t^3 - 15t^4 + 6t^5.
    Duration scales with distance (Fitts' Law approximation)."""

    def __init__(self, min_ms: int = 1, max_ms: int = 12):
        self.min_duration = min_ms / 1000.0
        self.max_duration = max_ms / 1000.0
        self.step_interval = 0.002  # 2ms per micro-step
        self._x: int = 0
        self._y: int = 0
        self._lock = threading.Lock()

    def move_to(self, target_x: int, target_y: int):
        """Smooth min-jerk movement to (target_x, target_y).
        Short distances (< 4px) teleport instantly."""
        with self._lock:
            dx = target_x - self._x
            dy = target_y - self._y
            dist = math.sqrt(dx * dx + dy * dy)
            if dist < 4:
                pyautogui.moveTo(target_x, target_y)
                self._x, self._y = target_x, target_y
                return
            dur = self.min_duration + (self.max_duration - self.min_duration) * \
                  min(1.0, dist / 800.0)
            steps = max(2, int(dur / self.step_interval))
            for i in range(1, steps + 1):
                t = i / steps
                s = 10 * t**3 - 15 * t**4 + 6 * t**5
                xi = int(self._x + dx * s)
                yi = int(self._y + dy * s)
                pyautogui.moveTo(xi, yi)
            self._x, self._y = target_x, target_y

    def set_pos(self, x: int, y: int):
        """Teleport (no interpolation). Used for initial cursor sync."""
        with self._lock:
            self._x, self._y = x, y
            pyautogui.moveTo(x, y)

    def get_pos(self) -> tuple:
        with self._lock:
            return (self._x, self._y)

# --- Config ---
PORT = 9900
HTTP_PORT = 8080

# Defaults — overridden by the ControlPanel at startup
DEFAULTS = {
    "fps": 30,
    "quality": 85,            # v1.4.0: was 75 — better visual quality
    "scale": 1.0,             # v1.4.0: was 0.75 — full resolution by default
    "encoding": "JPEG",       # future: "WebP"
}

# Bounds
FPS_OPTIONS = [15, 24, 30, 45, 60, 90]
SCALE_OPTIONS = [0.50, 0.75, 1.00]   # v1.4.0: removed 0.25 (too pixelated)
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
        self.auto_adjust = False  # v1.4.1: user prefers fixed quality; checkbox remains in panel
        # v1.4.3: tighter adaptation thresholds for WiFi
        # - React faster to FPS drops (was 22, now 25)
        # - Recover faster when FPS improves (was 38, now 35)
        # - Critical threshold stays low (18) to avoid panic reductions
        self.low_fps_threshold = 25           # was 22 — react sooner on WiFi
        self.high_fps_threshold = 35          # was 38 — recover sooner
        self.critical_fps = 18               # unchanged
        # v1.2 additions
        self.strict_pen = False          # reject non-stylus touch events
        self.cursor_during_pen = True     # show PC cursor while writing
        self.target_tablet_fps = 60       # tablet display target (60/120Hz)
        # v1.3.0: adaptation tracking (read by panel & sent to APK)
        self.adapt_mode = "auto"          # "auto" | "manual" | "reduced" | "boosted"
        self.adapt_reason = ""            # human-readable explanation
        # v1.4.2: smooth cursor OFF by default — user reports high latency
        # on WiFi. The interpolator adds 4-12ms per move that the user can
        # perceive. With OFF, moves are instant (pyautogui.moveTo teleport).
        # Checkbox "Cursor suave" remains in .exe for users who prefer it.
        self.smooth_cursor = False

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
    Also tracks whether the cursor should be visible on the tablet
    (set by APK when stylus is active).
    """

    def __init__(self):
        self._x = 0
        self._y = 0
        self._lock = threading.Lock()
        self._cursor_visible = True

    def set(self, x: int, y: int):
        with self._lock:
            self._x, self._y = x, y

    def get(self) -> tuple[int, int]:
        with self._lock:
            return (self._x, self._y)

    def set_cursor_visible(self, visible: bool):
        with self._lock:
            self._cursor_visible = visible

    def is_cursor_visible(self) -> bool:
        with self._lock:
            return self._cursor_visible


# --- Tkinter control panel ---

class ControlPanel:
    """Dark-themed control window. Runs on the main thread; reads/writes
    Settings and displays live stats from the server. Closes cleanly on
    window close (also quits the asyncio loop)."""

    def __init__(self, settings: Settings, server: "MirrorServer"):
        self.settings = settings
        self.server = server

        self.root = tk.Tk()
        self.root.title("MirrorX v1.4.3 — Painel de Controle")
        self.root.geometry("440x680")
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
        tk.Label(outer, text="v1.4.3 — Icon + Frozen Screen Fix + Bigger Buttons + WiFi Optimization",
                 font=("Segoe UI", 10),
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

        # v1.4.2: Smooth cursor toggle — when OFF (default), moves are
        # instant (pyautogui.moveTo teleport). When ON, the MotionInterpolator
        # adds 1-12ms of minimum-jerk curve per move. The user reported
        # the interpolator made WiFi feel laggy.
        self.smooth_var = tk.BooleanVar(value=self.settings.smooth_cursor)
        ttk.Checkbutton(stream, text="Cursor suave (interpolador — pode adicionar até 12ms)",
                        variable=self.smooth_var, command=self._on_smooth_change,
                        style="TCheckbutton").pack(anchor=tk.W, pady=(2, 0))

        # v1.2 toggles
        self.strict_pen_var = tk.BooleanVar(value=self.settings.strict_pen)
        ttk.Checkbutton(stream, text="Modo Caneta Estrito (ignora dedos)",
                        variable=self.strict_pen_var, command=self._on_strict_pen_change,
                        style="TCheckbutton").pack(anchor=tk.W, pady=(2, 0))

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
        # v1.3.0: show which encoder is active
        self.encoder_live = self._stat_row(stats, "Encoder", "--", ACCENT)
        # v1.3.0: live adaptation status — tells the user what AUTO mode is doing
        self.adapt_live = self._stat_row(stats, "Adaptação", "AUTO (ok)", ACCENT)

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

    def _on_smooth_change(self):
        self.settings.smooth_cursor = self.smooth_var.get()
        log(f"[MirrorX] [panel] Cursor suave -> {'ON' if self.settings.smooth_cursor else 'OFF'}")

    def _on_strict_pen_change(self):
        self.settings.strict_pen = self.strict_pen_var.get()
        log(f"[MirrorX] [panel] Modo Caneta Estrito -> {'ON' if self.settings.strict_pen else 'OFF'}")

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
        # v1.3.0: show encoder + quality
        enc = s.get("encoder", "?")
        self.encoder_live.config(
            text=f"{enc} @ {int(s.get('quality', 0))}%",
            fg="#22C55E" if "Turbo" in str(enc) else DIM
        )
        # v1.3.0: show current adaptation state
        adapt = s.get("adapt", {}) or {}
        mode = adapt.get("mode", "auto")
        reason = adapt.get("reason", "")
        if mode == "reduced":
            self.adapt_live.config(text=f"REDUZIDA — {reason}", fg=YELLOW)
        elif mode == "boosted":
            self.adapt_live.config(text=f"OTIMIZADA — {reason}", fg=GREEN)
        elif mode == "manual":
            self.adapt_live.config(text="MANUAL (auto desligado)", fg=DIM)
        else:
            self.adapt_live.config(text=f"AUTO (Q={adapt.get('q','--')}% S={adapt.get('s','--')}%)",
                                   fg=GREEN)

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
        # v1.4.1: ghost cursor broadcast state (throttled, deduplicated)
        self._last_cursor_t = 0.0
        self._last_cursor_xy = (0, 0)
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

        # v1.3.0: smooth cursor movement
        self._interpolator = MotionInterpolator()

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
            "encoder": _ENCODER_NAME,
            "adapt": {
                "mode": self.settings.adapt_mode,
                "reason": self.settings.adapt_reason,
                "q": int(self.settings.get("quality")),
                "s": int(self.settings.get("scale") * 100),
                "fps_target": int(self.settings.get("fps")),
                "fps_target_actual": round(self.current_fps, 1),
            },
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
        self.camera = dxcam.create(output_color="BGR", max_buffer_len=3)
        if self.camera is None:
            raise RuntimeError("dxcam failed to initialize. GPU with DXGI required.")
        self.screen_w = self.camera.width
        self.screen_h = self.camera.height
        self._last_frame = None  # v1.4.3: cache to avoid frozen screen on None frames
        self._update_stream_dims()
        target_fps = max(1, int(self.settings.get("fps")))
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
            # v1.4.3: reuse last frame instead of skipping — prevents frozen
            # screen when dxcam returns None (e.g. new window opened, DXGI
            # refresh lag). OpenCV uses BGR natively so no color conversion.
            frame = self._last_frame
        if frame is None:
            return None
        self._last_frame = frame
        s = self.settings.get("scale")
        if s < 1.0:
            self.stream_w = int(self.screen_w * s)
            self.stream_h = int(self.screen_h * s)
            frame = cv2.resize(frame, (self.stream_w, self.stream_h),
                             interpolation=cv2.INTER_AREA)
        # v1.3.0: use the auto-detected best encoder (OpenCV handles BGR directly)
        q = int(self.settings.get("quality"))
        return _ENCODE_FN(frame, q)

    def _broadcast_cursor(self, x: int, y: int):
        """v1.4.1: notify clients of current cursor position so they can draw
        a ghost cursor that moves instantly, without waiting for the next
        JPEG frame. Throttled to 60 Hz and deduped (skips <1px moves).
        Safe to call from any thread."""
        now = time.time()
        if now - self._last_cursor_t < (1.0 / 60.0):
            return
        last_x, last_y = self._last_cursor_xy
        if abs(x - last_x) < 1 and abs(y - last_y) < 1:
            return
        self._last_cursor_t = now
        self._last_cursor_xy = (x, y)
        if not self.clients:
            return
        msg = json.dumps({"type": "cursor_pos", "x": int(x), "y": int(y)})
        try:
            loop = asyncio.get_event_loop()
        except RuntimeError:
            return
        async def send_all():
            dead = []
            for ws in list(self.clients):
                try:
                    await ws.send(msg)
                except Exception:
                    dead.append(ws)
            for ws in dead:
                self.clients.discard(ws)
        try:
            loop.call_soon_threadsafe(lambda: asyncio.ensure_future(send_all()))
        except Exception:
            pass

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
        """Convert tablet touch to Windows mouse events. v1.2 supports
        stylus pressure, tilt, tool type, and button mapping."""
        try:
            x_ratio = data.get("x", 0.5)
            y_ratio = data.get("y", 0.5)
            action = data.get("action", "move")
            pressure = float(data.get("pressure", 0.5))
            tilt = float(data.get("tilt", 0.0))
            tool = data.get("tool", "finger")        # finger/stylus/mouse/eraser
            buttons = int(data.get("buttons", 0))    # 1=primary, 2=secondary, 4=tertiary

            # Strict-pen mode: ignore non-stylus touch events
            if self.settings.strict_pen and tool not in ("stylus", "eraser"):
                return

            target_x = int(x_ratio * self.screen_w)
            target_y = int(y_ratio * self.screen_h)

            # Update mouse cache (so next frame's cursor overlay is correct)
            self.mouse.set(target_x, target_y)
            self._broadcast_cursor(target_x, target_y)

            # Determine which mouse button (stylus tip = primary, barrel = secondary)
            # If the touch event specifies buttons, honor it; else default to primary
            button = "left"
            if action in ("right_click",) or (buttons & 2):
                button = "right"
            elif buttons & 4:
                button = "middle"

            # Log stylus events with pressure (useful for debugging note-taking apps)
            if tool in ("stylus", "eraser") and action in ("down", "move", "drag"):
                if not hasattr(self, '_last_stylus_log') or \
                   (time.monotonic() - self._last_stylus_log) > 1.0:
                    log(f"[MirrorX] Stylus {action} @ ({target_x},{target_y}) "
                        f"p={pressure:.2f} tilt={tilt:.0f}°")
                    self._last_stylus_log = time.monotonic()

            if action == "down":
                pyautogui.moveTo(target_x, target_y)
                pyautogui.mouseDown(button=button)
            elif action == "up":
                pyautogui.moveTo(target_x, target_y)
                pyautogui.mouseUp(button=button)
            elif action == "click":
                pyautogui.click(target_x, target_y, button=button)
            elif action == "right_click":
                pyautogui.rightClick(target_x, target_y)
            elif action == "move":
                # v1.3.0: smooth cursor movement via MotionInterpolator
                if self.settings.smooth_cursor:
                    self._interpolator.move_to(target_x, target_y)
                else:
                    pyautogui.moveTo(target_x, target_y)
            elif action == "scroll":
                amount = data.get("amount", 0)
                pyautogui.scroll(amount)
            elif action == "drag":
                # Minimal duration — v1.4.2 reduced from 50ms to 10ms
                # to lower perceived latency on WiFi. Stylus still gets
                # 20ms for smoother ink in drawing apps.
                dur = 0.02 if tool == "stylus" else 0.01
                pyautogui.dragTo(target_x, target_y, duration=dur,
                                button=button)
            elif action == "key":
                key = data.get("key", "")
                self._handle_key(key)
        except Exception as e:
            log(f"[MirrorX] Touch error: {e}")

    def handle_touch_path(self, data: dict):
        """v1.3.1: receive a compressed touch trajectory and smoothly move the
        cursor through it. Reduces network chatter vs individual 'move' events."""
        try:
            points = data.get("points", [])
            if len(points) < 2:
                return
            tool = data.get("tool", "finger")
            if self.settings.strict_pen and tool not in ("stylus", "eraser"):
                return
            buttons = int(data.get("buttons", 0))
            button = "left"
            if buttons & 2:
                button = "right"
            elif buttons & 4:
                button = "middle"

            # Convert ratios to screen coordinates
            screen_points = [(int(p["x"] * self.screen_w), int(p["y"] * self.screen_h),
                              int(p.get("t", 0))) for p in points]
            self._process_touch_path(screen_points, tool, button, duration_ms_default=50)
        except Exception as e:
            log(f"[MirrorX] Touch path error: {e}")

    def handle_touch_path_binary(self, raw: bytes):
        """v1.4.0: WebSocket binary touch path. Layout: 0x10 + count(u8) +
        (x:u16 LE)(y:u16 LE) x count. Much faster than JSON parsing."""
        try:
            if not raw or raw[0] != 0x10:
                return
            count = raw[1]
            now_ms = int(time.time() * 1000)
            screen_points = []
            for i in range(count):
                base = 2 + i * 4
                if base + 4 > len(raw):
                    break
                xi = int.from_bytes(raw[base:base+2], 'little')
                yi = int.from_bytes(raw[base+2:base+4], 'little')
                xr = xi / 65535.0
                yr = yi / 65535.0
                px = int(xr * self.screen_w)
                py = int(yr * self.screen_h)
                # Per-point timestamp: walk back from now
                pt_ms = now_ms - (count - i) * 8
                screen_points.append((px, py, pt_ms))
            if len(screen_points) < 2:
                return
            # Pinch detection is delegated to the touch handler on the tablet
            # side; server just moves the cursor along the path.
            self._process_touch_path(screen_points, "finger", "left", duration_ms_default=33)
        except Exception as e:
            log(f"[MirrorX] Touch path binary error: {e}")

    def handle_pinch(self, raw: bytes):
        """v1.4.0: pinch-to-zoom. Sends a Ctrl+Wheel event to the focused app.
        Layout: 0x11 + scale(f32 LE) + cx(f32 LE) + cy(f32 LE)."""
        try:
            if not raw or raw[0] != 0x11 or len(raw) < 13:
                return
            scale = struct.unpack('<f', raw[1:5])[0]
            cx = struct.unpack('<f', raw[5:9])[0]
            cy = struct.unpack('<f', raw[9:13])[0]
            # scale > 1.0 = zoom in (scroll up), scale < 1.0 = zoom out (down)
            # Use the relative change vs last frame
            if not hasattr(self, '_last_pinch_scale'):
                self._last_pinch_scale = 1.0
            delta = scale / self._last_pinch_scale
            self._last_pinch_scale = scale
            # Map: each 5% pinch = 1 wheel notch
            amount = int(round((delta - 1.0) * 20))
            if abs(amount) >= 1:
                # Move cursor to the center of the pinch first
                tx = int(cx * self.screen_w)
                ty = int(cy * self.screen_h)
                pyautogui.moveTo(tx, ty)
                # Ctrl+scroll = zoom in most apps
                import pyautogui as _pa
                _pa.hotkey('ctrl')
                _pa.scroll(amount)
        except Exception as e:
            log(f"[MirrorX] Pinch error: {e}")
        finally:
            # Reset pinch state on pointer up — but we have no signal here.
            # The pinch handler resets the last_pinch_scale at the next non-pinch
            # touch event via the touch path handler if needed. For simplicity
            # we accept small drift in the long run.
            pass

    def _process_touch_path(self, screen_points, tool, button, duration_ms_default=50):
        """Shared logic for handle_touch_path (JSON) and handle_touch_path_binary."""
        first = screen_points[0]
        last = screen_points[-1]
        total_dist = 0
        prev = screen_points[0]
        for p in screen_points[1:]:
            total_dist += math.hypot(p[0] - prev[0], p[1] - prev[1])
            prev = p
        duration_ms = last[2] - first[2]
        if duration_ms <= 0:
            duration_ms = duration_ms_default

        # Detect tap: short path in time and space
        ratio_dist = total_dist / max(self.screen_w, self.screen_h)
        is_tap = (duration_ms < 400) and (ratio_dist < 0.02)

        # Update mouse cache to final position
        self.mouse.set(last[0], last[1])
        self._broadcast_cursor(last[0], last[1])

        if is_tap:
            pyautogui.click(last[0], last[1], button=button)
            # Reset pinch state so the next pinch starts fresh
            self._last_pinch_scale = 1.0
            return

        # v1.4.0: bypass MotionInterpolator for fast path execution.
        # Calling move_to() once for the last point lets the interpolator
        # do its job (4-40ms) but avoids blocking through every point.
        if self.settings.smooth_cursor:
            self._interpolator.move_to(last[0], last[1])
        else:
            pyautogui.moveTo(last[0], last[1])

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
                "version": "1.4.3",
            }))
        except Exception as e:
            log(f"[MirrorX] Failed to send screen_info: {e}")
            self.clients.discard(websocket)
            return
        try:
            async for message in websocket:
                # v1.4.0: WebSocket binary frames (touch_path_binary, pinch)
                if isinstance(message, (bytes, bytearray)):
                    if len(message) >= 1:
                        msg_type = message[0]
                        if msg_type == 0x10:  # touch_path_binary
                            self.handle_touch_path_binary(bytes(message))
                        elif msg_type == 0x11:  # pinch
                            self.handle_pinch(bytes(message))
                    continue
                # JSON text messages
                try:
                    data = json.loads(message)
                    msg_type = data.get("type", "")
                    if msg_type == "touch":
                        self.handle_touch(data)
                    elif msg_type == "touch_path":
                        self.handle_touch_path(data)
                    elif msg_type == "ping":
                        await websocket.send(json.dumps({"type": "pong"}))
                    elif msg_type == "config":
                        self.handle_config(data)
                    elif msg_type == "cursor":
                        # v1.2: APK tells server whether to render cursor
                        self.mouse.set_cursor_visible(bool(data.get("visible", True)))
                    elif msg_type == "click_request":
                        # v1.4.2: explicit click button on tablet UI.
                        # Clicks at the current PC cursor position (no x/y
                        # sent — the cursor is already where the user dragged it).
                        button = data.get("button", "left")
                        try:
                            x, y = pyautogui.position()
                            if button == "left":
                                pyautogui.click(x, y, button="left")
                            elif button == "right":
                                pyautogui.click(x, y, button="right")
                            elif button == "middle":
                                pyautogui.click(x, y, button="middle")
                            elif button == "double":
                                pyautogui.doubleClick(x, y)
                            log(f"[MirrorX] click_request {button} @ ({x},{y})")
                        except Exception as e:
                            log(f"[MirrorX] click_request error: {e}")
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
        last_adapt_check = time.monotonic()
        last_adapt_broadcast = 0.0
        target_fps = max(1, int(self.settings.get("fps")))
        frame_time = 1.0 / target_fps
        last_frame_time = 0

        # Quality/scale step lists for auto-adaptation. Order matters:
        # we step DOWN the "down" list to reduce load, then back UP the
        # same list when FPS recovers.
        # v1.3.0: more steps at low end for finer control when FPS is tight
        QUALITY_STEPS = [95, 85, 75, 70, 65, 60, 55, 50, 45, 40, 35, 30, 25, 20, 15]
        SCALE_STEPS   = [1.00, 0.75, 0.50, 0.25]

        def _quality_index():
            q = int(self.settings.get("quality"))
            for i, v in enumerate(QUALITY_STEPS):
                if q >= v:
                    return i
            return len(QUALITY_STEPS) - 1

        def _scale_index():
            s = round(float(self.settings.get("scale")), 2)
            for i, v in enumerate(SCALE_STEPS):
                if abs(s - v) < 0.01:
                    return i
            return 1  # default to 0.75

        def _step_down():
            """Reduce quality first, then scale. Returns (changed, reason)."""
            qi = _quality_index()
            if qi < len(QUALITY_STEPS) - 1:
                new_q = QUALITY_STEPS[qi + 1]
                self.settings.set("quality", new_q)
                self._jpeg_params[1] = new_q
                self._last_quality = new_q
                return True, f"Q {QUALITY_STEPS[qi]}%→{new_q}%"
            si = _scale_index()
            if si < len(SCALE_STEPS) - 1:
                new_s = SCALE_STEPS[si + 1]
                self.settings.set("scale", new_s)
                self._update_stream_dims()
                self._last_scale = new_s
                return True, f"S {int(SCALE_STEPS[si]*100)}%→{int(new_s*100)}%"
            return False, "limite mínimo"

        def _step_up():
            """Recover quality. We don't change scale up automatically —
            that requires more bandwidth and is riskier."""
            si = _scale_index()
            if si > 0:
                new_s = SCALE_STEPS[si - 1]
                self.settings.set("scale", new_s)
                self._update_stream_dims()
                self._last_scale = new_s
                return True, f"S {int(SCALE_STEPS[si]*100)}%→{int(new_s*100)}%"
            qi = _quality_index()
            if qi > 0:
                new_q = QUALITY_STEPS[qi - 1]
                self.settings.set("quality", new_q)
                self._jpeg_params[1] = new_q
                self._last_quality = new_q
                return True, f"Q {QUALITY_STEPS[qi]}%→{new_q}%"
            return False, "limite máximo"

        async def _broadcast_adapt(mode: str, reason: str):
            msg = json.dumps({
                "type": "adapt",
                "mode": mode,
                "reason": reason,
                "q": int(self.settings.get("quality")),
                "s": int(self.settings.get("scale") * 100),
                "fps": round(self.current_fps, 1),
            })
            for client in list(self.clients):
                try:
                    await asyncio.wait_for(client.send(msg), timeout=1.0)
                except Exception:
                    pass

        while self.running:
            # Apply any settings changes
            self._maybe_apply_settings()

            now = time.monotonic()
            elapsed = now - last_frame_time
            
            # v1.4.3: adaptive frame timing — if we're behind, skip interpolation
            # and send next frame ASAP to catch up (reduces perceived lag)
            if elapsed < frame_time:
                await asyncio.sleep(frame_time - elapsed)
            # Don't wait if we're already behind — just send next frame
            
            last_frame_time = time.monotonic()

            if not self.clients:
                await asyncio.sleep(0.05)  # v1.4.3: was 0.1 — faster reconnect
                # Still poll the mouse so the cache stays warm
                try:
                    px, py = pyautogui.position()
                    self.mouse.set(px, py)
                    self._broadcast_cursor(px, py)
                except Exception:
                    pass
                continue

            jpeg_bytes = self.capture_frame()
            # v1.4.3: if frame is None, send LAST frame immediately (no sleep)
            # This prevents frozen screen without adding lag
            if jpeg_bytes is None:
                continue  # just try again next iteration ASAP

            # Refresh mouse position before sending (cheap)
            try:
                px, py = pyautogui.position()
                self.mouse.set(px, py)
                self._broadcast_cursor(px, py)
            except Exception:
                px, py = self.mouse.get()
            mx, my = self.mouse.get()

            # v1.3.0 frame header (11 bytes):
            #   type(1) + jpeg_len(4) + mouse_x(2) + mouse_y(2) +
            #   cursor_visible(1) + reserved(1) + jpeg
            #
            # v1.2.3: cursor is now visible as soon as a client is connected.
            # Previously it was hidden until the PC mouse moved (mx > 0 || my > 0),
            # which left a black-screen-no-cursor gap on first connect. The APK
            # still tells the server to hide it via {"type":"cursor"} when the
            # stylus is actively writing.
            cursor_visible = 1 if self.mouse.is_cursor_visible() else 0
            header = struct.pack('>B I H H B B', 0, len(jpeg_bytes), mx, my,
                                 cursor_visible, 0)
            frame_msg = header + jpeg_bytes
            self.sent_frame_count += 1
            self.bytes_sent += len(jpeg_bytes)
            self.total_frames += 1
            self.total_bytes += len(jpeg_bytes)

            for client in list(self.clients):
                asyncio.ensure_future(self._send_to_client(client, frame_msg))

            if now - self.last_fps_time >= 1.0:
                self.current_fps = self.sent_frame_count / (now - self.last_fps_time)
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

            # ------------------------------------------------------------------
            # v1.4.3: Faster adaptive loop (every 1.5s instead of 2s)
            # and uses last 1 reading instead of 2 for critical drops
            # ------------------------------------------------------------------
            if not hasattr(self, "_fps_history"):
                self._fps_history = []
            self._fps_history.append(self.current_fps)
            if len(self._fps_history) > 12:
                self._fps_history.pop(0)

            if now - last_adapt_check >= 1.5 and self.settings.auto_adjust and self.clients:
                last_adapt_check = now
                low = self.settings.low_fps_threshold
                high = self.settings.high_fps_threshold
                critical = self.settings.critical_fps

                if len(self._fps_history) >= 1:
                    # v1.4.3: check only last reading for critical (immediate action)
                    last_fps = self._fps_history[-1]
                    last2 = self._fps_history[-2:] if len(self._fps_history) >= 2 else [last_fps]
                    
                    # Critical FPS (< 18): double-step down immediately
                    if last_fps < critical:
                        changed1, reason1 = _step_down()
                        changed2, reason2 = (False, "") if not changed1 else _step_down()
                        total_reason = reason1
                        if changed2:
                            total_reason = f"{reason1} + {reason2}"
                        if changed1 or changed2:
                            self.settings.adapt_mode = "reduced"
                            self.settings.adapt_reason = total_reason
                            log(f"[MirrorX] [adapt] ↓↓ {total_reason}  (FPS {last_fps:.0f} < {critical})")
                            await _broadcast_adapt("reduced", total_reason)
                            last_adapt_broadcast = now
                    # Normal low FPS (< 25): single-step down after 2 readings
                    elif all(f < low for f in last2) and self.settings.adapt_mode != "reduced":
                        changed, reason = _step_down()
                        if changed:
                            self.settings.adapt_mode = "reduced"
                            self.settings.adapt_reason = reason
                            log(f"[MirrorX] [adapt] ↓ {reason}  (FPS {last_fps:.0f} < {low})")
                            await _broadcast_adapt("reduced", reason)
                            last_adapt_broadcast = now
                    # Keep stepping down if already reduced but FPS still below threshold
                    elif last_fps < low and self.settings.adapt_mode == "reduced":
                        changed, reason = _step_down()
                        if changed:
                            self.settings.adapt_reason = reason
                            log(f"[MirrorX] [adapt] ↓ {reason} (continuing, FPS {last_fps:.0f} < {low})")
                            await _broadcast_adapt("reduced", reason)
                            last_adapt_broadcast = now

                # Step UP: need 4 good readings (was 6) to recover — faster on WiFi
                if len(self._fps_history) >= 4:
                    last4 = self._fps_history[-4:]
                    if all(f > high for f in last4) and self.settings.adapt_mode == "reduced":
                        changed, reason = _step_up()
                        if changed:
                            if int(self.settings.get("quality")) >= 75 and \
                               round(float(self.settings.get("scale")), 2) >= 0.75:
                                self.settings.adapt_mode = "auto"
                                self.settings.adapt_reason = "recuperado"
                                log(f"[MirrorX] [adapt] ✓ recuperado para AUTO (FPS {last_fps:.0f})")
                                await _broadcast_adapt("auto", "recuperado")
                            else:
                                self.settings.adapt_reason = reason
                                log(f"[MirrorX] [adapt] ↑ {reason}  (FPS {last_fps:.0f} > {high})")
                                await _broadcast_adapt("reduced", reason)
                            last_adapt_broadcast = now
                # Healthy FPS, auto mode
                elif len(self._fps_history) >= 3 and self.settings.adapt_mode != "manual":
                    last3 = self._fps_history[-3:]
                    if all(f >= low for f in last3) and \
                       int(self.settings.get("quality")) >= 75 and \
                       round(float(self.settings.get("scale")), 2) >= 0.75 and \
                       self.settings.adapt_mode != "auto":
                        self.settings.adapt_mode = "auto"
                        self.settings.adapt_reason = "ok"
                        await _broadcast_adapt("auto", "ok")
            elif not self.settings.auto_adjust and self.settings.adapt_mode != "manual":
                self.settings.adapt_mode = "manual"
                self.settings.adapt_reason = ""
                await _broadcast_adapt("manual", "")

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
    # v1.3.0: init the best available encoder before starting anything
    log(f"[MirrorX] v1.3.0 starting...")
    _init_encoder()
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
