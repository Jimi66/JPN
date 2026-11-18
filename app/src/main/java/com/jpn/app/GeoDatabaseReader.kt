package com.jpn.app

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Reads GeoSiteList/GeoIPList protobuf files through a read-only memory map.
 * The OS pages file data on demand; the complete database is never copied
 * into a large heap byte array.
 */
internal object GeoDatabaseReader {
    private val categoryPattern = Regex("[A-Za-z0-9_-]{1,80}")

    fun readCategories(file: File): List<String> {
        if (!file.isFile || file.length() < 8L || file.length() > 100L * 1024L * 1024L) {
            return emptyList()
        }
        return runCatching {
            FileInputStream(file).channel.use { channel ->
                val data = channel.map(FileChannel.MapMode.READ_ONLY, 0L, file.length())
                val categories = linkedSetOf<String>()
                while (data.hasRemaining()) {
                    val tag = readVarint(data)
                    val field = (tag ushr 3).toInt()
                    if (field == 0) throw IllegalArgumentException("Invalid protobuf field")
                    when ((tag and 7L).toInt()) {
                        0 -> readVarint(data)
                        1 -> skip(data, 8)
                        2 -> {
                            val length = readLength(data)
                            if (field == 1) {
                                val record = takeSlice(data, length)
                                val name = readCategory(record)
                                if (name != null && categoryPattern.matches(name)) {
                                    categories.add(name)
                                }
                            } else {
                                skip(data, length)
                            }
                        }
                        5 -> skip(data, 4)
                        else -> throw IllegalArgumentException("Unknown protobuf wire type")
                    }
                }
                categories.sorted()
            }
        }.getOrDefault(emptyList())
    }

    private fun readCategory(record: ByteBuffer): String? {
        while (record.hasRemaining()) {
            val tag = readVarint(record)
            val field = (tag ushr 3).toInt()
            if (field == 0) throw IllegalArgumentException("Invalid protobuf field")
            when ((tag and 7L).toInt()) {
                0 -> readVarint(record)
                1 -> skip(record, 8)
                2 -> {
                    val length = readLength(record)
                    if (field == 1) {
                        if (length > 80) {
                            skip(record, length)
                            return null
                        }
                        val bytes = ByteArray(length)
                        record.get(bytes)
                        return String(bytes, Charsets.UTF_8)
                    }
                    skip(record, length)
                }
                5 -> skip(record, 4)
                else -> throw IllegalArgumentException("Unknown protobuf wire type")
            }
        }
        return null
    }

    private fun readLength(buffer: ByteBuffer): Int {
        val value = readVarint(buffer)
        if (value < 0L || value > buffer.remaining().toLong() || value > Int.MAX_VALUE) {
            throw IllegalArgumentException("Invalid protobuf length")
        }
        return value.toInt()
    }

    private fun takeSlice(buffer: ByteBuffer, length: Int): ByteBuffer {
        if (length < 0 || length > buffer.remaining()) {
            throw IllegalArgumentException("Truncated protobuf")
        }
        val slice = buffer.slice()
        slice.limit(length)
        buffer.position(buffer.position() + length)
        return slice
    }

    private fun skip(buffer: ByteBuffer, length: Int) {
        if (length < 0 || length > buffer.remaining()) {
            throw IllegalArgumentException("Truncated protobuf")
        }
        buffer.position(buffer.position() + length)
    }

    private fun readVarint(buffer: ByteBuffer): Long {
        var value = 0L
        for (index in 0 until 10) {
            if (!buffer.hasRemaining()) throw IllegalArgumentException("Truncated protobuf varint")
            val byte = buffer.get().toInt() and 0xFF
            if (index == 9 && byte > 1) throw IllegalArgumentException("Invalid protobuf varint")
            value = value or ((byte and 0x7F).toLong() shl (index * 7))
            if ((byte and 0x80) == 0) return value
        }
        throw IllegalArgumentException("Invalid protobuf varint")
    }
}
