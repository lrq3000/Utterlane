"""Render three history-list concepts (audio + text) as editable SVG and PNG.

Uses the existing Blue Harmony/Pillow design tooling; no Android/runtime dependency.
Synthetic examples use only metadata already available to the app. The SVG groups
also provide real row/pin hit targets for the companion interactive review page.
"""
from pathlib import Path
from xml.sax.saxutils import escape
import argparse
import json

from generate_design_docs import Artwork, DesignKit


EXAMPLES = [
    dict(time="10:42", day="Today", duration="2 min 36 sec", short="02:36", pinned=False,
         text="We should confirm the delivery date before updating the schedule."),
    dict(time="10:18", day="Today", duration="8 min 14 sec", short="08:14", pinned=True,
         text="The next step is to test the revised workflow on a slower phone."),
    dict(time="18:05", day="Yesterday", duration="36 min 42 sec", short="36:42", pinned=True,
         text="Keep the audio only when needed. The transcript can stay on its own."),
    dict(time="09:10", day="5 October", duration="1 hr 12 min", short="1:12:00", pinned=True,
         text="These are the observations we want to keep for the next review."),
]


class HistoryArtwork(Artwork):
    def polygon(self, points, fill, stroke=None, width=1.8):
        encoded = " ".join(f"{x},{y}" for x, y in points)
        self.elements.append(f'<polygon points="{encoded}" fill="{fill or "none"}" '
                             f'stroke="{stroke or "none"}" stroke-width="{width}" stroke-linejoin="round"/>')
        scaled = [(round(x * self.SCALE), round(y * self.SCALE)) for x, y in points]
        if fill:
            self.draw.polygon(scaled, fill=fill)
        if stroke:
            self.draw.line(scaled + [scaled[0]], fill=stroke, width=round(width * self.SCALE), joint="curve")


