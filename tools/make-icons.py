#!/usr/bin/env python3
"""Generate Android launcher mipmaps from the project-owned icon source."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app" / "assets" / "app-icon-source.png"
RES = ROOT / "app" / "res"
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
LEGACY = 48
FOREGROUND = 108


def load():
    if not SRC.is_file():
        raise SystemExit(f"missing icon source: {SRC}")
    return Image.open(SRC).convert("RGBA")


def fit(img, size):
    return img.resize((size, size), Image.LANCZOS)


def main():
    src = load()
    for name, scale in DENSITIES.items():
        d = RES / f"mipmap-{name}"
        d.mkdir(parents=True, exist_ok=True)
        legacy = fit(src, int(round(LEGACY * scale)))
        legacy.save(d / "ic_launcher.png", optimize=True)
        legacy.save(d / "ic_launcher_round.png", optimize=True)
        # Adaptive foreground: content inside the 72/108 safe zone.
        fg_size = int(round(FOREGROUND * scale))
        canvas = Image.new("RGBA", (fg_size, fg_size), (0, 0, 0, 0))
        safe = int(round(fg_size * 72 / 108))
        art = fit(src, safe)
        canvas.alpha_composite(art, ((fg_size - safe) // 2, (fg_size - safe) // 2))
        canvas.save(d / "ic_launcher_foreground.png", optimize=True)

    anydpi = RES / "mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)
    adaptive = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""
    (anydpi / "ic_launcher.xml").write_text(adaptive, encoding="utf-8")
    (anydpi / "ic_launcher_round.xml").write_text(adaptive, encoding="utf-8")

    # StepDemo shares the artwork but carries an unmistakable TEST badge.
    demo = fit(src, 192)
    draw = ImageDraw.Draw(demo)
    font = None
    for candidate in ("arialbd.ttf", "Arial Bold.ttf", "DejaVuSans-Bold.ttf"):
        try:
            font = ImageFont.truetype(candidate, 30)
            break
        except OSError:
            pass
    if font is None:
        font = ImageFont.load_default()
    label = "TEST"
    box = draw.textbbox((0, 0), label, font=font)
    tw, th = box[2] - box[0], box[3] - box[1]
    x0, y0 = 192 - tw - 24, 192 - th - 22
    draw.rounded_rectangle((x0 - 8, y0 - 5, x0 + tw + 8, y0 + th + 5),
                           radius=7, fill=(255, 126, 0, 255))
    draw.text((x0, y0 - box[1]), label, font=font, fill=(9, 13, 41, 255))
    demo_dir = ROOT / "tools" / "stepdemo" / "res" / "drawable-nodpi"
    demo_dir.mkdir(parents=True, exist_ok=True)
    demo.save(demo_dir / "stepdemo_icon.png", optimize=True)
    print(f"icon source: {SRC}")
    print(f"generated: {RES} mipmaps, {demo_dir / 'stepdemo_icon.png'}")


if __name__ == "__main__":
    main()
