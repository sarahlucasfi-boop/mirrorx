# MirrorX

**Turn any Android tablet into a wireless second screen for Windows.**

No cables. No tablet app install. Just open Chrome and go.

[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Windows](https://img.shields.io/badge/Windows-10%2F11-blue)](https://github.com)
[![Version](https://img.shields.io/badge/version-1.0.0-6366f1)](https://github.com)

---

## Why MirrorX?

| | MirrorX | SuperDisplay | Spacedesk |
|---|---|---|---|
| **Price** | Free / R$22 lifetime | $14.99 | Free |
| **Open Source** | ✅ MIT | ❌ | ❌ |
| **Tablet App** | Browser (no install) | Android APK | Android APK |
| **WiFi** | ✅ | ✅ | ✅ |
| **Touch Input** | ✅ | ✅ | ✅ |
| **Virtual Display** | Premium | ✅ | ✅ |

---

## Quick Start

### 1. Download & Install on PC

Download the latest `MirrorX.exe` from [Releases](https://github.com) and run it.

Or run from source:

```bash
git clone https://github.com/YOUR_USER/mirrorx.git
cd mirrorx
pip install -r requirements.txt
python server.py
```

### 2. Open on Tablet

Open Chrome on your tablet and go to:
```
http://192.168.X.X:8080
```

The IP is shown in the MirrorX server window on your PC.

### 3. Start Working

Your PC screen appears on your tablet. Touch, draw, type — just like a real monitor.

---

## Features

- **DXGI Screen Capture** — Hardware-accelerated, low latency
- **WebSocket Streaming** — 30 FPS (60 FPS Premium)
- **Touch & Pen Input** — Tap, drag, scroll, draw
- **100% Local** — Nothing leaves your WiFi network
- **Multiple Modes** — Move cursor, click, or draw
- **Keyboard Shortcuts** — Esc, Win, Tab, Enter from toolbar

---

## Premium (R$22 Lifetime)

| Feature | Free | Premium |
|---|---|---|
| Screen Mirroring | ✅ | ✅ |
| Touch Input | ✅ | ✅ |
| Frame Rate | 30 FPS | 60 FPS |
| Virtual Display Driver | ❌ | ✅ |
| Multi-Monitor | ❌ | ✅ |
| Stylus Pressure | ❌ | ✅ |
| Custom Resolutions | ❌ | ✅ |

[Get Premium License](https://github.com)

---

## Tech Stack

- **PC Server:** Python 3.11+, dxcam, OpenCV, websockets, PyAutoGUI
- **Tablet Client:** HTML5 Canvas, WebSocket, Vanilla JS
- **Packaging:** PyInstaller (.exe)

---

## From Source

```bash
git clone https://github.com/YOUR_USER/mirrorx.git
cd mirrorx
pip install -r requirements.txt
python server.py
```

## License

MIT — free for personal and commercial use. Premium features require a license key.
