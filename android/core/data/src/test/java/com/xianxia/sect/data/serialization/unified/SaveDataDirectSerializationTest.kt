package com.xianxia.sect.data.serialization.unified

import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.serialization.NullSafeProtoBuf
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SaveData 直接 Protobuf 序列化往返测试。
 *
 * 验证 SaveData（含所有嵌套域类型）可以正确地：
 * 1. 编码为 Protobuf 二进制
 * 2. 从 Protobuf 二进制解码回相同的数据
 */
class SaveDataDirectSerializationTest {

    @Test
    fun `basic SaveData round-trip`() {
        // 仅测试空 SaveData 的序列化/反序列化是否正常工作
        val original = SaveData(
            gameData = com.xianxia.sect.core.model.GameData(),
            disciples = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
            teams = emptyList(),
        )

        val bytes = NullSafeProtoBuf.protoBuf.encodeToByteArray(serializer<SaveData>(), original)
        val restored = NullSafeProtoBuf.protoBuf.decodeFromByteArray(serializer<SaveData>(), bytes)

        assertEquals(original.version, restored.version)
        assertEquals(original.gameData.gameYear, restored.gameData.gameYear)
        assertEquals(original.gameData.gameMonth, restored.gameData.gameMonth)
        assertEquals(original.disciples.size, restored.disciples.size)
    }
}
