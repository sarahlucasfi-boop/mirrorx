"""
MirrorX v1.5.4 — Modern control panel (customtkinter).

Replaces the v1.4.3-era Tkinter panel with a dark, rounded, modern UI.
Works for both server modes:

  • "mirror" — screen capture, FPS/quality/scale controls
  • "hermes" — mouse-only touchpad, sensitivity / send-rate controls

The panel is API-driven: the asyncio loop calls update_* methods from
the Tk thread via root.after(0, ...) so we never touch Tk widgets from
a foreign thread (which is what gave us the "Calling Tcl from different
apartment" warning before).

Public API:
    panel = ControlPanel(mode="mirror" | "hermes",
                         server_obj=mirror_or_hermes,
                         port=9900, host="0.0.0.0",
                         on_stop=callback_to_stop_server,
                         version="1.5.4")
    panel.start()       # blocks (call from main thread)
    panel.update_stats(stats_dict)   # from any thread
    panel.log_event(text)            # from any thread
"""

from __future__ import annotations

import logging
import queue
import time
import tkinter as tk
from tkinter import ttk
from typing import Any, Callable, Dict, Optional

import customtkinter as ctk

log = logging.getLogger("mirrorx.panel")


# ── Palette ──────────────────────────────────────────────────────────────
BG_OUTER   = "#0f0f14"
BG_CARD    = "#1a1a24"
BG_CARD2   = "#22222e"
BG_INPUT   = "#2a2a38"
FG         = "#e8e8f0"
FG_DIM     = "#8a8aa0"
FG_BRIGHT  = "#ffffff"
ACCENT     = "#6366f1"   # indigo
ACCENT_HOT = "#818cf8"
OK         = "#10b981"   # green
WARN       = "#f59e0b"   # amber
BAD        = "#ef4444"   # red
HERMES_TINT = "#a78bfa"  # violet
MIRROR_TINT = "#38bdf8"  # sky


# ── Small helpers ───────────────────────────────────────────────────────
class CardFrame(ctk.CTkFrame):
    """A rounded card with a colored title strip."""

    def __init__(self, master, title: str, accent: str = ACCENT, **kw):
        super().__init__(
            master,
            corner_radius=12,
            fg_color=BG_CARD,
            border_width=1,
            border_color="#2e2e40",
            **kw,
        )
        self._accent = accent
        self._title = title

        # Title strip
        strip = ctk.CTkFrame(self, fg_color="transparent", height=28)
        strip.pack(fill="x", padx=14, pady=(10, 0))
        strip.pack_propagate(False)

        ctk.CTkLabel(
            strip, text=title,
            font=("Segoe UI Semibold", 12),
            text_color=accent,
            anchor="w",
        ).pack(side="left")

        # Body container
        self.body = ctk.CTkFrame(self, fg_color="transparent")
        self.body.pack(fill="both", expand=True, padx=14, pady=(4, 12))


class StatRow(ctk.CTkFrame):
    """A label : value row."""

    def __init__(self, master, label: str, value: str = "—", **kw):
        super().__init__(master, fg_color="transparent", **kw)
        self._label_text = label
        self._value_text = value

        self.lbl = ctk.CTkLabel(
            self, text=label,
            font=("Segoe UI", 11),
            text_color=FG_DIM,
            anchor="w", width=130,
        )
        self.lbl.pack(side="left")

        self.val = ctk.CTkLabel(
            self, text=value,
            font=("Consolas", 11),
            text_color=FG_BRIGHT,
            anchor="w",
        )
        self.val.pack(side="left", fill="x", expand=True)

    def set_value(self, text: str, color: Optional[str] = None):
        self.val.configure(text=text, text_color=(color or FG_BRIGHT))


class SegmentedRow(ctk.CTkFrame):
    """A horizontal row of pill-style buttons (single-select)."""

    def __init__(self, master, options, default=None, command=None, **kw):
        super().__init__(master, fg_color="transparent", **kw)
        self._command = command
        self._var = tk.StringVar(value=str(default if default is not None else options[0]))
        self._buttons = {}
        for opt in options:
            text = str(opt)
            b = ctk.CTkButton(
                self, text=text, width=58, height=26,
                corner_radius=8,
                font=("Segoe UI", 11),
                fg_color=BG_INPUT,
                hover_color="#353548",
                text_color=FG,
                border_width=0,
                command=lambda o=opt: self._select(o),
            )
            b.pack(side="left", padx=(0, 6))
            self._buttons[opt] = b
        self._refresh()

    def _select(self, opt):
        self._var.set(str(opt))
        self._refresh()
        if self._command:
            try:
                self._command(opt)
            except Exception as e:
                log.exception("segmented callback failed: %s", e)

    def _refresh(self):
        cur = self._var.get()
        for opt, btn in self._buttons.items():
            if str(opt) == cur:
                btn.configure(fg_color=ACCENT, text_color="#ffffff",
                              hover_color=ACCENT_HOT)
            else:
                btn.configure(fg_color=BG_INPUT, text_color=FG,
                              hover_color="#353548")

    def get(self):
        return self._var.get()


