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
                    )

        val bytes = NullSafeProtoBuf.protoBuf.encodeToByteArray(serializer<SaveData>(), original)
        val restored = NullSafeProtoBuf.protoBuf.decodeFromByteArray(serializer<SaveData>(), bytes)

        assertEquals(original.version, restored.version)
        assertEquals(original.gameData.gameYear, restored.gameData.gameYear)
        assertEquals(original.gameData.gameMonth, restored.gameData.gameMonth)
        assertEquals(original.disciples.size, restored.disciples.size)
        // G01 空档新字段默认值往返（旧档/新档无 gacha 段时必须仍可解码）
        assertEquals(emptyMap<String, Int>(), restored.gameData.gachaFragmentCounts)
        assertEquals(emptyMap<String, Int>(), restored.gameData.gachaStarMap)
        assertEquals(emptyMap<String, Int>(), restored.gameData.gachaPityCounters)
        assertEquals(emptyList<com.xianxia.sect.core.model.GachaHistoryEntry>(), restored.gameData.gachaHistory)
    }

    @Test
    fun `gacha fields and templateId round-trip`() {
        // G01：碎片/星级/保底/历史 + 弟子 templateId 写读往返（协议字段先落验收）
        val historyEntry = com.xianxia.sect.core.model.GachaHistoryEntry(
            poolId = "standard",
            category = "pity",
            templateId = "zhouming",
            count = 5,
            isPity = true,
            gameMonthIndex = 14,
        )
        val original = SaveData(
            gameData = com.xianxia.sect.core.model.GameData(
                gachaFragmentCounts = mapOf("zhouming" to 7, "suqing" to 0),
                gachaStarMap = mapOf("zhouming" to 1),
                gachaPityCounters = mapOf("standard" to 9),
                gachaHistory = listOf(historyEntry),
            ),
            disciples = listOf(
                com.xianxia.sect.core.model.Disciple(
                    id = "1",
                    name = "周明",
                    templateId = "zhouming",
                ),
            ),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
        )

        val bytes = NullSafeProtoBuf.protoBuf.encodeToByteArray(serializer<SaveData>(), original)
        val restored = NullSafeProtoBuf.protoBuf.decodeFromByteArray(serializer<SaveData>(), bytes)

        assertEquals(original.gameData.gachaFragmentCounts, restored.gameData.gachaFragmentCounts)
        assertEquals(original.gameData.gachaStarMap, restored.gameData.gachaStarMap)
        assertEquals(original.gameData.gachaPityCounters, restored.gameData.gachaPityCounters)
        assertEquals(listOf(historyEntry), restored.gameData.gachaHistory)
        assertEquals("zhouming", restored.disciples.single().templateId)
    }

    @Test
    fun `battle team fields round-trip preserves teams and initialized flag`() {
        // battleTeams/usedTeamNumbers/battleTeamsInitialized
        // 持久化后必须进入 proto——读档不再清空玩家出战队伍
        val original = SaveData(
            gameData = com.xianxia.sect.core.model.GameData(
                battleTeams = listOf(
                    com.xianxia.sect.core.model.BattleTeam(
                        name = "主力队",
                        teamNumber = 1,
                        slots = listOf(
                            com.xianxia.sect.core.model.BattleTeamSlot(
                                index = 0, discipleId = "d1", discipleName = "大弟子",
                                slotType = com.xianxia.sect.core.model.BattleSlotType.ELDER
                            )
                        )
                    )
                ),
                usedTeamNumbers = listOf(1, 3),
                battleTeamsInitialized = true
            ),
            disciples = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
                    )

        val bytes = NullSafeProtoBuf.protoBuf.encodeToByteArray(serializer<SaveData>(), original)
        val restored = NullSafeProtoBuf.protoBuf.decodeFromByteArray(serializer<SaveData>(), bytes)

        assertEquals("battleTeams 往返保留", original.gameData.battleTeams, restored.gameData.battleTeams)
        assertEquals("usedTeamNumbers 往返保留", listOf(1, 3), restored.gameData.usedTeamNumbers)
        assertEquals("battleTeamsInitialized 往返保留", true, restored.gameData.battleTeamsInitialized)
    }

    @Test
    fun `protoBuf decode skips unknown field numbers instead of throwing`() {
        // kotlinx.serialization ProtoBuf 按 wire format
        // 规范跳过未知字段号——旧版 App 读新版云档（新增字段）不抛异常，
        // 缺失字段取默认值尽力解码。此测试固化该行为，防止未来库升级改变。
        val original = SaveData(
            gameData = com.xianxia.sect.core.model.GameData(
                sectName = "测试宗",
                gameYear = 7
            ),
            disciples = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
                    )
        val base = NullSafeProtoBuf.protoBuf.encodeToByteArray(serializer<SaveData>(), original)

        // 追加一个未知字段（字段号 999，wire type 2 length-delimited，payload "TEST"）：
        // tag = 999*8+2 = 7994 → varint [0xBA, 0x1F]；length=4 → [0x04]；payload
        val unknownField = byteArrayOf(
            0xBA.toByte(), 0x1F, 0x04, 'T'.code.toByte(), 'E'.code.toByte(),
            'S'.code.toByte(), 'T'.code.toByte()
        )
        val withUnknown = base + unknownField

        val restored = NullSafeProtoBuf.protoBuf.decodeFromByteArray(serializer<SaveData>(), withUnknown)

        assertEquals("已知字段正常解码", "测试宗", restored.gameData.sectName)
        assertEquals("已知字段正常解码", 7, restored.gameData.gameYear)
    }
}
