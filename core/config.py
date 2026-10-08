"""Paths, constants and small property-file helpers for LAN-Link."""

from pathlib import Path

APP_NAME = "LAN-Link"
VERSION = "1.0.1"

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
