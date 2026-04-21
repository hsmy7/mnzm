package com.xianxia.sect.data.unified

import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.serialization.unified.*
import kotlinx.serialization.KSerializer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
class SerializationHelperTest {

    private val serializationEngine: UnifiedSerializationEngine = mock()

    private val saveDataConverter: SaveDataConverter = mock()

    private lateinit var helper: SerializationHelper

    private fun createMinimalSaveData(): SaveData {
        return SaveData(
            version = "2.0",
            timestamp = System.currentTimeMillis(),
            gameData = com.xianxia.sect.core.model.GameData(),
            disciples = emptyList(),
            equipmentStacks = emptyList(),
            equipmentInstances = emptyList(),
            manualStacks = emptyList(),
            manualInstances = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
            teams = emptyList(),
            events = emptyList()
        )
    }

    private fun createMinimalSerializable(): SerializableSaveData {
        return SerializableSaveData(
            version = "2.0",
            timestamp = System.currentTimeMillis(),
            gameData = SerializableGameData()
        )
    }

    @Before
    fun setUp() {
        helper = SerializationHelper(serializationEngine, saveDataConverter)
    }

    @Test
    fun `serializeAndCompressSaveData - converts to serializable then serializes`() {
        val saveData = createMinimalSaveData()
        val serializable = createMinimalSerializable()
        val serializedBytes = ByteArray(50) { it.toByte() }

        whenever(saveDataConverter.toSerializable(saveData)).thenReturn(serializable)

        val serializationResult = SerializationResult(
            data = serializedBytes,
            originalSize = 100,
            compressedSize = 50,
            format = SerializationFormat.PROTOBUF,
            compression = CompressionType.LZ4,
            checksum = ByteArray(32),
            serializationTimeMs = 10,
            compressionTimeMs = 5
        )
        whenever(serializationEngine.serialize(any<SerializableSaveData>(), any<SerializationContext>(), any<KSerializer<SerializableSaveData>>())).thenReturn(serializationResult)

        val result = helper.serializeAndCompressSaveData(saveData)

        assertArrayEquals(serializedBytes, result)
        verify(saveDataConverter).toSerializable(saveData)
    }

    @Test(expected = SerializationException::class)
    fun `serializeAndCompressSaveData - throws SerializationException on failure`() {
        val saveData = createMinimalSaveData()
        whenever(saveDataConverter.toSerializable(saveData)).thenThrow(RuntimeException("Conversion failed"))

        helper.serializeAndCompressSaveData(saveData)
    }

    @Test
    fun `deserializeSaveData - returns SaveData on success`() {
        val serializable = createMinimalSerializable()
        val saveData = createMinimalSaveData()
        val data = ByteArray(50) { it.toByte() }

        val deserializationResult = DeserializationResult<SerializableSaveData>(
            data = serializable,
            format = SerializationFormat.PROTOBUF,
            compression = CompressionType.LZ4,
            checksumValid = true,
            deserializationTimeMs = 10,
            decompressionTimeMs = 5
        )
        whenever(serializationEngine.deserialize<SerializableSaveData>(any<ByteArray>(), any<SerializationContext>(), any<KSerializer<SerializableSaveData>>())).thenReturn(deserializationResult)
        whenever(saveDataConverter.fromSerializable(serializable)).thenReturn(saveData)

        val result = helper.deserializeSaveData(data)

        assertEquals(saveData.version, result.version)
    }

    @Test(expected = SerializationException::class)
    fun `deserializeSaveData - throws SerializationException when deserialization returns null`() {
        val data = ByteArray(50) { it.toByte() }
        val deserializationResult = DeserializationResult<SerializableSaveData>(
            data = null,
            format = SerializationFormat.PROTOBUF,
            compression = CompressionType.LZ4,
            checksumValid = false,
            deserializationTimeMs = 10,
            decompressionTimeMs = 5,
            error = RuntimeException("Corrupted")
        )
        whenever(serializationEngine.deserialize<SerializableSaveData>(any<ByteArray>(), any<SerializationContext>(), any<KSerializer<SerializableSaveData>>())).thenReturn(deserializationResult)

        helper.deserializeSaveData(data)
    }

