#!/usr/bin/env python3
"""LAN-Link — stream your Android phone screen & cameras over local WiFi,
and control the phone from your PC. 100% LAN, no cloud, no accounts.

    python lanlink.py            -> interactive menu
    python lanlink.py build      -> option 1 directly
    python lanlink.py start      -> option 2 directly
"""

import argparse
import sys
import webbrowser

from core import APP_NAME, VERSION, config
from core import builder, netutils, qrgen, server


BANNER = r"""
 _   _    _    _     _      _ _ _
| | | |  / \  | |   | |    | | (_) __ _ _ __   ___ _ __
| |_| | / _ \ | |   | |    | | | |/ _` | '_ \ / _ \ '__|
|  _  |/ ___ \| |___| |___ | | | | (_| | | | |  __/ |
|_| |_/_/   \_\_____|_____||_|_|_|\__,_|_| |_|\___|_|
     screen & camera streaming over local WiFi
"""


def line() -> None:
    print("-" * 62)


def menu() -> str:
    print(BANNER)
    print(f"  {APP_NAME} v{VERSION}  |  local network only  |  MIT license")
    line()
    print("  [1] Build / prepare the Android app (APK)")
    print("  [2] Start a feature (screen share / front / back camera) + QR")
    print("  [0] Exit")
    line()
    return input("  Select an option: ").strip()


# ---------------------------------------------------------------------
# Option 1 — build the app
# ---------------------------------------------------------------------

def option_build() -> None:
    print()
    print(f"  {APP_NAME} needs to know where your PC lives on the WiFi.")
    ip = netutils.ask_ip()
    port = netutils.ask_port(config.DEFAULT_PORT)
    builder.run(ip, port)
    remember_defaults(ip, port)


# ---------------------------------------------------------------------
# Option 2 — start a feature and show the QR
# ---------------------------------------------------------------------

def option_start() -> None:
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
    ip = defaults.get("host") or netutils.get_lan_ip()
    ip = netutils.ask_ip(ip)
    port = netutils.ask_port(int(defaults.get("port", config.DEFAULT_PORT)))
    remember_defaults(ip, port)

    print()
    print(f"  Starting {config.FEATURE_LABELS[feature]}...")
    try:
        handle = server.start_background(ip, port, feature)
    except RuntimeError as exc:
        print(f"  [!] {exc}")
        return

    line()
    print(f"  Server      : {handle.base_url}")
    print(f"  Feature     : {config.FEATURE_LABELS[feature]}")
    print(f"  Session code: {handle.token}")
    qrgen.make(handle.connect_url, feature, config.DIST_DIR)
    print(f"  PC browser viewer : {handle.viewer_url}")
    print(f"  Desktop window    : run  python lanlink.py window -p {port} -t {handle.token} -f {feature}")
    print()

    mode = input("  Open viewer now?  [1] Browser  [2] Desktop window  [3] Both  [1]: ").strip() or "1"
    if mode in ("1", "3"):
        webbrowser.open(handle.viewer_url)
        print("  [OK] Browser viewer opened")
    if mode in ("2", "3"):
        print("  Opening desktop window (press q in the window to close it)...")
        try:
            # Imported lazily: opencv-python is optional (not available on Termux)
            from core.viewer import run_window
            run_window(handle.base_url, handle.token, feature)
        except KeyboardInterrupt:
            pass
        except ImportError:
            print("  [!] opencv-python is not installed — use the browser viewer instead:")
            print(f"      {handle.viewer_url}")

    print()
    print("  Server is still running. Press Ctrl+C to stop everything.")
    try:
        while True:
            input("")
    except (KeyboardInterrupt, EOFError):
        print()
        handle.stop()
        print("  [OK] Server stopped")


def remember_defaults(ip: str, port: int) -> None:
    """Persist the last used IP/port so option 2 can prefill them."""
    config.save_properties(config.APP_PROPERTIES_FILE, {"host": ip, "port": str(port)})


# ---------------------------------------------------------------------
# Direct subcommands (for power users)
# ---------------------------------------------------------------------

def cmd_window(args) -> None:
    from core.viewer import run_window
    ip = args.host or netutils.get_lan_ip()
    try:
        run_window(f"http://{ip}:{args.port}", args.token, args.feature)
    except ImportError:
        print("  [!] opencv-python is not installed:  pip install -r requirements.txt")


def main() -> int:
    parser = argparse.ArgumentParser(prog="lanlink", description=APP_NAME)
    sub = parser.add_subparsers(dest="cmd")

    sub.add_parser("build", help="option 1: prepare + build the Android APK")

    start = sub.add_parser("start", help="option 2: start a feature + QR")
    start.add_argument("-f", "--feature", choices=config.FEATURES, default=config.DEFAULT_FEATURE)
    start.add_argument("-p", "--port", type=int, default=config.DEFAULT_PORT)
    start.add_argument("--host", help="PC LAN IP (auto-detected by default)")
    start.add_argument("--view", choices=["none", "browser", "window", "both"], default="browser")

    win = sub.add_parser("window", help="open the desktop viewer window for a running server")
    win.add_argument("-p", "--port", type=int, default=config.DEFAULT_PORT)
    win.add_argument("-t", "--token", required=True)
    win.add_argument("-f", "--feature", choices=config.FEATURES, default=config.DEFAULT_FEATURE)
    win.add_argument("--host")

    args = parser.parse_args()

    if args.cmd == "build":
        option_build()
    elif args.cmd == "start":
        option_start()
    elif args.cmd == "window":
        cmd_window(args)
    else:
        while True:
            choice = menu()
            if choice == "1":
                option_build()
            elif choice == "2":
                option_start()
            elif choice in ("0", "q", "exit", "quit"):
                print("\n  Bye!\n")
                return 0
            else:
                print("\n  [!] Unknown option — choose 1, 2 or 0\n")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\n  [OK] Stopped\n")
        sys.exit(0)
