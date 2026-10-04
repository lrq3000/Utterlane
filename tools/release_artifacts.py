"""Fail release preparation when an ARM64 library cannot load with 16 KB pages.

ELF load alignment and APK ZIP alignment are independent requirements. This tool
checks the former in APKs, AABs and AARs; use SDK zipalign for APK ZIP alignment.
Inspection is linear in the uncompressed native-library bytes.
"""
import argparse
import hashlib
import pathlib
import struct
import zipfile


def verify_digest(data, expected):
    actual = hashlib.sha256(data).hexdigest()
    if actual != expected:
        raise ValueError(f"SHA-256 mismatch: expected {expected}, got {actual}")


class ElfLibrary:
    def __init__(self, data):
        self.data = data

    def verify(self):
        data = self.data
        if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
            raise ValueError("Expected a complete little-endian ELF64 header")
        if struct.unpack_from("<H", data, 18)[0] != 183:
            raise ValueError("Expected an ARM64 library")
        offset = struct.unpack_from("<Q", data, 32)[0]
        size, count = struct.unpack_from("<HH", data, 54)
        if size < 56 or offset < 64 or offset + size * count > len(data):
            raise ValueError("Invalid ELF program header table")
        loads = 0
        for index in range(count):
            fields = struct.unpack_from("<IIQQQQQQ", data, offset + index * size)
            if fields[0] == 1:  # PT_LOAD, not section alignment or file padding
                loads += 1
                alignment = fields[7]
                if alignment < 16384 or alignment & (alignment - 1) or (fields[2] - fields[3]) % 16384:
                    raise ValueError("PT_LOAD does not support 16 KB memory pages")
        if not loads:
            raise ValueError("ELF has no loadable segments")


class NativeArchive:
    def __init__(self, path):
        self.path = pathlib.Path(path)

    def verify(self):
        with zipfile.ZipFile(self.path) as archive:
            libraries = [name for name in archive.namelist() if name.endswith(".so")]
            if not libraries:
                raise ValueError(f"No native libraries in {self.path}")
            for name in libraries:
                if "/arm64-v8a/" not in name:
                    raise ValueError(f"Unexpected ABI: {name}")
                try:
                    ElfLibrary(archive.read(name)).verify()
                except ValueError as error:
                    raise ValueError(f"{self.path.name}: {name}: {error}") from error
        print(f"{self.path.name}: {len(libraries)} ARM64 libraries, all 16 KB-aligned")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archives", nargs="+", type=pathlib.Path)
    for path in parser.parse_args().archives:
        NativeArchive(path).verify()
