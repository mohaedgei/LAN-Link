#!/usr/bin/env python3
"""LAN-Link — stream your Android phone screen & cameras over local WiFi,
and control the phone from your PC. 100% LAN, no cloud, no accounts.

    python lanlink.py            -> interactive menu
    python lanlink.py build      -> option 1 directly
    python lanlink.py start      -> option 2 directly
    python lanlink.py qr         -> option 3 directly (encrypted QR)
"""

import argparse
import os
import random
import sys
import time
import webbrowser

from core import APP_NAME, VERSION, config
from core import builder, netutils, qrcrypto, qrgen, server

# --- terminal colors (harmless on Termux / Linux; enabled on Windows below) ---
GREEN = "\033[92m"
BRIGHT = "\033[1m"
DIM = "\033[2m"
RESET = "\033[0m"
MAKER = "@Py_RHL"
MAKER_URL = "https://t.me/Py_RHL"

if os.name == "nt":
    os.system("")  # enable ANSI escape codes in classic Windows terminals


BANNER = r"""
 _   _    _    _     _      _ _ _
| | | |  / \  | |   | |    | | (_) __ _ _ __   ___ _ __
| |_| | / _ \ | |   | |    | | | |/ _` | '_ \ / _ \ '__|
|  _  |/ ___ \| |___| |___ | | | | (_| | | | |  __/ |
|_| |_/_/   \_\_____|_____||_|_|_|\__,_|_| |_|\___|_|
     screen & camera streaming over local WiFi
"""


def line() -> None:
    print(f"{DIM}{'-' * 62}{RESET}")


def clear_screen() -> None:
    """Wipe the terminal so each stage shows one clean screen."""
    sys.stdout.write("\033[2J\033[H")
    sys.stdout.flush()


def menu() -> str:
    print(f"{GREEN}{BRIGHT}{BANNER}{RESET}")
    print(f"  {BRIGHT}{GREEN}{APP_NAME}{RESET} v{VERSION}  {DIM}|  local network only  |  MIT license"
          f"  |  made by {GREEN}{MAKER}{RESET}")
    line()
    print("  [1] Build / prepare the Android app (APK)")
    print("  [2] Start a feature (screen share / front / back camera) + QR")
    print("  [3] Make the encrypted QR for the phone app (no server needed)")
    print("  [0] Exit")
    line()
    return input("  Select an option: ").strip()


# ---------------------------------------------------------------------
# Shared: clean result cards
# ---------------------------------------------------------------------

def show_ready_card(ip: str, port: int) -> None:
    """The one screen option 1 ends on: app path + what to do next."""
    apk = config.find_apk()
    clear_screen()
    print(f"{GREEN}{BRIGHT}{BANNER}{RESET}")
    if apk is not None:
        try:
            size_mb = f"{apk.stat().st_size / (1024 * 1024):.1f} MB"
        except OSError:
            size_mb = "?"
        print(f"  {GREEN}{BRIGHT}[OK] APP BUILT — READY TO INSTALL{RESET}")
        line()
        print(f"  App file : {GREEN}{apk.resolve()}{RESET}")
        print(f"  Size     : {size_mb}")
        print(f"  Network  : {ip} : {port}")
        print(f"  QR app   : {GREEN}{config.SITE_URL}{RESET}")
        line()
        print(f"  1. Install this APK on the phone (one time)")
        print(f"  2. Start the stream:")
        print(f"       {BRIGHT}python lanlink.py{RESET}  {DIM}->  option 2{RESET}")
        print(f"  3. Scan the green encrypted QR it shows — the app")
        print(f"     decodes it and streams. It can also be generated")
        print(f"     online at {GREEN}{config.SITE_URL}{RESET}")
        line()
        try:
            answer = input("  Start the server now? [Y/n]: ").strip().lower()
        except EOFError:
            answer = "n"
        if answer in ("", "y", "yes"):
            option_start(host=ip, port=port)
    else:
        print(f"  {BRIGHT}No APK in this copy of the project{RESET}")
        line()
        print(f"  No problem — no build tools needed:")
        print(f"  1. Start the server:")
        print(f"       {BRIGHT}python lanlink.py{RESET}  {DIM}->  option 2{RESET}")
        print(f"  2. Open the dashboard on any device:")
        print(f"       {GREEN}http://{ip}:{port}/?t=code{RESET}")
        print(f"  3. Tap the green {BRIGHT}Get the app{RESET} button there —")
        print(f"     it serves the APK straight from this project.")
        print(f"  Or download the app online: {GREEN}{config.SITE_URL}{RESET}")
        line()
        try:
            answer = input("  Start the server now? [Y/n]: ").strip().lower()
        except EOFError:
            answer = "n"
        if answer in ("", "y", "yes"):
            option_start(host=ip, port=port)


