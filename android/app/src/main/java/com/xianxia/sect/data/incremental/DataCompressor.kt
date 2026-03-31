package com.xianxia.sect.data.incremental

import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class DataCompressor {
    companion object {
        private const val TAG = "DataCompressor"
        private const val COMPRESSION_THRESHOLD = 1024
        private const val COMPRESSION_MARKER = "GZIP:"
    }

    fun compress(data: ByteArray): ByteArray {
        if (data.size < COMPRESSION_THRESHOLD) {
            return data
        }

        val startTime = System.nanoTime()

        val compressed = try {
            val bos = ByteArrayOutputStream(data.size)
            GZIPOutputStream(bos).use { it.write(data) }
            bos.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Compression failed", e)
            return data
        }

        if (compressed.size >= data.size) {
            return data
        }

        val duration = System.nanoTime() - startTime
        val ratio = data.size.toDouble() / compressed.size

        Log.d(TAG, "Compressed ${data.size} -> ${compressed.size} bytes " +
                "(ratio: ${"%.2f".format(ratio)}, time: ${duration / 1_000_000}ms)")

        return (COMPRESSION_MARKER.toByteArray() + compressed)
    }

    fun decompress(compressedData: ByteArray): ByteArray {
        if (compressedData.isEmpty()) {
            return compressedData
        }

        val markerBytes = COMPRESSION_MARKER.toByteArray()
        if (compressedData.size < markerBytes.size) {
            return compressedData
        }

        val isCompressed = compressedData.take(markerBytes.size).toByteArray().contentEquals(markerBytes)

        return if (isCompressed) {
            try {
                val actualData = compressedData.drop(markerBytes.size).toByteArray()
                GZIPInputStream(ByteArrayInputStream(actualData)).use { it.readBytes() }
            } catch (e: Exception) {
                Log.w(TAG, "Decompression failed, returning as-is", e)
                compressedData
            }
        } else {
            compressedData
        }
    }
}
