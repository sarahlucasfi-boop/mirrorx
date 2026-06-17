"""
MirrorX — HTTP server to serve the web client to the tablet.
"""
import asyncio
import http.server
import os
import threading
from pathlib import Path

CLIENT_DIR = Path(__file__).parent / "client"


def start_http_server(port: int = 8080):
    """Start a simple HTTP server to serve the client files."""
    os.chdir(str(CLIENT_DIR))
    handler = http.server.SimpleHTTPRequestHandler
    httpd = http.server.HTTPServer(("0.0.0.0", port), handler)
    print(f"[MirrorX] HTTP server on http://0.0.0.0:{port}")
    httpd.serve_forever()