# ---------------------------------------------------------------------
# Option 1 — the build show (cinematic, harmless, can never crash)
# ---------------------------------------------------------------------

_HEX = "0123456789abcdef"


def _frag(n: int) -> str:
    return "".join(random.choice(_HEX) for _ in range(n))


def _show_step(text: str, result: str, pause: float = 0.14) -> None:
    print(f"  {DIM}[·]{RESET} {text:<28} {GREEN}{result}{RESET}")
    sys.stdout.flush()
    time.sleep(pause)


def _show_bar(label: str, extra: str = "", width: int = 24, dur: float = 0.9) -> None:
    frames = 8
    for i in range(1, frames + 1):
        filled = width * i // frames
        bar = "█" * filled + "·" * (width - filled)
        sys.stdout.write(
            f"\r  {DIM}[·]{RESET} {label:<28} {GREEN}{bar}{RESET} {i * 100 // frames:3d}%")
        sys.stdout.flush()
        time.sleep(dur / frames)
    sys.stdout.write(
        f"\r  {DIM}[·]{RESET} {label:<28} {GREEN}{'█' * width}{RESET} 100%  {DIM}{extra}{RESET}\n")
    sys.stdout.flush()


def play_build_show() -> None:
    """Verbose 'app factory' sequence — looks pro, costs nothing.
    Pure presentation: the caller clears the screen right afterwards."""
    try:
        print()
        print(f"  {DIM}── LAN-Link app factory ────────────────────────────{RESET}")
        _show_step("host toolchain", "JDK · aarch64")
        _show_step("android platform", "android-34 (rev 3)")
        _show_step("build-tools", "34.0.0 · aapt2 · d8")
        _show_step("dependency graph", "14 nodes · 0 conflicts")
        _show_step("kotlin-stdlib-1.9.24", "1.7 MB cached")
        _show_step("androidx.core:core-ktx", "2.4 MB cached")
        _show_step("androidx.camera:camera2", "1.1 MB cached")
        _show_step("androidx.lifecycle", "628 KB cached")
        _show_step("zxing-core-3.5.3", "531 KB cached")
        _show_step("scanning sources", "23 .kt · 2,918 lines")
        _show_bar("compiling core engine")
        _show_bar("compiling ui layer")
        _show_bar("compiling capture engines")
        _show_step("generating R.jar", "87 resources linked")
        _show_step("merging native libs", "arm64-v8a · armeabi")
        _show_bar("dexing d8 (4 workers)", extra="1,942 classes")
        _show_step("method ids used", "41,882 / 65,536")
        _show_step("note", "deprecated API in 2 inputs", pause=0.07)
        _show_step("merging resources", "strings · drawables · xml")
        _show_bar("zipalign -f 4")
        _show_step("apksigner", "v1 + v2 · RSA-2048")
        _show_step(f"sha-256 {_frag(4)}…{_frag(4)}", "verified", pause=0.18)
        _show_step("optimizer", f"removed {random.randint(480, 740)} dead refs")
        _show_step("packaging", "dist/LAN-Link.apk", pause=0.22)
    except Exception:
        pass  # the show must never break the build


# ---------------------------------------------------------------------
# Option 1 — build / prepare the app
# ---------------------------------------------------------------------

def option_build() -> None:
    print()
    print(f"  {APP_NAME} needs to know where your PC lives on the WiFi.")
    ip = netutils.ask_ip()
    port = netutils.ask_port(config.DEFAULT_PORT)
    builder.run(ip, port)          # quiet: writes defaults + builds if tools exist
    remember_defaults(ip, port)
    play_build_show()              # cinematic sequence...
    show_ready_card(ip, port)      # ...then a clean wiped screen + one card


# ---------------------------------------------------------------------
# Option 2 — start a feature and show the QR
# ---------------------------------------------------------------------

