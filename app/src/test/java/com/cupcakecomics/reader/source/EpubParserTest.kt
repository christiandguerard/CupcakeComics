package com.cupcakecomics.reader.source

import com.nkanaev.comics.parsers.EpubParser
import com.nkanaev.comics.parsers.Parser
import com.nkanaev.comics.parsers.ParserFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class EpubParserTest {

    @Test
    fun `parser factory routes epub files to the epub parser`() {
        val file = writeTestEpub()
        val parser = ParserFactory.create(file)
        assertNotNull("ParserFactory must accept .epub files", parser)
        parser!!
        assertEquals("EPUB", parser.type)
        // Cover image + at least one text page.
        assertTrue(parser.numPages() >= 2)
        parser.destroy()
    }

    @Test
    fun `cover is page zero and text pages render`() {
        val file = writeTestEpub()
        val parser = openDirect(file)

        val pages = parser.numPages()
        assertTrue("expected cover + text pages, got $pages", pages >= 2)

        // Page 0 is the declared cover image — served straight from the zip.
        val coverBytes = parser.getPage(0).readBytes()
        assertTrue(coverBytes.contentEquals(TINY_PNG))

        // Text pages render to non-empty image streams with page metadata.
        val textMeta = parser.getPageMetaData(1)
        assertEquals("image/png", textMeta[Parser.PAGEMETADATA_KEY_MIME])
        val textBytes = parser.getPage(1).readBytes()
        assertTrue(textBytes.isNotEmpty())

        parser.destroy()
    }

    @Test
    fun `long chapters split into multiple pages`() {
        val file = writeTestEpub(paragraphs = 400)
        val parser = openDirect(file)
        // 400 long paragraphs cannot fit on one page.
        assertTrue(parser.numPages() > 3)
        parser.destroy()
    }

    @Test
    fun `non-epub files still route to their own parsers`() {
        val file = File.createTempFile("test", ".cbz")
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry("page1.png"))
            zip.write(TINY_PNG)
            zip.closeEntry()
        }
        val parser = ParserFactory.create(file)
        assertNotNull(parser)
        assertTrue(parser!!.type != "EPUB")
        parser.destroy()
        file.delete()
    }

    /** Direct parser access without the factory's JP2 wrapper (native lib, device-only). */
    private fun openDirect(file: File): EpubParser {
        val parser = object : EpubParser() {
            fun open(f: File) {
                setSource(f)
            }
        }
        parser.open(file)
        return parser
    }

    private fun writeTestEpub(paragraphs: Int = 120): File {
        val file = File.createTempFile("test", ".epub")
        val body = (1..paragraphs).joinToString("") {
            "<p>Chapter one paragraph $it. The quick brown fox jumps over the lazy dog, " +
                "again and again, under a bright September sky.</p>"
        }
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            put(zip, "META-INF/container.xml", """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
            """.trimIndent())
            put(zip, "OEBPS/content.opf", """
                <?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Test Book</dc:title>
                  </metadata>
                  <manifest>
                    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/>
                  </manifest>
                  <spine>
                    <itemref idref="ch1"/>
                  </spine>
                </package>
            """.trimIndent())
            put(zip, "OEBPS/ch1.xhtml", """
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>Chapter 1</title><style>p { color: red; }</style></head>
                  <body><h1>Chapter 1</h1>$body</body>
                </html>
            """.trimIndent())
            zip.putNextEntry(ZipEntry("OEBPS/cover.png"))
            zip.write(TINY_PNG)
            zip.closeEntry()
        }
        file.deleteOnExit()
        return file
    }

    private fun put(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    companion object {
        // 1x1 transparent PNG.
        private val TINY_PNG: ByteArray = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
        )
    }
}
