package com.xianxia.sect.data.serialization.delta

import android.util.Log
import com.xianxia.sect.data.model.SaveData
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

object OptimizedDeltaSerializer {
    private const val TAG = "OptimizedDeltaSerial"
    
    data class DeltaResult(
        val delta: ByteArray,
        val originalSize: Int,
        val deltaSize: Int,
        val compressionRatio: Double
    ) {
        val savedBytes: Int get() = (originalSize - deltaSize).coerceAtLeast(0)
    }
    
    fun computeDelta(oldData: ByteArray, newData: ByteArray): DeltaResult {
        val startTime = System.currentTimeMillis()
        
        val delta = if (oldData.isEmpty()) {
            newData
        } else {
            computeBinaryDelta(oldData, newData)
        }
        
        val compressedDelta = compressDelta(delta)
        
        val time = System.currentTimeMillis() - startTime
        Log.d(TAG, "Delta computed in ${time}ms, ratio: ${"%.2f".format(compressedDelta.size.toDouble() / newData.size)}")
        
        return DeltaResult(
            delta = compressedDelta,
            originalSize = newData.size,
            deltaSize = compressedDelta.size,
            compressionRatio = compressedDelta.size.toDouble() / newData.size.coerceAtLeast(1)
        )
    }
    
    fun applyDelta(baseData: ByteArray, delta: ByteArray): ByteArray {
        val decompressedDelta = decompressDelta(delta)
        
        return if (decompressedDelta.size == baseData.size) {
            decompressedDelta
        } else {
            applyBinaryDelta(baseData, decompressedDelta)
        }
    }
    
    private fun computeBinaryDelta(oldData: ByteArray, newData: ByteArray): ByteArray {
        val result = ByteArrayOutputStream()
        
        var oldPos = 0
        var newPos = 0
        
        while (newPos < newData.size) {
            val matchResult = findLongestMatch(oldData, newData, oldPos, newPos)
            
            if (matchResult != null && matchResult.length >= 4) {
                result.write(0)
                writeVarInt(result, matchResult.offset)
                writeVarInt(result, matchResult.length)
                newPos += matchResult.length
            } else {
                val literalStart = newPos
                var literalEnd = newPos
                
                while (literalEnd < newData.size && literalEnd - literalStart < 255) {
                    val match = findLongestMatch(oldData, newData, oldPos, literalEnd)
                    if (match != null && match.length >= 4) break
                    literalEnd++
                }
                
                val literalLength = literalEnd - literalStart
                result.write(literalLength + 1)
                result.write(newData, literalStart, literalLength)
                newPos = literalEnd
            }
        }
        
        return result.toByteArray()
    }
    
    private data class MatchResult(
        val offset: Int,
        val length: Int
    )
    
    private fun findLongestMatch(
        oldData: ByteArray,
        newData: ByteArray,
        oldStart: Int,
        newStart: Int
    ): MatchResult? {
        var bestMatch: MatchResult? = null
        var bestLength = 0
        
        val searchStart = (oldStart - 4096).coerceAtLeast(0)
        val searchEnd = (oldStart + 4096).coerceAtMost(oldData.size)
        
        for (i in searchStart until searchEnd) {
            var length = 0
            while (i + length < oldData.size &&
                   newStart + length < newData.size &&
                   oldData[i + length] == newData[newStart + length] &&
                   length < 65535) {
                length++
            }
            
            if (length > bestLength) {
                bestLength = length
                bestMatch = MatchResult(offset = i, length = length)
            }
        }
        
        return bestMatch
    }
    
    private fun applyBinaryDelta(baseData: ByteArray, delta: ByteArray): ByteArray {
        val result = ByteArrayOutputStream()
        val input = ByteArrayInputStream(delta)
        
        while (input.available() > 0) {
            val cmd = input.read()
            
            if (cmd == 0) {
                val offset = readVarInt(input)
                val length = readVarInt(input)
                result.write(baseData, offset, length)
            } else {
                val length = cmd - 1
                val bytes = ByteArray(length)
                input.read(bytes)
                result.write(bytes)
            }
        }
        
        return result.toByteArray()
    }
    
    private fun writeVarInt(stream: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v >= 0x80) {
            stream.write((v and 0x7F) or 0x80)
            v = v shr 7
        }
        stream.write(v)
    }
    
    private fun readVarInt(input: ByteArrayInputStream): Int {
        var result = 0
        var shift = 0
        var b: Int
        
        do {
            b = input.read()
            result = result or ((b and 0x7F) shl shift)
            shift += 7
        } while (b and 0x80 != 0)
        
        return result
    }
    
    private fun compressDelta(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
        deflater.setInput(data)
        deflater.finish()
        
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            output.write(buffer, 0, count)
        }
        
        deflater.end()
        return output.toByteArray()
    }
    
    private fun decompressDelta(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            output.write(buffer, 0, count)
        }
        
        inflater.end()
        return output.toByteArray()
    }
}

class IncrementalDeltaCache {
    private val baseDataCache = mutableMapOf<String, ByteArray>()
    private val maxCacheSize = 5 * 1024 * 1024
    private var currentCacheSize = 0
    
    @Synchronized
    fun setBase(key: String, data: ByteArray) {
        if (baseDataCache.containsKey(key)) {
            currentCacheSize -= baseDataCache[key]!!.size
        }
        
        while (currentCacheSize + data.size > maxCacheSize && baseDataCache.isNotEmpty()) {
            val oldest = baseDataCache.keys.first()
            currentCacheSize -= baseDataCache[oldest]!!.size
            baseDataCache.remove(oldest)
        }
        
        baseDataCache[key] = data
        currentCacheSize += data.size
    }
    
    @Synchronized
    fun getBase(key: String): ByteArray? = baseDataCache[key]
    
    @Synchronized
    fun clear() {
        baseDataCache.clear()
        currentCacheSize = 0
    }
    
    fun getStats(): CacheStats {
        return CacheStats(
            entryCount = baseDataCache.size,
            totalSize = currentCacheSize,
            maxSize = maxCacheSize
        )
    }
    
    data class CacheStats(
        val entryCount: Int,
        val totalSize: Int,
        val maxSize: Int
    )
}
