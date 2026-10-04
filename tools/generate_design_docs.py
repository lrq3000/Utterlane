"""Export the Blue harmony design reference and GitHub card as SVG and PNG.

Uses the existing Pillow branding dependency. The same scene primitives generate
both formats; original logo pixels are embedded in otherwise editable SVG artwork.
Run from any directory: python tools/generate_design_docs.py
Use --font-dir with a directory containing Arial or Liberation Sans TTF files on
machines without the usual system font paths.
"""
import argparse
import base64
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape

from PIL import Image, ImageColor, ImageDraw, ImageFont


class ThemeTokens:
    """Read the implemented palette, rather than maintaining a second color list."""

    SOURCE = "app/src/main/java/io/github/lrq3000/utterlane/ui/theme/Color.kt"

    def __init__(self, root):
        source = (root / self.SOURCE).read_text(encoding="utf-8")
        self.themes = {}
        for name in ("Light", "Dark"):
            match = re.search(rf"val {name} = BrandPalette\((.*?)\n    \)", source, re.S)
            if not match:
                raise ValueError(f"Cannot read {name} palette; review the Kotlin format")
            block = match.group(1)
            palette = {
                key: "#" + value[-6:]
                for key, value in re.findall(r"(\w+)\s*=\s*Color\((0x[0-9A-F]+)\)", block)
            }
            palette.update({key: "#FFFFFF" for key in re.findall(r"(\w+)\s*=\s*Color.White", block)})
            for gradient in ("header", "waveform"):
                line = re.search(rf"{gradient} = listOf\((.*)\),", block).group(1)
                palette[gradient] = ["#" + value[-6:] for value in re.findall(r"0x[0-9A-F]+", line)]
                if len(palette[gradient]) != 3:
                    raise ValueError(f"Expected three {gradient} stops in {name}")
            self.themes[name.lower()] = palette


class Fonts:
    def __init__(self, directory=None):
        candidates = [Path(directory)] if directory else [
            Path("C:/Windows/Fonts"),
            Path("/usr/share/fonts/truetype/liberation2"),
            Path("/usr/share/fonts/truetype/liberation"),
            Path("/Library/Fonts"),
        ]
        for folder in candidates:
            for regular, bold, family in (
                ("arial.ttf", "arialbd.ttf", "Arial"),
                ("Arial.ttf", "Arial Bold.ttf", "Arial"),
                ("LiberationSans-Regular.ttf", "LiberationSans-Bold.ttf", "Liberation Sans"),
            ):
                if (folder / regular).is_file() and (folder / bold).is_file():
                    self.paths = {False: folder / regular, True: folder / bold}
                    self.family = family
                    self.cache = {}
                    return
        raise RuntimeError("Supply --font-dir with Arial or Liberation Sans regular/bold TTF files")

    def get(self, size, bold=False):
        key = (size, bold)
        if key not in self.cache:
            self.cache[key] = ImageFont.truetype(str(self.paths[bold]), size)
        return self.cache[key]


