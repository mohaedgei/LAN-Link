"""LAN-Link streaming relay server (HTTP + WebSocket), built on aiohttp.

Topology (everything stays inside the local WiFi):

    Android app (WS client, role=stream)
            |  JPEG frames  /  JSON control
            v
    LAN-Link server (this module)  <-- browser viewer(s) (role=viewer)
            ^                          + desktop OpenCV window (/frame.jpg)
    QR code contains: http://<pc-ip>:<port>/c?t=<token>&f=<feature>

All endpoints are gated by a session token so only devices that scanned
the QR can interact with the server.
"""

import asyncio
import json
import secrets
import threading
import time
from string import Template

from aiohttp import WSMsgType, web

from core import config


def log(message: str) -> None:
    stamp = time.strftime("%H:%M:%S")
    print(f"  [{stamp}] {message}")


class Hub:
    """Shared state: the phone connection, viewers and the latest frame."""

    def __init__(self, token: str, feature: str):
        self.token = token
        self.requested_feature = feature
        self.device = None            # WebSocketResponse of the Android app
        self.viewers = set()          # WebSocketResponse of browser viewers
        self.latest_frame = b""
        self.frame_time = 0.0
        self.frames = 0               # total binary frames received from the app
        self.connected_at = 0.0       # when the app last joined
        self.started_at = time.time()
        # APK download stats (this session + all-time, persisted by config)
        self.apk_downloads = 0
        self.apk_downloads_base = config.read_apk_downloads()

    def count_apk_download(self) -> int:
        """Register one APK download; returns the all-time total."""
        self.apk_downloads += 1
        total = self.apk_downloads_base + self.apk_downloads
        config.write_apk_downloads(total)
        return total

    # -- token ---------------------------------------------------------
    def check_token(self, request) -> bool:
        got = request.query.get("t") or request.query.get("token") or ""
        # Case-insensitive: some keyboards/QR flows uppercase the code
        return bool(got) and secrets.compare_digest(
            got.strip().lower(), self.token.strip().lower()
        )

    # -- helpers -------------------------------------------------------
    async def relay_to_device(self, payload: dict) -> bool:
        if self.device is None or self.device.closed:
            return False
        try:
            await self.device.send_str(json.dumps(payload))
            return True
        except ConnectionResetError:
            return False

    async def broadcast_frame(self, data: bytes) -> None:
        self.latest_frame = data
        self.frame_time = time.time()
        dead = []
        for viewer in self.viewers:
            try:
                await viewer.send_bytes(data)
            except ConnectionResetError:
                dead.append(viewer)
        for viewer in dead:
            self.viewers.discard(viewer)

    async def broadcast_status(self, value: str) -> None:
        for viewer in list(self.viewers):
            try:
                await viewer.send_str(json.dumps({"type": "status", "value": value}))
            except ConnectionResetError:
                self.viewers.discard(viewer)


# ---------------------------------------------------------------------
# HTTP handlers
# ---------------------------------------------------------------------

def _render_template(name: str, **values) -> str:
    text = (config.TEMPLATES_DIR / name).read_text(encoding="utf-8")
    return Template(text).safe_substitute(**values)


def _no_token_response() -> web.Response:
    """Friendly 403 page shown when the URL has no (or a wrong) session code."""
    html = _render_template("notoken.html", repo_url=config.REPO_URL)
    return web.Response(status=403, text=html, content_type="text/html")


# ---------------------------------------------------------------------
# App factory
# ---------------------------------------------------------------------

