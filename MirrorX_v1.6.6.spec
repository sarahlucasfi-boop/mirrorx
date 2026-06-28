# -*- mode: python ; coding: utf-8 -*-
"""
MirrorX v1.5.3 — PyInstaller spec (rebuilt clean).
==================================================

v1.5.0 spec used upx=True but UPX is NOT installed on this PC, which
caused PyInstaller to silently corrupt the EXE tail (no MEI marker,
bootloader crashes with PYI-1180/18172 "Could not load PyInstaller's
embedded PKG archive"). Fixed: upx=False here.

Also renamed to v1.5.3 to match the APK.
"""

import sys
from pathlib import Path
from PyInstaller.utils.hooks import collect_data_files, collect_submodules

block_cipher = None
PROJECT = Path(SPECPATH)
SRC = PROJECT / "src"

# Files to bundle into the EXE
# customtkinter ships JSON theme assets that PyInstaller must embed.
datas = collect_data_files("customtkinter")
hiddenimports = []

hiddenimports += collect_submodules("customtkinter")
hiddenimports += collect_submodules("tkinter")

hiddenimports += [
    # Engines
    "cv2",
    "numpy",
    "PIL",
    "PIL._imaging",
    "PIL.Image",
    "PIL.ImageGrab",
    "dxcam",
    "mss",
    # Input
    "pyautogui",
    "pynput",
    "pynput.mouse",
    "pynput.keyboard",
    "mouseinfo",
    "pyscreeze",
    "pymsgbox",
    "pygetwindow",
    "pyrect",
    "pyperclip",
    "pytweening",
    # Web
    "websocket",
    "websockets",
    "websockets.asyncio",
    "websockets.legacy",
    "websockets.server",
    "websockets.exceptions",
    # Concurrency
    "asyncio",
    "aiohttp",
    # stdlib that PyInstaller sometimes skips
    "email",
    "email.message",
    "http",
    "http.client",
    "xml",
    "xml.etree",
    "html",
    "pydoc",
    "doctest",
    "importlib",
    "importlib.metadata",
    "importlib.util",
    # Our own modules
    "protocols",
    "server_hermes",
    "panel_ui",
]

a = Analysis(
    ['src/server.py'],
    pathex=[str(SRC)],
    binaries=[],
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[
        "tkinter.test",
        "unittest.test",
        "test",
        "pytest",
    ],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=block_cipher,
    noarchive=False,
)
pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.zipfiles,
    a.datas,
    [],
    name='MirrorX_v1.6.6',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,  # UPX not installed — must be False or build corrupts
    upx_exclude=[],
    runtime_tmpdir=None,
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=r'assets\icon.ico',
)