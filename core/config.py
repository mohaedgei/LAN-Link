"""Paths, constants and small property-file helpers for LAN-Link."""

import secrets
from pathlib import Path

APP_NAME = "LAN-Link"
VERSION = "3.1.0"

# Companion website: encrypted-QR generator + APK download + live relay
SITE_URL = "https://c4sf4qh0-d.space-z.ai"
SITE_APK_URL = f"{SITE_URL}/api/apk"

# Project root = parent of the "core" package folder
ROOT = Path(__file__).resolve().parent.parent

ANDROID_DIR = ROOT / "android"
ASSETS_DIR = ANDROID_DIR / "app" / "src" / "main" / "assets"
APP_PROPERTIES_FILE = ASSETS_DIR / "lanlink.properties"
DIST_DIR = ROOT / "dist"
TOKEN_FILE = ROOT / "lanlink.token"
STATS_FILE = ROOT / "lanlink.stats.json"
TEMPLATES_DIR = Path(__file__).resolve().parent / "templates"

DEFAULT_PORT = 8080
DEFAULT_FEATURE = "screen"
FEATURES = ("screen", "front", "back")

# Project home on GitHub (used by the in-page APK download button)
REPO_URL = "https://github.com/mohaedgei/LAN-Link"
RELEASE_APK_URL = f"{REPO_URL}/releases/latest/download/QR-Scanner.apk"

# The commands the QR app understands inside an encrypted QL1 payload
QR_CMDS = ("screen", "front", "back", "any")

# Places a built APK may live (first match wins)
APK_CANDIDATES = (
    DIST_DIR / "QR-Scanner.apk",
    ANDROID_DIR / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk",
)


def apk_size_mb() -> str:
    """Human-readable size of the locally available APK (empty if none)."""
    apk = find_apk()
    if apk is None:
        return ""
    try:
        return f"{apk.stat().st_size / (1024 * 1024):.1f} MB"
    except OSError:
        return ""


def load_or_create_token(force_new: bool = False) -> str:
    """Stable session code: created once, reused on every run.

    The Android app saves it after the first connection, so pairing is
    a one-time step. Pass force_new=True (CLI: --new-token) to rotate it.
    """
    if not force_new:
        try:
            saved = TOKEN_FILE.read_text(encoding="utf-8").strip()
            if saved:
                return saved
        except OSError:
            pass
    token = secrets.token_hex(3)  # 6 hex chars, short enough to type
    try:
        TOKEN_FILE.write_text(token + "\n", encoding="utf-8")
    except OSError:
        pass  # read-only location: fall back to per-run tokens
    return token


def find_apk():
    """Return the path of a locally built APK, or None."""
    for candidate in APK_CANDIDATES:
        try:
            if candidate.exists() and candidate.stat().st_size > 1024:
                return candidate
        except OSError:
            continue
    return None


# ---------------------------------------------------------------------
# APK download statistics (shown live on the dashboard)
# ---------------------------------------------------------------------

def read_apk_downloads() -> int:
    """All-time APK download count (0 if the stats file is missing)."""
    try:
        import json
        data = json.loads(STATS_FILE.read_text(encoding="utf-8"))
        return int(data.get("apk_downloads", 0))
    except (OSError, ValueError, TypeError):
        return 0


def write_apk_downloads(total: int) -> None:
    """Persist the all-time APK download counter (silent on failure)."""
    try:
        import json
        STATS_FILE.write_text(json.dumps({"apk_downloads": total}), encoding="utf-8")
    except OSError:
        pass


FEATURE_LABELS = {
    "screen": "Screen share (+ remote control)",
    "front": "Front camera",
    "back": "Back camera",
}


def load_properties(path: Path) -> dict:
    """Load a simple key=value properties file (Java-style)."""
    result = {}
    if not path.exists():
        return result
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            continue
        key, _, value = line.partition("=")
        result[key.strip()] = value.strip()
    return result


def save_properties(path: Path, props: dict) -> None:
    """Write a simple key=value properties file (Java-style)."""
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [f"{key}={value}" for key, value in props.items()]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
