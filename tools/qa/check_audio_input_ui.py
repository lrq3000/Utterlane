"""Exercise audio-input settings on an isolated QA install with no external microphones.

Uses accessibility-tree bounds, including the switch associated with its label.
The supplied package is force-stopped to verify persistence across process restart.
"""
import argparse
import time

from emulator_ui import EmulatorUI


class AudioInputUiCheck:
    AUTO_LABEL = "Always prefer a Bluetooth microphone"

    def __init__(self, serial, package):
        if not package.startswith("io.github.lrq3000.utterlane."):
            raise ValueError("Use an isolated QA application suffix, not the regular app")
        self.ui = EmulatorUI(serial)
        self.package = package

    def launch(self):
        self.ui.run("shell", "am", "force-stop", self.package)
        self.ui.run("shell", "am", "start", "-n",
                    f"{self.package}/io.github.lrq3000.utterlane.settings.SettingsActivity")
        self.ui.tap("Audio input")

    def related(self, label, predicate):
        _, tree = self.ui.tree()
        parents = {child: parent for parent in tree.iter() for child in parent}
        node = next(node for node in tree.iter("node") if node.get("text") == label)
        while node is not None:
            matches = [candidate for candidate in node.iter("node") if predicate(candidate)]
            if len(matches) == 1:
                return matches[0]
            node = parents.get(node)
        raise AssertionError(f"No unique control associated with {label!r}")

    def switch(self):
        # Compose exposes switches as checkable Views on some Android versions.
        return self.related(self.AUTO_LABEL, lambda node: node.get("checkable") == "true")

    def click(self, node):
        x1, y1, x2, y2 = self.ui.bounds(node)
        self.ui.run("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def expect_auto(self, enabled):
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if (self.switch().get("checked") == "true") == enabled:
                return
            time.sleep(0.1)
        raise AssertionError(f"Automatic Bluetooth preference did not become {enabled}")

    def set_auto(self, enabled):
        control = self.switch()
        if (control.get("checked") == "true") != enabled:
            self.click(control)
        self.expect_auto(enabled)

    def run(self):
        self.launch()
        self.set_auto(False)
        self.ui.snapshot("bluetooth-settings-off")
        self.set_auto(True)
        self.launch()
        self.expect_auto(True)
        self.ui.snapshot("bluetooth-auto-persisted-without-headset")
        self.click(self.related("Audio input", lambda node: node.get("text") == "Change"))
        self.ui.snapshot("bluetooth-phone-picker")
        self.ui.tap("Phone microphone")
        self.expect_auto(False)
        self.ui.snapshot("bluetooth-manual-phone-disables-auto")
        print("PASS: automatic preference persists without a headset; manual Phone disables it")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", default="io.github.lrq3000.utterlane.bluetoothqa")
    args = parser.parse_args()
    AudioInputUiCheck(args.serial, args.package).run()
