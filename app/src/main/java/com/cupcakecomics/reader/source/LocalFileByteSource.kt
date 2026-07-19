package com.cupcakecomics.reader.source

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class LocalFileByteSource(private val file: File) : SeekableByteSource {
    private val raf = RandomAccessFile(file, "r")

    @Throws(IOException::class)
    override fun length(): Long = raf.length()

    @Throws(IOException::class)
    override fun read(offset: Long, dest: ByteArray, destOffset: Int, length: Int): Int {
        synchronized(raf) {
            raf.seek(offset)
            return raf.read(dest, destOffset, length)
        }
    }

    override fun close() {
        raf.close()
    }
}
