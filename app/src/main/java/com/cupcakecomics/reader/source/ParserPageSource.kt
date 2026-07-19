package com.cupcakecomics.reader.source

import android.net.Uri
import com.cupcakecomics.reader.model.PageDescriptor
import com.cupcakecomics.reader.model.TocEntry
import com.nkanaev.comics.parsers.Parser
import com.nkanaev.comics.parsers.ParserFactory
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Adapts Bubble2 [Parser] instances to the Cupcake [PageSource] contract.
 */
class ParserPageSource(
    private val parser: Parser,
    override val title: String,
) : PageSource {
    private val descriptorCache = ConcurrentHashMap<Int, PageDescriptor>()
    private val parserLock = Any()
    @Volatile private var opened = false

    override val type: String
        get() = parser.type

    @Throws(Exception::class)
    override fun open() {
        if (opened) return
        synchronized(parserLock) {
            if (opened) return
            parser.parse()
            opened = true
        }
    }

    @Throws(Exception::class)
    override fun pageCount(): Int {
        open()
        synchronized(parserLock) {
            return parser.numPages()
        }
    }

    @Throws(Exception::class)
    override fun descriptor(index: Int): PageDescriptor {
        open()
        descriptorCache[index]?.let { return it }
        @Suppress("UNCHECKED_CAST")
        val meta = synchronized(parserLock) {
            parser.getPageMetaData(index) as? Map<String, Any?> ?: emptyMap()
        }
        val name = meta[Parser.PAGEMETADATA_KEY_NAME]?.toString().orEmpty()
        val folder = name.substringBeforeLast('/', missingDelimiterValue = "")
        val width = meta[Parser.PAGEMETADATA_KEY_WIDTH]?.toString()?.toIntOrNull() ?: 0
        val height = meta[Parser.PAGEMETADATA_KEY_HEIGHT]?.toString()?.toIntOrNull() ?: 0
        val size = meta[Parser.PAGEMETADATA_KEY_SIZE]?.toString()?.toLongOrNull() ?: 0L
        val mime = meta[Parser.PAGEMETADATA_KEY_MIME]?.toString()
        val desc = PageDescriptor(
            index = index,
            name = name.substringAfterLast('/'),
            mime = mime,
            width = width,
            height = height,
            sizeBytes = size,
            folder = folder,
        )
        descriptorCache[index] = desc
        return desc
    }

    @Throws(Exception::class)
    override fun openPage(index: Int): InputStream {
        open()
        return synchronized(parserLock) {
            parser.getPage(index)
                ?: throw IllegalStateException("Page $index unavailable")
        }
    }

    override fun tableOfContents(): List<TocEntry> {
        val count = runCatching { pageCount() }.getOrDefault(0)
        if (count <= 0) return emptyList()
        val folders = LinkedHashMap<String, Int>()
        for (i in 0 until count) {
            val folder = runCatching { descriptor(i).folder }.getOrDefault("")
            if (folder.isNotBlank()) {
                folders.putIfAbsent(folder, i)
            }
        }
        if (folders.size <= 1) return emptyList()
        return folders.map { (folder, page) ->
            TocEntry(title = folder.substringAfterLast('/').ifBlank { folder }, pageIndex = page)
        }
    }

    override fun close() {
        parser.destroy()
        descriptorCache.clear()
        opened = false
    }

    companion object {
        @JvmStatic
        fun fromFile(file: File): ParserPageSource {
            val parser = ParserFactory.create(file)
                ?: throw IllegalArgumentException("No parser for ${file.name}")
            return ParserPageSource(parser, file.name)
        }

        @JvmStatic
        fun fromUri(uri: Uri, intentType: String? = null): ParserPageSource {
            // Intent path uses ParserFactory.create(Intent) via ReaderFragment historically;
            // for URI-only opens, stage through temp file is handled upstream.
            throw UnsupportedOperationException("Use fromParser with an Intent-created Parser")
        }

        @JvmStatic
        fun fromParser(parser: Parser, title: String): ParserPageSource =
            ParserPageSource(parser, title)
    }
}
