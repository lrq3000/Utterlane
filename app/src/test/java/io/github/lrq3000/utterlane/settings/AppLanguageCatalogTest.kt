package io.github.lrq3000.utterlane.settings

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AppLanguageCatalogTest {
    @Test fun offersExactlyThePackagedTranslationsAndEnglish() {
        val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        val translated = res.listFiles()!!.filter { it.name.startsWith("values-") && File(it, "strings.xml").isFile }
            .map { it.name.removePrefix("values-") }.toSet() + "en"
        assertEquals(translated, AppLanguageCatalog.tags.toSet())
        val config = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "xml/locales_config.xml"))
        val nodes = config.getElementsByTagName("locale")
        assertEquals(translated, (0 until nodes.length).map { nodes.item(it).attributes.getNamedItem("android:name").nodeValue }.toSet())
    }
    @Test fun systemIsEmptyAndInvalidStoredValuesFallBackToSystem() {
        assertEquals("", AppLanguageCatalog.normalize("system"))
        assertEquals("", AppLanguageCatalog.normalize("zz"))
        assertEquals("en", AppLanguageCatalog.normalize("en"))
        assertEquals("fr", AppLanguageCatalog.normalize("fr"))
        assertEquals("", AppLanguageCatalog.normalize(null))
    }
}
