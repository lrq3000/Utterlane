"""Capture Android's IME state while a focused instrumentation test is running.

Useful when an emulator's settings-provider values disagree with the bound IME.
Evidence stays in ignored qa-artifacts; this runner does not change system settings.
"""
import argparse
from pathlib import Path
import subprocess
import time


class ImeEvidence:
    def __init__(self, serial, package):
        self.adb = ["adb", "-s", serial]
        self.package = package

    def run(self):
        target = Path("qa-artifacts")
        target.mkdir(exist_ok=True)
        command = self.adb + ["shell", "am", "instrument", "-w", "-e", "class",
            self.package + ".AudioIntegrationAndroidTest#voiceImeCommitsLiveTextAndDrainsOnDone",
            self.package + ".test/androidx.test.runner.AndroidJUnitRunner"]
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        try:
            time.sleep(18)
            for label, args in (("input-method", ["shell", "dumpsys", "input_method"]),
                                ("ime-logcat", ["logcat", "-d", "-s", "VoiceInputMethodService:I", "MicrophoneSession:I", "InputMethodManagerService:V"]),
                                ("ime-settings", ["shell", "settings", "get", "secure", "show_ime_with_hard_keyboard"])):
                output = subprocess.check_output(self.adb + args)
                (target / f"{label}.txt").write_bytes(output)
                text = output.decode("utf-8", errors="replace")
                if label == "input-method":
                    print("\n".join(line for line in text.splitlines() if any(key in line for key in
                        ("mCurMethodId=", "mCurId=", "mInputViewStarted=", "mInputShown=", "mShowImeWithHardKeyboard=", "targetWin=", "mInputView="))))
            stdout, _ = process.communicate(timeout=120)
            (target / "ime-test.txt").write_bytes(stdout)
            print(stdout.decode("utf-8", errors="replace"))
        finally:
            if process.poll() is None:
                process.terminate()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--package", default="io.github.lrq3000.utterlane")
    args = parser.parse_args()
    ImeEvidence(args.serial, args.package).run()
