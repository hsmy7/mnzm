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

    @Test
    fun `disciple round-trip preserves physique and affix ids`() {
        // 守卫：DiscipleSerializer 必须序列化体质/词条（recruitList/AI 弟子
        // 读档后丢失会导致玩家招募到无体质词条弟子）
        val original = SaveData(
            gameData = com.xianxia.sect.core.model.GameData(
                recruitList = listOf(
                    com.xianxia.sect.core.model.Disciple(
                        id = "recruit-1",
                        name = "张三",
                        physiqueIds = listOf("physique_a", "physique_b"),
                        affixIds = listOf("affix_c")
                    )
                )
            ),
            disciples = emptyList(),
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
            teams = emptyList(),
        )

        val bytes = NullSafeProtoBuf.protoBuf.encodeToByteArray(serializer<SaveData>(), original)
        val restored = NullSafeProtoBuf.protoBuf.decodeFromByteArray(serializer<SaveData>(), bytes)

        val recruit = restored.gameData.recruitList.first()
        assertEquals(listOf("physique_a", "physique_b"), recruit.physiqueIds)
        assertEquals(listOf("affix_c"), recruit.affixIds)
    }

    @Test
    fun `battle team fields round-trip preserves teams and initialized flag`() {
        // A3 守卫（2026-08-05）：battleTeams/usedTeamNumbers/battleTeamsInitialized
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
            teams = emptyList(),
        )

        val bytes = NullSafeProtoBuf.protoBuf.encodeToByteArray(serializer<SaveData>(), original)
        val restored = NullSafeProtoBuf.protoBuf.decodeFromByteArray(serializer<SaveData>(), bytes)

        assertEquals("battleTeams 往返保留", original.gameData.battleTeams, restored.gameData.battleTeams)
        assertEquals("usedTeamNumbers 往返保留", listOf(1, 3), restored.gameData.usedTeamNumbers)
        assertEquals("battleTeamsInitialized 往返保留", true, restored.gameData.battleTeamsInitialized)
    }

    @Test
    fun `protoBuf decode skips unknown field numbers instead of throwing`() {
        // A4 实证（2026-08-05）：kotlinx.serialization ProtoBuf 按 wire format
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
            teams = emptyList(),
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
