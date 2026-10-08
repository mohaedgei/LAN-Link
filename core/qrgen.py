"""QR code generation: terminal ASCII + PNG file."""

import qrcode


def make(url: str, feature: str, dist_dir) -> str:
    """Print the QR in the terminal and save a PNG next to the server.

    Returns the absolute path of the saved PNG file.
    """
    qr = qrcode.QRCode(border=1, box_size=2)
    qr.add_data(url)
    qr.make(fit=True)

    print()
    print("  Scan this QR with the LAN-Link app or Google Lens:")
    qr.print_ascii(invert=True)

    dist_dir.mkdir(parents=True, exist_ok=True)
    png_path = dist_dir / f"qr-{feature}.png"
    try:
        img = qrcode.make(url, border=2, box_size=8)
        img.save(png_path)
        print(f"  QR image saved to: {png_path}")
        return str(png_path)
    except Exception:
        # Pillow/pypng may be missing on minimal setups (e.g. Termux);
        # the terminal QR above already works without any image file.
        print("  (PNG file skipped - the terminal QR above is enough)")
        return None
