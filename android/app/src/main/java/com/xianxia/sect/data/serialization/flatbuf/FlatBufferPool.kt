package com.xianxia.sect.data.serialization.flatbuf

import com.google.flatbuffers.FlatBufferBuilder
import com.google.flatbuffers.Table
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

object FlatBufferPool {
    private const val INITIAL_BUFFER_SIZE = 1024 * 1024
    private const val MAX_POOL_SIZE = 16
    private const val MAX_BUFFER_SIZE = 10 * 1024 * 1024
    
    private val builderPool = ConcurrentLinkedQueue<FlatBufferBuilder>()
    private val poolSize = AtomicInteger(0)
    
    fun obtain(initialSize: Int = INITIAL_BUFFER_SIZE): FlatBufferBuilder {
        val builder = builderPool.poll()
        return if (builder != null) {
            builder.clear()
            builder
        } else {
            FlatBufferBuilder(initialSize)
        }
    }
    
    fun recycle(builder: FlatBufferBuilder) {
        if (poolSize.get() < MAX_POOL_SIZE && builder.dataBuffer().capacity() <= MAX_BUFFER_SIZE) {
            builderPool.offer(builder)
            poolSize.incrementAndGet()
        }
    }
    
    fun getPoolStats(): PoolStats {
        return PoolStats(
            poolSize = poolSize.get(),
            availableBuilders = builderPool.size
        )
    }
    
    fun clearPool() {
        builderPool.clear()
        poolSize.set(0)
    }
    
    data class PoolStats(
        val poolSize: Int,
        val availableBuilders: Int
    )
}

class ByteBufferPool {
    private val smallPool = ConcurrentLinkedQueue<ByteBuffer>()
    private val mediumPool = ConcurrentLinkedQueue<ByteBuffer>()
    private val largePool = ConcurrentLinkedQueue<ByteBuffer>()
    
    companion object {
        const val SMALL_SIZE = 4 * 1024
        const val MEDIUM_SIZE = 64 * 1024
        const val LARGE_SIZE = 512 * 1024
        const val MAX_POOL_SIZE = 32
    }
    
    fun obtain(size: Int): ByteBuffer {
        val pool = when {
            size <= SMALL_SIZE -> smallPool
            size <= MEDIUM_SIZE -> mediumPool
            else -> largePool
        }
        
        val buffer = pool.poll()
        return if (buffer != null && buffer.capacity() >= size) {
            buffer.clear()
            buffer
        } else {
            ByteBuffer.allocateDirect(size)
        }
    }
    
    fun recycle(buffer: ByteBuffer) {
        val pool = when (buffer.capacity()) {
            in 0..SMALL_SIZE -> smallPool
            in SMALL_SIZE + 1..MEDIUM_SIZE -> mediumPool
            else -> largePool
        }
        
        if (getPoolSize(pool) < MAX_POOL_SIZE) {
            buffer.clear()
            pool.offer(buffer)
        }
    }
    
    private fun getPoolSize(pool: ConcurrentLinkedQueue<ByteBuffer>): Int {
        return pool.size
    }
    
    fun clearAll() {
        smallPool.clear()
        mediumPool.clear()
        largePool.clear()
    }
}