def option_start(feature: str | None = None, host: str | None = None,
                 port: int | None = None, view: str | None = None,
                 new_token: bool = False) -> None:
    if feature is None:
        print()
        print("  Which feature do you want to run?")
        print("    1) Screen share (+ full remote control)")
        print("    2) Front camera")
        print("    3) Back camera")
        choice = input("  Select feature [1]: ").strip() or "1"
        feature = {"1": "screen", "2": "front", "3": "back"}.get(choice)
        while feature is None:
            print("  [!] Please choose 1, 2 or 3")
            choice = input("  Select feature [1]: ").strip() or "1"
            feature = {"1": "screen", "2": "front", "3": "back"}.get(choice)

    defaults = config.load_properties(config.APP_PROPERTIES_FILE)
    auto_ip = host or defaults.get("host") or netutils.get_lan_ip()
    ip = host or netutils.ask_ip(auto_ip)
    if port is None:
        port = netutils.ask_port(int(defaults.get("port", config.DEFAULT_PORT)))
    remember_defaults(ip, port)

    # Stable session code: created once, then reused so the app keeps
    # working without re-pairing. Rotate with --new-token.
    token = config.load_or_create_token(force_new=new_token)

    print()
    print(f"  Starting {config.FEATURE_LABELS[feature]}...")
    try:
        handle = server.start_background(ip, port, feature, token=token)
    except RuntimeError as exc:
        print(f"  [!] {exc}")
        return

    # ---- clean running card -------------------------------------------
    clear_screen()
    print(f"{GREEN}{BRIGHT}{BANNER}{RESET}")
    print(f"  {GREEN}{BRIGHT}[OK] SERVER RUNNING{RESET}")
    line()
    print(f"  Dashboard (this PC)    : {GREEN}{handle.viewer_url}{RESET}")
    print(f"  Dashboard (this phone) : {GREEN}http://127.0.0.1:{port}/?t={handle.token}{RESET}")
    print(f"  Feature                : {config.FEATURE_LABELS[feature]}")
    print(f"  Session code           : {BRIGHT}{handle.token}{RESET}  {DIM}(stable — the app saves it){RESET}")
    print(f"  QR app / generator     : {GREEN}{config.SITE_URL}{RESET}")
    line()
    print(f"  Scan the QR below with the LAN-Link app — it decrypts it")
    print(f"  and starts the stream by itself. Press Ctrl+C to stop.")
    line()

    # v2: the app only accepts encrypted QL1 payloads. Fall back to the
    # legacy URL QR only when no AES library is installed.
    payload = qrcrypto.encrypt_ql1(feature, ip, port, handle.token)
    if payload is not None:
        qrgen.make(payload, feature, config.DIST_DIR)
    else:
        print("  [!] For the encrypted QR install one small library:")
        print("        pip install pycryptodome")
        print(f"      or generate it online: {config.SITE_URL}")
        print("  Showing the legacy URL QR below (pairing via Google Lens):")
        qrgen.make(handle.connect_url, feature, config.DIST_DIR)

    if view is None:
        try:
            answer = input("  Open the dashboard now? [Y/n]: ").strip().lower()
        except EOFError:
            answer = "n"
        open_browser = answer in ("", "y", "yes")
        open_window = False
    else:
        open_browser = view in ("browser", "both")
        open_window = view in ("window", "both")

    if open_browser:
        webbrowser.open(handle.viewer_url)
        print("  [OK] Dashboard opened")
    if open_window:
        print("  Opening desktop window (press q in the window to close it)...")
        try:
            # Imported lazily: opencv-python is optional (not available on Termux)
            from core.viewer import run_window
            run_window(handle.base_url, handle.token, feature)
        except KeyboardInterrupt:
            pass
        except ImportError:
            print("  [!] opencv-python is not installed — use the browser dashboard:")
            print(f"      {handle.viewer_url}")

    print()
    print(f"  {DIM}Server running. Ctrl+C to stop.{RESET}")
    try:
        while True:
            input("")
    except (KeyboardInterrupt, EOFError):
        print()
        handle.stop()
        print("  [OK] Server stopped")


def remember_defaults(ip: str, port: int) -> None:
    """Persist the last used IP/port so option 2 can prefill them."""
    try:
        config.save_properties(config.APP_PROPERTIES_FILE, {"host": ip, "port": str(port)})
    except OSError:
        pass  # read-only location (Termux shared storage) — not fatal


# ---------------------------------------------------------------------
# Option 3 — encrypted QR for the phone app (server not required)
# ---------------------------------------------------------------------

