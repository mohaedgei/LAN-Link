"""Desktop viewer window (OpenCV) with mouse/keyboard remote control.

Controls inside the window:
    - Left click            -> tap on the phone screen
    - Left drag             -> swipe on the phone screen
    - 1 / 2 / 3             -> switch feature (screen / front / back)
    - b / h / r             -> Back / Home / Recents
    - z / x                 -> pinch zoom in / out
    - t                     -> type a line of text (prompt in terminal)
    - q or ESC              -> close the window
"""

import json

import cv2
import numpy as np
import requests


class DesktopViewer:
    def __init__(self, base_url: str, token: str, feature: str):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.feature = feature
        self.session = requests.Session()
        self.down_pos = None
        self.win = "LAN-Link  |  1=Screen 2=Front 3=Back  b/h/r=Back/Home/Recents  z/x=Zoom  t=Text  q=Quit"

    # -- control -------------------------------------------------------
    def _control(self, payload: dict) -> None:
        try:
            self.session.post(
                f"{self.base_url}/control",
                params={"t": self.token},
                data=json.dumps(payload),
                headers={"Content-Type": "application/json"},
                timeout=4,
            )
        except requests.RequestException:
            pass

    def _switch(self, feature: str) -> None:
        self.feature = feature
        self._control({"type": "feature", "value": feature})

    # -- mouse ---------------------------------------------------------
    def _on_mouse(self, event, x, y, flags, param) -> None:
        frame = param.get("frame") if param else None
        if frame is None:
            return
        h, w = frame.shape[:2]
        if event == cv2.EVENT_LBUTTONDOWN:
            self.down_pos = (x, y)
        elif event == cv2.EVENT_LBUTTONUP and self.down_pos:
            x1, y1 = self.down_pos
            self.down_pos = None
            if abs(x - x1) < 12 and abs(y - y1) < 12:
                self._control({"type": "tap", "x": x1 / w, "y": y1 / h})
            else:
                self._control({
                    "type": "swipe",
                    "x1": x1 / w, "y1": y1 / h,
                    "x2": x / w, "y2": y / h,
                    "ms": 300,
                })

    # -- keys ----------------------------------------------------------
    def _on_key(self, key: int, frame) -> bool:
        """Handle a key press. Returns False when the window should close."""
        if key in (27, ord("q")):
            return False
        keys = {ord("1"): "screen", ord("2"): "front", ord("3"): "back"}
        if key in keys:
            self._switch(keys[key])
        elif key == ord("b"):
            self._control({"type": "key", "value": "back"})
        elif key == ord("h"):
            self._control({"type": "key", "value": "home"})
        elif key == ord("r"):
            self._control({"type": "key", "value": "recents"})
        elif key == ord("z"):
            self._control({"type": "pinch", "dir": "in"})
        elif key == ord("x"):
            self._control({"type": "pinch", "dir": "out"})
        elif key == ord("t"):
            cv2.destroyWindow(self.win)
            text = input("  Text to send to the phone (empty = cancel): ")
            if text:
                self._control({"type": "text", "value": text})
            cv2.namedWindow(self.win, cv2.WINDOW_NORMAL)
            cv2.setMouseCallback(self.win, self._on_mouse, {"frame": frame})
        return True

    # -- main loop -----------------------------------------------------
    def run(self) -> None:
        cv2.namedWindow(self.win, cv2.WINDOW_NORMAL)
        cv2.resizeWindow(self.win, 720, 405)
        waiting = np.zeros((405, 720, 3), dtype=np.uint8)
        cv2.putText(waiting, "Waiting for the phone... scan the QR", (90, 200),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.7, (150, 170, 200), 1, cv2.LINE_AA)
        cv2.putText(waiting, "q = quit", (90, 235),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.6, (110, 130, 160), 1, cv2.LINE_AA)

        alive = True
        while alive:
            frame = None
            try:
                resp = self.session.get(
                    f"{self.base_url}/frame.jpg", params={"t": self.token}, timeout=4
                )
                if resp.status_code == 200 and resp.content:
                    frame = cv2.imdecode(
                        np.frombuffer(resp.content, dtype=np.uint8), cv2.IMREAD_COLOR
                    )
            except requests.RequestException:
                pass

            shown = frame if frame is not None else waiting
            cv2.setWindowTitle(self.win, f"{self.win}  [{self.feature}]")
            if not hasattr(self, "_hooked"):
                cv2.setMouseCallback(self.win, self._on_mouse, {"frame": shown})
                self._hooked = True
            if frame is not None:
                # keep the callback frame fresh
                cv2.setMouseCallback(self.win, self._on_mouse, {"frame": frame})
            cv2.imshow(self.win, shown)

            key = cv2.waitKey(60) & 0xFF
            if key != 255:
                alive = self._on_key(key, shown)

        cv2.destroyWindow(self.win)


def run_window(base_url: str, token: str, feature: str) -> None:
    DesktopViewer(base_url, token, feature).run()
