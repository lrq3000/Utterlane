"""ADB UI evidence/actions using UI-tree bounds, never screenshot guesses.

Examples:
  python tools/qa/emulator_ui.py snapshot --name settings
  python tools/qa/emulator_ui.py tap --text Change
  python tools/qa/emulator_ui.py back
"""
import argparse
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET


class EmulatorUI:
    def __init__(self, serial):
        self.prefix = ["adb", "-s", serial]

    def run(self, *args):
        return subprocess.check_output(self.prefix + list(args))

    def tree(self):
        for attempt in range(3):
            text = self.run("exec-out", "uiautomator", "dump", "/dev/tty").decode("utf-8")
            if "<?xml" in text and "</hierarchy>" in text:
                xml = text[text.index("<?xml"):text.rindex("</hierarchy>") + len("</hierarchy>")]
                return xml, ET.fromstring(xml)
            time.sleep(0.25)
        raise RuntimeError(f"Android returned no active accessibility root: {text.strip()}")

    @staticmethod
    def bounds(node):
        return [int(x) for x in re.findall(r"\d+", node.get("bounds", ""))]

    def tap(self, label):
        for attempt in range(6):
            _, tree = self.tree()
            matches = [n for n in tree.iter("node") if n.get("text") == label or n.get("content-desc") == label]
            if matches:
                x1, y1, x2, y2 = self.bounds(matches[0])
                self.run("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                print(f"Tapped {label!r} from UI bounds {matches[0].get('bounds')}")
                return
            scroll = next((n for n in tree.iter("node") if n.get("scrollable") == "true"), None)
            if scroll is None or attempt == 5:
                raise RuntimeError(f"No visible UI element {label!r}")
            x1, y1, x2, y2 = self.bounds(scroll)
            self.run("shell", "input", "swipe", str((x1 + x2) // 2), str(y2 - 80), str((x1 + x2) // 2), str(y1 + 80), "400")

    def snapshot(self, name):
        directory = pathlib.Path("qa-artifacts")
        directory.mkdir(exist_ok=True)
        xml, tree = self.tree()
        (directory / f"{name}.xml").write_text(xml, encoding="utf-8")
        (directory / f"{name}.png").write_bytes(self.run("exec-out", "screencap", "-p"))
        for node in tree.iter("node"):
            if node.get("text"):
                print(node.get("text")[:300])
        print(f"Evidence: {directory / name}.xml/.png")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("tap", "snapshot", "back"))
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--text")
    parser.add_argument("--name", default="ui")
    args = parser.parse_args()
    ui = EmulatorUI(args.serial)
    if args.action == "tap":
        if not args.text:
            parser.error("tap requires --text")
        ui.tap(args.text)
    elif args.action == "snapshot":
        ui.snapshot(args.name)
    else:
        ui.run("shell", "input", "keyevent", "4")