def make_app(hub: Hub) -> web.Application:
    app = web.Application()
    routes = web.RouteTableDef()

    @routes.get("/c")
    async def connect_page(request):
        """Landing page opened when the QR is scanned with Google Lens.

        It immediately tries to deep-link into the installed app via the
        custom `lanlink://` scheme, with a manual fallback link.
        """
        if not hub.check_token(request):
            return _no_token_response()
        feature = request.query.get("f", hub.requested_feature)
        deep_link = (
            f"lanlink://connect?host={request.host.split(':')[0]}"
            f"&port={request.host.split(':')[1] if ':' in request.host else 8080}"
            f"&token={hub.token}&feature={feature}"
        )
        html = _render_template(
            "connect.html",
            deep_link=deep_link,
            feature=feature,
            host=request.host,
            apk_url=f"/app.apk?t={hub.token}",
            viewer_url=f"/?t={hub.token}",
            repo_url=config.REPO_URL,
        )
        return web.Response(text=html, content_type="text/html")

    @routes.get("/")
    async def viewer_page(request):
        if not hub.check_token(request):
            return _no_token_response()
        html = _render_template(
            "viewer.html",
            token=hub.token,
            feature=hub.requested_feature,
            repo_url=config.REPO_URL,
            apk_url=f"/app.apk?t={hub.token}",
            apk_info=(f"QR-Scanner.apk · {config.apk_size_mb()}" if config.find_apk()
                      else "QR-Scanner.apk · latest release"),
            version=config.VERSION,
        )
        return web.Response(text=html, content_type="text/html")

    @routes.get("/app.apk")
    async def app_apk(request):
        """Serve the LAN-Link APK so anyone with the session link can install it.

        If an APK was built locally (dist/ or the gradle output folder) it is
        streamed straight from disk; otherwise the request is redirected to
        the latest GitHub Release asset.
        """
        if not hub.check_token(request):
            return _no_token_response()
        apk = config.find_apk()
        if apk is not None:
            total = hub.count_apk_download()
            log(f"APK downloaded  (all-time total: {total})")
            return web.FileResponse(
                apk,
                headers={"Content-Disposition": 'attachment; filename="QR-Scanner.apk"'},
            )
        total = hub.count_apk_download()
        log(f"APK download redirected to GitHub Releases  (all-time total: {total})")
        raise web.HTTPFound(config.RELEASE_APK_URL)

    @routes.get("/ws")
    async def websocket_handler(request):
        if not hub.check_token(request):
            return web.Response(status=403, text="LAN-Link: bad token")
        ws = web.WebSocketResponse(max_msg_size=8 * 1024 * 1024)
        await ws.prepare(request)
        role = request.query.get("role", "viewer")

        if role == "stream":
            # v2.1: the app names the feature it wants in the URL, so one
            # running server can serve screen / front / back without a restart
            wanted = request.query.get("feature", "")
            if wanted in config.FEATURES:
                hub.requested_feature = wanted
            hub.device = ws
            hub.connected_at = time.time()
            log(f"Device connected (feature requested: {hub.requested_feature})")
            await hub.broadcast_status("device-connected")
            async for msg in ws:
                if msg.type == WSMsgType.BINARY:
                    hub.frames += 1
                    await hub.broadcast_frame(msg.data)
                elif msg.type == WSMsgType.TEXT:
                    try:
                        data = json.loads(msg.data)
                    except json.JSONDecodeError:
                        continue
                    if data.get("type") == "status":
                        log(f"Device: {data.get('value', '')}")
                        await hub.broadcast_status(str(data.get("value", "")))
                elif msg.type == WSMsgType.ERROR:
                    break
            hub.device = None
            log("Device disconnected")
            await hub.broadcast_status("device-disconnected")
        else:  # viewer
            hub.viewers.add(ws)
            if hub.latest_frame:
                try:
                    await ws.send_bytes(hub.latest_frame)
                except ConnectionResetError:
                    pass
            async for msg in ws:
                if msg.type == WSMsgType.TEXT:
                    try:
                        data = json.loads(msg.data)
                    except json.JSONDecodeError:
                        continue
                    if data.get("type") == "feature":
                        hub.requested_feature = str(data.get("value", "screen"))
                        log(f"Feature switch requested -> {hub.requested_feature}")
                    sent = await hub.relay_to_device(data)
                    if not sent:
                        await ws.send_str(json.dumps({"type": "status", "value": "no-device"}))
                elif msg.type in (WSMsgType.ERROR, WSMsgType.CLOSE):
                    break
            hub.viewers.discard(ws)
        return ws

    @routes.get("/frame.jpg")
    async def frame_jpg(request):
        if not hub.check_token(request):
            return web.Response(status=403, text="bad token")
        if hub.latest_frame:
            return web.Response(
                body=hub.latest_frame,
                content_type="image/jpeg",
                headers={"Cache-Control": "no-store"},
            )
        return web.Response(status=204)

    @routes.post("/control")
    async def control(request):
        if not hub.check_token(request):
            return web.json_response({"ok": False, "error": "bad token"}, status=403)
        try:
            payload = await request.json()
        except Exception:
            return web.json_response({"ok": False, "error": "bad json"}, status=400)
        sent = await hub.relay_to_device(payload)
        return web.json_response({"ok": sent})

    @routes.get("/status")
    async def status(request):
        if not hub.check_token(request):
            return web.json_response({"ok": False}, status=403)
        return web.json_response({
            "app": config.APP_NAME,
            "version": config.VERSION,
            "device_connected": hub.device is not None and not hub.device.closed,
            "feature": hub.requested_feature,
            "viewers": len(hub.viewers),
            "has_frame": bool(hub.latest_frame),
            "frames": hub.frames,
            "fps_hint": round(1.0 / (time.time() - hub.frame_time), 1) if hub.frame_time else 0.0,
            "connected_sec": round(time.time() - hub.connected_at, 1) if hub.connected_at else 0.0,
            "uptime_sec": round(time.time() - hub.started_at, 1),
            "apk_downloads": hub.apk_downloads,
            "apk_downloads_total": hub.apk_downloads_base + hub.apk_downloads,
        })

    app.add_routes(routes)
    return app