# ── Main panel ──────────────────────────────────────────────────────────
class ControlPanel:
    """Modern customtkinter control panel for MirrorX."""

    POLL_MS = 500

    def __init__(
        self,
        mode: str,
        server_obj: Any,
        port: int,
        host: str,
        on_stop: Callable[[], None],
        version: str = "1.6.4",
    ):
        self.mode = mode            # "mirror" or "hermes"
        self.server = server_obj
        self.port = port
        self.host = host
        self.on_stop = on_stop
        self.version = version

        # Cross-thread queue (asyncio → Tk)
        self._q: "queue.Queue[Callable[[], None]]" = queue.Queue()

        # Latest snapshot of stats (set by update_stats, drawn by _poll_stats)
        self._stats: Dict[str, Any] = {}

        # Build UI
        ctk.set_appearance_mode("dark")
        ctk.set_default_color_theme("dark-blue")

        self.root = ctk.CTk()
        self.root.title(f"MirrorX v{self.version} — {mode.title()}")
        self.root.geometry("540x680")
        self.root.minsize(540, 680)
        self.root.configure(fg_color=BG_OUTER)

        # Try to set window icon (best-effort, ignore if missing)
        try:
            self.root.iconbitmap(default="")
        except Exception:
            pass

        self._build()

    # ── Build UI tree ──────────────────────────────────────────────────
    def _build(self):
        # === Top header (FIXED, not scrollable) =========================
        header = ctk.CTkFrame(self.root, fg_color="transparent", height=72)
        header.pack(fill="x", padx=18, pady=(18, 6))
        header.pack_propagate(False)

        # Title block (left)
        title_box = ctk.CTkFrame(header, fg_color="transparent")
        title_box.pack(side="left", anchor="w")

        ctk.CTkLabel(
            title_box, text="MirrorX",
            font=("Segoe UI", 22, "bold"),
            text_color=FG_BRIGHT,
            anchor="w",
        ).pack(anchor="w")

        # Subtitle with version + mode pill
        sub_row = ctk.CTkFrame(title_box, fg_color="transparent")
        sub_row.pack(anchor="w", pady=(2, 0))

        ctk.CTkLabel(
            sub_row, text=f"v{self.version}",
            font=("Segoe UI", 11),
            text_color=FG_DIM,
        ).pack(side="left")

        ctk.CTkLabel(sub_row, text="  •  ", text_color=FG_DIM,
                     font=("Segoe UI", 11)).pack(side="left")

        tint = HERMES_TINT if self.mode == "hermes" else MIRROR_TINT
        self._mode_badge = ctk.CTkLabel(
            sub_row,
            text=f"● {self.mode.upper()}",
            font=("Segoe UI Semibold", 11),
            text_color=tint,
        )
        self._mode_badge.pack(side="left")

        # Right: status dot
        self._status_dot = ctk.CTkLabel(
            header, text="● OFFLINE",
            font=("Segoe UI Semibold", 11),
            text_color=BAD,
        )
        self._status_dot.pack(side="right", anchor="e", pady=(14, 0))

        # === Scrollable body (everything below the header goes here) ===
        # CTkScrollableFrame gives a native scrollbar so users can scroll
        # through tall content on smaller windows / higher DPI displays.
        self._scroll = ctk.CTkScrollableFrame(
            self.root,
            fg_color=BG_OUTER,
            corner_radius=0,
            scrollbar_button_color=ACCENT,
            scrollbar_button_hover_color=ACCENT_HOT,
        )
        self._scroll.pack(fill="both", expand=True, padx=0, pady=(0, 0))

        # === Network card ===============================================
        local_ip = self._get_local_ip()
        tablet_url = f"http://{local_ip}:{self.port}"
        ws_url = f"ws://{local_ip}:{self.port}"

        net_card = CardFrame(self._scroll, "SERVIDOR", accent=ACCENT)
        net_card.pack(fill="x", padx=18, pady=(8, 4))
        StatRow(net_card.body, "PC IP", local_ip).pack(fill="x", pady=2)
        StatRow(net_card.body, "Porta", str(self.port)).pack(fill="x", pady=2)
        StatRow(net_card.body, "WebSocket", ws_url).pack(fill="x", pady=2)
        StatRow(net_card.body, "Tablet URL", tablet_url,
                ).pack(fill="x", pady=2)

        # === Mode-specific settings card ================================
        if self.mode == "mirror":
            self._build_mirror_card()
        else:
            self._build_hermes_card()

        # === Live stats card ============================================
        self._build_stats_card()

        # === Event log card =============================================
        self._build_log_card()

        # === Bottom: stop button ========================================
        bottom = ctk.CTkFrame(self.root, fg_color="transparent")
        bottom.pack(fill="x", padx=18, pady=(10, 18))

        self._stop_btn = ctk.CTkButton(
            bottom, text="⏹  Parar servidor",
            height=42,
            corner_radius=10,
            font=("Segoe UI Semibold", 13),
            fg_color="#7f1d1d",
            hover_color="#991b1b",
            text_color="#ffffff",
            command=self._do_stop,
        )
        self._stop_btn.pack(fill="x")

        # Footer
        ctk.CTkLabel(
            self.root,
            text="MirrorX • PC ↔ Tablet via WiFi • 2026",
            font=("Segoe UI", 9),
            text_color=FG_DIM,
        ).pack(pady=(0, 8))

    # ── Settings cards (per mode) ─────────────────────────────────────
    def _build_mirror_card(self):
        card = CardFrame(self._scroll, "STREAM", accent=MIRROR_TINT)
        card.pack(fill="x", padx=18, pady=4)

        # FPS row (segmented: 15 / 24 / 30 / 45 / 60 / 90)
        ctk.CTkLabel(card.body, text="FPS",
                     font=("Segoe UI", 10), text_color=FG_DIM,
                     anchor="w").pack(fill="x", pady=(2, 0))
        self.fps_seg = SegmentedRow(
            card.body,
            options=[15, 24, 30, 45, 60, 90],
            default=self._safe_get("target_fps", 30),
            command=self._on_fps_change,
        )
        self.fps_seg.pack(fill="x", pady=(2, 8))

        # Quality slider
        qrow = ctk.CTkFrame(card.body, fg_color="transparent")
        qrow.pack(fill="x", pady=(2, 2))
        ctk.CTkLabel(qrow, text="Qualidade",
                     font=("Segoe UI", 10), text_color=FG_DIM,
                     anchor="w").pack(side="left")
        self._qual_value_lbl = ctk.CTkLabel(
            qrow, text=f"{int(self._safe_get('quality', 75))}%",
            font=("Consolas", 11), text_color=FG_BRIGHT,
        )
        self._qual_value_lbl.pack(side="right")
        self._qual_var = tk.IntVar(value=int(self._safe_get("quality", 75)))
        self._qual_slider = ctk.CTkSlider(
            card.body, from_=20, to=95,
            variable=self._qual_var,
            progress_color=ACCENT,
            button_color=ACCENT_HOT,
            button_hover_color=ACCENT_HOT,
            command=self._on_quality_change,
        )
        self._qual_slider.pack(fill="x", pady=(0, 8))

        # Scale row (segmented)
        ctk.CTkLabel(card.body, text="Escala",
                     font=("Segoe UI", 10), text_color=FG_DIM,
                     anchor="w").pack(fill="x", pady=(2, 0))
        self.scale_seg = SegmentedRow(
            card.body,
            options=["50%", "75%", "100%"],
            default=f"{int(self._safe_get('scale', 0.75) * 100)}%",
            command=self._on_scale_change,
        )
        self.scale_seg.pack(fill="x", pady=(2, 8))

        # Auto-adjust toggle
        self._auto_var = tk.BooleanVar(value=False)
        self._auto_switch = ctk.CTkSwitch(
            card.body, text="Ajuste automático baseado no FPS",
            variable=self._auto_var, command=self._on_auto_change,
            font=("Segoe UI", 11),
            progress_color=ACCENT,
            button_color=ACCENT_HOT,
            button_hover_color=ACCENT_HOT,
            text_color=FG,
        )
        self._auto_switch.pack(anchor="w", pady=(4, 2))

    def _build_hermes_card(self):
        card = CardFrame(self._scroll, "INPUT  (Hermes)", accent=HERMES_TINT)
        card.pack(fill="x", padx=18, pady=4)

        # Sensitivity slider
        srow = ctk.CTkFrame(card.body, fg_color="transparent")
        srow.pack(fill="x", pady=(2, 2))
        ctk.CTkLabel(srow, text="Sensibilidade",
                     font=("Segoe UI", 10), text_color=FG_DIM,
                     anchor="w").pack(side="left")
        self._sens_value_lbl = ctk.CTkLabel(
            srow, text="1.0x",
            font=("Consolas", 11), text_color=FG_BRIGHT,
        )
        self._sens_value_lbl.pack(side="right")
        self._sens_var = tk.DoubleVar(value=1.0)
        self._sens_slider = ctk.CTkSlider(
            card.body, from_=0.3, to=3.0,
            variable=self._sens_var,
            progress_color=HERMES_TINT,
            button_color=HERMES_TINT,
            button_hover_color="#c4b5fd",
            command=self._on_sens_change,
        )
        self._sens_slider.pack(fill="x", pady=(0, 8))

        # Send rate (60Hz = Normal, 30Hz = Ruim, 15Hz = Ultra)
        ctk.CTkLabel(card.body, text="Taxa de envio (limite)",
                     font=("Segoe UI", 10), text_color=FG_DIM,
                     anchor="w").pack(fill="x", pady=(2, 0))
        self.rate_seg = SegmentedRow(
            card.body,
            options=["60Hz", "30Hz", "15Hz"],
            default="60Hz",
            command=self._on_rate_change,
        )
        self.rate_seg.pack(fill="x", pady=(2, 8))

        # Smoothing toggle
        self._smooth_var = tk.BooleanVar(value=False)
        self._smooth_switch = ctk.CTkSwitch(
            card.body, text="Suavizar cursor (pode adicionar latência)",
            variable=self._smooth_var, command=self._on_smooth_change,
            font=("Segoe UI", 11),
            progress_color=HERMES_TINT,
            button_color=HERMES_TINT,
            button_hover_color="#c4b5fd",
            text_color=FG,
        )
        self._smooth_switch.pack(anchor="w", pady=(4, 2))

    # ── Stats card (shared, mode-aware) ────────────────────────────────
    def _build_stats_card(self):
        card = CardFrame(self._scroll, "STATUS  (tempo real)", accent=OK)
        card.pack(fill="x", padx=18, pady=4)
        self._stats_card_body = card.body

        if self.mode == "mirror":
            self._s_fps      = StatRow(card.body, "FPS", "—")
            self._s_clients  = StatRow(card.body, "Clientes", "0")
            self._s_screen   = StatRow(card.body, "Tela", "—")
            self._s_stream   = StatRow(card.body, "Stream", "—")
            self._s_frame    = StatRow(card.body, "Frame médio", "—")
            self._s_banda    = StatRow(card.body, "Banda", "—")
            self._s_cursor   = StatRow(card.body, "Cursor", "—")
            self._s_mode     = StatRow(card.body, "Encoder", "—")
            for w in (self._s_fps, self._s_clients, self._s_screen,
                      self._s_stream, self._s_frame, self._s_banda,
                      self._s_cursor, self._s_mode):
                w.pack(fill="x", pady=1)
        else:
            self._s_clients  = StatRow(card.body, "Clientes", "0")
            self._s_cmds     = StatRow(card.body, "Comandos/s", "—")
            self._s_latency  = StatRow(card.body, "Latência", "—")
            self._s_conn     = StatRow(card.body, "Modo conexão", "—")
            self._s_moves    = StatRow(card.body, "Moves", "0")
            self._s_clicks   = StatRow(card.body, "Clicks", "0")
            self._s_scrolls  = StatRow(card.body, "Scrolls", "0")
            self._s_errors   = StatRow(card.body, "Erros", "0")
            for w in (self._s_clients, self._s_cmds, self._s_latency,
                      self._s_conn, self._s_moves, self._s_clicks,
                      self._s_scrolls, self._s_errors):
                w.pack(fill="x", pady=1)

    # ── Event log card ────────────────────────────────────────────────
    def _build_log_card(self):
        card = CardFrame(self._scroll, "EVENTOS", accent=WARN)
        card.pack(fill="both", expand=True, padx=18, pady=4)

        style = ttk.Style()
        style.theme_use("clam")
        style.configure(
            "MirrorX.Treeview",
            background=BG_INPUT,
            fieldbackground=BG_INPUT,
            foreground=FG,
            rowheight=20,
            borderwidth=0,
            font=("Consolas", 10),
        )
        style.map("MirrorX.Treeview",
                  background=[("selected", ACCENT)],
                  foreground=[("selected", "#ffffff")])

        self._log = ttk.Treeview(
            card.body,
            columns=("t", "msg"),
            show="headings",
            height=6,
            style="MirrorX.Treeview",
        )
        self._log.heading("t", text="hora")
        self._log.heading("msg", text="evento")
        self._log.column("t", width=70, anchor="w")
        self._log.column("msg", width=380, anchor="w")
        self._log.tag_configure("info", foreground=FG)
        self._log.tag_configure("ok", foreground=OK)
        self._log.tag_configure("warn", foreground=WARN)
        self._log.tag_configure("bad", foreground=BAD)
        self._log.pack(fill="both", expand=True, pady=(4, 4))

        self._log_count = 0

    # ── Cross-thread helpers ──────────────────────────────────────────
    def _post(self, fn: Callable[[], None]):
        """Schedule `fn` on the Tk thread (safe from asyncio)."""
        try:
            self._q.put_nowait(fn)
        except Exception:
            pass
        try:
            self.root.after(0, self._drain)
        except Exception:
            pass

    def _drain(self):
        try:
            while True:
                fn = self._q.get_nowait()
                try:
                    fn()
                except Exception as e:
                    log.exception("queued fn failed: %s", e)
        except queue.Empty:
            pass

    # ── Public update API ─────────────────────────────────────────────
    def update_stats(self, stats: Dict[str, Any]):
        """Called by server loop (any thread). Cheap snapshot only."""
        self._stats = dict(stats)
        self._post(self._render_stats)

    def log_event(self, text: str, level: str = "info"):
        self._post(lambda: self._append_log(text, level))

    def _append_log(self, text: str, level: str):
        ts = time.strftime("%H:%M:%S")
        self._log.insert("", "end", values=(ts, text), tags=(level,))
        self._log_count += 1
        # Keep last 200 entries
        if self._log_count > 200:
            children = self._log.get_children()
            if children:
                self._log.delete(children[0])
                self._log_count -= 1
        kids = self._log.get_children()
        if kids:
            self._log.see(kids[-1])

    # ── Render stats (called on Tk thread) ─────────────────────────────
    def _render_stats(self):
        st = self._stats
        if not st:
            return

        # Status dot
        clients = int(st.get("clients", 0))
        if clients > 0:
            self._status_dot.configure(text="● ONLINE", text_color=OK)
        else:
            self._status_dot.configure(text="● AGUARDANDO", text_color=WARN)

        if self.mode == "mirror":
            self._render_mirror_stats(st)
        else:
            self._render_hermes_stats(st)

        # Reschedule
        try:
            self.root.after(self.POLL_MS, self._render_stats)
        except Exception:
            pass

    def _render_mirror_stats(self, st: Dict[str, Any]):
        fps = float(st.get("fps", 0))
        fps_color = OK if fps >= 25 else (WARN if fps >= 15 else BAD)
        self._s_fps.set_value(f"{fps:.1f}", fps_color)
        self._s_clients.set_value(str(clients := st.get("clients", 0)))

        sw, sh = st.get("screen_size") or (0, 0)
        self._s_screen.set_value(f"{sw}×{sh}")

        scale = float(st.get("scale", 0.75))
        q = int(st.get("quality", 75))
        ssw, ssh = int(sw * scale) if sw else 0, int(sh * scale) if sh else 0
        self._s_stream.set_value(f"{ssw}×{ssh}  ({scale:.0%}, q={q})")

        fb = st.get("frame_bytes", 0) or 0
        self._s_frame.set_value(f"{fb/1024:.1f} KB")

        bps = st.get("bandwidth_bps", 0) or 0
        self._s_banda.set_value(f"{bps/1024:.0f} KB/s")

        cur = st.get("cursor") or (0, 0)
        self._s_cursor.set_value(f"({cur[0]}, {cur[1]})")

        enc = st.get("encoder", "—")
        self._s_mode.set_value(enc)

    def _render_hermes_stats(self, st: Dict[str, Any]):
        self._s_clients.set_value(str(st.get("clients", 0)))
        self._s_cmds.set_value(f"{st.get('cmds_per_sec', 0):.0f}")
        lat = float(st.get("latency_ms", 0))
        lat_color = OK if lat < 60 else (WARN if lat < 120 else BAD)
        self._s_latency.set_value(f"{lat:.0f} ms", lat_color)
        self._s_conn.set_value(st.get("conn_mode", "—"))
        self._s_moves.set_value(str(st.get("moves", 0)))
        self._s_clicks.set_value(str(st.get("clicks", 0)))
        self._s_scrolls.set_value(str(st.get("scrolls", 0)))
        err = st.get("errors", 0)
        self._s_errors.set_value(str(err), BAD if err > 0 else FG_BRIGHT)

    # ── Settings callbacks (mirror) ───────────────────────────────────
    def _on_fps_change(self, opt):
        if hasattr(self.server, "target_fps"):
            self.server.target_fps = int(opt)
            self.log_event(f"FPS → {opt}", "ok")

    def _on_quality_change(self, v):
        q = int(float(v))
        self._qual_value_lbl.configure(text=f"{q}%")
        if hasattr(self.server, "quality"):
            self.server.quality = q

    def _on_scale_change(self, opt):
        scale = int(opt.rstrip("%")) / 100.0
        if hasattr(self.server, "scale"):
            self.server.scale = scale
            self.log_event(f"Scale → {opt}", "ok")

    def _on_auto_change(self):
        if hasattr(self.server, "auto_adjust"):
            self.server.auto_adjust = bool(self._auto_var.get())
            self.log_event(
                f"Auto-ajuste → {'ON' if self._auto_var.get() else 'OFF'}",
                "ok",
            )

    # ── Settings callbacks (hermes) ───────────────────────────────────
    def _on_sens_change(self, v):
        sens = float(v)
        self._sens_value_lbl.configure(text=f"{sens:.1f}x")
        if hasattr(self.server, "sensitivity"):
            self.server.sensitivity = sens

    def _on_rate_change(self, opt):
        rate_map = {"60Hz": 0, "30Hz": 1, "15Hz": 2}
        m = rate_map.get(opt, 0)
        try:
            from server_hermes import ConnectionMode  # type: ignore
            mode = [ConnectionMode.NORMAL, ConnectionMode.BAD,
                    ConnectionMode.ULTRA][m]
            if hasattr(self.server, "smoother"):
                self.server.smoother.set_mode(mode)
            if hasattr(self.server, "mode"):
                self.server.mode = mode
            self.log_event(f"Rate → {opt}", "ok")
        except Exception as e:
            log.warning("could not apply rate: %s", e)

    def _on_smooth_change(self):
        v = bool(self._smooth_var.get())
        if hasattr(self.server, "smoothing"):
            self.server.smoothing = v
        self.log_event(f"Smoothing → {'ON' if v else 'OFF'}",
                       "ok" if v else "warn")

    # ── Stop ──────────────────────────────────────────────────────────
    def _do_stop(self):
        self.log_event("Parando servidor...", "warn")
        try:
            if callable(self.on_stop):
                self.on_stop()
        except Exception as e:
            log.exception("on_stop failed: %s", e)
        try:
            self.root.after(300, self.root.destroy)
        except Exception:
            pass

    # ── Misc ──────────────────────────────────────────────────────────
    def _safe_get(self, attr, default):
        try:
            return getattr(self.server, attr, default)
        except Exception:
            return default

    @staticmethod
    def _get_local_ip() -> str:
        try:
            import socket
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            s.close()
            return ip
        except Exception:
            return "127.0.0.1"

    # ── Main loop ─────────────────────────────────────────────────────
    def start(self):
        """Blocks. Call from main thread (customtkinter requirement)."""
        try:
            self.root.mainloop()
        except Exception as e:
            log.exception("panel mainloop crashed: %s", e)


# ── Stand-alone launcher (for manual UI testing) ────────────────────────
if __name__ == "__main__":
    logging.basicConfig(level=logging.DEBUG)

    class FakeServer:
        target_fps = 30
        quality = 75
        scale = 0.75
        auto_adjust = False
        sensitivity = 1.0
        smoothing = False
        smoother = None
        mode = None

    p = ControlPanel(
        mode="hermes",
        server_obj=FakeServer(),
        port=9900,
        host="0.0.0.0",
        on_stop=lambda: print("[stop requested]"),
    )
    p.log_event("MirrorX v1.5.4 iniciado", "ok")
    p.log_event("Modo Hermes ativo — aguardando tablet...", "info")
    p.start()
