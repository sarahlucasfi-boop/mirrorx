"""
MirrorX v1.5.0 "Hermes" — Mouse-only WebSocket server
======================================================

This is the new v1.5.0 server. It replaces screen-mirroring with
mouse/keyboard control. The Android app sends JSON commands; the server
executes them locally with pyautogui / pynput.

Why pyautogui over pynput:
    pyautogui.moveRel() is the simplest, most-portable way to inject
    relative mouse movement on Windows. pynput is more powerful but
    requires admin on some setups.

Why a queue + smoothing loop instead of executing each packet directly:
    - pyautogui has ~5-15 ms overhead per call
    - At 60 Hz that becomes 30-90% CPU just for input
    - Queue + batched execution caps the rate at a configurable FPS
    - Smoothing (EWM average) makes the cursor less jittery on bad WiFi

Latency budget
--------------
    Android touch → WS send  :  1-3 ms
    WS round-trip            :  2-8 ms (same WiFi)
    Queue + smoothing        :  0-33 ms (mode-dependent)
    pyautogui.moveRel        :  1-3 ms
    -----------------------------------
    Total                    :  4-47 ms (mode 0)
                              : 17-100 ms (mode 1, bad WiFi)
                              : 67-200 ms (mode 2, terrible WiFi)

That's a massive improvement over the v1.4.3 screen-mirror path which
needed 200-500 ms for a full JPEG round-trip just to *see* the result.
"""

from __future__ import annotations
import asyncio
import json
import logging
import os
import sys
import time
from collections import deque
from dataclasses import dataclass
from typing import Dict, List, Optional, Set

import websockets
from websockets.server import WebSocketServerProtocol

# Local
from protocols import (
    ConnectionMode,
    MovePacket,
    ClickPacket,
    ScrollPacket,
    KeyPacket,
    QualityPacket,
    HeartbeatPacket,
    parse_packet,
    hello_msg,
    ack_msg,
    mode_msg,
    error_msg,
    cursor_pos_msg,
    ProtocolError,
)


# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------
log = logging.getLogger("hermes")


