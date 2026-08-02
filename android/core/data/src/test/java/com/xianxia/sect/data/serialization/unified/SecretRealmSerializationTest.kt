package com.xianxia.sect.data.serialization.unified

import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MaterialCategory
import com.xianxia.sect.core.model.SecretRealmAITeam
import com.xianxia.sect.core.model.SecretRealmBackpack
import com.xianxia.sect.core.model.SecretRealmEventParams
import com.xianxia.sect.core.model.SecretRealmEventRecord
import com.xianxia.sect.core.model.SecretRealmEventType
import com.xianxia.sect.core.model.SecretRealmExplorationSession
import com.xianxia.sect.core.model.SecretRealmMemberState
import com.xianxia.sect.core.model.SecretRealmOption
import com.xianxia.sect.core.model.SecretRealmState
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.serialization.NullSafeProtoBuf
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 远古秘境全模型 Protobuf 序列化往返测试
 * （断线续玩数据一致性守卫）。
 */
class SecretRealmSerializationTest {

    @Test
    fun `secret realm session round-trip preserves dying member and backpack`() {
        val session = sampleSession()
        val save = SaveData(
            gameData = com.xianxia.sect.core.model.GameData(
                secretRealmSession = session
            ),
            disciples = emptyList(), pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(), seeds = emptyList(), teams = emptyList()
        )
        val bytes = NullSafeProtoBuf.protoBuf.encodeToByteArray(
            serializer<SaveData>(), save)
        val restored = NullSafeProtoBuf.protoBuf.decodeFromByteArray(
            serializer<SaveData>(), bytes)
        val session2 = restored.gameData.secretRealmSession

        assertEquals(session.secretRealmId, session2.secretRealmId)
        assertEquals(session.stamina, session2.stamina)
        assertEquals(2, session2.members.size)
        val dying = session2.members.first { it.discipleId == "1" }
        assertTrue(dying.isDying)
        assertEquals(1, dying.currentHp)
        assertEquals(2500L, session2.backpack.spiritStones)
        assertEquals(1, session2.backpack.materials.size)
        assertEquals("虎骨", session2.backpack.materials.first().name)
        assertNotNull(session2.currentEvent)
        val event = session2.currentEvent ?: return
        assertEquals(SecretRealmEventType.BEAST_ENCOUNTER.name, event.eventType)
        assertEquals(3, event.options.size)
        assertTrue(event.params.ambushSucceeded)
        assertEquals(3, event.params.beastCount)
        // 非默认值 beastLayer（7）往返保持（@EncodeDefault(ALWAYS) 守卫）
        assertEquals(7, event.params.beastLayer)
        assertEquals("你方一击得手！", session2.resultMessage)
    }

    private fun sampleSession(): SecretRealmExplorationSession =
        SecretRealmExplorationSession(
        secretRealmId = "realm_1",
        members = listOf(
            SecretRealmMemberState(
                discipleId = "1", name = "张三",
                portraitRes = "female_disciple_1",
                realm = 5, realmName = "化神", currentHp = 1, isDying = true
            ),
            SecretRealmMemberState(
                discipleId = "2", name = "李四", currentHp = -1
            )
        ),
        stamina = 13,
        backpack = SecretRealmBackpack(
            spiritStones = 2500L,
            materials = listOf(
                Material(
                    id = "m1", name = "虎骨", rarity = 2,
                    description = "虎妖的骨骼",
                    category = MaterialCategory.BEAST_BONE,
                    quantity = 1
                )
            )
        ),
        currentEvent = SecretRealmEventRecord(
            eventType = SecretRealmEventType.BEAST_ENCOUNTER.name,
            title = "遭遇妖兽",
            description = "途中遭遇妖兽！狂暴虎妖 × 3，" +
                "境界：化神",
            options = listOf(
                SecretRealmOption("远离妖兽", "小心避让"),
                SecretRealmOption("发起战斗", "正面交锋"),
                SecretRealmOption("尝试偷袭", "伺机偷袭")
            ),
            params = SecretRealmEventParams(
                beastTypeName = "虎妖", beastRealm = 5, beastCount = 3,
                ambushSucceeded = true, beastLayer = 7
            )
        ),
        resultMessage = "你方一击得手！"
    )

    @Test
    fun `realm state and ai teams round-trip`() {
        val state = SecretRealmState(
            id = "realm_9", name = "远古秘境", x = 123f, y = 456f,
            spawnYear = 33, spawnMonth = 7, spriteIndex = 2
        )
        val aiTeams = listOf(
            SecretRealmAITeam(
                id = "team_1", sectId = "sect1", sectName = "青云宗",
                members = listOf(
                    com.xianxia.sect.core.model.SecretRealmAIMember(
                        discipleId = "a1", name = "剑尘",
                        portraitRes = "male_disciple_1", realm = 3
                    )
                )
            )
        )
        val save = SaveData(
            gameData = com.xianxia.sect.core.model.GameData(
                secretRealmState = state,
                secretRealmCooldownYear = 77,
                secretRealmAITeams = aiTeams
            ),
            disciples = emptyList(), pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(), seeds = emptyList(), teams = emptyList()
        )
        val bytes = NullSafeProtoBuf.protoBuf.encodeToByteArray(
            serializer<SaveData>(), save)
        val restored = NullSafeProtoBuf.protoBuf.decodeFromByteArray(
            serializer<SaveData>(), bytes)

        val gd = restored.gameData
        assertTrue(gd.secretRealmState.exists)
        assertEquals("realm_9", gd.secretRealmState.id)
        assertEquals(123f, gd.secretRealmState.x, 0.01f)
        assertEquals(77, gd.secretRealmCooldownYear)
        assertEquals(1, gd.secretRealmAITeams.size)
        assertEquals("青云宗", gd.secretRealmAITeams.first().sectName)
        assertEquals(
            "剑尘", gd.secretRealmAITeams.first().members.first().name
        )
    }

    @Test
    fun `old save without secret realm fields decodes to empty defaults`() {
        // 旧存档没有秘境字段 → 解码为默认空状态
        val save = SaveData(
            gameData = com.xianxia.sect.core.model.GameData(),
            disciples = emptyList(), pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(), seeds = emptyList(), teams = emptyList()
        )
        val bytes = NullSafeProtoBuf.protoBuf.encodeToByteArray(
            serializer<SaveData>(), save)
        val restored = NullSafeProtoBuf.protoBuf.decodeFromByteArray(
            serializer<SaveData>(), bytes)

        assertFalse(restored.gameData.secretRealmState.exists)
        assertFalse(restored.gameData.secretRealmSession.isActive)
        assertEquals(0, restored.gameData.secretRealmCooldownYear)
        assertTrue(restored.gameData.secretRealmAITeams.isEmpty())
    }
}
