# LAN-Link Protocol v1

Everything travels over plain HTTP + WebSocket inside the local WiFi.
All endpoints require a **session token** (`t` query parameter) shown in
the QR code. No TLS yet (v2 candidate); the token keeps casual LAN
neighbors out.

## 1. Server endpoints

| Method | Path          | Purpose                                        |
|--------|---------------|------------------------------------------------|
| GET    | `/?t=`        | Browser viewer (canvas + control bar)          |
| WS     | `/ws?t=&role=stream\|viewer` | Device / viewer channel          |
| GET    | `/frame.jpg?t=` | Latest JPEG frame (desktop window polling)   |
| POST   | `/control?t=` | Send a control JSON (desktop window)           |
| GET    | `/status?t=`  | JSON server status                             |

## 2. QR content

```
LL1|<pc-ip>|<port>|<token>|<feature>[|<relay-site>]
```

A plain, human-readable payload. The phone app (a pure QR reader called
**QR Scanner**) recognises the small `LL1|` marker and connects by
itself — the QR decides the address, the session and the feature.
Anyone scanning it with another reader sees exactly what it does.

`feature` is one of `screen`, `front`, `back`, `any`.

## 3. WebSocket roles

### role=stream (the phone)

- Sends **binary** messages: JPEG frames (~720p, quality 60, ~10-11 fps).
- Sends **text** JSON status messages:
  ```json
  {"type": "status", "value": "capture-started | capture-stopped | screen-consent-needed"}
  ```
- Receives **text** JSON commands (see below).

### role=viewer (browser)

- Receives **binary** JPEG frames.
- Sends **text** JSON: feature switches and control commands, relayed
  by the server to the device.

## 4. Control JSON (viewer -> server -> phone)

| Action       | Payload                                                            |
|--------------|--------------------------------------------------------------------|
| Switch feed  | `{"type":"feature","value":"screen\|front\|back\|stop"}`           |
| Tap          | `{"type":"tap","x":0.42,"y":0.77}` (normalized 0..1)               |
| Swipe        | `{"type":"swipe","x1":..,"y1":..,"x2":..,"y2":..,"ms":300}`        |
| Pinch zoom   | `{"type":"pinch","dir":"in\|out"}`                                 |
| Global key   | `{"type":"key","value":"back\|home\|recents\|notifications"}`      |
| Type text    | `{"type":"text","value":"hello"}` (via QR Scanner IME)             |

## 5. App connection defaults

`android/app/src/main/assets/lanlink.properties` (written by option 1):

```
host=<pc lan ip>
port=8080
```

## 6. Future / v2 ideas

- H.264 hardware encoding (MediaCodec) instead of JPEG frames
- Audio streaming
- File push/pull, notifications mirror, clipboard sync
- WSS with a self-signed certificate + PIN pairing
- Extract the Python side into an importable `lanlink` library
