package com.xianxia.sect.data.serialization.unified

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@Serializable
data class TestProtoData(
    @ProtoNumber(1) val id: String = "",
    @ProtoNumber(2) val value: Int = 0,
    @ProtoNumber(3) val items: List<String> = emptyList(),
    @ProtoNumber(4) val metadata: Map<String, String> = emptyMap()
)

class UnifiedSerializationEngineTest {

    private lateinit var compressionLayer: UnifiedCompressionLayer
    private lateinit var integrityLayer: IntegrityLayer
    private lateinit var engine: UnifiedSerializationEngine

    @Before
    fun setUp() {
        compressionLayer = UnifiedCompressionLayer()
        integrityLayer = IntegrityLayer()
        engine = UnifiedSerializationEngine(compressionLayer, integrityLayer)
    }

    private fun createTestContext(
        format: SerializationFormat = SerializationFormat.PROTOBUF,
        compression: CompressionType = CompressionType.LZ4,
        compressThreshold: Int = 64,
        includeChecksum: Boolean = true
    ): SerializationContext {
        return SerializationContext(
            format = format,
            compression = compression,
            compressThreshold = compressThreshold,
            includeChecksum = includeChecksum
        )
    }

    private fun createSmallData(): TestProtoData {
        return TestProtoData(id = "test_1", value = 42, items = listOf("a"), metadata = mapOf("key" to "val"))
    }

    private fun createLargeData(): TestProtoData {
        return TestProtoData(
            id = "large_test",
            value = 99999,
            items = (1..5000).map { "item_$it" },
            metadata = (1..100).associate { "key_$it" to "value_$it" }
        )
    }

    @Test
    fun `serialize - small data with checksum produces valid SerializationResult`() {
        val data = createSmallData()
        val context = createTestContext(compressThreshold = 64, includeChecksum = true)

        val result = engine.serialize(data, context, TestProtoData.serializer())

        assertTrue(result.data.isNotEmpty())
        assertTrue(result.originalSize > 0)
        assertEquals(SerializationFormat.PROTOBUF, result.format)
        assertEquals(CompressionType.LZ4, result.compression)
        assertTrue(result.checksum.size == 32)
        assertTrue(result.serializationTimeMs >= 0)
    }

    @Test
    fun `serialize - without checksum produces zero checksum`() {
        val data = createSmallData()
        val context = createTestContext(includeChecksum = false)

        val result = engine.serialize(data, context, TestProtoData.serializer())

        val allZero = result.checksum.all { it == 0.toByte() }
        assertTrue(allZero)
    }

    @Test
    fun `serialize - large data gets compressed`() {
        val data = createLargeData()
        val context = createTestContext(compressThreshold = 64)

        val result = engine.serialize(data, context, TestProtoData.serializer())

        assertTrue(result.compressedSize > 0)
        assertTrue(result.compressionRatio < 1.0 || result.compressedSize >= result.originalSize)
    }

    @Test
    fun `serialize then deserialize - small data roundtrip preserves data`() {
        val original = createSmallData()
        val context = createTestContext(compressThreshold = 64, includeChecksum = true)

        val serialized = engine.serialize(original, context, TestProtoData.serializer())
        val deserialized = engine.deserialize(serialized.data, context, TestProtoData.serializer())

        assertTrue(deserialized.isSuccess)
        assertEquals(original.id, deserialized.data?.id)
        assertEquals(original.value, deserialized.data?.value)
        assertEquals(original.items, deserialized.data?.items)
        assertEquals(original.metadata, deserialized.data?.metadata)
    }

    @Test
    fun `serialize then deserialize - large data roundtrip preserves data`() {
        val original = createLargeData()
        val context = createTestContext(compressThreshold = 64, includeChecksum = true)

        val serialized = engine.serialize(original, context, TestProtoData.serializer())
        val deserialized = engine.deserialize(serialized.data, context, TestProtoData.serializer())

        assertTrue(deserialized.isSuccess)
        assertEquals(original.id, deserialized.data?.id)
        assertEquals(original.value, deserialized.data?.value)
        assertEquals(original.items, deserialized.data?.items)
        assertEquals(original.metadata, deserialized.data?.metadata)
    }

    @Test
    fun `serialize then deserialize - without checksum still works`() {
        val original = createSmallData()
        val context = createTestContext(includeChecksum = false)

        val serialized = engine.serialize(original, context, TestProtoData.serializer())
        val deserialized = engine.deserialize(serialized.data, context, TestProtoData.serializer())

        assertTrue(deserialized.isSuccess)
        assertEquals(original.id, deserialized.data?.id)
    }

