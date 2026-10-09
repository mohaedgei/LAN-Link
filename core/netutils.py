"""LAN IP detection and small network helpers."""

import socket
import struct
import fcntl


def get_lan_ip() -> str:
    """Return the primary LAN IP of this machine.

    Strategy (first success wins):
      1. UDP-route trick — the OS picks the interface used for the
         default route (no packet is actually sent).
      2. Read the default-route interface from /proc/net/route and ask
         the kernel for its IPv4 via SIOCGIFADDR — this works on Termux
         even when there is no internet route to 8.8.8.8.
      3. Walk all interfaces and return the first private IPv4,
         preferring wlan* / eth* / ap* names.
    Falls back to 127.0.0.1 (callers should warn when that happens).
    """
    # -- 1. UDP connect trick -----------------------------------------
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            sock.connect(("8.8.8.8", 80))
            ip = sock.getsockname()[0]
        finally:
            sock.close()
        if ip and not ip.startswith("127."):
            return ip
    except OSError:
        pass

    # -- 2. /proc/net/route default iface + ioctl ----------------------
    iface = _default_route_iface()
    if iface:
        ip = _iface_ip(iface)
        if ip:
            return ip

    # -- 3. scan interfaces, prefer wifi/ethernet names ----------------
    try:
        names = [name for _, name in socket.if_nameindex()]
    except OSError:
        names = []
    preferred = [n for n in names if n.startswith(("wlan", "eth", "ap"))]
    for name in preferred + [n for n in names if n not in preferred]:
        if name == "lo":
            continue
        ip = _iface_ip(name)
        if ip and not ip.startswith("127."):
            return ip

    return "127.0.0.1"


def _default_route_iface() -> str | None:
    """Parse /proc/net/route for the interface of the default route."""
    try:
        with open("/proc/net/route", encoding="ascii") as fh:
            for line in fh.readlines()[1:]:
                fields = line.strip().split()
                if len(fields) < 2:
                    continue
                iface, dest = fields[0], fields[1]
                if dest == "00000000":
                    return iface
    except OSError:
        pass
    return None


def _iface_ip(iface: str) -> str | None:
    """IPv4 of an interface via SIOCGIFADDR (pure Python, no binaries)."""
    SIOCGIFADDR = 0x8915
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            packed = struct.pack("256s", iface[:15].encode("ascii"))
            addr = fcntl.ioctl(sock.fileno(), SIOCGIFADDR, packed)[20:24]
        finally:
            sock.close()
        return socket.inet_ntoa(addr)
    except (OSError, UnicodeEncodeError):
        return None


def is_loopback(ip: str) -> bool:
    """True when the address only reaches this same device."""
    return ip == "127.0.0.1" or ip.startswith("127.")


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
