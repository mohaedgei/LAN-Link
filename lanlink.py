#!/usr/bin/env python3
"""LAN-Link — stream your Android phone screen & cameras over local WiFi.

v3.1 flow:
    python lanlink.py            -> asks IP + port, runs the full doctor,
                                    starts the server, then the menu
    python lanlink.py qr         -> same, then shows the connect QR
    python lanlink.py qr -f screen --host 192.168.1.100 --port 8080
    python lanlink.py start      -> same as qr (kept for old habits)
"""

import argparse
import os
import sys
import time

from core import APP_NAME, VERSION, config
from core import doctor, netutils, qrcrypto, qrgen, server, siteapi

# --- terminal colors (harmless on Termux / Linux; enabled on Windows below) ---
GREEN = "\033[92m"
BRIGHT = "\033[1m"
DIM = "\033[2m"
RED = "\033[91m"
RESET = "\033[0m"
MAKER = "@Py_RHL"

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
    sys.stdout.write("\033[2J\033[H")
    sys.stdout.flush()


def ask(prompt: str) -> str:
    try:
        return input(prompt).strip()
    except EOFError:
        # stdin closed (terminal closed / piped input done) — never spin
        raise SystemExit(0)


# ---------------------------------------------------------------------
# Step 1 — setup: IP + port + full doctor
# ---------------------------------------------------------------------

def run_setup_checks(ip: str, port: int) -> list:
    """All checks that run before the server starts."""
    checks = [
        doctor.check_ip_format(ip),
        doctor.check_ip_scope(ip),
        doctor.check_ip_local(ip),
        doctor.check_port_format(port),
        doctor.check_port_free(port),
    ]
    return checks


def _print_checks(checks: list) -> None:
    for res in checks:
        if res.ok:
            print(f"  {GREEN}[OK]{RESET} {res.label:<24} {DIM}{res.detail}{RESET}")
        else:
            print(f"  {RED}[FAIL]{RESET} {res.label:<24} {res.detail}")


def setup_connection(host: str | None = None, port: int | None = None) -> tuple[str, int, str]:
    """Ask for IP + port and loop until every check passes.

    Returns (ip, port, via) — via is 'direct' on a LAN IP, 'relay' when
    the user typed a public IP (stream then goes through the website).
    """
    defaults = config.load_properties(config.APP_PROPERTIES_FILE)
    auto_ip = host or defaults.get("host") or netutils.get_lan_ip()
    auto_port = port or int(defaults.get("port", config.DEFAULT_PORT))

    print()
    print(f"  {BRIGHT}Step 1 — where does this device live on the network?{RESET}")
    print(f"  {DIM}(type 0.0.0.0 to listen on every interface){RESET}")
    while True:
        ip = netutils.ask_ip(auto_ip)
        if ip == "0.0.0.0":
            ip = netutils.get_lan_ip()
            print(f"  {DIM}(listening on every interface — advertising {ip}){RESET}")
        port = netutils.ask_port(auto_port)

        print(f"\n  {DIM}Checking your setup...{RESET}")
        checks = run_setup_checks(ip, port)
        _print_checks(checks)
        failed = [c for c in checks if not c.ok]
        if not failed:
            kind = doctor.ip_kind(ip)
            via = "relay" if kind == "public" else "direct"
            return ip, port, via

        print(f"\n  {RED}[!] {len(failed)} problem(s) found — fix them and we continue{RESET}")
        # carry the last values as new defaults so fixing is one keypress
        auto_ip, auto_port = ip, port
        if failed and failed[-1].label == "Port is free":
            # suggest the next free port directly in the prompt
            import re
            match = re.search(r"try (\d+)", failed[-1].detail)
            if match:
                auto_port = int(match.group(1))
        again = ask("  Press Enter to try again (q to quit): ")
        if again in ("q", "Q", "quit", "exit"):
            print("\n  Bye!\n")
            sys.exit(0)

# ---------------------------------------------------------------------
# Step 2 — server + website registration
# ---------------------------------------------------------------------

def start_everything(ip: str, port: int, via: str, feature: str = "any"):
    """Start the server thread, self-test it, register the session."""
    token = config.load_or_create_token()
    print(f"\n  {DIM}Starting server on {ip}:{port} ...{RESET}")
    handle = server.start_background(ip, port, feature, token=token)

    tail = [
        doctor.check_http_alive(handle.base_url, token),
        doctor.check_ws_alive(handle.base_url, token),
    ]
    _print_checks(tail)
    if any(not c.ok for c in tail):
        handle.stop()
        raise RuntimeError("the server started but did not answer its own door")

    # remember these values for the next run
    try:
        config.save_properties(config.APP_PROPERTIES_FILE, {"host": ip, "port": str(port)})
    except OSError:
        pass

    siteapi.register_session_async(ip, port, token, via, feature)
    return handle