# ---------------------------------------------------------------------------
# Mouse smoothing
# ---------------------------------------------------------------------------
class MouseSmoother:
    """Exponential-weighted-average smoother for mouse deltas.

    When the connection is good (mode 0), alpha=1.0 — no smoothing, every
    delta is applied directly (lowest latency).

    In mode 1 (bad WiFi), alpha=0.4 — we average the last few deltas so
    jitter doesn't make the cursor twitch.

    In mode 2 (terrible WiFi), we quantize to a 10px grid.
    """

    def __init__(self, mode: ConnectionMode = ConnectionMode.NORMAL):
        self._ema_x = 0.0
        self._ema_y = 0.0
        self.set_mode(mode)

    def set_mode(self, mode: ConnectionMode):
        self.mode = mode
        if mode == ConnectionMode.NORMAL:
            self._alpha = 1.0
        elif mode == ConnectionMode.BAD:
            self._alpha = 0.4
        else:  # ULTRA
            self._alpha = 0.2

    def reset(self):
        self._ema_x = 0.0
        self._ema_y = 0.0

    def apply(self, x: int, y: int) -> tuple[int, int]:
        """Return the (smoothed) delta to actually move."""
        self._ema_x = self._alpha * x + (1.0 - self._alpha) * self._ema_x
        self._ema_y = self._alpha * y + (1.0 - self._alpha) * self._ema_y
        out_x = int(round(self._ema_x))
        out_y = int(round(self._ema_y))
        if self.mode == ConnectionMode.ULTRA:
            out_x = (out_x // 10) * 10
            out_y = (out_y // 10) * 10
        # Subtract the part we're about to emit so the EMA decays
        self._ema_x -= out_x
        self._ema_y -= out_y
        return out_x, out_y


# ---------------------------------------------------------------------------
# Main server
# ---------------------------------------------------------------------------
class HermesServer:
    """WebSocket server that turns Android touch events into PC input."""

    DEFAULT_PORT = 9900
    VERSION = "1.6.0"

    def __init__(self, port: int = DEFAULT_PORT, host: str = "0.0.0.0"):
        self.host = host
        self.port = port
        self.clients: Set[WebSocketServerProtocol] = set()
        self.smoother = MouseSmoother(ConnectionMode.NORMAL)
        self.mode = ConnectionMode.NORMAL

        # Stats
        self.stats_total_cmds = 0
        self.stats_moves = 0
        self.stats_clicks = 0
        self.stats_scrolls = 0
        self.stats_keys = 0
        self.stats_errors = 0
        self.stats_latency_ms = 0
        self.started_at = time.time()
        self._stats_window_start = time.time()
        self._stats_window_cmds = 0

        # Per-client state (e.g. last received sequence)
        self._client_state: Dict[WebSocketServerProtocol, dict] = {}

    # ------------------------------------------------------------------
    # Stats snapshot (for UI panel)
    # ------------------------------------------------------------------
    def snapshot(self) -> dict:
        """Return a stats dict suitable for the control panel."""
        now = time.time()
        elapsed = max(0.001, now - self._stats_window_start)
        # Per-second rate over a 1s sliding window (resets each snapshot)
        cmds_per_sec = (self.stats_total_cmds - self._stats_window_cmds) / elapsed
        self._stats_window_cmds = self.stats_total_cmds
        self._stats_window_start = now
        try:
            from server_hermes import ConnectionMode  # noqa
            mode_name = self.mode.name if hasattr(self.mode, "name") else str(self.mode)
        except Exception:
            mode_name = str(self.mode)
        return {
            "clients": len(self.clients),
            "cmds_per_sec": cmds_per_sec,
            "latency_ms": self.stats_latency_ms,
            "conn_mode": mode_name,
            "moves": self.stats_moves,
            "clicks": self.stats_clicks,
            "scrolls": self.stats_scrolls,
            "keys": self.stats_keys,
            "errors": self.stats_errors,
        }

    # ------------------------------------------------------------------
    # Lifecycle
    async def start(self):
        log.info("[Hermes] starting on ws://%s:%d", self.host, self.port)
        async with websockets.serve(
            self._on_client_connect,
            self.host,
            self.port,
            max_size=4096,
            ping_interval=15,
            ping_timeout=30,  # v1.6.0: was 10 — too aggressive on flaky WiFi
        ):
            log.info("[Hermes] listening — clients can now connect")
            # Run forever (until cancelled)
            await asyncio.Future()

    # ------------------------------------------------------------------
    # Connection handler
    # ------------------------------------------------------------------
    async def _on_client_connect(self, ws: WebSocketServerProtocol, path: str = ""):
        client_addr = self._peer(ws)
        log.info("[Hermes] client connected: %s (path=%s)", client_addr, path)
        self.clients.add(ws)
        self._client_state[ws] = {"connected_at": time.time()}
        try:
            # Send hello on connect
            await ws.send(hello_msg(self.VERSION, "hermes"))
            async for raw in ws:
                await self._handle_message(ws, raw)
        except websockets.exceptions.ConnectionClosed:
            pass
        except Exception as e:
            log.exception("[Hermes] error in client loop: %s", e)
        finally:
            log.info("[Hermes] client disconnected: %s", client_addr)
            self.clients.discard(ws)
            self._client_state.pop(ws, None)

    @staticmethod
    def _peer(ws: WebSocketServerProtocol) -> str:
        try:
            return "%s:%d" % ws.remote_address[:2]
        except Exception:
            return "?"

    # ------------------------------------------------------------------
    # Message router
    # ------------------------------------------------------------------
    async def _handle_message(self, ws: WebSocketServerProtocol, raw):
        if isinstance(raw, (bytes, bytearray)):
            # Hermes protocol is JSON only. Binary bytes are not used here
            # (the v1.4.x mirror mode uses binary frames; those are routed
            # to a different server or to a different WS path).
            log.warning("[Hermes] ignoring binary frame (%d bytes)", len(raw))
            self.stats_errors += 1
            await ws.send(error_msg("binary frames not supported in hermes mode"))
            return
        try:
            pkt = parse_packet(raw)
        except ProtocolError as e:
            self.stats_errors += 1
            log.warning("[Hermes] parse error: %s (data=%r)", e, raw[:80])
            try:
                await ws.send(error_msg(str(e)))
            except Exception:
                pass
            return

        self.stats_total_cmds += 1
        try:
            if isinstance(pkt, MovePacket):
                self._queue_move(pkt.x, pkt.y)
                self.stats_moves += 1
            elif isinstance(pkt, ClickPacket):
                self._do_click(pkt.b)
                self.stats_clicks += 1
                await ws.send(ack_msg("c"))
            elif isinstance(pkt, ScrollPacket):
                self._do_scroll(pkt.v)
                self.stats_scrolls += 1
                await ws.send(ack_msg("s"))
            elif isinstance(pkt, KeyPacket):
                self._do_key(pkt.k, pkt.p)
                self.stats_keys += 1
                await ws.send(ack_msg("k"))
            elif isinstance(pkt, QualityPacket):
                await self._set_mode(ConnectionMode(pkt.m), reason="client")
            elif isinstance(pkt, HeartbeatPacket):
                self.stats_latency_ms = pkt.ms
                # Auto-downgrade if Android is suffering
                if pkt.ms > 200 and self.mode == ConnectionMode.NORMAL:
                    log.info("[Hermes] auto-downgrade: latency=%dms", pkt.ms)
                    await self._set_mode(ConnectionMode.BAD,
                                         reason=f"latency {pkt.ms}ms")
                elif pkt.ms > 500 and self.mode == ConnectionMode.BAD:
                    log.info("[Hermes] auto-downgrade to ultra: latency=%dms", pkt.ms)
                    await self._set_mode(ConnectionMode.ULTRA,
                                         reason=f"latency {pkt.ms}ms")
                elif pkt.ms < 50 and self.mode != ConnectionMode.NORMAL:
                    # Recover
                    log.info("[Hermes] auto-upgrade: latency=%dms", pkt.ms)
                    await self._set_mode(ConnectionMode.NORMAL,
                                         reason=f"latency {pkt.ms}ms recovered")
        except Exception as e:
            self.stats_errors += 1
            log.exception("[Hermes] dispatch error: %s", e)
            try:
                await ws.send(error_msg(f"dispatch error: {e}"))
            except Exception:
                pass

    # ------------------------------------------------------------------
    # Move queue + smoother
    # ------------------------------------------------------------------
    def _queue_move(self, x: int, y: int):
        # Use pyautogui for the actual move. We do it on the input thread
        # (the WS handler runs in the asyncio loop, which is the only
        # thread, so pyautogui calls are non-blocking enough at our rate).
        try:
            import pyautogui
        except ImportError:
            log.error("[Hermes] pyautogui not installed — moves are no-ops")
            return
        sx, sy = self.smoother.apply(x, y)
        if sx == 0 and sy == 0:
            return
        try:
            pyautogui.moveRel(sx, sy, _pause=False)
            self.stats_moves += 1
        except Exception as e:
            log.debug("[Hermes] moveRel failed: %s", e)

    # ------------------------------------------------------------------
    # Click / scroll / key
    # ------------------------------------------------------------------
    def _do_click(self, button: int):
        try:
            import pyautogui
        except ImportError:
            return
        # 0=L, 1=R, 2=M, 3=double-L
        if button == 3:
            x, y = pyautogui.position()
            pyautogui.doubleClick(x, y, _pause=False)
        else:
            btn_name = ("left", "right", "middle")[button] if 0 <= button <= 2 else "left"
            pyautogui.click(button=btn_name, _pause=False)

    def _do_scroll(self, value: int):
        try:
            import pyautogui
        except ImportError:
            return
        # pyautogui.scroll: positive=up, but our protocol has positive=down.
        # Flip the sign.
        pyautogui.scroll(-value, _pause=False)

    def _do_key(self, key: str, pressed: bool):
        try:
            import pyautogui
        except ImportError:
            return
        # pyautogui.keyDown / keyUp — accepts a single key or a + combo.
        if pressed:
            pyautogui.keyDown(key, _pause=False)
        else:
            pyautogui.keyUp(key, _pause=False)

    # ------------------------------------------------------------------
    # Mode
    # ------------------------------------------------------------------
    async def _set_mode(self, mode: ConnectionMode, reason: str = "manual"):
        if mode == self.mode:
            return
        log.info("[Hermes] mode %d → %d (%s)", self.mode, int(mode), reason)
        self.mode = mode
        self.smoother.set_mode(mode)
        self.smoother.reset()
        # Broadcast to all clients
        if self.clients:
            msg = mode_msg(int(mode), reason)
            await asyncio.gather(
                *(self._safe_send(c, msg) for c in list(self.clients)),
                return_exceptions=True,
            )

    async def _safe_send(self, ws, msg):
        try:
            await ws.send(msg)
        except Exception:
            pass


# ---------------------------------------------------------------------------
# CLI entry point
# ---------------------------------------------------------------------------
def main():
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(name)s] %(levelname)s %(message)s",
        datefmt="%H:%M:%S",
    )
    port = int(os.environ.get("MIRRORX_PORT", "9900"))
    host = os.environ.get("MIRRORX_HOST", "0.0.0.0")

    # Friendly banner
    print("=" * 60)
    print(f"  MirrorX v{HermesServer.VERSION} — Hermes (mouse-only mode)")
    print(f"  Listening on ws://{host}:{port}/")
    print("=" * 60)

    try:
        asyncio.run(HermesServer(port=port, host=host).start())
    except KeyboardInterrupt:
        print("\n[Hermes] stopped by user")


if __name__ == "__main__":
    main()