    @Test(expected = SerializationException::class)
    fun `deserializeSaveData - throws SerializationException on engine exception`() {
        val data = ByteArray(50) { it.toByte() }
        whenever(serializationEngine.deserialize<SerializableSaveData>(any<ByteArray>(), any<SerializationContext>(), any<KSerializer<SerializableSaveData>>()))
            .thenThrow(RuntimeException("Engine error"))

        helper.deserializeSaveData(data)
    }

    @Test
    fun `deserializeProtobufData - returns SaveData on success`() {
        val serializable = createMinimalSerializable()
        val saveData = createMinimalSaveData()
        val data = ByteArray(50) { it.toByte() }

        val deserializationResult = DeserializationResult<SerializableSaveData>(
            data = serializable,
            format = SerializationFormat.PROTOBUF,
            compression = CompressionType.LZ4,
            checksumValid = true,
            deserializationTimeMs = 10,
            decompressionTimeMs = 5
        )
        whenever(serializationEngine.deserialize<SerializableSaveData>(any<ByteArray>(), any<SerializationContext>(), any<KSerializer<SerializableSaveData>>())).thenReturn(deserializationResult)
        whenever(saveDataConverter.fromSerializable(serializable)).thenReturn(saveData)

        val result = helper.deserializeProtobufData(data)

        assertNotNull(result)
        assertEquals(saveData.version, result!!.version)
    }

    @Test
    fun `deserializeProtobufData - returns null when deserialization fails`() {
        val data = ByteArray(50) { it.toByte() }
        val deserializationResult = DeserializationResult<SerializableSaveData>(
            data = null,
            format = SerializationFormat.PROTOBUF,
            compression = CompressionType.LZ4,
            checksumValid = false,
            deserializationTimeMs = 10,
            decompressionTimeMs = 5,
            error = RuntimeException("Corrupted")
        )
        whenever(serializationEngine.deserialize<SerializableSaveData>(any<ByteArray>(), any<SerializationContext>(), any<KSerializer<SerializableSaveData>>())).thenReturn(deserializationResult)

        val result = helper.deserializeProtobufData(data)

        assertNull(result)
    }

    @Test
    fun `deserializeProtobufData - returns null on exception`() {
        val data = ByteArray(50) { it.toByte() }
        whenever(serializationEngine.deserialize<SerializableSaveData>(any<ByteArray>(), any<SerializationContext>(), any<KSerializer<SerializableSaveData>>()))
            .thenThrow(RuntimeException("Parse error"))

        val result = helper.deserializeProtobufData(data)

        assertNull(result)
    }

    @Test
    fun `SerializationException - preserves message and cause`() {
        val cause = RuntimeException("root cause")
        val exception = SerializationException("test error", cause)

        assertEquals("test error", exception.message)
        assertEquals(cause, exception.cause)
    }

    @Test
    fun `SerializationException - works without cause`() {
        val exception = SerializationException("test error")
        assertEquals("test error", exception.message)
        assertNull(exception.cause)
    }

    @Test
    fun `serializeAndCompressSaveData - uses PROTOBUF format and LZ4 compression`() {
        val saveData = createMinimalSaveData()
        val serializable = createMinimalSerializable()
        whenever(saveDataConverter.toSerializable(saveData)).thenReturn(serializable)

        val serializationResult = SerializationResult(
            data = ByteArray(10),
            originalSize = 10,
            compressedSize = 8,
            format = SerializationFormat.PROTOBUF,
            compression = CompressionType.LZ4,
            checksum = ByteArray(32),
            serializationTimeMs = 1,
            compressionTimeMs = 1
        )
        whenever(serializationEngine.serialize(any<SerializableSaveData>(), any<SerializationContext>(), any<KSerializer<SerializableSaveData>>())).thenReturn(serializationResult)

        helper.serializeAndCompressSaveData(saveData)

        verify(serializationEngine).serialize(
            any<SerializableSaveData>(),
            argThat { ctx ->
                ctx.format == SerializationFormat.PROTOBUF && ctx.compression == CompressionType.LZ4 && ctx.includeChecksum
            },
            any<KSerializer<SerializableSaveData>>()
        )
    }
}
