"""LAN IP detection and small network helpers."""

import socket


def get_lan_ip() -> str:
    """Return the primary LAN IP of this machine.

    Opens a UDP socket towards a public address (no packet is actually
    sent) so the OS picks the interface used for internet/LAN routing.
    Falls back to 127.0.0.1 if it fails.
    """
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            sock.connect(("8.8.8.8", 80))
            ip = sock.getsockname()[0]
        finally:
            sock.close()
        return ip
    except OSError:
        return "127.0.0.1"


def is_valid_ip(value: str) -> bool:
    """Light IPv4 validation (good enough for a CLI prompt)."""
    parts = value.split(".")
    if len(parts) != 4:
        return False
    for part in parts:
        if not part.isdigit():
            return False
        if not 0 <= int(part) <= 255:
            return False
    return True


def is_port_free(port: int) -> bool:
    """Check whether a TCP port is bindable on all interfaces."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(("0.0.0.0", port))
            return True
    except OSError:
        return False


def ask_ip(default_ip: str | None = None) -> str:
    """Prompt the user for the LAN IP, auto-detected by default."""
    auto = default_ip or get_lan_ip()
    print(f"  Auto-detected LAN IP: {auto}")
    try:
        raw = input(f"  Your PC IP on the WiFi network [{auto}]: ").strip()
    except EOFError:
        return auto
    if not raw:
        return auto
    while not is_valid_ip(raw):
        print("  [!] Invalid IPv4 address, try again (e.g. 192.168.1.10)")
        try:
            raw = input(f"  Your PC IP on the WiFi network [{auto}]: ").strip()
        except EOFError:
            return auto
        if not raw:
            return auto
    return raw


def ask_port(default_port: int) -> int:
    """Prompt the user for the server port."""
    try:
        raw = input(f"  Server port [{default_port}]: ").strip()
    except EOFError:
        return default_port
    if not raw:
        return default_port
    while True:
        if raw.isdigit() and 1024 <= int(raw) <= 65535:
            return int(raw)
        print("  [!] Port must be a number between 1024 and 65535")
        try:
            raw = input(f"  Server port [{default_port}]: ").strip()
        except EOFError:
            return default_port
        if not raw:
            return default_port
