<p align="center">
  <img src="docs/logo.svg" alt="LAN-Link" width="560">
</p>

<h1 align="center">LAN-Link</h1>

<p align="center">
  <img src="https://img.shields.io/badge/platform-Android%20%2B%20PC-blue" alt="platform">
  <img src="https://img.shields.io/badge/network-local%20WiFi%20only-22c55e" alt="network">
  <img src="https://img.shields.io/badge/license-MIT-7c3aed" alt="license">
  <img src="https://img.shields.io/badge/python-3.10%2B-2563eb" alt="python">
</p>

**English** | [العربية](#العربية)

---

LAN-Link turns **your own Android phone + your own PC** into a self-hosted
streaming & remote-control pair. The phone shares its **screen** or its
**front / back camera**, and you watch — and fully control the phone —
from the PC. Everything flows **inside your local WiFi only**: no cloud,
no accounts, no internet dependency.

A small Python script drives everything:

- **Option 1** — builds the Android app for your network and tells you
  exactly where the APK is, ready to install.
- **Option 2** — starts a feature (screen share / front camera / back
  camera) and shows a **fully encrypted QR code**. Scan it with the app
  and the connection + streaming starts automatically.
- **Option 3** — prints the same encrypted QR **without running the
  server** (handy to pre-pair the phone), or generate it online at
  [c4sf4qh0-d.space-z.ai](https://c4sf4qh0-d.space-z.ai) — the companion
  site that also hosts the APK download and a live relay.

## Features

- **v2 QR app — a pure scanner** — the app now does ONE thing: scan the
  encrypted QR (or decode one from a picture). Inside is the PC address
  + session code, sealed with **AES-256-GCM** so only the app can read
  it. No manual IP entry, no codes to type, nothing else on screen —
  just three buttons: **Watch Screen / Front Camera / Back Camera**.

- **Screen share with full remote control** — watch the phone screen in
  the browser or a desktop window, then tap, swipe, pinch-zoom, press
  Back / Home / Recents and even type text from the PC.
- **Front & back camera streaming** — live camera view at 720p.
- **QR pairing, sealed** — one scan connects the phone to the PC. The
  QL1 payload (`QL1.` + base64url(nonce ‖ ciphertext ‖ tag)) is produced
  identically by the script, the website API (`/api/qr/encrypt`) and can
  only be opened by the app; it also expires after 24 hours.
- **Three buttons, nothing more** — screen / front camera / back camera.
- **Full web dashboard** — the viewer page is a complete control center
  with four tabs: **Live** (stream + feature switches + fullscreen),
  **Remote control** (navigation keys, zoom, text typing with quick
  phrases), **Get the app** (in-page APK download) and **Help**.
- **Live stats** — the dashboard header shows stream fps, resolution,
  connected viewers, uptime and how many times the APK was downloaded
  (persisted all-time counter); the terminal logs each download too.
- **Ready-made APK** — a built `LAN-Link.apk` ships in `dist/`, and the
  green “⬇ Get the app” button on every page serves it instantly — no
  build tools needed on any machine.
- **App remembers your setup** — IP, port and session code are saved by
  the app after the first pairing; next runs are one tap. The session
  code itself is stable between runs (`--new-token` rotates it).
- **Dual viewer** — the web dashboard and a native OpenCV window with
  keyboard/mouse control.
- **Session code** — every endpoint is gated by a 6-char code; devices
  without it cannot talk to the server.
- **100% LAN** — nothing leaves your router.

## How it works

```
+----------------+  QR (http://pc-ip:port/c?t=token)  +----------------+
|     PC (you)   |----------------------------------->|  Phone camera  |
|  python CLI    |   Google Lens -> lanlink:// deep   |  or LAN-Link   |
|  + server      |   link -> app opens + connects     |  app scanner   |
+-------+--------+                                    +-------+--------+
        |            JPEG frames (WS, binary)                 |
        |  <--------------------------------------------------+
        |            JSON control (tap/swipe/pinch/key/text)  |
        +---------------------------------------------------->|
```

The PC runs the relay server (aiohttp). The phone connects as a WebSocket
client and pushes JPEG frames. Viewers receive the frames and send control
commands back through the Accessibility service (gestures) and the
LAN-Link IME (text). Protocol details: [docs/PROTOCOL.md](docs/PROTOCOL.md).

## Quick start

### 0. Requirements

- PC: Python 3.10+ and the core packages — `pip install -r requirements.txt`
  (for the optional OpenCV desktop window add
  `pip install -r requirements-desktop.txt`)
- Phone: Android 7.0+ (API 24), same WiFi as the PC
- For the automatic APK build: JDK 17+, Gradle and the Android SDK
  (Android Studio bundles all of them) — *or just let GitHub Actions
  build it for you, see below.*

### 1. Build & install the app (script option 1)

```bash
python lanlink.py
# choose 1
# accept the auto-detected IP (or type it)
# -> "THE APP IS READY: .../dist/LAN-Link.apk"
```

Move `dist/LAN-Link.apk` to the phone and install it (allow *Install
unknown apps* for your file manager when asked).

### 2. One-time setup on the phone

1. Open **LAN-Link**.
2. Grant the camera permission (for the camera features).
3. Enable **Settings > Accessibility > LAN-Link Control** -> ON
   *(required for remote tap/swipe/keys)*.
4. Enable **Settings > System > Languages & input > On-screen keyboard >
   LAN-Link Text** -> ON *(required for remote typing; switch the
   keyboard to it when you want to type)*.

### 3. Start streaming (script option 2)

```bash
python lanlink.py
# choose 2
# pick: 1) Screen share  2) Front camera  3) Back camera
# -> a QR code appears in the terminal (also saved as dist/qr-<feature>.png)
# -> scan it with the LAN-Link app (in-app scanner) or Google Lens
# -> the app connects and the stream starts
```

On the PC, pick how to watch:

- **Browser dashboard** — opened automatically. Tabs: Live (Screen /
  Front / Back / Stop + fullscreen), Remote control (Back / Home /
  Recents / Notifications, Zoom +-, text box with quick phrases),
  Get the app and Help.
- **Desktop window** — `python lanlink.py window -p 8080 -t <token> -f screen`
  (the exact command is printed by option 2).

### 4. Controls

| PC input                          | Phone action               |
|-----------------------------------|----------------------------|
| Left click on the stream          | Tap                        |
| Left drag                         | Swipe                      |
| `z` / `x` or Zoom +/- buttons     | Pinch zoom in / out        |
| `b` / `h` / `r` or the buttons    | Back / Home / Recents      |
| `t` then type (window) / text box | Type text via LAN-Link IME |
| `1` / `2` / `3` (window)          | Switch screen/front/back   |
| `q` / ESC                         | Close the window           |

## Running on Linux & Termux (Android)

The "PC" side is plain Python — any Linux machine **or another Android
phone running Termux** can act as the server/viewer:

**Linux**
```bash
pip install -r requirements.txt            # core: server + QR
pip install -r requirements-desktop.txt    # optional: OpenCV desktop window
python3 lanlink.py
```

**Termux (a second Android phone as the "PC")**
```bash
pkg update && pkg install python git
pip install -r requirements.txt      # aiohttp + qrcode only — Termux-safe
python lanlink.py                    # option 2 -> QR -> stream
python lanlink.py qr                 # option 3 -> encrypted QR offline
```

Notes for Termux:

- Skip `opencv-python` — open the **browser viewer** instead
  (`http://<termux-ip>:8080/?t=<code>` from any browser on any device).
- Get the Termux IP with `ifconfig wlan0` (or `pkg install net-tools`).
- Phone-to-phone works: phone A runs Termux + browser viewer, phone B
  runs the LAN-Link app. Same WiFi required.
- If pip fails to build a wheel: `pkg install build-essential` then retry.

## Building the APK — 4 ways

| Way | When | How |
|-----|------|-----|
| **Ready-made (v1.1.0+)** | Nothing installed at all | A built APK ships in `dist/LAN-Link.apk` — install it directly, or download it from the green button on any LAN-Link web page |
| Automatic (`python lanlink.py` -> 1) | JDK 17 + Gradle + Android SDK are installed | The script writes `assets/lanlink.properties` and runs `gradle :app:assembleDebug`, copying the APK to `dist/` |
| Android Studio | Daily development | Open the `android/` folder, *Build > Build APK(s)* |
| GitHub Actions | Nothing installed locally | Activate the build workflow once (below), then every push uploads the APK as an artifact |

### Enable the GitHub Actions APK builder (one-time, 30 seconds)

The workflow ships as a plain text file so you can add it from the
GitHub website without any special token:

1. Open `docs/android-build-workflow.yml.txt` in this repo and copy its content.
2. On GitHub: **Add file > Create new file** -> name it
   `.github/workflows/android-build.yml`
3. Paste the content, commit — done. The **Actions** tab will now build
   the APK on every push (download it from the run's *Artifacts*).

The APK is also attached automatically to a rolling
**LAN-Link APK (auto-build)** release, so it stays downloadable through a
permanent link:

```
https://github.com/mohaedgei/LAN-Link/releases/latest/download/LAN-Link.apk
```

Even better: the **green “⬇ Get the app” button** inside the LAN-Link web
viewer (and on the phone's connect page) serves that same APK — if an APK
was built locally it streams straight from disk, otherwise it redirects to
the release above. Anyone who opens the viewer link can install the app.

### Can't build anywhere? That's fine on Termux

Termux has no Android SDK, so `python lanlink.py -> 1` only saves your
connection defaults and prints guidance. Use the GitHub Actions path above
(or Android Studio on a PC) to get the actual `.apk` file.

## Security & responsible use

- **Use LAN-Link only on devices you own, or with the owner's explicit
  consent.** The app cannot be installed silently — every capture needs
  an explicit on-device permission (MediaProjection consent dialog,
  camera permission) and remote control needs Accessibility + IME to be
  enabled manually in system settings. That is by design.
- The session code gates every endpoint. It is created once and reused
  between runs so the app keeps working without re-pairing; rotate it
  anytime with `python lanlink.py start --new-token`.
- Traffic is plain HTTP/WS on your LAN — do not expose the port to the
  internet (no port forwarding).

## Troubleshooting

- **App can't connect** — PC firewall blocking Python/port 8080? Allow
  it, and confirm both devices are on the same WiFi (guest WiFi often
  isolates clients).
- **"screen-consent-needed"** — to stream the *screen*, start the screen
  share from the phone app (or a QR with `f=screen`) so the system
  consent dialog can appear. Camera feeds can be switched live from the
  viewer.
- **Remote tap not working** — enable *LAN-Link Control* in Accessibility
  settings.
- **Remote typing not working** — enable *LAN-Link Text* keyboard and
  switch to it on the phone.
- **Low fps** — both sides default to 720p JPEG @ ~10 fps; a strong 5GHz
  WiFi helps a lot.

## Roadmap

- [ ] H.264 hardware encoding (MediaCodec)
- [ ] Audio streaming
- [ ] File push/pull + clipboard sync
- [ ] WSS + PIN pairing
- [ ] Package the Python side as an importable `lanlink` library

## License

[MIT](LICENSE) — use it, fork it, build your own library on top of it.

<p align="center">Made with 💚 by <a href="https://t.me/Py_RHL">@Py_RHL</a></p>

---

<a id="العربية"></a>
## العربية

**LAN-Link** بيحوّل **موبايلك الأندرويد + الكمبيوتر بتاعك** لزوج بث وتحكم
شغّال على شبكتك المحلية: الموبايل يبعت شاشته أو الكاميرا الأمامية/الخلفية،
وانت تتفرج — وتتحكم في الموبايل بالكامل — من الكمبيوتر. كل حاجة جوه
الواي فاي بس: **بدون كلود، بدون حسابات، بدون إنترنت**.

سكريبت بايثون واحد بيتحكم في كل حاجة:

- **الخيار 1** — يجهز التطبيق على شبكتك ويبني الـ APK ويقولك مكانه بالظبط
  عشان تثبته عادي على الموبايل وتديله الصلاحيات.
- **الخيار 2** — يشغّل الميزة (مشاركة شاشة / كاميرا أمامية / كاميرا خلفية)
  ويطلعلك **QR كود مشفّر بالكامل (AES-256-GCM)**. امسحه بالتطبيق — الاتصال
  والبث بيبدأوا لوحدهم.
- **الخيار 3** — يطبع نفس الـ QR المشفّر **من غير تشغيل السيرفر**، أو
  اعمله أونلاين من [c4sf4qh0-d.space-z.ai](https://c4sf4qh0-d.space-z.ai)
  — الموقع الرسمي اللي فيه مولّد الـ QR + تحميل الـ APK + رلاي بث حي.

> **جديد في v2.0.0:** التطبيق بقى **ماسح QR نقي** — شاشة واحدة: اسكان
> (كاميرا أو صورة من الجاليري)، والـ QR جواه عنوان الكمبيوتر والكود
> مشفّرين بحيث **مفيش حد غير التطبيق يقدر يفكهم**. بعد الاسكان: 3 زرار
> بس — **شوف الشاشة / الكاميرا الأمامية / الكاميرا الخلفية**. مفيش
> كتابة IP ولا إعدادات ولا حاجة تانية خالص. الـ QR بصلاحية 24 ساعة.

### الخطوات باختصار

1. `pip install -r requirements.txt`
2. `python lanlink.py` → خيار **1** → يطلعلك APK في مجلد `dist/`
3. ثبّت الـ APK على الموبايل وافتحه وادّيله الصلاحيات:
   - إعدادات > **الوصولية (Accessibility)** > LAN-Link Control → ON
     *(للتحكم عن بعد: لمس/سحب/أزرار)*
   - إعدادات > **اللغة والإدخال** > لوحات المفاتيح > LAN-Link Text → ON
     *(لكتابة النصوص عن بعد)*
4. `python lanlink.py` → خيار **2** → اختار الميزة → امسح الـ QR
5. اتفرج من المتصفح أو نافذة سطح المكتب، وتحكم: كليك = لمسة، سحب = سوايب،
   `z/x` = زووم، `b/h/r` = رجوع/هوم/المتاحة، `t` = اكتب نص.

### تفعيل بيلد الـ APK أوتوماتيك من GitHub (مرة واحدة، 30 ثانية)

> **جديد في v1.1.0:** في **APK جاهز ومبني** جوه المشروع في
> `dist/LAN-Link.apk` — ثبّته على طول من غير أي أدوات، أو نزّله من زرار
> **"⬇ Get the app"** الأخضر في أي صفحة من صفحات LAN-Link. والتطبيق نفسه
> بيحفظ الـ IP والبورت والكود بعد أول اتصال — يعني مرة واحدة وبس.

1. افتح ملف `docs/android-build-workflow.yml.txt` في الريبو وانقل محتواه.
2. على GitHub: **Add file > Create new file** → سمّيه
   `.github/workflows/android-build.yml`
3. الصق المحتوى واعمل commit — خلاص. من تاب **Actions** هتلاقي الـ APK
   جاهز للتحميل مع كل رفع جديد (من Artifacts).

الـ APK كمان بيتحط أوتوماتيك في **Releases** بلينك دائم:

```
https://github.com/mohaedgei/LAN-Link/releases/latest/download/LAN-Link.apk
```

والأحلى: جوه صفحة المتصفح في تيرمكس في زرار أخضر **"⬇ Get the app"**
(وكمان على صفحة الاتصال في الموبايل) بينزّل نفس الـ APK — لو في نسخة
مبنية محلياً بيقدمها من الجهاز نفسه، ولو مفيش بيحوّلك على الريليز فوق.
يعني أي حد يفتح لينك العرض يقدر ينزّل التطبيق ويثبته على طول.

> ملحوظة: تيرمكس مفيهوش Android SDK، فالخيار 1 بيحفظ إعداداتك وبيرشّدك
> بس — ملف الـ `.apk` الفعلي هييجي من GitHub Actions أو Android Studio.

### استخدام مسؤول

استخدم الأداة **على أجهزتك أنت فقط أو بإذن صريح من صاحبها**. التطبيق
مصمم بحيث كل صلاحية تتطلب موافقة ظاهرة على شاشة الموبايل — مفيش تثبيت
خفي ولا تشغيل متخفي، وده عن قصد.

### على لينكس / تيرمكس (موبايل تاني يشتغل بدور "الكمبيوتر")

الجزء اللي على الكمبيوتر بايثون خالص — يشتغل على أي لينكس **أو حتى
موبايل أندرويد تاني عليه تطبيق تيرمكس**:

```bash
# لينكس:
pip install -r requirements.txt            # الأساسيات: السيرفر + QR
pip install -r requirements-desktop.txt    # اختياري: نافذة سطح المكتب (OpenCV)
python3 lanlink.py

# تيرمكس (موبايل تاني):
pkg update && pkg install python git
pip install -r requirements.txt      # aiohttp + qrcode فقط — آمنة على تيرمكس
python lanlink.py
```

- على تيرمكس سيب `opencv` وشيلك — افتح **عارض المتصفح** بدال النافذة:
  `http://<ip-تيرمكس>:8080/?t=<الكود>` من أي متصفح على أي جهاز.
- اطلع IP تيرمكس بأمر `ifconfig wlan0`.
- تقدر تعمل موبايل-لموبايل: موبايل A عليه تيرمكس + المتصفح، وموبايل B
  عليه تطبيق LAN-Link — بشرط نفس شبكة الواي فاي.

### الأمان

- كل الطلبات محمية بكود جلسة (6 حروف). الكود بيتحفظ وبيفضل ثابت بين
  التشغيلات عشان التطبيق ميحتاج إعادة اقتران — وبتقدر تغيّره في أي وقت
  بـ `python lanlink.py start --new-token`.
- حافظ على البورت داخل الشبكة المحلية — لا تعمل port forwarding.

### الصانع

مشروع LAN-Link من صنع **[@Py_RHL](https://t.me/Py_RHL)** 💚
