package com.cupcakecomics.reader.source

import java.io.Closeable
import java.io.IOException

/**
 * Random-access byte source for local files and SMB range reads.
 */
interface SeekableByteSource : Closeable {
    /** Total length in bytes, or -1 if unknown. */
    @Throws(IOException::class)
    fun length(): Long

    /**
     * Read [length] bytes starting at [offset] into [dest] at [destOffset].
     * @return bytes actually read
     */
    @Throws(IOException::class)
    fun read(offset: Long, dest: ByteArray, destOffset: Int, length: Int): Int

    @Throws(IOException::class)
    fun readFully(offset: Long, length: Int): ByteArray {
        require(length >= 0) { "negative length" }
        val out = ByteArray(length)
        var got = 0
        while (got < length) {
            val n = read(offset + got, out, got, length - got)
            if (n <= 0) throw IOException("Unexpected EOF at ${offset + got}")
            got += n
        }
        return out
    }
}
