"""LAN-Link core package.

Modules:
    config    - paths, constants, app properties helpers
    netutils  - LAN IP detection and port helpers
    qrgen     - QR generation (terminal ASCII + PNG file)
    builder   - Option 1: prepare + auto-build the Android app
    server    - Option 2: streaming relay server (HTTP + WebSocket)
    viewer    - Desktop viewer window (OpenCV) with remote control
"""

__version__ = "1.0.0"
APP_NAME = "LAN-Link"
