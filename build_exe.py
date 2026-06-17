"""
Build MirrorX into a standalone Windows .exe using PyInstaller.
"""
import subprocess
import sys
import os
from pathlib import Path

ROOT = Path(__file__).parent

def main():
    # Install PyInstaller if needed
    subprocess.check_call([sys.executable, "-m", "pip", "install", "pyinstaller", "-q"])

    # Build
    cmd = [
        sys.executable, "-m", "PyInstaller",
        "--onefile",
        "--windowed",
        "--name", "MirrorX",
        "--add-data", f"{ROOT / 'client'};client",
        "--icon", str(ROOT / "assets" / "icons" / "mirrorx.ico"),
        str(ROOT / "server.py"),
    ]
    print(f"[build] Running: {' '.join(cmd)}")
    subprocess.check_call(cmd)

    exe_path = ROOT / "dist" / "MirrorX.exe"
    if exe_path.exists():
        size_mb = exe_path.stat().st_size / (1024 * 1024)
        print(f"[build] ✅ MirrorX.exe built: {exe_path} ({size_mb:.1f} MB)")
    else:
        print("[build] ❌ Build failed — .exe not found")


if __name__ == "__main__":
    main()
