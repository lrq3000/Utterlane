package io.github.lrq3000.utterlane.onboarding

import org.junit.Assert.*
import org.junit.Test

class LocalAudioFolderTest {
    @Test fun mapsPrimaryAndRemovableLocalStorageWithoutLosingSpaces() {
        assertEquals("/storage/emulated/0/Download/Voice notes",
            LocalAudioFolder.resolve("primary:Download/Voice notes", "/storage/emulated/0"))
        assertEquals("/storage/1234-ABCD/Recordings",
            LocalAudioFolder.resolve("1234-ABCD:Recordings", "/storage/emulated/0"))
    }

    @Test fun rejectsTraversalAbsolutePathsAndProviderIds() {
        for (id in listOf("primary:../private", "primary:Download/../../private", "primary:/etc",
            "primary:Download\\..\\private", "raw:/data/data/private", "cloud:folder", "primary:a\u0000b")) {
            assertNull(id, LocalAudioFolder.resolve(id, "/storage/emulated/0"))
        }
    }

    @Test fun localDownloadsProviderTreesResolveWithinTheDownloadsRoot() {
        val root = "/storage/emulated/0/Download"
        assertEquals("$root/Voice notes", LocalAudioFolder.resolveDownloads("raw:$root/Voice notes", root))
        assertEquals(root, LocalAudioFolder.resolveDownloads("raw:$root", root))
        assertNull(LocalAudioFolder.resolveDownloads("raw:/data/data/private", root))
        assertNull(LocalAudioFolder.resolveDownloads("raw:$root/../private", root))
        assertNull(LocalAudioFolder.resolveDownloads("raw:${root}Other/Voice", root))
        assertNull(LocalAudioFolder.resolveDownloads("msd:123", root))
    }
}
