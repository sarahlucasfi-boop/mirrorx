"""
MirrorX v1.5.0 "Hermes" — JSON Protocol Definitions
=====================================================

Defines the compact JSON packet format that the Android app sends to the
PC server for mouse/keyboard control. JSON was chosen over binary because
it's trivial to parse on Android (org.json / Gson) and the per-packet
overhead (~25 bytes vs ~5 bytes binary) is insignificant at 60 Hz.

Packet types
------------
    m : relative mouse move       {"t":"m","x":12,"y":-4}
    c : mouse click               {"t":"c","b":0}    # 0=L, 1=R, 2=M
    s : scroll                    {"t":"s","v":-3}
    k : key press / release       {"t":"k","k":"a","p":true}
    q : quality / connection mode {"t":"q","m":2}     # 0=normal, 1=ruim, 2=ultra
    h : heartbeat                 {"t":"h","ms":35}   # Android→PC latency

Server responses (PC → Android)
------------------------------
    {"type":"hello","version":"1.5.0","mode":"hermes"}
    {"type":"ack","t":"c","ok":true}
    {"type":"mode","m":2,"reason":"manual"}
    {"type":"error","msg":"unknown command"}
"""

from __future__ import annotations
import json
from dataclasses import dataclass
from enum import IntEnum
from typing import Optional, Union


# ---------------------------------------------------------------------------
# Connection quality modes
# ---------------------------------------------------------------------------
class ConnectionMode(IntEnum):
    NORMAL = 0   # 60 FPS mouse updates, no smoothing
    BAD    = 1   # 30 FPS, smooth + group moves
    ULTRA  = 2   # 15 FPS, quantize to 10px grid


# ---------------------------------------------------------------------------
# Packet dataclasses
# ---------------------------------------------------------------------------
@dataclass
class MovePacket:
    t: str = "m"
    x: int = 0
    y: int = 0


@dataclass
class ClickPacket:
    t: str = "c"
    b: int = 0  # 0=L, 1=R, 2=M, 3=double-left


@dataclass
class ScrollPacket:
    t: str = "s"
    v: int = 0  # positive=down, negative=up


@dataclass
class KeyPacket:
    t: str = "k"
    k: str = ""
    p: bool = True  # true=down, false=up


@dataclass
class QualityPacket:
    t: str = "q"
    m: int = 0  # ConnectionMode


@dataclass
class HeartbeatPacket:
    t: str = "h"
    ms: int = 0  # round-trip latency Android is feeling


Packet = Union[MovePacket, ClickPacket, ScrollPacket, KeyPacket,
               QualityPacket, HeartbeatPacket]


# ---------------------------------------------------------------------------
# Parser
# ---------------------------------------------------------------------------
class ProtocolError(ValueError):
    """Raised when a packet cannot be parsed."""


# String constants for the JSON "t" field — avoids typos.
TYPE_MOVE      = "m"
TYPE_CLICK     = "c"
TYPE_SCROLL    = "s"
TYPE_KEY       = "k"
TYPE_QUALITY   = "q"
TYPE_HEARTBEAT = "h"

# Max sizes — protects server from malformed/oversized packets.
MAX_PACKET_BYTES = 4096
MAX_KEY_LEN      = 32


def parse_packet(data: str) -> Packet:
    """Parse a JSON string into a typed Packet object.

    Raises ProtocolError on malformed input. The function never raises
    on missing fields; defaults are used so a slightly-malformed packet
    doesn't kill the input loop.
    """
    if not isinstance(data, str):
        raise ProtocolError(f"expected str, got {type(data).__name__}")
    if len(data) > MAX_PACKET_BYTES:
        raise ProtocolError(f"packet too large ({len(data)} bytes)")
    try:
        obj = json.loads(data)
    except json.JSONDecodeError as e:
        raise ProtocolError(f"invalid JSON: {e.msg}") from None
    if not isinstance(obj, dict):
        raise ProtocolError("packet must be a JSON object")

    t = obj.get("t")
    if not isinstance(t, str) or len(t) != 1:
        raise ProtocolError(f"missing/invalid type field: {t!r}")

    if t == TYPE_MOVE:
        x = _coerce_int(obj.get("x", 0))
        y = _coerce_int(obj.get("y", 0))
        # Cap deltas to sane range (one screen-width per packet is plenty)
        x = max(-8192, min(8192, x))
        y = max(-8192, min(8192, y))
        return MovePacket(x=x, y=y)

    if t == TYPE_CLICK:
        b = _coerce_int(obj.get("b", 0))
        if b not in (0, 1, 2, 3):
            raise ProtocolError(f"invalid click button: {b}")
        return ClickPacket(b=b)

    if t == TYPE_SCROLL:
        v = _coerce_int(obj.get("v", 0))
        v = max(-50, min(50, v))  # 50 notches is already a lot
        return ScrollPacket(v=v)

    if t == TYPE_KEY:
        k = obj.get("k", "")
        if not isinstance(k, str) or not k or len(k) > MAX_KEY_LEN:
            raise ProtocolError(f"invalid key: {k!r}")
        p = bool(obj.get("p", True))
        return KeyPacket(k=k, p=p)

    if t == TYPE_QUALITY:
        m = _coerce_int(obj.get("m", 0))
        if m not in (0, 1, 2):
            raise ProtocolError(f"invalid mode: {m}")
        return QualityPacket(m=m)

    if t == TYPE_HEARTBEAT:
        ms = _coerce_int(obj.get("ms", 0))
        ms = max(0, min(10_000, ms))
        return HeartbeatPacket(ms=ms)

    raise ProtocolError(f"unknown packet type: {t!r}")


def _coerce_int(value) -> int:
    """Convert to int, treating floats with no fractional part as ints."""
    if isinstance(value, bool):  # bool is a subclass of int — reject
        return int(value)
    if isinstance(value, int):
        return value
    if isinstance(value, float):
        if value != value:  # NaN
            return 0
        return int(value)
    if isinstance(value, str):
        try:
            return int(value)
        except ValueError:
            return 0
    return 0


# ---------------------------------------------------------------------------
# Response builders (PC → Android)
# ---------------------------------------------------------------------------
def hello_msg(version: str = "1.7.2", mode: str = "hermes") -> str:
    return json.dumps({"type": "hello", "version": version, "mode": mode})


def ack_msg(t: str, ok: bool = True) -> str:
    return json.dumps({"type": "ack", "t": t, "ok": ok})


def mode_msg(m: int, reason: str = "manual") -> str:
    return json.dumps({"type": "mode", "m": m, "reason": reason})


def error_msg(msg: str) -> str:
    return json.dumps({"type": "error", "msg": msg[:200]})


def cursor_pos_msg(x: int, y: int) -> str:
    """Tell the tablet where the PC cursor actually is (for ghost cursor)."""
    return json.dumps({"type": "cursor_pos", "x": int(x), "y": int(y)})
