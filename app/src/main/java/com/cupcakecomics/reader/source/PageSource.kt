package com.cupcakecomics.reader.source

import com.cupcakecomics.reader.model.PageDescriptor
import com.cupcakecomics.reader.model.TocEntry
import java.io.Closeable
import java.io.InputStream

/**
 * Abstract page provider for local parsers and remote byte-range archives.
 */
interface PageSource : Closeable {
    val title: String
    val type: String
    val remoteStreaming: Boolean get() = false

    @Throws(Exception::class)
    fun open()

    @Throws(Exception::class)
    fun pageCount(): Int

    @Throws(Exception::class)
    fun descriptor(index: Int): PageDescriptor

    @Throws(Exception::class)
    fun openPage(index: Int): InputStream

    /** Prefetch metadata for a range of pages without decoding pixels. */
    @Throws(Exception::class)
    fun warmDescriptors(fromInclusive: Int, toExclusive: Int) {
        val n = pageCount()
        val start = fromInclusive.coerceIn(0, n)
        val end = toExclusive.coerceIn(start, n)
        for (i in start until end) {
            descriptor(i)
        }
    }

    fun tableOfContents(): List<TocEntry> = emptyList()

    override fun close()
}
