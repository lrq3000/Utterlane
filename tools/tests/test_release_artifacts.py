"""Exercise release gates with deliberately invalid native artifacts."""
import hashlib
import struct
import unittest

from tools.release_artifacts import ElfLibrary, verify_digest


class ReleaseArtifactsTest(unittest.TestCase):
    def elf(self, alignment=16384, offset=0, address=0):
        data = bytearray(120)
        data[:7] = b"\x7fELF\x02\x01\x01"
        struct.pack_into("<H", data, 18, 183)  # ELF machine: AArch64
        struct.pack_into("<Q", data, 32, 64)
        struct.pack_into("<HH", data, 54, 56, 1)
        struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, offset, address, 0, 0, 0, alignment)
        return bytes(data)

    def test_accepts_16k_and_64k_load_alignment(self):
        for alignment in (16384, 65536):
            ElfLibrary(self.elf(alignment)).verify()

    def test_rejects_4k_alignment(self):
        with self.assertRaisesRegex(ValueError, "16 KB"):
            ElfLibrary(self.elf(4096)).verify()

    def test_rejects_incongruent_virtual_address(self):
        with self.assertRaisesRegex(ValueError, "16 KB"):
            ElfLibrary(self.elf(address=4096)).verify()

    def test_rejects_non_arm64_and_truncated_elf(self):
        for data in (b"not an ELF", self.elf()[:100], self.elf()[:18] + b"\x3e\x00" + self.elf()[20:]):
            with self.assertRaises(ValueError):
                ElfLibrary(data).verify()

    def test_rejects_empty_program_table(self):
        data = bytearray(self.elf())
        struct.pack_into("<H", data, 56, 0)
        with self.assertRaises(ValueError):
            ElfLibrary(data).verify()

    def test_dependency_digest_is_checked(self):
        digest = hashlib.sha256(b"expected").hexdigest()
        verify_digest(b"expected", digest)
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            verify_digest(b"tampered", digest)


if __name__ == "__main__":
    unittest.main()
