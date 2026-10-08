"""QL1 payload encryption — the QR language spoken by the LAN-Link app.

One encrypted QR carries {v, cmd, host, port, t, exp} and only the
LAN-Link app can decode it (the AES-256 secret is baked into the app).

Three producers, one format:
  - this script        (option 2 while the server runs + option 3 offline)
  - the website        https://c4sf4qh0-d.space-z.ai  (/api/qr/encrypt)
  - the app            (decrypt only)

Wire format:  QL1. + base64url( nonce[12] || ciphertext || tag[16] )
Cipher:       AES-256-GCM, key = SHA-256(secret), 128-bit auth tag.

AES-GCM needs one optional library: `cryptography` OR `pycryptodome`
(whichever is installed). Without it the script falls back to telling
you to generate the QR on the website.
"""

import base64
import hashlib
import json
import os
import time

PREFIX = "QL1."
# Must match QLINK_SECRET on the website and the constant in Ql1.kt (app).
SECRET = "qlink-secret-v1-do-not-share"
TTL_SECONDS = 24 * 60 * 60

# v3.0 plain marker format (readable by humans, recognised by the app):
#   LL1|<ip>|<port>|<token>|<feature>[|<relay-site>]
# e.g.  LL1|192.168.1.100|8080|ab12cd|screen
PLAIN_PREFIX = "LL1|"
PLAIN_FEATURES = ("screen", "front", "back", "any")

NONCE_LEN = 12
TAG_LEN = 16


def _key() -> bytes:
    return hashlib.sha256(SECRET.encode("utf-8")).digest()


def _pad_b64(raw: bytes) -> str:
    """base64url without padding — identical to Node's base64url and to
    android.util.Base64.URL_SAFE | NO_PADDING."""
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def _unpad_b64(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def build_plain(cmd: str, host: str, port: int, token: str,
                via: str = "direct", site: str | None = None) -> str:
    """Build the v3.0 plain LAN-Link QR payload.

    The app recognises it by the small ``LL1|`` marker and reads the
    fields directly — no encryption, no extra library, and anyone who
    scans it with Lens sees exactly what it does: where it connects and
    which feature it opens.
    """
    fields = ["LL1", str(host).strip(), str(int(port)), str(token).strip(), cmd]
    if via == "relay" and site:
        fields.append(site.rstrip("/"))
    return "|".join(fields)


def parse_plain(payload: str) -> dict | None:
    """Parse an LL1 payload (self-check / tests). None when invalid."""
    if not payload.startswith(PLAIN_PREFIX):
        return None
    parts = payload[len("LL1"):].split("|")
    if len(parts) < 5:
        return None
    _, host, port, token, feature = parts[:5]
    if not host or not port.isdigit() or feature not in PLAIN_FEATURES:
        return None
    out = {"host": host, "port": int(port), "t": token, "cmd": feature}
    if len(parts) > 5 and parts[5]:
        out["site"] = parts[5]
        out["via"] = "relay"
    else:
        out["via"] = "direct"
    return out


def crypto_available() -> bool:
    """True when `cryptography` or `pycryptodome` can be imported."""
    try:
        import cryptography.hazmat.primitives.ciphers.aead  # noqa: F401
        return True
    except ImportError:
        pass
    try:
        import Crypto  # noqa: F401  (pycryptodome)
        return True
    except ImportError:
        return False


def encrypt_ql1(cmd: str, host: str, port: int, token: str,
                ttl: int = TTL_SECONDS, via: str = "direct",
                site: str | None = None) -> str | None:
    """Build the encrypted QL1 payload, or None when no AES library exists.

    via="direct" -> the app opens ws://host:port straight away (LAN).
    via="relay"  -> the app streams through the website relay instead
                    (used when the PC advertises a public IP).
    """
    if not crypto_available():
        return None
    data = {"v": 1, "cmd": cmd, "host": host, "port": int(port),
            "t": token, "exp": int(time.time()) + ttl, "via": via}
    if site:
        data["site"] = site
    payload = json.dumps(
        data,
        separators=(",", ":"),
    ).encode("utf-8")
    nonce = os.urandom(NONCE_LEN)
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        blob = AESGCM(_key()).encrypt(nonce, payload, None)  # ct||tag
    except ImportError:
        from Crypto.Cipher import AES  # pycryptodome
        cipher = AES.new(_key(), AES.MODE_GCM, nonce=nonce)
        ct, tag = cipher.encrypt_and_digest(payload)
        blob = ct + tag
    return PREFIX + _pad_b64(nonce + blob)


def decrypt_ql1(payload: str) -> dict | None:
    """Decrypt a QL1 payload (test helper / self-check). None if invalid."""
    try:
        raw = _unpad_b64(payload[len(PREFIX):])
        nonce, body = raw[:NONCE_LEN], raw[NONCE_LEN:]
        try:
            from cryptography.hazmat.primitives.ciphers.aead import AESGCM
            plain = AESGCM(_key()).decrypt(nonce, body, None)
        except ImportError:
            from Crypto.Cipher import AES
            ct, tag = body[:-TAG_LEN], body[-TAG_LEN:]
            cipher = AES.new(_key(), AES.MODE_GCM, nonce=nonce)
            plain = cipher.decrypt_and_verify(ct, tag)
        return json.loads(plain.decode("utf-8"))
    except Exception:
        return None
