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

def run(ip: str, port: int) -> None:
    print()
    print("=" * 62)
    print("  STEP 1 — Prepare the LAN-Link Android app")
    print("=" * 62)

    # 1) Bundle the connection defaults into the app assets -------------
    try:
        config.save_properties(config.APP_PROPERTIES_FILE, {
            "host": ip,
            "port": str(port),
        })
        _ok(f"Connection defaults written: {config.APP_PROPERTIES_FILE}")
        _info(f"    host = {ip}")
        _info(f"    port = {port}")
    except OSError:
        # Read-only location (e.g. Termux shared storage) — the build can
        # still proceed or the guidance below still applies.
        _warn(f"Could not write {config.APP_PROPERTIES_FILE} (read-only location)")

    # 2) Toolchain check -------------------------------------------------
    print()
    print("  Checking build tools...")
    java_ver = find_java()
    gradle = find_gradle()
    sdk = find_android_sdk()
    write_local_properties(sdk)

    if java_ver and java_ver >= 17:
        _ok(f"JDK found (version {java_ver})")
    else:
        _warn("JDK 17+ not found (required by Android Gradle Plugin 8.x)")

    if gradle:
        _ok(f"Gradle found: {gradle}")
    else:
        _warn("Gradle not found in PATH")

    if sdk:
        _ok(f"Android SDK found: {sdk}")
    else:
        _warn("Android SDK not found (ANDROID_HOME is not set)")

    # 3) Try the automatic build ----------------------------------------
    apk_path = None
    if java_ver and java_ver >= 17 and gradle and sdk:
        print()
        _info("Building the APK with Gradle (first build may take a few minutes)...")
        env = os.environ.copy()
        env["ANDROID_HOME"] = str(sdk)
        try:
            subprocess.run(
                [gradle, "-p", str(config.ANDROID_DIR), ":app:assembleDebug", "--no-daemon"],
                check=True,
                env=env,
            )
            built = config.ANDROID_DIR / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
            if built.exists():
                config.DIST_DIR.mkdir(parents=True, exist_ok=True)
                apk_path = config.DIST_DIR / "LAN-Link.apk"
                shutil.copy2(built, apk_path)
        except (subprocess.CalledProcessError, OSError) as exc:
            _warn(f"Gradle build failed: {exc}")

    # 4) Report the result ------------------------------------------------
    print()
    if apk_path:
        print("=" * 62)
        print(f"  [OK] THE APP IS READY: {apk_path.resolve()}")
        print("=" * 62)
        print("  Next steps:")
        print("    1. Move the APK to your phone and install it")
        print("       (allow 'Install unknown apps' for your file manager)")
        print("    2. Open LAN-Link on the phone, grant the permissions")
        print("    3. Enable the two services (one-time setup):")
        print("         Settings > Accessibility > LAN-Link Control  -> ON")
        print("         Settings > System > Languages & input")
        print("                  > On-screen keyboard > LAN-Link Text -> ON")
        print("    4. Run: python lanlink.py  ->  option 2  ->  scan the QR")
        print()
    else:
        print("  Automatic build is not possible on this machine right now.")
        print("  No problem — pick one of these two easy ways:")
        print()
        print("  A) Android Studio (recommended for daily development)")
        print("     1. Install Android Studio (it bundles SDK + Gradle)")
        print("     2. Open the folder:  " + str(config.ANDROID_DIR))
        print("     3. Menu: Build > Build App Bundle(s) / APK(s) > Build APK(s)")
        print("     4. The APK appears under android/app/build/outputs/apk/debug/")
        print()
        print("  B) GitHub Actions (no tools installed at all)")
        print("     1. Push this repo to GitHub")
        print("     2. Open the Actions tab -> 'Build Android APK'")
        print("     3. Download the APK artifact from the finished run")
        print()
        _info("The connection defaults are already saved — the built app")
        _info("will connect to this PC automatically once installed.")
        print()
        print("  C) No tools installed at all? Activate the GitHub Actions build")
        print("     (README > 'Enable the GitHub Actions APK builder', 30 seconds).")
        print("     The APK lands in the repo Releases, and the green")
        print("     'Get the app' button inside the web viewer serves it.")
        print()