def show_running_card(handle, via: str) -> None:
    token = handle.token
    reg = siteapi.last_registration()
    if reg:
        site_state = f"{GREEN}session registered{RESET}"
    elif reg is False:
        site_state = f"{DIM}unreachable — LAN mode still works{RESET}"
    else:
        site_state = f"{DIM}connecting...{RESET}"

    print()
    line()
    print(f"  {GREEN}{BRIGHT}[OK] SERVER IS RUNNING — WAITING FOR CONNECTIONS{RESET}")
    line()
    print(f"  Target   : {BRIGHT}{handle.host}:{handle.port}{RESET}")
    print(f"  Session  : {BRIGHT}{token}{RESET}  {DIM}(stable — the app saves it){RESET}")
    route = "direct LAN connection" if via == "direct" else f"through {config.SITE_URL}"
    print(f"  Route    : {route}")
    line()
    print(f"  {BRIGHT}YOUR LINKS{RESET}  {DIM}(the session code ?t= is REQUIRED){RESET}")
    print(f"  Watch in browser : {GREEN}{handle.viewer_url}{RESET}")
    print(f"  App APK (WiFi)   : {GREEN}{handle.base_url}/app.apk?t={token}{RESET}")
    line()
    print(f"  Website  : {config.SITE_URL}  {DIM}({site_state}){RESET}")
    print(f"  App APK  : {GREEN}{config.SITE_APK_URL}{RESET}")
    line()
    print(f"  {DIM}Opening {handle.base_url} without ?t= shows a code page —{RESET}")
    print(f"  {DIM}always use the full links above.{RESET}")
    line()


# ---------------------------------------------------------------------
# Step 3 — the menu (server already running underneath)
# ---------------------------------------------------------------------

def menu(handle) -> str:
    print(f"{GREEN}{BRIGHT}{BANNER}{RESET}")
    print(f"  {BRIGHT}{GREEN}{APP_NAME}{RESET} v{VERSION}  {DIM}|  local network only  |  MIT license"
          f"  |  made by {GREEN}{MAKER}{RESET}")
    line()
    print("  [1] Make the connect QR — Watch the screen")
    print("  [2] Make the connect QR — Front camera")
    print("  [3] Make the connect QR — Back camera")
    print("  [4] Make the connect QR — Let me choose in the viewer")
    print("  [5] Session & website status")
    print("  [0] Exit")
    line()
    return ask("  Select an option: ")


# ---------------------------------------------------------------------
# QR + wait mode (the fix: the script STAYS here until the app connects)
# ---------------------------------------------------------------------

def make_qr_and_wait(handle, cmd: str, via: str) -> None:
    ip, port, token = handle.host, handle.port, handle.token
    # v3.0: plain, human-readable payload — LL1|ip|port|token|feature.
    # No crypto library needed and any scanner shows what it does.
    payload = qrcrypto.build_plain(cmd, ip, port, token, via=via, site=config.SITE_URL)

    clear_screen()
    print(f"{GREEN}{BRIGHT}{BANNER}{RESET}")
    print(f"  {GREEN}{BRIGHT}[OK] LAN-LINK QR READY{RESET}")
    line()
    label = config.FEATURE_LABELS.get(cmd, "Your choice in the app")
    route = "direct" if via == "direct" else f"via {config.SITE_URL}"
    print(f"  Opens  : {label}")
    print(f"  Target : {ip}:{port}  {DIM}({route}){RESET}")
    print(f"  Session: {BRIGHT}{token}{RESET}")
    print(f"  Valid  : 24 hours")
    line()
    print(f"  Watch in browser : {GREEN}http://{ip}:{port}/?t={token}{RESET}")
    line()
    print(f"  Scan it with the LAN-Link app (camera or a saved picture).")
    print(f"  {DIM}This screen waits for the app — Ctrl+C returns to the menu,{RESET}")
    print(f"  {DIM}the server keeps running either way.{RESET}")
    line()

    qrgen.make(payload, f"{cmd}-app", config.DIST_DIR)
    _wait_for_connection(handle, cmd)


def _wait_for_connection(handle, cmd: str) -> None:
    """Live status screen: waiting -> connected -> streaming stats."""
    hub = handle.hub
    started = time.time()
    was_connected = False
    last_frames = 0
    last_t = time.time()
    fps = 0.0

    print()
    print(f"  {DIM}Ctrl+C = back to menu (server keeps running){RESET}")
    print()
    try:
        while True:
            connected = hub.device is not None and not hub.device.closed
            now = time.time()
            if now - last_t >= 2.0:
                fps = (hub.frames - last_frames) / (now - last_t)
                last_frames, last_t = hub.frames, now

            if connected:
                if not was_connected:
                    print(f"\n  {GREEN}{BRIGHT}[OK] APP CONNECTED{RESET}"
                          f" — streaming {hub.requested_feature}\n")
                    was_connected = True
                status = (f"\r  {GREEN}●{RESET} streaming {hub.requested_feature}"
                          f"  ·  frames: {hub.frames}"
                          f"  ·  fps: {fps:.1f}"
                          f"  ·  viewers: {len(hub.viewers)}"
                          f"  ·  session: {int(now - started)}s   ")
            else:
                if was_connected:
                    print(f"\n  {DIM}○ app disconnected — waiting again...{RESET}")
                    was_connected = False
                    started = time.time()
                status = (f"\r  {GREEN}●{RESET} waiting for the app to connect ..."
                          f"  {int(now - started)}s   ")
            sys.stdout.write(status)
            sys.stdout.flush()
            time.sleep(1)
    except (KeyboardInterrupt, EOFError):
        print(f"\n\n  {DIM}Back to menu — server still running.{RESET}\n")
        time.sleep(0.6)