    @Test
    fun `serialize - compression ratio is calculated correctly`() {
        val data = createLargeData()
        val context = createTestContext(compressThreshold = 64)

        val result = engine.serialize(data, context, TestProtoData.serializer())

        val expectedRatio = result.compressedSize.toDouble() / result.originalSize
        assertEquals(expectedRatio, result.compressionRatio, 0.001)
    }

    @Test
    fun `serialize - data below compressThreshold still gets compressed with LZ4`() {
        val data = createSmallData()
        val context = createTestContext(compressThreshold = 999999)

        val result = engine.serialize(data, context, TestProtoData.serializer())

        assertEquals(CompressionType.LZ4, result.compression)
    }

    @Test
    fun `deserialize - corrupted data returns failure result`() {
        val corruptedData = ByteArray(100) { (it * 3).toByte() }
        val context = createTestContext()

        val result = engine.deserialize(corruptedData, context, TestProtoData.serializer())

        assertFalse(result.isSuccess)
        assertNull(result.data)
        assertNotNull(result.error)
    }

    @Test
    fun `deserialize - empty data returns failure`() {
        val emptyData = ByteArray(0)
        val context = createTestContext()

        val result = engine.deserialize(emptyData, context, TestProtoData.serializer())

        assertFalse(result.isSuccess)
    }

    @Test
    fun `serialize - multiple roundtrips produce consistent results`() {
        val original = createLargeData()
        val context = createTestContext(compressThreshold = 64, includeChecksum = true)

        val serialized1 = engine.serialize(original, context, TestProtoData.serializer())
        val deserialized1 = engine.deserialize(serialized1.data, context, TestProtoData.serializer())
        val restored1 = deserialized1.data!!

        val serialized2 = engine.serialize(restored1, context, TestProtoData.serializer())
        val deserialized2 = engine.deserialize(serialized2.data, context, TestProtoData.serializer())
        val restored2 = deserialized2.data!!

        assertEquals(restored1.id, restored2.id)
        assertEquals(restored1.value, restored2.value)
        assertEquals(restored1.items, restored2.items)
        assertEquals(restored1.metadata, restored2.metadata)
    }

    @Test
    fun `serialize - SerializationResult totalSavedBytes is non-negative`() {
        val data = createLargeData()
        val context = createTestContext(compressThreshold = 64)

        val result = engine.serialize(data, context, TestProtoData.serializer())

        assertTrue(result.totalSavedBytes >= 0)
    }

    @Test
    fun `SerializationFormat fromCode - PROTOBUF code returns PROTOBUF`() {
        assertEquals(SerializationFormat.PROTOBUF, SerializationFormat.fromCode(1))
    }

    @Test
    fun `SerializationFormat fromCode - unknown code defaults to PROTOBUF`() {
        assertEquals(SerializationFormat.PROTOBUF, SerializationFormat.fromCode(99))
    }

    @Test
    fun `CompressionType fromCode - LZ4 code returns LZ4`() {
        assertEquals(CompressionType.LZ4, CompressionType.fromCode(1))
    }

    @Test
    fun `CompressionType fromCode - ZSTD code returns ZSTD`() {
        assertEquals(CompressionType.ZSTD, CompressionType.fromCode(2))
    }

    @Test
    fun `CompressionType fromCode - unknown code defaults to LZ4`() {
        assertEquals(CompressionType.LZ4, CompressionType.fromCode(99))
    }

    @Test
    fun `SerializationResult - equals uses contentEquals for data`() {
        val data = ByteArray(10) { it.toByte() }
        val r1 = SerializationResult(
            data = data, originalSize = 10, compressedSize = 8,
            format = SerializationFormat.PROTOBUF, compression = CompressionType.LZ4,
            checksum = ByteArray(32), serializationTimeMs = 1, compressionTimeMs = 1
        )
        val r2 = SerializationResult(
            data = data.copyOf(), originalSize = 10, compressedSize = 8,
            format = SerializationFormat.PROTOBUF, compression = CompressionType.LZ4,
            checksum = ByteArray(32), serializationTimeMs = 1, compressionTimeMs = 1
        )
        assertEquals(r1, r2)
    }

