package com.xianxia.sect.data.backup

import com.xianxia.sect.data.model.SaveData

/**
 * 存档序列化接口——将 SaveFileManager 与 SerializationModule 解耦，
 * 使 SaveFileManager 可独立测试。
 */
fun interface SaveSerializer {
    /** 序列化 + 压缩 SaveData 为 ByteArray */
    fun serializeAndCompressSaveData(data: SaveData): ByteArray
}