# ---------------------------------------------------------------------
# Background server runner
# ---------------------------------------------------------------------

class ServerHandle:
    """Controls the server thread started by start_background()."""

    def __init__(self, host: str, port: int, token: str, feature: str):
        self.host = host          # LAN IP advertised in the QR
        self.bind_host = "0.0.0.0"
        self.port = port
        self.token = token
        self.feature = feature
        self.hub = Hub(token, feature)
        self.ready = threading.Event()
        self._loop = None
        self._runner = None
        self._thread = threading.Thread(target=self._run, name="lanlink-server", daemon=True)

    @property
    def base_url(self) -> str:
        return f"http://{self.host}:{self.port}"

    @property
    def viewer_url(self) -> str:
        return f"{self.base_url}/?t={self.token}"

    @property
    def connect_url(self) -> str:
        return f"{self.base_url}/c?t={self.token}&f={self.feature}"

    def _run(self):
        self._loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self._loop)
        try:
            self._loop.run_until_complete(self._serve())
        except Exception as exc:  # pragma: no cover
            log(f"Server error: {exc}")
        finally:
            self._loop.close()

    async def _serve(self):
        self._runner = web.AppRunner(make_app(self.hub), access_log=None)
        await self._runner.setup()
        site = web.TCPSite(self._runner, self.bind_host, self.port)
        try:
            await site.start()
        except OSError as exc:
            log(f"Cannot bind port {self.port}: {exc}")
            return
        self.ready.set()
        log(f"Server listening on {self.base_url}")
        while True:  # keep the loop alive until stop() is called
            await asyncio.sleep(3600)

    def start(self):
        self._thread.start()
        if not self.ready.wait(timeout=6):
            raise RuntimeError("LAN-Link server failed to start (is the port busy?)")

    def stop(self):
        if self._loop is not None and self._runner is not None:
            async def _shutdown():
                await self._runner.cleanup()
            asyncio.run_coroutine_threadsafe(_shutdown(), self._loop)
            time.sleep(0.4)


def start_background(host: str, port: int, feature: str, token: str | None = None) -> ServerHandle:
    """Start the relay server in a daemon thread and return its handle."""
    token = token or secrets.token_hex(3)  # 6 hex chars, short enough to type
    handle = ServerHandle(host, port, token, feature)
    handle.start()
    return handle