def show_session_info(handle, via: str) -> None:
    reg = siteapi.last_registration()
    health = doctor.probe_site()
    print()
    line()
    print(f"  {BRIGHT}SESSION STATUS{RESET}")
    line()
    print(f"  Server   : {handle.base_url}  (uptime {int(time.time() - handle.hub.started_at)}s)")
    print(f"  Session  : {BRIGHT}{handle.token}{RESET}")
    print(f"  Feature  : {handle.hub.requested_feature}")
    print(f"  Watch in browser : {GREEN}{handle.viewer_url}{RESET}")
    print(f"  App APK (WiFi)   : {GREEN}{handle.base_url}/app.apk?t={handle.token}{RESET}")
    print(f"  Device   : "
          f"{GREEN}connected{RESET}" if handle.hub.device is not None and not handle.hub.device.closed
          else f"  Device   : {DIM}not connected yet{RESET}")
    print(f"  Frames   : {handle.hub.frames}")
    print(f"  Viewers  : {len(handle.hub.viewers)}")
    print(f"  Website  : {config.SITE_URL} — "
          + (f"{GREEN}alive{RESET} ({health.get('service', 'ok')})" if health
             else f"{DIM}unreachable (LAN still works){RESET}"))
    print(f"  Session @ site : "
          + (f"{GREEN}registered{RESET}" if reg
             else f"{DIM}not registered{RESET}"))
    print(f"  App APK  : {config.SITE_APK_URL}")
    line()
    ask("  Press Enter to go back... ")


# ---------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------

def run_interactive(host: str | None, port: int | None,
                    qr_feature: str | None, via_hint: str = "direct") -> int:
    clear_screen()
    print(f"{GREEN}{BRIGHT}{BANNER}{RESET}")
    ip, port, via = setup_connection(host, port)

    handle = start_everything(ip, port, via)
    show_running_card(handle, via)

    if qr_feature:  # subcommand pre-selected a QR
        make_qr_and_wait(handle, qr_feature, via)
        if qr_feature != "any" and qr_feature in config.FEATURES:
            pass

    while True:
        try:
            choice = menu(handle)
        except EOFError:
            print("\n  Bye!\n")
            handle.stop()
            return 0
        if choice == "1":
            make_qr_and_wait(handle, "screen", via)
        elif choice == "2":
            make_qr_and_wait(handle, "front", via)
        elif choice == "3":
            make_qr_and_wait(handle, "back", via)
        elif choice == "4":
            make_qr_and_wait(handle, "any", via)
        elif choice == "5":
            show_session_info(handle, via)
        elif choice in ("0", "q", "exit", "quit"):
            print(f"\n  {DIM}Stopping the server...{RESET}")
            handle.stop()
            print(f"  {GREEN}[OK]{RESET} Closed. Bye!\n")
            return 0
        else:
            print("\n  [!] Unknown option — choose 0-5\n")


def main() -> int:
    parser = argparse.ArgumentParser(prog="lanlink", description=APP_NAME)
    sub = parser.add_subparsers(dest="cmd")

    qr = sub.add_parser("qr", help="setup + server + connect QR + wait for the app")
    qr.add_argument("-f", "--feature", choices=config.QR_CMDS, default="any")
    qr.add_argument("-p", "--port", type=int)
    qr.add_argument("--host")

    start = sub.add_parser("start", help="same as qr (kept for old habits)")
    start.add_argument("-f", "--feature", choices=config.QR_CMDS, default="screen")
    start.add_argument("-p", "--port", type=int)
    start.add_argument("--host")

    win = sub.add_parser("window", help="open the desktop viewer window for a running server")
    win.add_argument("-p", "--port", type=int, default=config.DEFAULT_PORT)
    win.add_argument("-t", "--token", required=True)
    win.add_argument("-f", "--feature", choices=config.FEATURES, default=config.DEFAULT_FEATURE)
    win.add_argument("--host")

    args = parser.parse_args()

    if args.cmd in ("qr", "start"):
        return run_interactive(args.host, args.port, args.feature)
    if args.cmd == "window":
        from core.viewer import run_window
        ip = args.host or netutils.get_lan_ip()
        run_window(f"http://{ip}:{args.port}", args.token, args.feature)
        return 0

    return run_interactive(None, None, None)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (KeyboardInterrupt, EOFError):
        print("\n  [OK] Stopped\n")
        sys.exit(0)