class Artwork:
    """A small shared scene renderer: SVG vectors and supersampled Pillow pixels."""

    SCALE = 2

    def __init__(self, width, height, title, fonts, safe_margin=0):
        self.width, self.height = width, height
        self.fonts, self.safe_margin = fonts, safe_margin
        self.image = Image.new("RGBA", (width * self.SCALE, height * self.SCALE), "white")
        self.draw = ImageDraw.Draw(self.image)
        self.definitions, self.elements = [], []
        self.title = title
        self.important_bounds = []

    def box(self, x, y, width, height, important=True):
        if important:
            self.important_bounds.append((x, y, x + width, y + height))
        return tuple(round(v * self.SCALE) for v in (x, y, x + width, y + height))

    def gradient(self, name, colors):
        self.definitions.append(f'<linearGradient id="{name}" x1="0" y1="0" x2="1" y2="0">' +
            "".join(f'<stop offset="{i / (len(colors) - 1):.3f}" stop-color="{color}"/>'
                    for i, color in enumerate(colors)) + '</linearGradient>')

    def rect(self, x, y, width, height, fill, radius=0, stroke=None, gradient=None, important=True):
        box = self.box(x, y, width, height, important)
        color = f"url(#{gradient[0]})" if gradient else fill
        outline = f' stroke="{stroke}" stroke-width="1"' if stroke else ""
        self.elements.append(f'<rect x="{x}" y="{y}" width="{width}" height="{height}" rx="{radius}" fill="{color}"{outline}/>')
        if gradient:
            name, colors = gradient
            self.gradient(name, colors)
            w, h = round(width * self.SCALE), round(height * self.SCALE)
            band = Image.new("RGBA", (w, h))
            painter = ImageDraw.Draw(band)
            rgb = [ImageColor.getrgb(c) for c in colors]
            # Rasterize each vertical column once. Complexity is O(width*height),
            # with no per-pixel Python loop or runtime work in the Android app.
            for column in range(w):
                t = column / max(1, w - 1) * (len(rgb) - 1)
                index = min(int(t), len(rgb) - 2)
                fraction = t - index
                value = tuple(round(a + (b - a) * fraction) for a, b in zip(rgb[index], rgb[index + 1]))
                painter.line((column, 0, column, h), fill=value)
            mask = Image.new("L", (w, h), 0)
            ImageDraw.Draw(mask).rounded_rectangle((0, 0, w - 1, h - 1), radius=radius * self.SCALE, fill=255)
            self.image.paste(band, box[:2], mask)
            if stroke:
                self.draw.rounded_rectangle(box, radius * self.SCALE, outline=stroke, width=self.SCALE)
        else:
            self.draw.rounded_rectangle(box, radius * self.SCALE, fill=fill, outline=stroke, width=self.SCALE)

    def line(self, x1, y1, x2, y2, color, width=1, important=True):
        padding = width / 2
        self.box(min(x1, x2) - padding, min(y1, y2) - padding,
                 abs(x2 - x1) + width, abs(y2 - y1) + width, important)
        self.elements.append(f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{color}" stroke-width="{width}" stroke-linecap="round"/>')
        if x1 == x2 or y1 == y2:
            # Draw each axial stroke as one capsule, avoiding the tiny bulges
            # caused by overlapping an integer-width line and fractional caps.
            bounds = (min(x1, x2) - padding, min(y1, y2) - padding,
                      max(x1, x2) + padding, max(y1, y2) + padding)
            self.draw.rounded_rectangle(tuple(round(v * self.SCALE) for v in bounds),
                                        radius=padding * self.SCALE, fill=color)
            return
        self.draw.line(tuple(round(v * self.SCALE) for v in (x1, y1, x2, y2)), fill=color, width=round(width * self.SCALE))
        for x, y in ((x1, y1), (x2, y2)):
            self.draw.ellipse(tuple(round(v * self.SCALE) for v in (x - padding, y - padding, x + padding, y + padding)), fill=color)

    def text(self, x, baseline, text, size, color, bold=False, anchor="start", important=True):
        font = self.fonts.get(round(size * self.SCALE), bold)
        advance = self.draw.textlength(text, font=font) / self.SCALE
        left = x - (advance / 2 if anchor == "middle" else advance if anchor == "end" else 0)
        bounds = self.draw.textbbox((left * self.SCALE, baseline * self.SCALE), text, font=font, anchor="ls")
        if important:
            self.important_bounds.append(tuple(v / self.SCALE for v in bounds))
        self.elements.append(f'<text x="{x}" y="{baseline}" font-family="{self.fonts.family}, sans-serif" font-size="{size}" font-weight="{700 if bold else 400}" text-anchor="{anchor}" fill="{color}">{escape(text)}</text>')
        self.draw.text((left * self.SCALE, baseline * self.SCALE), text, font=font, fill=color, anchor="ls")

    def asset(self, path, x, y, width, height=None):
        with Image.open(path) as source:
            source = source.convert("RGBA")
            height = height or width * source.height / source.width
            box = self.box(x, y, width, height)
            resized = source.resize((round(width * self.SCALE), round(height * self.SCALE)), Image.Resampling.LANCZOS)
            self.image.alpha_composite(resized, box[:2])
        data = base64.b64encode(path.read_bytes()).decode("ascii")
        self.elements.append(f'<image x="{x}" y="{y}" width="{width}" height="{height}" xlink:href="data:image/png;base64,{data}"/>')

    def group(self, name):
        self.elements.append(f'<g id="{name}">')

    def end_group(self):
        self.elements.append('</g>')

    def save(self, directory, name, github=False):
        for x1, y1, x2, y2 in self.important_bounds:
            margin = self.safe_margin
            if not (margin <= x1 <= x2 <= self.width - margin and margin <= y1 <= y2 <= self.height - margin):
                raise ValueError(f"{name}: content outside safe area: {(x1, y1, x2, y2)}")
        svg = ('<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" '
               f'width="{self.width}" height="{self.height}" viewBox="0 0 {self.width} {self.height}" role="img" aria-labelledby="title">\n'
               f'<title id="title">{escape(self.title)}</title>\n'
               '<desc>Blue harmony design. Original raster brand artwork is embedded; shapes and text are editable vectors. Illustrative UI, not an Android screenshot.</desc>\n'
               '<defs>' + '\n'.join(self.definitions) + '</defs>\n' + '\n'.join(self.elements) + '\n</svg>\n')
        (directory / f"{name}.svg").write_text(svg, encoding="utf-8")
        png = directory / f"{name}.png"
        self.image.resize((self.width, self.height), Image.Resampling.LANCZOS).convert("RGB").save(png, optimize=True)
        if github and png.stat().st_size >= 1_000_000:
            raise ValueError("GitHub social preview must be under 1 MB")
        print(f"{name}: SVG + PNG, {self.width} x {self.height}, PNG {png.stat().st_size:,} bytes; safe bounds passed")


class DesignKit:
    def __init__(self, root, font_dir=None):
        self.root = root
        self.directory = root / "docs/design"
        self.fonts = Fonts(font_dir)
        self.tokens = ThemeTokens(root)
        self.light, self.dark = self.tokens.themes.values()
        self.assets = root / "app/src/main/res/drawable-nodpi"

    def logo(self, art, x, y, width, dark=False):
        art.asset(self.assets / ("utterlane_wordmark_dark.png" if dark else "utterlane_wordmark.png"), x, y, width)

    def icon(self, art, x, y, width):
        art.asset(self.assets / "utterlane_icon.png", x, y, width, width)

    def waveform(self, art, x, y, width, height, color="#FFFFFF"):
        levels = [.12, .22, .43, .3, .6, .83, .48, .28, .73, .96, .56, .36,
                  .8, 1, .75, .42, .6, .87, .52, .34, .68, .5, .28, .15]
        gap = width / len(levels)
        for index, level in enumerate(levels):
            center = x + (index + .5) * gap
            art.line(center, y + height * (1 - level) / 2, center,
                     y + height * (1 + level) / 2, color, max(3, gap * .38))

    def check_icon(self, art, x, y, color):
        # Clean strokes intentionally have no badge or tile behind them.
        art.line(x, y + 10, x + 7, y + 17, color, 2)
        art.line(x + 7, y + 17, x + 22, y, color, 2)

    def reference_theme(self, art, x, palette, dark):
        art.group("dark-theme" if dark else "light-theme")
        art.rect(x, 228, 712, 834, palette["background"], 24, palette["outlineVariant"])
        art.text(x + 28, 272, "02 / DARK" if dark else "01 / LIGHT", 16, palette["muted"], True)
        roles = [("primary", "Action"), ("violet", "Violet"), ("background", "Canvas"),
                 ("surface", "Surface"), ("text", "Text"), ("container", "Tonal fill")]
        for index, (key, label) in enumerate(roles):
            left = x + 28 + index * 110
            art.rect(left, 300, 94, 62, palette[key], 12, palette["outlineVariant"])
            art.text(left, 388, label, 15, palette["text"], True)
            art.text(left, 410, palette[key], 13, palette["muted"])
        art.rect(x + 24, 446, 664, 158, palette["surface"], 20, palette["outlineVariant"])
        art.text(x + 48, 480, "Speech model", 14, palette["primary"], True)
        self.check_icon(art, x + 48, 510, palette["primary"])
        art.text(x + 86, 530, "Moondream Ultra · Q8", 22, palette["text"], True)
        art.text(x + 86, 560, "Downloaded · ready on device", 16, palette["muted"])
        art.rect(x + 486, 507, 176, 48, palette["primary"], 12)
        art.text(x + 574, 537, "Load model", 16, palette["onPrimary"], True, "middle")
        art.rect(x + 24, 628, 664, 358, palette["surface"], 26, palette["outlineVariant"])
        art.text(x + 356, 676, "Listening — speak now", 23, palette["text"], False, "middle")
        art.text(x + 356, 704, "Moondream Ultra · 00:12", 15, palette["muted"], False, "middle")
        art.rect(x + 48, 728, 616, 136, palette["violet"], 24,
                 gradient=("reference-wave-dark" if dark else "reference-wave-light", palette["waveform"]))
        self.waveform(art, x + 116, 750, 480, 52)
        art.text(x + 356, 841, "Tap waveform to finish recording", 16, "#FFFFFF", False, "middle")
        art.rect(x + 48, 888, 616, 48, palette["container"], 12)
        art.text(x + 356, 918, "Cancel", 16, palette["onContainer"], True, "middle")
        art.text(x + 48, 965, "Clean icons · solid reading surfaces · functional gradients", 15, palette["muted"])
        art.rect(x + 28, 1010, 656, 22, palette["container"], 11,
                 gradient=("reference-header-dark" if dark else "reference-header-light", palette["header"]))
        art.end_group()

    def reference(self):
        art = Artwork(1600, 1200, "Utterlane Blue harmony — light and dark design reference", self.fonts)
        art.rect(0, 0, 1600, 1200, self.light["background"], important=False)
        self.logo(art, 72, 58, 432)
        art.text(880, 101, "Blue harmony", 42, self.light["text"], True)
        art.text(882, 139, "Color, components & visual identity", 23, self.light["muted"])
        art.text(72, 200, "UTTERLANE  /  DESIGN REFERENCE  /  2026-10-04", 14, self.light["muted"], True)
        self.reference_theme(art, 72, self.light, False)
        self.reference_theme(art, 816, self.dark, True)
        art.text(72, 1111, "Rounded surfaces. Clear hierarchy. A blue-led identity with a violet-to-blue voice accent.", 23, self.light["text"])
        art.text(72, 1150, "Illustrative UI states · authoritative tokens: Color.kt · spacing and typography: blue-harmony-spec.md", 17, self.light["muted"])
        art.save(self.directory, "blue-harmony-reference")

    def phone(self, art, x, y):
        p = self.light
        art.group("recording-panel-illustration")
        art.rect(x - 8, y + 4, 350, 468, "#DDE5F1", 44)
        art.rect(x, y, 334, 468, p["text"], 40)
        art.rect(x + 8, y + 8, 318, 452, p["background"], 33)
        self.icon(art, x + 28, y + 28, 38)
        self.logo(art, x + 78, y + 27, 226)
        art.text(x + 28, y + 114, "Message", 18, p["text"], True)
        art.text(x + 28, y + 143, "Your next thought, in words.", 15, p["muted"])
        art.rect(x + 9, y + 166, 316, 283, p["surface"], 26)
        art.text(x + 167, y + 204, "Listening — speak now", 20, p["text"], False, "middle")
        art.text(x + 167, y + 229, "Moondream Ultra · 00:12", 12, p["muted"], False, "middle")
        art.rect(x + 28, y + 248, 278, 118, p["violet"], 24, gradient=("card-wave", p["waveform"]))
        self.waveform(art, x + 49, y + 270, 236, 46)
        art.text(x + 167, y + 346, "Tap to finish", 14, "#FFFFFF", False, "middle")
        art.text(x + 31, y + 391, "Let’s meet tomorrow morning.", 14, p["text"])
        art.rect(x + 28, y + 406, 278, 36, p["container"], 12)
        art.text(x + 167, y + 429, "Cancel", 14, p["onContainer"], True, "middle")
        art.rect(x + 135, y + 453, 64, 4, p["text"], 2)
        art.end_group()

    def repo_card(self):
        art = Artwork(1280, 640, "Utterlane — Near real-time transcription. On your Android phone.", self.fonts, safe_margin=80)
        art.group("background")
        art.rect(0, 0, 1280, 640, self.light["background"],
                 gradient=("card-background", self.light["header"]), important=False)
        art.end_group()
        art.group("identity-and-message")
        self.logo(art, 96, 110, 622)
        # Preserve the supplied slogan's explicit two-line composition; 28 px
        # keeps the first sentence clear of the app illustration without wrapping.
        art.text(100, 345, "Near real-time transcription. On your Android phone.", 28, self.light["text"], True)
        art.text(100, 403, "100% offline. 100% private. 100% free.", 28, self.light["text"], True)
        art.text(100, 538, "github.com/lrq3000/Utterlane", 22, self.light["onContainer"])
        art.end_group()
        self.phone(art, 856, 84)
        art.save(self.directory, "utterlane-repo-card", github=True)
        # The guide is deliberately a separate deliverable, never the upload file.
        art.group("safe-area-guides")
        for x1, y1, x2, y2 in ((80, 0, 80, 640), (1200, 0, 1200, 640),
                              (0, 80, 1280, 80), (0, 560, 1280, 560)):
            art.line(x1, y1, x2, y2, "#B3261E", 2, important=False)
        art.text(96, 43, "SAFE-AREA PROOF · 80 PX INSET · DO NOT UPLOAD THIS VERSION", 15, "#B3261E", True, important=False)
        art.end_group()
        art.save(self.directory, "utterlane-repo-card-safe-area")

    def generate(self):
        self.directory.mkdir(parents=True, exist_ok=True)
        self.reference()
        self.repo_card()
        tokens = {"source": ThemeTokens.SOURCE, "gradientDirection": "left-to-right",
                  "gradientStops": [0, 0.5, 1], "themes": self.tokens.themes}
        (self.directory / "blue-harmony-tokens.json").write_text(json.dumps(tokens, indent=2) + "\n", encoding="utf-8")
        self.validate()

    def validate(self):
        for name, dimensions in (("blue-harmony-reference", (1600, 1200)),
                                 ("utterlane-repo-card", (1280, 640)),
                                 ("utterlane-repo-card-safe-area", (1280, 640))):
            svg = ET.parse(self.directory / f"{name}.svg").getroot()
            assert (int(svg.attrib["width"]), int(svg.attrib["height"])) == dimensions
            ids = [node.attrib["id"] for node in svg.iter() if "id" in node.attrib]
            assert len(ids) == len(set(ids)), f"Duplicate SVG IDs in {name}"
            for node in svg.iter("{http://www.w3.org/2000/svg}image"):
                uri = node.attrib["{http://www.w3.org/1999/xlink}href"]
                assert uri.startswith("data:image/png;base64,"), "SVG assets must be self-contained"
                base64.b64decode(uri.split(",", 1)[1], validate=True)
            with Image.open(self.directory / f"{name}.png") as image:
                assert image.size == dimensions and image.mode == "RGB"
                image.verify()
        for document in self.directory.glob("*.md"):
            for link in re.findall(r"\[[^\]]*\]\(([^)]+)\)", document.read_text(encoding="utf-8")):
                if ":" not in link and not link.startswith("#"):
                    assert (document.parent / link.split("#", 1)[0]).exists(), f"Broken link in {document.name}: {link}"
        print("Validated SVG structure, embedded artwork, PNG integrity/dimensions, and design-document links")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--font-dir", type=Path)
    args = parser.parse_args()
    DesignKit(Path(__file__).resolve().parents[1], args.font_dir).generate()