class HistoryConcepts:
    WIDTH, HEIGHT = 440, 920
    CONCEPTS = {
        "a": ("Focused cards", "Clear separation and a strong reading hierarchy."),
        "b": ("Compact grouped list", "More entries at a glance, with quiet date grouping."),
        "c": ("Chronological timeline", "Time in the margin; recording details and words take the lead."),
    }

    def __init__(self, root, font_dir=None):
        self.root = root
        self.kit = DesignKit(root, font_dir)
        self.p = self.kit.light
        self.output = root / "docs/design/history-proposals"
        self.output.mkdir(parents=True, exist_ok=True)

    def width(self, text, size, bold=False):
        return self.kit.fonts.get(size * 2, bold).getlength(text) / 2

    def wrapped(self, art, text, x, baseline, width, size, color, lines=3, bold=False, spacing=None):
        pending = text.split()
        rendered = []
        while pending and len(rendered) < lines:
            row = pending.pop(0)
            while pending and self.width(row + " " + pending[0], size, bold) <= width:
                row += " " + pending.pop(0)
            if len(rendered) == lines - 1 and pending:
                while row and self.width(row + "…", size, bold) > width:
                    row = row[:-1]
                row += "…"
            rendered.append(row)
        for index, row in enumerate(rendered):
            art.text(x, baseline + index * (spacing or size + 6), row, size, color, bold)

    def pin(self, art, x, y, pinned, key):
        p = self.p
        art.elements.append(f'<g class="pin" data-pin="{key}" role="button" tabindex="0" '
                             f'aria-label="{"Unpin" if pinned else "Keep forever"}" aria-pressed="{str(pinned).lower()}">')
        art.elements.append('<g class="pin-background">')
        art.rect(x, y, 48, 48, p["container"] if pinned else p["surface"], 14)
        art.end_group()
        points = [(8, 3), (16, 3), (15, 5), (15, 10), (18, 13), (18, 15),
                  (13, 15), (13, 21), (12, 23), (11, 21), (11, 15), (6, 15), (6, 13), (9, 10), (9, 5)]
        points = [(x + 12 + px, y + 11 + py) for px, py in points]
        art.elements.append('<g class="pin-symbol">')
        art.polygon(points, p["primary"] if pinned else p["surface"], p["primary"] if pinned else p["muted"])
        art.end_group()
        art.elements.append(f'<rect class="pin-hit" x="{x}" y="{y}" width="48" height="48" fill="transparent"/>')
        art.end_group()

    def mic(self, art, x, y, color):
        art.rect(x + 8, y + 2, 8, 15, color, 4)
        art.line(x + 4, y + 12, x + 4, y + 16, color, 2)
        art.line(x + 4, y + 16, x + 8, y + 21, color, 2)
        art.line(x + 8, y + 21, x + 16, y + 21, color, 2)
        art.line(x + 16, y + 21, x + 20, y + 16, color, 2)
        art.line(x + 20, y + 16, x + 20, y + 12, color, 2)
        art.line(x + 12, y + 21, x + 12, y + 26, color, 2)
        art.line(x + 8, y + 26, x + 16, y + 26, color, 2)

    def retention(self, art, x, y, entry, kind, size=13):
        pinned = entry["pinned"]
        label = "Kept forever" if pinned else ("1 hour retention" if kind == "audio" else "24 hour retention")
        art.elements.append('<g class="retention-label">')
        art.text(x, y, label, size, self.p["primary"] if pinned else self.p["muted"], pinned)
        art.end_group()

    def row(self, art, concept, kind, entry, y, index, height):
        p = self.p
        key = f"{concept}-{kind}-{index}"
        name = f'{entry["day"]}, {entry["time"]}'
        art.elements.append(f'<g class="history-entry" data-entry="{key}" data-kind="{kind}" '
                             f'data-y="{y}" data-height="{height}" data-pinned="{str(entry["pinned"]).lower()}" '
                             f'data-label="{escape(name)}" role="link" tabindex="0" '
                             f'aria-label="Open {"recording" if kind == "audio" else "transcript"} from {escape(name)}">')
        # Includes the timeline gutter/whitespace, not just painted card pixels.
        # Pins are painted later and retain their own independent 48-unit target.
        art.elements.append(f'<rect class="row-hit" x="32" y="{y}" width="376" height="{height}" fill="transparent" pointer-events="all"/>')
        if concept == "a":
            art.rect(32, y + 3, 376, height, "#E3EAF5", 18, important=False)
            art.elements.append('<g class="row-surface">')
            art.rect(32, y, 376, height, p["surface"], 18, "#CBD8EB")
            art.end_group()
            if kind == "audio":
                art.rect(48, y + 20, 42, 42, p["container"], 13)
                self.mic(art, 57, y + 27, p["primary"])
                art.text(104, y + 38, entry["time"], 23, p["text"], True)
                art.text(104, y + 64, entry["duration"], 17, p["muted"])
                self.retention(art, 104, y + 106, entry, kind)
            else:
                art.text(50, y + 33, entry["time"], 17, p["text"], True)
                self.wrapped(art, entry["text"], 50, y + 78, 338, 19, p["text"], lines=3, spacing=24)
                self.retention(art, 50, y + 154, entry, kind)
                art.text(390, y + 154, "Ultra · Q8", 12, p["muted"], anchor="end")
            self.pin(art, 352, y + 10, entry["pinned"], key)
        elif concept == "b":
            art.elements.append('<g class="row-surface">')
            art.rect(32, y, 376, height, "#F5F8FD" if index % 2 == 0 else p["surface"], 8)
            art.end_group()
            art.line(48, y + height, 392, y + height, p["outlineVariant"])
            if kind == "audio":
                self.mic(art, 49, y + 25, p["primary"])
                art.text(88, y + 33, entry["time"], 21, p["text"], True)
                art.text(332, y + 33, entry["short"], 18, p["text"], anchor="end")
                self.retention(art, 88, y + 65, entry, kind)
            else:
                art.text(48, y + 26, entry["time"], 15, p["primary"], True)
                art.text(330, y + 26, "Ultra · Q8", 12, p["muted"], anchor="end")
                self.wrapped(art, entry["text"], 48, y + 55, 288, 17, p["text"], lines=2, spacing=23)
                self.retention(art, 48, y + 106, entry, kind, 12)
            self.pin(art, 352, y + 12, entry["pinned"], key)
        else:
            # The gutter carries time, leaving the content itself uncluttered.
            art.text(40, y + 28, entry["time"], 14, p["muted"], True)
            art.rect(87, y + 20, 8, 8, p["primary"], 4)
            art.line(91, y + 34, 91, y + height - 5, "#D4DFEE", 2)
            art.elements.append('<g class="row-surface">')
            art.rect(108, y, 300, height, "#F6F9FE", 16, "#CBD8EB")
            art.end_group()
            if kind == "audio":
                art.text(126, y + 47, entry["short"], 29, p["text"], True)
                art.text(126, y + 75, "Audio recording", 15, p["muted"])
                self.retention(art, 126, y + 113, entry, kind)
            else:
                art.text(126, y + 30, "Ultra · Q8", 13, p["muted"])
                self.wrapped(art, entry["text"], 126, y + 78, 264, 19, p["text"], lines=3, spacing=24)
                self.retention(art, 126, y + 158, entry, kind)
            self.pin(art, 352, y + 8, entry["pinned"], key)
        art.end_group()

    def screen(self, concept, kind):
        p = self.p
        art = HistoryArtwork(self.WIDTH, self.HEIGHT, f"{self.CONCEPTS[concept][0]} — {kind} history", self.kit.fonts)
        art.rect(0, 0, self.WIDTH, self.HEIGHT, p["background"], important=False)
        art.text(24, 28, "11:00", 14, p["text"], True)
        art.rect(391, 15, 23, 11, p["background"], 3, p["text"])
        art.rect(394, 18, 15, 5, p["text"], 1)
        self.kit.logo(art, 24, 47, 148)
        art.text(24, 100, "Settings", 15, p["muted"])
        count = 4 if concept == "b" else 3
        height = {"a": (132, 174), "b": (92, 122), "c": (136, 178)}[concept][kind == "text"]
        gap = 0 if concept == "b" else 12
        examples = [dict(item) for item in EXAMPLES[:count]]
        if kind == "text":
            examples[2]["pinned"] = False  # Yesterday still falls inside text's 24-hour policy.
        group_count = len({item["day"] for item in examples})
        panel_height = 106 + group_count * 36 + count * height + (count - group_count) * gap + 32
        art.rect(16, 118, 408, panel_height, "#DFE7F3", 26, important=False)
        art.elements.append('<g class="history-panel">')
        art.rect(16, 112, 408, panel_height, p["surface"], 26)
        art.end_group()
        art.text(40, 159, "Recordings" if kind == "audio" else "Transcripts", 29, p["text"], True)
        art.text(40, 184, "Microphone history" if kind == "audio" else "Text history", 15, p["muted"])
        art.elements.append('<g class="close-history" role="button" tabindex="0" aria-label="Close history">')
        art.rect(368, 124, 48, 48, p["surface"], 14)
        art.line(385, 141, 399, 155, p["muted"], 2)
        art.line(399, 141, 385, 155, p["muted"], 2)
        art.end_group()
        art.line(40, 203, 400, 203, p["outlineVariant"])
        y, index = 218, 0
        for day in dict.fromkeys(item["day"] for item in examples):
            group = [item for item in examples if item["day"] == day]
            span = 36 + len(group) * height + (len(group) - 1) * gap
            art.elements.append(f'<g class="day-group" data-y="{y}" data-span="{span}" data-gap="{gap}" data-height="{height}">')
            art.text(40, y + 15, day, 14, p["muted"], True)
            row_y = y + 28
            for item in group:
                self.row(art, concept, kind, item, row_y, index, height)
                row_y += height + gap
                index += 1
            art.end_group()
            y += span
        art.elements.append('<g class="history-hint">')
        art.text(40, y + 20, "Tap an entry to view it. Pin to keep it.", 12, p["muted"])
        art.end_group()
        art.rect(168, 897, 104, 4, p["text"], 2)
        art.save(self.output, f"{concept}-{kind}")
        return examples

    def board(self, concept):
        width, height = 976, 1080
        art = HistoryArtwork(width, height, self.CONCEPTS[concept][0], self.kit.fonts)
        art.rect(0, 0, width, height, "#F8FAFD", important=False)
        art.text(32, 46, f"{concept.upper()}  {self.CONCEPTS[concept][0]}", 29, self.p["text"], True)
        art.text(32, 76, self.CONCEPTS[concept][1], 17, self.p["muted"])
        art.asset(self.output / f"{concept}-audio.png", 32, 112, self.WIDTH, self.HEIGHT)
        art.asset(self.output / f"{concept}-text.png", 504, 112, self.WIDTH, self.HEIGHT)
        art.text(32, 1060, "Concept mockups · Synthetic examples · Whole entry opens; pin acts independently", 14, self.p["muted"])
        art.save(self.output, f"{concept}-comparison")

    def run(self):
        data = {}
        for concept in self.CONCEPTS:
            for kind in ("audio", "text"):
                data[f"{concept}-{kind}"] = self.screen(concept, kind)
            self.board(concept)
        (self.output / "examples.json").write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--font-dir")
    args = parser.parse_args()
    HistoryConcepts(Path(__file__).resolve().parents[1], args.font_dir).run()
