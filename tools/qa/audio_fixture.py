"""Build a bounded-memory, repeatable spoken WAV for adb long-audio QA.

Usage: python tools/qa/audio_fixture.py --seconds 1440 --rate 48000 --channels 2
The small source is the sherpa-onnx Parakeet v3 example (JFK's spoken sentence).
Generated audio is a run artifact, not a source-code fixture.
"""
import argparse
import array
import pathlib
import sys
import urllib.request
import wave


class AudioFixture:
    URL = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/test_wavs/en.wav"

    def create(self, output: pathlib.Path, seconds: int, rate: int, channels: int):
        output.parent.mkdir(parents=True, exist_ok=True)
        source = output.parent / "speech-source.wav"
        if not source.exists():
            urllib.request.urlretrieve(self.URL, source)
        with wave.open(str(source), "rb") as reader:
            if reader.getsampwidth() != 2 or reader.getnchannels() != 1:
                raise ValueError("Expected a PCM16 mono source")
            pcm = array.array("h", reader.readframes(reader.getnframes()))
            source_rate = reader.getframerate()
        if sys.byteorder != "little":
            pcm.byteswap()
        converted = array.array("h")
        for i in range(int(len(pcm) * rate / source_rate)):
            p = i * source_rate / rate
            j = int(p)
            sample = round(pcm[j] + (pcm[min(j + 1, len(pcm) - 1)] - pcm[j]) * (p - j))
            converted.extend([sample] * channels)
        if sys.byteorder != "little":
            converted.byteswap()
        block = converted.tobytes()
        remaining = seconds * rate * channels * 2
        with wave.open(str(output), "wb") as writer:
            writer.setnchannels(channels)
            writer.setsampwidth(2)
            writer.setframerate(rate)
            while remaining:
                n = min(remaining, len(block))
                writer.writeframesraw(block[:n])
                remaining -= n
        print(f"Created {output}: {seconds}s, {rate}Hz, {channels}ch, {output.stat().st_size} bytes")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seconds", type=int, default=1440)
    parser.add_argument("--rate", type=int, default=48000)
    parser.add_argument("--channels", type=int, choices=(1, 2), default=2)
    parser.add_argument("--output", type=pathlib.Path, default=pathlib.Path("qa-artifacts/long.wav"))
    args = parser.parse_args()
    if args.seconds <= 0 or args.rate <= 0:
        parser.error("duration and rate must be positive")
    AudioFixture().create(args.output, args.seconds, args.rate, args.channels)