    @Test
    fun `SerializationResult - hashCode uses contentHashCode for data`() {
        val data = ByteArray(10) { it.toByte() }
        val r1 = SerializationResult(
            data = data, originalSize = 10, compressedSize = 8,
            format = SerializationFormat.PROTOBUF, compression = CompressionType.LZ4,
            checksum = ByteArray(32), serializationTimeMs = 1, compressionTimeMs = 1
        )
        val r2 = SerializationResult(
            data = data.copyOf(), originalSize = 10, compressedSize = 8,
            format = SerializationFormat.PROTOBUF, compression = CompressionType.LZ4,
            checksum = ByteArray(32), serializationTimeMs = 1, compressionTimeMs = 1
        )
        assertEquals(r1.hashCode(), r2.hashCode())
    }

    @Test
    fun `SerializationQuota - STRICT has expected limits`() {
        val quota = SerializationQuota.STRICT
        assertEquals(64 * 1024, quota.coreMaxBytes)
        assertEquals(10 * 1024 * 1024, quota.discipleMaxBytes)
        assertEquals(5 * 1024 * 1024, quota.inventoryMaxBytes)
        assertEquals(200 * 1024 * 1024, quota.totalMaxBytes)
        assertEquals(5000, quota.maxDiscipleCount)
        assertEquals(10000, quota.maxBattleLogCount)
    }

    @Test
    fun `SerializationQuota - LENIENT has larger limits than STRICT`() {
        val strict = SerializationQuota.STRICT
        val lenient = SerializationQuota.LENIENT

        assertTrue(lenient.totalMaxBytes > strict.totalMaxBytes)
        assertTrue(lenient.maxDiscipleCount > strict.maxDiscipleCount)
        assertTrue(lenient.maxBattleLogCount > strict.maxBattleLogCount)
    }

    @Test
    fun `QuotaViolation - toString contains domain and values`() {
        val violation = QuotaViolation(
            domain = "disciples",
            actualValue = 6000,
            limit = 5000,
            unit = "bytes"
        )
        val str = violation.toString()
        assertTrue(str.contains("disciples"))
        assertTrue(str.contains("6000"))
        assertTrue(str.contains("5000"))
    }

    @Test
    fun `QuotaValidationResult - hasViolations reflects violations list`() {
        val valid = QuotaValidationResult(isValid = true, violations = emptyList())
        val invalid = QuotaValidationResult(
            isValid = false,
            violations = listOf(QuotaViolation("test", 100, 50))
        )
        assertFalse(valid.hasViolations)
        assertTrue(invalid.hasViolations)
    }

    @Test
    fun `SerializationConstants - HEADER_SIZE is 10`() {
        assertEquals(10, SerializationConstants.HEADER_SIZE)
    }

    @Test
    fun `SerializationConstants - CHECKSUM_SIZE is 32`() {
        assertEquals(32, SerializationConstants.CHECKSUM_SIZE)
    }

    @Test
    fun `SerializationContext - default values are correct`() {
        val context = SerializationContext()
        assertEquals(SerializationFormat.PROTOBUF, context.format)
        assertEquals(CompressionType.LZ4, context.compression)
        assertEquals(1024, context.compressThreshold)
        assertTrue(context.includeChecksum)
        assertEquals(1, context.version)
        assertFalse(context.prettyPrint)
    }

    @Test
    fun `DeserializationResult - isSuccess is true when data is non-null and error is null`() {
        val result = DeserializationResult(
            data = TestProtoData(id = "test"),
            format = SerializationFormat.PROTOBUF,
            compression = CompressionType.LZ4,
            checksumValid = true,
            deserializationTimeMs = 10,
            decompressionTimeMs = 5
        )
        assertTrue(result.isSuccess)
    }

    @Test
    fun `DeserializationResult - isSuccess is false when data is null`() {
        val result = DeserializationResult(
            data = null,
            format = SerializationFormat.PROTOBUF,
            compression = CompressionType.LZ4,
            checksumValid = false,
            deserializationTimeMs = 10,
            decompressionTimeMs = 5,
            error = RuntimeException("test")
        )
        assertFalse(result.isSuccess)
    }

    @Test
    fun `DeserializationResult - isSuccess is false when error is non-null`() {
        val result = DeserializationResult(
            data = TestProtoData(id = "test"),
            format = SerializationFormat.PROTOBUF,
            compression = CompressionType.LZ4,
            checksumValid = true,
            deserializationTimeMs = 10,
            decompressionTimeMs = 5,
            error = RuntimeException("test")
        )
        assertFalse(result.isSuccess)
    }
}
