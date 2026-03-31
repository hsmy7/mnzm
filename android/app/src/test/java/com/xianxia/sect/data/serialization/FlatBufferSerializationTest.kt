package com.xianxia.sect.data.serialization

import com.xianxia.sect.data.serialization.unified.SerializationFormat
import com.xianxia.sect.data.serialization.unified.SerializationContext
import com.xianxia.sect.data.serialization.unified.DataType
import com.xianxia.sect.data.serialization.unified.SerializationConstants
import com.xianxia.sect.data.serialization.unified.CompressionType
import com.xianxia.sect.data.serialization.flatbuf.FlatBufferPool
import com.xianxia.sect.data.serialization.flatbuf.ByteBufferPool
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FlatBufferPoolTest {
    
    @Before
    fun setup() {
        FlatBufferPool.clearPool()
    }
    
    @Test
    fun testObtainAndRecycle() {
        val builder1 = FlatBufferPool.obtain()
        assertNotNull(builder1)
        
        FlatBufferPool.recycle(builder1)
        
        val stats = FlatBufferPool.getPoolStats()
        assertEquals(1, stats.poolSize)
    }
    
    @Test
    fun testMultipleObtain() {
        val builders = mutableListOf<com.google.flatbuffers.FlatBufferBuilder>()
        
        repeat(5) {
            builders.add(FlatBufferPool.obtain())
        }
        
        assertEquals(5, builders.size)
        
        builders.forEach { FlatBufferPool.recycle(it) }
        
        val stats = FlatBufferPool.getPoolStats()
        assertTrue(stats.poolSize <= 16)
    }
    
    @Test
    fun testPoolReuse() {
        val builder1 = FlatBufferPool.obtain(1024)
        FlatBufferPool.recycle(builder1)
        
        val builder2 = FlatBufferPool.obtain(1024)
        
        assertSame(builder1, builder2)
    }
}

class ByteBufferPoolTest {
    
    private val pool = ByteBufferPool()
    
    @Test
    fun testObtainSmallBuffer() {
        val buffer = pool.obtain(1024)
        assertNotNull(buffer)
        assertTrue(buffer.capacity() >= 1024)
        pool.recycle(buffer)
    }
    
    @Test
    fun testObtainMediumBuffer() {
        val buffer = pool.obtain(32 * 1024)
        assertNotNull(buffer)
        assertTrue(buffer.capacity() >= 32 * 1024)
        pool.recycle(buffer)
    }
    
    @Test
    fun testObtainLargeBuffer() {
        val buffer = pool.obtain(256 * 1024)
        assertNotNull(buffer)
        assertTrue(buffer.capacity() >= 256 * 1024)
        pool.recycle(buffer)
    }
    
    @Test
    fun testBufferRecycling() {
        val buffer1 = pool.obtain(ByteBufferPool.SMALL_SIZE)
        pool.recycle(buffer1)
        
        val buffer2 = pool.obtain(ByteBufferPool.SMALL_SIZE)
        
        assertEquals(buffer1, buffer2)
        pool.recycle(buffer2)
    }
}

class SerializationFormatTest {
    
    @Test
    fun testFormatCodes() {
        assertEquals(1, SerializationFormat.PROTOBUF.code)
    }
    
    @Test
    fun testFromCode() {
        assertEquals(SerializationFormat.PROTOBUF, SerializationFormat.fromCode(1))
    }
    
    @Test
    fun testFromCodeInvalid() {
        assertEquals(SerializationFormat.PROTOBUF, SerializationFormat.fromCode(99))
    }
}

class SerializationContextTest {
    
    @Test
    fun testDefaultContext() {
        val context = SerializationContext()
        
        assertEquals(SerializationFormat.PROTOBUF, context.format)
        assertEquals(CompressionType.ZSTD, context.compression)
        assertEquals(1024, context.compressThreshold)
        assertTrue(context.includeChecksum)
    }
    
    @Test
    fun testProtobufContext() {
        val context = SerializationContext(
            format = SerializationFormat.PROTOBUF,
            compression = CompressionType.LZ4,
            compressThreshold = 512
        )
        
        assertEquals(SerializationFormat.PROTOBUF, context.format)
        assertEquals(CompressionType.LZ4, context.compression)
        assertEquals(512, context.compressThreshold)
    }
}

class DataTypeTest {
    
    @Test
    fun testDataTypeValues() {
        assertEquals(4, DataType.values().size)
        assertTrue(DataType.values().contains(DataType.HOT_DATA))
        assertTrue(DataType.values().contains(DataType.COLD_DATA))
        assertTrue(DataType.values().contains(DataType.DELTA))
        assertTrue(DataType.values().contains(DataType.PERFORMANCE_CRITICAL))
    }
}

class SerializationConstantsTest {
    
    @Test
    fun testConstants() {
        assertEquals(0x5853, SerializationConstants.MAGIC_HEADER.toInt())
        assertEquals(3, SerializationConstants.FORMAT_VERSION.toInt())
        assertEquals(8, SerializationConstants.HEADER_SIZE)
        assertEquals(32, SerializationConstants.CHECKSUM_SIZE)
        assertEquals(200 * 1024 * 1024, SerializationConstants.MAX_DATA_SIZE)
    }
}
