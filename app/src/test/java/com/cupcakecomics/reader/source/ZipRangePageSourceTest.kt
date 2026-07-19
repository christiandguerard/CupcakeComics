package com.cupcakecomics.reader.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipRangePageSourceTest {
    @Test
    fun parsesCentralDirectoryAndListsImages() {
        val zip = File.createTempFile("cupcake-test", ".cbz")
        try {
            ZipOutputStream(zip.outputStream()).use { zos ->
                fun put(name: String, data: ByteArray) {
                    val e = ZipEntry(name)
                    zos.putNextEntry(e)
                    zos.write(data)
                    zos.closeEntry()
                }
                put("ch1/001.jpg", jpegStub())
                put("ch1/002.png", pngStub())
                put("__MACOSX/._001.jpg", byteArrayOf(1, 2, 3))
                put("readme.txt", "hi".toByteArray())
            }
            LocalFileByteSource(zip).use { src ->
                val entries = ZipRangePageSource.parseCentralDirectory(src)
                assertTrue(entries.any { it.name.endsWith("001.jpg") })
                val pageSource = ZipRangePageSource(LocalFileByteSource(zip), "test.cbz")
                pageSource.open()
                assertEquals(2, pageSource.pageCount())
                pageSource.openPage(0).use { stream ->
                    assertTrue(stream.readBytes().isNotEmpty())
                }
                pageSource.close()
            }
        } finally {
            zip.delete()
        }
    }

    @Test
    fun isZipNameDetectsCbz() {
        assertTrue(ZipRangePageSource.isZipName("Book.CBZ"))
        assertTrue(ZipRangePageSource.isZipName("x.zip"))
        assertTrue(!ZipRangePageSource.isZipName("x.cbr"))
    }

    private fun jpegStub(): ByteArray = pngStub()

    private fun pngStub(): ByteArray {
        // 1x1 transparent PNG
        return byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(), 0x89.toByte(),
            0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54,
            0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00, 0x05, 0x00, 0x01,
            0x0D, 0x0A, 0x2D, 0xB4.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )
    }
}
