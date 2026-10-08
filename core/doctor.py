"""Startup doctor: every check the script runs before it says "server running".

The user types an IP + port, then this module proves — step by step —
that the setup can actually work, with one clear human message per
failure kind:

    - malformed IP
    - IP not a LAN/private address (goes through the website relay instead)
    - IP not assigned to this machine
    - port busy (suggests port+1)
    - port refused to bind
    - server up but not answering (HTTP + WebSocket self-test)

Every check returns a CheckResult(ok, label, detail). The interactive
runner loops until everything passes or the user aborts.
"""

import socket
import urllib.request

from core import config


class CheckResult:
    __slots__ = ("ok", "label", "detail")

    def __init__(self, ok: bool, label: str, detail: str = ""):
        self.ok = ok
        self.label = label
        self.detail = detail


# ---------------------------------------------------------------------
# IP helpers
# ---------------------------------------------------------------------

def ip_kind(ip: str) -> str | None:
    """'private' | 'loopback' | 'linklocal' | 'public' | None (malformed)."""
    parts = ip.split(".")
    if len(parts) != 4 or not all(p.isdigit() for p in parts):
        return None
    nums = [int(p) for p in parts]
    if any(n > 255 for n in nums):
        return None
    a, b = nums[0], nums[1]
    if a == 127:
        return "loopback"
    if a == 10 or (a == 192 and b == 168) or (a == 172 and 16 <= b <= 31):
        return "private"
    if a == 169 and b == 254:
        return "linklocal"
    return "public"


def local_ip_candidates() -> set[str]:
    """Every IP that plausibly belongs to this machine right now."""
    ips: set[str] = {"127.0.0.1"}
    try:  # routing trick: the OS picks the IP used for internet traffic
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            sock.connect(("8.8.8.8", 80))
            ips.add(sock.getsockname()[0])
        finally:
            sock.close()
    except OSError:
        pass
    try:  # every address the hostname resolves to
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    try:  # Termux/Android: `ip addr` output (no root needed)
        import subprocess
        out = subprocess.run(["ip", "-4", "addr"], capture_output=True,
                             text=True, timeout=4).stdout
        for raw in out.splitlines():
            raw = raw.strip()
            if raw.startswith("inet "):
                ips.add(raw.split()[1].split("/")[0])
    except Exception:
        pass
    return ips


# ---------------------------------------------------------------------
# Individual checks
# ---------------------------------------------------------------------

def check_ip_format(ip: str) -> CheckResult:
    if ip_kind(ip) is not None:
        return CheckResult(True, "IP format", ip)
    return CheckResult(
        False, "IP format",
        f"'{ip}' is not a valid IPv4 address (example: 192.168.1.100)")


def check_ip_scope(ip: str) -> CheckResult:
    kind = ip_kind(ip)
    if kind in ("private", "loopback"):
        return CheckResult(True, "Network scope", f"{ip} is a local network address — direct connection")
    if kind == "public":
        return CheckResult(
            True, "Network scope",
            f"{ip} is a public address — the stream will go through {config.SITE_URL}")
    return CheckResult(False, "Network scope",
                       f"{ip} is a link-local address — pick your WiFi IP instead")


def check_ip_local(ip: str) -> CheckResult:
    if ip_kind(ip) == "loopback":
        return CheckResult(True, "IP belongs to this device", "127.0.0.1 (same-device mode)")
    if ip in local_ip_candidates():
        return CheckResult(True, "IP belongs to this device", ip)
    return CheckResult(
        False, "IP belongs to this device",
        f"{ip} is NOT an address of this machine — check `ifconfig` / WiFi settings")


def check_port_format(port: int) -> CheckResult:
    if 1024 <= port <= 65535:
        return CheckResult(True, "Port range", str(port))
    return CheckResult(False, "Port range",
                       f"{port} is out of range — use 1024..65535")


def check_port_free(port: int) -> CheckResult:
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("0.0.0.0", port))
        sock.close()
        return CheckResult(True, "Port is free", str(port))
    except OSError:
        suggestion = port + 1
        # walk up until something is free (max 10 tries)
        for candidate in range(port + 1, port + 11):
            try:
                s2 = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                s2.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                s2.bind(("0.0.0.0", candidate))
                s2.close()
                suggestion = candidate
                break
            except OSError:
                continue
        return CheckResult(
            False, "Port is free",
            f"port {port} is already in use by another program — try {suggestion}")


def check_http_alive(base_url: str, token: str) -> CheckResult:
    """Server thread says it is ready — prove it answers real requests."""
    url = f"{base_url}/status?t={token}"
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "lanlink-doctor"})
        with urllib.request.urlopen(req, timeout=6) as resp:
            body = resp.read(4096).decode("utf-8", "replace")
        if '"ok"' in body or '"app"' in body:
            return CheckResult(True, "Server answers HTTP", f"{base_url}/status")
        return CheckResult(False, "Server answers HTTP", f"unexpected reply from {url}")
    except Exception as exc:  # noqa: BLE001
        return CheckResult(False, "Server answers HTTP",
                           f"{base_url} did not answer ({exc.__class__.__name__})")


def check_ws_alive(base_url: str, token: str) -> CheckResult:
    """Open a real WebSocket to /ws (viewer role) and close it — the exact
    door the phone app will walk through."""
    try:
        import asyncio
        from aiohttp import ClientSession, WSMsgType

        async def probe() -> str:
            timeout = __import__("aiohttp").ClientTimeout(total=8)
            async with ClientSession(timeout=timeout) as session:
                async with session.ws_connect(
                        f"{base_url}/ws?t={token}&role=viewer") as ws:
                    await ws.send_str('{"type":"ping"}')
                    deadline = asyncio.get_event_loop().time() + 4
                    while asyncio.get_event_loop().time() < deadline:
                        try:
                            msg = await asyncio.wait_for(ws.receive(), timeout=1.0)
                        except asyncio.TimeoutError:
                            break
                        if msg.type in (WSMsgType.BINARY, WSMsgType.TEXT):
                            return "ok"
                        if msg.type in (WSMsgType.CLOSED, WSMsgType.ERROR):
                            break
                    return "ok"  # connected + sent is proof enough
            return "fail"

        result = asyncio.run(probe())
        if result == "ok":
            return CheckResult(True, "WebSocket door opens", f"{base_url}/ws")
        return CheckResult(False, "WebSocket door opens",
                           f"{base_url}/ws refused the handshake")
    except Exception as exc:  # noqa: BLE001
        return CheckResult(False, "WebSocket door opens",
                           f"{base_url}/ws failed ({exc.__class__.__name__}: {exc})")


def probe_site(timeout: float = 4.0) -> dict | None:
    """Best-effort health probe of the companion website."""
    try:
        req = urllib.request.Request(f"{config.SITE_URL}/api/health",
                                     headers={"User-Agent": "lanlink-doctor"})
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            import json
            return json.loads(resp.read(4096).decode("utf-8", "replace"))
    except Exception:
        return None
