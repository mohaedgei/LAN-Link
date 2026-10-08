"""Talk to the companion website (https://c4sf4qh0-d.space-z.ai).

The site is a headless API. This module is the script's client for it:

    register_session()  - tell the site "a server is live here" so QRs
                          generated through the API stay in sync
    site_health()       - probe /api/health

Everything is best-effort: if the site is unreachable (offline, blocked,
far away) the LAN still works exactly the same — the site only adds the
online QR generator and the relay path for public IPs.
"""

import json
import threading
import urllib.request

from core import config

STATE = {
    "registered": None,     # dict reply from the site, or False, or None (not tried)
    "tried_at": 0.0,
}
_LOCK = threading.Lock()

_UA = "lanlink-script"


def _post(path: str, data: dict, timeout: float = 6.0) -> dict | None:
    try:
        req = urllib.request.Request(
            f"{config.SITE_URL}{path}",
            data=json.dumps(data).encode("utf-8"),
            headers={"Content-Type": "application/json", "User-Agent": _UA},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read(8192).decode("utf-8", "replace"))
    except Exception:
        return None


def register_session(ip: str, port: int, token: str, via: str,
                     feature: str = "any") -> dict | None:
    """Register the live session with the site (background thread)."""
    reply = _post("/api/session/register", {
        "ip": ip,
        "port": int(port),
        "token": token,
        "via": via,
        "feature": feature,
        "version": config.VERSION,
    })
    with _LOCK:
        STATE["registered"] = reply if reply else False
        STATE["tried_at"] = __import__("time").time()
    return reply


def register_session_async(ip: str, port: int, token: str, via: str,
                           feature: str = "any") -> threading.Thread:
    thread = threading.Thread(
        target=register_session, name="lanlink-site-reg",
        args=(ip, port, token, via, feature), daemon=True)
    thread.start()
    return thread


def last_registration():
    with _LOCK:
        return STATE["registered"]
