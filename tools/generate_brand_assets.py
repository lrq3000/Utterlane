"""Regenerate Utterlane PNG assets from the archived, owner-supplied design.

Install tools/branding-requirements.txt, then run from any working directory.
Only derivative PNGs are written; the source design and Android XML stay intact.
The source is a raster design board, so enlarging exports does not add detail.
"""
import hashlib
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageOps


class BrandAssets:
    SOURCE_SHA256 = "411769b4143a1b7f35251d94a245081accd466604b3de2d524cd77b27cb48061"
    BANNER_BOX = (32, 320, 1222, 646)
    MARK_BOX = (500, 790, 750, 930)

    def __init__(self, root):
        self.root = root
        source = root / "docs/design/utterlane-logo-and-icon.png"
        if hashlib.sha256(source.read_bytes()).hexdigest() != self.SOURCE_SHA256:
            raise ValueError("Source design changed: review crop coordinates before regenerating")
        self.design = Image.open(source).convert("RGB")
        self.res = Path("app/src/main/res/drawable-nodpi")
        self.store = Path("fastlane/metadata/android/en-US/images")

    def save(self, image, path):
        destination = self.root / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        image.save(destination, optimize=True)
        print(f"Generated {path} ({image.width} x {image.height})")

    def mark(self):
        # The white symbol has a high red channel; the cyan/blue backdrop has
        # almost none. This matte retains antialiasing without tracing a new logo
        # or extracting the white rounded corners of the original icon tile.
        crop = self.design.crop(self.MARK_BOX)
        alpha = crop.getchannel("R").point(lambda r: max(0, min(255, round((r - 30) * 255 / 185))))
        result = Image.new("RGBA", crop.size, "white")
        result.putalpha(alpha)
        return result.crop(alpha.getbbox())

    def wordmarks(self):
        """Remove the white design-board matte without redrawing the lettering."""
        source = self.design.crop(self.BANNER_BOX)
        pixels = source.load()
        light = Image.new("RGBA", source.size)
        dark = Image.new("RGBA", source.size)
        light_pixels, dark_pixels = light.load(), dark.load()
        for y in range(source.height):
            for x in range(source.width):
                rgb = pixels[x, y]
                alpha = 255 - min(rgb)
                # Suppress near-white raster texture, while retaining antialiased
                # letter edges. Undo the white matte before applying transparency.
                if alpha < 14:
                    continue
                color = tuple(round((value - (255 - alpha)) * 255 / alpha) for value in rgb)
                light_pixels[x, y] = (*color, alpha)
                dark_pixels[x, y] = (244, 248, 255, alpha)
        self.save(light, self.res / "utterlane_wordmark.png")
        self.save(dark, self.res / "utterlane_wordmark_dark.png")

    @staticmethod
    def centered(mark, size, width):
        canvas = Image.new("RGBA", (size, size))
        height = round(mark.height * width / mark.width)
        resized = mark.resize((width, height), Image.Resampling.LANCZOS)
        canvas.alpha_composite(resized, ((size - width) // 2, (size - height) // 2))
        return canvas

    @staticmethod
    def background(size):
        # A full-bleed diagonal gradient lets the launcher own the outer mask.
        # Rendering at asset-generation time adds no runtime image processing.
        cyan, blue = (0, 201, 246), (0, 53, 201)
        image = Image.new("RGBA", (size, size))
        pixels = image.load()
        for y in range(size):
            for x in range(size):
                t = (x + y) / (2 * (size - 1))
                pixels[x, y] = tuple(round(a + (b - a) * t) for a, b in zip(cyan, blue)) + (255,)
        return image

    def generate(self):
        banner = self.design.crop(self.BANNER_BOX)
        self.save(banner, Path("assets/utterlane-banner.png"))
        self.wordmarks()
        mark = self.mark()

        # 56dp wide in a 108dp layer: the complete mark fits inside the centered
        # 66dp safe circle even when the launcher chooses a circular mask.
        foreground = self.centered(mark, 432, 224)
        self.save(foreground, self.res / "utterlane_foreground.png")
        self.save(self.background(432), self.res / "utterlane_background.png")
        self.save(self.centered(mark, 96, 88), self.res / "ic_utterlane_notification.png")

        icon = self.background(512)
        icon.alpha_composite(self.centered(mark, 512, 410))
        # Stores apply their own rounding; this export has no transparent corners.
        self.save(icon.convert("RGB"), self.store / "icon.png")
        rounded = Image.new("L", icon.size)
        ImageDraw.Draw(rounded).rounded_rectangle((0, 0, 511, 511), radius=100, fill=255)
        icon.putalpha(rounded)
        self.save(icon, Path("assets/utterlane-icon.png"))
        self.save(icon, self.res / "utterlane_icon.png")

        graphic = Image.new("RGB", (1024, 500), "white")
        wordmark = ImageOps.contain(banner, (944, 275), Image.Resampling.LANCZOS)
        graphic.paste(wordmark, ((1024 - wordmark.width) // 2, 40))
        small_icon = icon.resize((144, 144), Image.Resampling.LANCZOS)
        graphic.paste(small_icon, (440, 314), small_icon)
        self.save(graphic, self.store / "featureGraphic.png")

        # This review sheet previews the actual adaptive layers after the usual
        # 108dp -> 72dp viewport crop. It also exposes monochrome/tinted contrast.
        full = self.background(432)
        full.alpha_composite(foreground)
        view = full.crop((72, 72, 360, 360))
        mono = foreground.crop((72, 72, 360, 360))
        sheet = Image.new("RGB", (1008, 384), "#eef3fa")
        draw = ImageDraw.Draw(sheet)
        for index, (shape, label) in enumerate((("circle", "Circular launcher"), ("round", "Rounded-square launcher"), ("mono", "Themed launcher"))):
            mask = Image.new("L", view.size)
            painter = ImageDraw.Draw(mask)
            if shape == "round":
                painter.rounded_rectangle((0, 0, 287, 287), radius=62, fill=255)
            else:
                painter.ellipse((0, 0, 287, 287), fill=255)
            tile = view.copy()
            if shape == "mono":
                tile = Image.new("RGBA", view.size, "#052145")
                tint = Image.new("RGBA", view.size, "#b9e5ff")
                tint.putalpha(mono.getchannel("A"))
                tile.alpha_composite(tint)
            tile.putalpha(ImageChops.multiply(tile.getchannel("A"), mask))
            x = 24 + index * 336
            sheet.paste(tile, (x, 28), tile)
            draw.text((x + 25, 338), label, fill="#052145")
        self.save(sheet, Path("docs/design/utterlane-icon-preview.png"))


if __name__ == "__main__":
    BrandAssets(Path(__file__).resolve().parents[1]).generate()
