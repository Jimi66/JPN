package com.jpn.app

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Reads GeoSiteList/GeoIPList protobuf files without loading the complete database
 * into memory. Only category names (at most 80 ASCII characters) are retained.
 */
internal object GeoDatabaseReader {
    private val categoryPattern = Regex("[A-Za-z0-9_-]{1,80}")

    fun readCategories(file: File): List<String> {
        if (!file.isFile || file.length() < 8L) return emptyList()
        return runCatching {
            BufferedInputStream(FileInputStream(file)).use { input ->
                val reader = ProtoReader(input, file.length())
                val categories = linkedSetOf<String>()
                while (reader.hasRemaining()) {
                    val tag = reader.readVarint()
                    val field = (tag ushr 3).toInt()
                    if (field == 0) throw IllegalArgumentException("Invalid protobuf field")
                    when ((tag and 7L).toInt()) {
                        0 -> reader.readVarint()
                        1 -> reader.skip(8L)
                        2 -> {
                            val length = reader.readLength()
                            if (field == 1) {
                                val record = reader.readMessage(length)
                                val name = readCategory(record)
                                record.skipRemaining()
                                if (name != null && categoryPattern.matches(name)) categories.add(name)
                            } else {
                                reader.skip(length)
                            }
                        }
                        5 -> reader.skip(4L)
                        else -> throw IllegalArgumentException("Unknown protobuf wire type")
                    }
                }
                categories.sorted()
            }
        }.getOrDefault(emptyList())
    }

    private fun readCategory(record: ProtoReader): String? {
        while (record.hasRemaining()) {
            val tag = record.readVarint()
            val field = (tag ushr 3).toInt()
            if (field == 0) throw IllegalArgumentException("Invalid protobuf field")
            when ((tag and 7L).toInt()) {
                0 -> record.readVarint()
                1 -> record.skip(8L)
                2 -> {
                    val length = record.readLength()
                    if (field == 1) {
                        if (length > 80L) {
                            record.skip(length)
                            return null
                        }
                        val bytes = record.readBytes(length.toInt())
                        return String(bytes, Charsets.UTF_8)
                    }
                    record.skip(length)
                }
                5 -> record.skip(4L)
                else -> throw IllegalArgumentException("Unknown protobuf wire type")
            }
        }
        return null
    }

    private class ProtoReader(
        private val input: InputStream,
        length: Long
    ) {
        private var remaining = length

        fun hasRemaining(): Boolean = remaining > 0L

        fun readVarint(): Long {
            var value = 0L
            for (index in 0 until 10) {
                val byte = readByte()
                if (index == 9 && byte > 1) {
                    throw IllegalArgumentException("Invalid protobuf varint")
                }
                value = value or ((byte and 0x7F).toLong() shl (index * 7))
                if ((byte and 0x80) == 0) return value
            }
            throw IllegalArgumentException("Invalid protobuf varint")
        }

        fun readLength(): Long {
            val length = readVarint()
            if (length < 0L || length > remaining) {
                throw IllegalArgumentException("Invalid protobuf length")
            }
            return length
        }

        fun readMessage(length: Long): ProtoReader {
            if (length < 0L || length > remaining) {
                throw IllegalArgumentException("Invalid protobuf length")
            }
            remaining -= length
            return ProtoReader(LimitedInputStream(input, length), length)
        }

        fun readBytes(length: Int): ByteArray {
            if (length < 0 || length.toLong() > remaining) {
                throw IllegalArgumentException("Truncated protobuf")
            }
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = input.read(bytes, offset, length - offset)
                if (count < 0) throw IllegalArgumentException("Truncated protobuf")
                if (count == 0) continue
                offset += count
                remaining -= count.toLong()
            }
            return bytes
        }

        fun skip(length: Long) {
            if (length < 0L || length > remaining) {
                throw IllegalArgumentException("Truncated protobuf")
            }
            var left = length
            val buffer = ByteArray(8192)
            while (left > 0L) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (count < 0) throw IllegalArgumentException("Truncated protobuf")
                if (count == 0) continue
                left -= count.toLong()
                remaining -= count.toLong()
            }
        }

        fun skipRemaining() {
            skip(remaining)
        }

        private fun readByte(): Int {
            if (remaining <= 0L) throw IllegalArgumentException("Truncated protobuf")
            val value = input.read()
            if (value < 0) throw IllegalArgumentException("Truncated protobuf")
            remaining--
            return value
        }
    }

    private class LimitedInputStream(
        private val source: InputStream,
        length: Long
    ) : InputStream() {
        private var remaining = length

        override fun read(): Int {
            if (remaining <= 0L) return -1
            val value = source.read()
            if (value < 0) throw IllegalArgumentException("Truncated protobuf")
            remaining--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0L) return -1
            val count = source.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (count < 0) throw IllegalArgumentException("Truncated protobuf")
            if (count > 0) remaining -= count.toLong()
            return count
        }
    }
}
