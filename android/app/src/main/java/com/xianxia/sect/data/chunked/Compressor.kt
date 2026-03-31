package com.xianxia.sect.data.chunked

import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

interface Compressor {
    fun compress(data: ByteArray): ByteArray
    fun decompress(data: ByteArray): ByteArray
    val name: String
}

class DeflaterCompressor(
    private val level: Int = Deflater.BEST_SPEED
) : Compressor {
    companion object {
        private const val TAG = "DeflaterCompressor"
    }

    override val name: String = "DEFLATE"

    override fun compress(data: ByteArray): ByteArray {
        return try {
            val outputStream = ByteArrayOutputStream()
            val deflater = Deflater(level)
            val deflaterStream = DeflaterOutputStream(outputStream, deflater)
            deflaterStream.write(data)
            deflaterStream.close()
            outputStream.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Deflater compression failed, falling back to raw", e)
            data
        }
    }

    override fun decompress(data: ByteArray): ByteArray {
        return try {
            val inputStream = ByteArrayInputStream(data)
            val inflater = Inflater()
            val inflaterStream = InflaterInputStream(inputStream, inflater)
            val outputStream = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var read: Int
            while (inflaterStream.read(buffer).also { read = it } > 0) {
                outputStream.write(buffer, 0, read)
            }
            inflaterStream.close()
            outputStream.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Deflater decompression failed", e)
            throw e
        }
    }
}

typealias LZ4Compressor = DeflaterCompressor
typealias ZstdCompressor = DeflaterCompressor

class NoOpCompressor : Compressor {
    override val name: String = "NONE"

    override fun compress(data: ByteArray): ByteArray = data

    override fun decompress(data: ByteArray): ByteArray = data
}

class CompositeCompressor(
    private val primary: Compressor,
    private val fallback: Compressor = NoOpCompressor()
) : Compressor {

    override val name: String = "${primary.name}+${fallback.name}"

    override fun compress(data: ByteArray): ByteArray {
        return try {
            primary.compress(data)
        } catch (e: Exception) {
            fallback.compress(data)
        }
    }

    override fun decompress(data: ByteArray): ByteArray {
        return try {
            primary.decompress(data)
        } catch (e: Exception) {
            fallback.decompress(data)
        }
    }
}
