"""Paths, constants and small property-file helpers for LAN-Link."""

from pathlib import Path

APP_NAME = "LAN-Link"
VERSION = "1.0.2"

# Project root = parent of the "core" package folder
ROOT = Path(__file__).resolve().parent.parent

ANDROID_DIR = ROOT / "android"
ASSETS_DIR = ANDROID_DIR / "app" / "src" / "main" / "assets"
APP_PROPERTIES_FILE = ASSETS_DIR / "lanlink.properties"
DIST_DIR = ROOT / "dist"
TEMPLATES_DIR = Path(__file__).resolve().parent / "templates"

DEFAULT_PORT = 8080
DEFAULT_FEATURE = "screen"
FEATURES = ("screen", "front", "back")

# Project home on GitHub (used by the in-page APK download button)
REPO_URL = "https://github.com/mohaedgei/LAN-Link"
RELEASE_APK_URL = f"{REPO_URL}/releases/latest/download/LAN-Link.apk"

# Places a built APK may live (first match wins)
APK_CANDIDATES = (
    DIST_DIR / "LAN-Link.apk",
    ANDROID_DIR / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk",
)


def find_apk():
    """Return the path of a locally built APK, or None."""
    for candidate in APK_CANDIDATES:
        try:
            if candidate.exists() and candidate.stat().st_size > 1024:
                return candidate
        except OSError:
            continue
    return None


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
