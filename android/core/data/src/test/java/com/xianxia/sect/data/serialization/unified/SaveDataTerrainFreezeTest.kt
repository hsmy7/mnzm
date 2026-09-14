package com.xianxia.sect.data.serialization.unified

import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.serialization.NullSafeProtoBuf
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 地图冻结（WS-5b）云存档不丢地形——SaveData Protobuf 往返测试。
 *
 * 堵死 `@Transient` + heavy_data 侧车的丢数据路径（云上传链无 heavy 补偿）：
 * 地形段必须作为 GameData 的常规 @ProtoNumber 字段进入 SaveData ProtoBuf
 * 字节流（本地 Room 路径与云路径共用同一 SaveData 载体）。
 *
 * 覆盖（batch-W4C §5.2）：
 * 1. 存读往返：含地形段的 SaveData 编码 → 解码 ⇒ 地形段与版本戳逐位一致；
 * 2. 无段省略：空段（默认值）往返后保持无段（encodeDefaults=false 语义）；
 * 3. 大段：16384（128²生产规模）整数段往返逐位一致。
 */
class SaveDataTerrainFreezeTest {

    @Test
    fun `terrain segment round-trips through SaveData proto`() {
        val terrain = listOf(0, 1, 1, 5, 5, 5, 10, 9, 8, 7) // 含全部真实瓦片类型样例
        val original = SaveData(
            gameData = GameData(
                mapSeed = 424242,
                mapGenVersion = 1,
                terrainTiles = terrain
            ),
            disciples = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList()
        )
        val bytes = NullSafeProtoBuf.protoBuf.encodeToByteArray(serializer<SaveData>(), original)
        val restored = NullSafeProtoBuf.protoBuf.decodeFromByteArray(serializer<SaveData>(), bytes)

        assertEquals("mapSeed 往返一致", 424242, restored.gameData.mapSeed)
        assertEquals("mapGenVersion 往返一致", 1, restored.gameData.mapGenVersion)
        assertEquals("地形段逐位一致", terrain, restored.gameData.terrainTiles)
    }

    @Test
    fun `empty segment stays absent after round-trip`() {
        val original = SaveData(
            gameData = GameData(mapSeed = 7),
            disciples = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList()
        )
        val bytes = NullSafeProtoBuf.protoBuf.encodeToByteArray(serializer<SaveData>(), original)
        val restored = NullSafeProtoBuf.protoBuf.decodeFromByteArray(serializer<SaveData>(), bytes)

        assertEquals("无段档 mapGenVersion 保持 0", 0, restored.gameData.mapGenVersion)
        assertTrue("无段档 terrainTiles 保持空", restored.gameData.terrainTiles.isEmpty())
    }

    @Test
    fun `production-size segment round-trips bitwise`() {
        // 128² 生产规模确定性段（伪随机填充，覆盖全值域）
        val terrain = List(128 * 128) { i -> (i * 2654435761L).toInt() % 11 }
        val original = SaveData(
            gameData = GameData(
                mapSeed = 20260915,
                mapGenVersion = 1,
                terrainTiles = terrain
            ),
            disciples = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList()
        )
        val bytes = NullSafeProtoBuf.protoBuf.encodeToByteArray(serializer<SaveData>(), original)
        val restored = NullSafeProtoBuf.protoBuf.decodeFromByteArray(serializer<SaveData>(), bytes)

        assertEquals(terrain, restored.gameData.terrainTiles)
    }
}
