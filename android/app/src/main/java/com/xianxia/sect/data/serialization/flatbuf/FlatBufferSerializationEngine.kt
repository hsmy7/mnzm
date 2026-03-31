package com.xianxia.sect.data.serialization.flatbuf

import android.util.Log
import com.xianxia.sect.data.model.SaveData
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

object FlatBufferSerializationEngine {
    private const val TAG = "FlatBufferSerial"
    private const val SCHEMA_VERSION = 1
    private const val MAGIC_NUMBER: Short = 0x4642
    
    private val byteBufferPool = ByteBufferPool()
    
    fun serialize(saveData: SaveData): ByteArray {
        throw UnsupportedOperationException("Use UnifiedSerializationEngine with FLATBUFFERS format instead")
    }
    
    fun isFlatBufferData(data: ByteArray): Boolean {
        if (data.size < 4) return false
        val magic = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        return magic == MAGIC_NUMBER.toInt()
    }
    
    fun deserialize(data: ByteArray): SaveData? {
        throw UnsupportedOperationException("Use UnifiedSerializationEngine with FLATBUFFERS format instead")
    }
}
