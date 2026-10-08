"""Option 1 — prepare the Android project and auto-build the APK.

Steps performed here:
    1. Write the PC's LAN IP + port into the app's bundled defaults
       (android/app/src/main/assets/lanlink.properties) so the app knows
       where to connect without typing anything.
    2. Detect JDK / Android SDK / Gradle on this machine.
    3. If the toolchain exists  -> run `gradle :app:assembleDebug` and copy
       the APK to dist/LAN-Link.apk, printing its absolute path.
       If something is missing -> print clear step-by-step guidance
       (Android Studio, or the GitHub Actions build bot).
"""

import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

from core import config


def _info(msg: str) -> None:
    print(f"  {msg}")


def _ok(msg: str) -> None:
    print(f"  [OK] {msg}")


def _warn(msg: str) -> None:
    print(f"  [!] {msg}")


# ---------------------------------------------------------------------
# Toolchain detection
# ---------------------------------------------------------------------

def find_java() -> str | None:
    """Return the Java major version if a JDK is available, else None."""
    java = shutil.which("java")
    if not java:
        return None
    try:
        out = subprocess.check_output(
            ["java", "-version"], stderr=subprocess.STDOUT, text=True, timeout=15
        )
        match = re.search(r'version "?(\d+)', out)
        return int(match.group(1)) if match else None
    except (subprocess.SubprocessError, ValueError):
        return None


def find_android_sdk() -> Path | None:
    """Locate the Android SDK through env vars or the usual install paths."""
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(var)
        if value and Path(value).exists():
            return Path(value)

    home = Path.home()
    candidates = [
        home / "Android" / "Sdk",                    # Linux/macOS Studio default
        home / "Library" / "Android" / "sdk",        # macOS
        Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk",  # Windows
        home / "AppData" / "Local" / "Android" / "Sdk",
    ]
    for candidate in candidates:
        if str(candidate) != "" and candidate.exists() and (candidate / "platforms").exists():
            return candidate
    return None


def find_gradle() -> str | None:
    return shutil.which("gradle") or shutil.which("gradle.bat")


def write_local_properties(sdk: Path | None) -> None:
    """Generate android/local.properties (never committed to git)."""
    if sdk is None:
        return
    path = config.ANDROID_DIR / "local.properties"
    sdk_str = str(sdk).replace("\\", "\\\\")
    path.write_text(f"sdk.dir={sdk_str}\n", encoding="utf-8")


# ---------------------------------------------------------------------
# Main entry: run(ip, port)
# ---------------------------------------------------------------------

def run(ip: str, port: int) -> Path | None:
    """Prepare the app quietly and return the APK path (or None).

    Deliberately SILENT: option 1 clears the screen afterwards and shows
    one clean summary card. Progress is limited to a build in progress.
    """
    # 1) Bundle the connection defaults into the app assets -------------
    try:
        config.save_properties(config.APP_PROPERTIES_FILE, {
            "host": ip,
            "port": str(port),
        })
    except OSError:
        pass  # read-only location — the app still works via QR/manual entry

    # 2) Already built (ships inside the project since v1.1.0)? --------
    apk = config.find_apk()
    if apk is not None:
        return apk

    # 3) Try an automatic build only if the full toolchain exists ------
    java_ver = find_java()
    gradle = find_gradle()
    sdk = find_android_sdk()
    write_local_properties(sdk)
    if not (java_ver and java_ver >= 17 and gradle and sdk):
        return None

    print("  Building the APK (first build may take a few minutes)...")
    env = os.environ.copy()
    env["ANDROID_HOME"] = str(sdk)
    try:
        subprocess.run(
            [gradle, "-p", str(config.ANDROID_DIR), ":app:assembleDebug",
             "--console=plain", "--no-daemon"],
            check=True,
            env=env,
        )
        built = config.ANDROID_DIR / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
        if built.exists():
            config.DIST_DIR.mkdir(parents=True, exist_ok=True)
            shutil.copy2(built, config.DIST_DIR / "LAN-Link.apk")
    except (subprocess.CalledProcessError, OSError):
        return None
    return config.find_apk()
