# -*- mode: python ; coding: utf-8 -*-
# PyInstaller spec for MirrorX server v1.4.3
# - BGR capture (no RGB→BGR conversion) — matches OpenCV natively
# - max_buffer_len=3 to prevent frozen screen
# - Last-frame cache: never sends black/blank when dxcam misses
# - Custom mirrorx.ico embedded
# - Includes TurboJPEG DLL (Windows) and Pillow's libs

import os
import sys
import glob

block_cipher = None

# Locate TurboJPEG DLL at runtime on the build machine
TURBOJPEG_DLL = None
for cand in [
    r"C:\Python312\Lib\site-packages\TurboJPEG-3.1.3.dist-info",
    r"C:\Python312\Lib\site-packages",
    r"C:\Python312\Lib\site-packages\pillow.libs",
]:
    for f in glob.glob(os.path.join(cand, "turbojpeg*.dll")) + glob.glob(os.path.join(cand, "libturbojpeg*.dll")):
        TURBOJPEG_DLL = f
        break
    if TURBOJPEG_DLL:
        break

if not TURBOJPEG_DLL:
    import site
    for sp in site.getsitepackages():
        for f in glob.glob(os.path.join(sp, "**", "turbojpeg*.dll"), recursive=True) + \
                   glob.glob(os.path.join(sp, "**", "libturbojpeg*.dll"), recursive=True):
            TURBOJPEG_DLL = f
            break
        if TURBOJPEG_DLL:
            break

print(f"[spec v1.4.3] TurboJPEG DLL: {TURBOJPEG_DLL}")

ICON_PATH = r"C:\Users\a8912\Projects\mirrorx\assets\mirrorx.ico"
if not os.path.exists(ICON_PATH):
    ICON_PATH = None
    print(f"[spec v1.4.3] WARNING: icon not found at {ICON_PATH}")

a = Analysis(
    ['server.py'],
    pathex=[r'C:\Users\a8912\Projects\mirrorx'],
    binaries=[(TURBOJPEG_DLL, '.')] if TURBOJPEG_DLL else [],
    datas=[],
    hiddenimports=[
        'PIL._imaging',
        'cv2',
        'numpy',
        'pyautogui',
        'pynput',
        'mss',
        'websocket',
        'websockets',
        'turbojpeg',
    ],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[
        'tkinter.test',
        'unittest',
        # DO NOT exclude 'email'/'html'/'http'/'xml' — websockets 13.x
        # transitively imports email.message via importlib.metadata.
        # Removing these caused "No module named 'email'" at startup.
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
    name='MirrorX_v1.4.3',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,  # GUI app
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=ICON_PATH,
    distpath=r'E:\salvar\1',
)