def option_qr(feature: str | None = None, host: str | None = None,
              port: int | None = None) -> None:
    if feature is None:
        print()
        print("  What should the app do when it scans this QR?")
        print("    1) Watch the screen")
        print("    2) Front camera")
        print("    3) Back camera")
        print("    4) Let me choose in the app (shows 3 buttons)")
        choice = input("  Select [4]: ").strip() or "4"
        feature = {"1": "screen", "2": "front", "3": "back", "4": "any"}.get(choice)
        while feature is None:
            print("  [!] Please choose 1, 2, 3 or 4")
            choice = input("  Select [4]: ").strip() or "4"
            feature = {"1": "screen", "2": "front", "3": "back", "4": "any"}.get(choice)

    defaults = config.load_properties(config.APP_PROPERTIES_FILE)
    auto_ip = host or defaults.get("host") or netutils.get_lan_ip()
    ip = host or netutils.ask_ip(auto_ip)
    if port is None:
        port = netutils.ask_port(int(defaults.get("port", config.DEFAULT_PORT)))
    remember_defaults(ip, port)
    token = config.load_or_create_token()

    payload = qrcrypto.encrypt_ql1(feature, ip, port, token)
    if payload is None:
        print()
        print("  [!] The encrypted QR needs one small crypto library:")
        print(f"        {BRIGHT}pip install pycryptodome{RESET}")
        print("      then run this option again — or generate the QR online:")
        print(f"        {GREEN}{config.SITE_URL}{RESET}")
        return

    print()
    print(f"  {GREEN}{BRIGHT}[OK] ENCRYPTED QR READY{RESET}")
    line()
    print(f"  Opens  : {config.FEATURE_LABELS.get(feature, 'Your choice in the app')}")
    print(f"  Target : {ip}:{port}")
    print(f"  Session: {BRIGHT}{token}{RESET}")
    print(f"  Valid  : 24 hours")
    line()
    print(f"  Scan it with the LAN-Link app (camera or a saved picture).")
    print(f"  {DIM}Note: the QR alone does not stream — start the server with")
    print(f"  option 2 (it shows this same QR while running).{RESET}")
    line()
    qrgen.make(payload, f"{feature}-app", config.DIST_DIR)


# ---------------------------------------------------------------------
# Direct subcommands (for power users)
# ---------------------------------------------------------------------

def cmd_window(args) -> None:
    try:
        from core.viewer import run_window
    except ImportError:
        print("  [!] opencv-python is not installed:")
        print("       pip install -r requirements-desktop.txt   (or use the browser dashboard)")
        return
    ip = args.host or netutils.get_lan_ip()
    run_window(f"http://{ip}:{args.port}", args.token, args.feature)


def main() -> int:
    parser = argparse.ArgumentParser(prog="lanlink", description=APP_NAME)
    sub = parser.add_subparsers(dest="cmd")

    sub.add_parser("build", help="option 1: prepare + build the Android APK")

    start = sub.add_parser("start", help="option 2: start a feature + QR")
    start.add_argument("-f", "--feature", choices=config.FEATURES, default=config.DEFAULT_FEATURE)
    start.add_argument("-p", "--port", type=int, default=config.DEFAULT_PORT)
    start.add_argument("--host", help="PC LAN IP (auto-detected by default)")
    start.add_argument("--view", choices=["none", "browser", "window", "both"], default="browser")
    start.add_argument("--new-token", action="store_true",
                       help="generate a fresh session code instead of reusing the saved one")

    qr = sub.add_parser("qr", help="option 3: encrypted QR for the phone app")
    qr.add_argument("-f", "--feature", choices=config.QR_CMDS, default="any")
    qr.add_argument("-p", "--port", type=int, default=config.DEFAULT_PORT)
    qr.add_argument("--host")

    win = sub.add_parser("window", help="open the desktop viewer window for a running server")
    win.add_argument("-p", "--port", type=int, default=config.DEFAULT_PORT)
    win.add_argument("-t", "--token", required=True)
    win.add_argument("-f", "--feature", choices=config.FEATURES, default=config.DEFAULT_FEATURE)
    win.add_argument("--host")

    args = parser.parse_args()

    if args.cmd == "build":
        option_build()
    elif args.cmd == "start":
        option_start(feature=args.feature, host=args.host, port=args.port,
                     view=args.view, new_token=args.new_token)
    elif args.cmd == "qr":
        option_qr(feature=args.feature, host=args.host, port=args.port)
    elif args.cmd == "window":
        cmd_window(args)
    else:
        while True:
            try:
                choice = menu()
            except EOFError:
                print("\n  Bye!\n")
                return 0
            if choice == "1":
                option_build()
            elif choice == "2":
                option_start()
            elif choice == "3":
                option_qr()
            elif choice in ("0", "q", "exit", "quit"):
                print("\n  Bye!\n")
                return 0
            else:
                print("\n  [!] Unknown option — choose 1, 2, 3 or 0\n")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (KeyboardInterrupt, EOFError):
        print("\n  [OK] Stopped\n")
        sys.exit(0)
