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
import com.xianxia.sect.core.model.SecretRealmRewardItem
import com.xianxia.sect.core.model.SecretRealmState
import com.xianxia.sect.core.model.Seed
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
            herbs = emptyList(), seeds = emptyList()
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
        // 种子往返 + totalItemCount 含种子
        assertEquals(1, session2.backpack.seeds.size)
        assertEquals("聚灵草种", session2.backpack.seeds.first().name)
        assertEquals(2, session2.backpack.totalItemCount)
        assertNotNull(session2.currentEvent)
        val event = session2.currentEvent ?: return
        assertEquals(SecretRealmEventType.BEAST_ENCOUNTER.name, event.eventType)
        assertEquals(3, event.options.size)
        // 选项默认体力消耗 1 往返保持（缺省字段读默认值）
        assertTrue(event.options.all { it.staminaCost == 1 })
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
            ),
            seeds = listOf(
                Seed(
                    id = "s1", name = "聚灵草种", rarity = 2,
                    description = "聚灵草的种子", growTime = 3, yield = 2, quantity = 3
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
            herbs = emptyList(), seeds = emptyList()
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
    fun `ruin events round-trip preserves stamina cost and item rewards`() {
        val baseSession = SecretRealmExplorationSession(
            secretRealmId = "realm_2",
            members = listOf(
                SecretRealmMemberState(discipleId = "1", name = "张三", currentHp = -1)
            ),
            stamina = 15,
            currentEvent = SecretRealmEventRecord(
                eventType = SecretRealmEventType.RUIN_EXPLORE.name,
                title = "发现遗迹",
                description = "发现未知遗迹可能存在未知宝物",
                options = listOf(
                    SecretRealmOption("直接离开", ""),
                    SecretRealmOption("简单搜寻", ""),
                    SecretRealmOption("仔细搜寻", "", staminaCost = 2)
                )
            )
        )
        val explore = roundTrip(baseSession)
        val event = explore.currentEvent ?: return
        assertEquals(SecretRealmEventType.RUIN_EXPLORE.name, event.eventType)
        // 非默认值 staminaCost=2 往返保持（@EncodeDefault(ALWAYS) 守卫）
        assertEquals(2, event.options[2].staminaCost)
        // 默认值 1 往返保持
        assertEquals(1, event.options[0].staminaCost)
        assertEquals(1, event.options[1].staminaCost)

        val resultSession = baseSession.copy(
            currentEvent = SecretRealmEventRecord(
                eventType = SecretRealmEventType.RUIN_RESULT.name,
                title = "发现秘宝",
                description = "发现物品：青锋剑",
                options = listOf(SecretRealmOption("继续前进", "")),
                params = SecretRealmEventParams(
                    itemRewards = listOf(
                        SecretRealmRewardItem(
                            type = "equipment", itemId = "e1",
                            name = "青锋剑", rarity = 2, quantity = 1
                        )
                    )
                )
            )
        )
        val result = roundTrip(resultSession)
        val resultEvent = result.currentEvent ?: return
        assertEquals(SecretRealmEventType.RUIN_RESULT.name, resultEvent.eventType)
        assertEquals(1, resultEvent.params.itemRewards.size)
        assertEquals("青锋剑", resultEvent.params.itemRewards.first().name)
        assertEquals("equipment", resultEvent.params.itemRewards.first().type)
    }

    @Test
    fun `direction event round-trips options and stamina cost`() {
        val session = SecretRealmExplorationSession(
            secretRealmId = "realm_3",
            members = listOf(
                SecretRealmMemberState(discipleId = "1", name = "张三", currentHp = -1)
            ),
            stamina = 12,
            currentEvent = SecretRealmEventRecord(
                eventType = SecretRealmEventType.DIRECTION_CHOICE.name,
                title = "探索方向",
                description = "你方击退了1只虎妖，请选择探索方向",
                options = listOf(
                    SecretRealmOption("向左走", ""),
                    SecretRealmOption("走中间", ""),
                    SecretRealmOption("向右走", "")
                )
            )
        )
        val restored = roundTrip(session)
        val event = restored.currentEvent ?: return
        assertEquals(SecretRealmEventType.DIRECTION_CHOICE.name, event.eventType)
        assertEquals("探索方向", event.title)
        assertEquals("你方击退了1只虎妖，请选择探索方向", event.description)
        assertEquals(listOf("向左走", "走中间", "向右走"), event.options.map { it.label })
        // 方向选项默认体力消耗 1 往返保持（@EncodeDefault(ALWAYS) 守卫）
        assertTrue(event.options.all { it.staminaCost == 1 })
    }

    /** 秘境会话 → SaveData → 往返 → 恢复会话 */
    private fun roundTrip(session: SecretRealmExplorationSession): SecretRealmExplorationSession {
        val save = SaveData(
            gameData = com.xianxia.sect.core.model.GameData(secretRealmSession = session),
            disciples = emptyList(), pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(), seeds = emptyList()
        )
        val bytes = NullSafeProtoBuf.protoBuf.encodeToByteArray(serializer<SaveData>(), save)
        val restored = NullSafeProtoBuf.protoBuf.decodeFromByteArray(serializer<SaveData>(), bytes)
        return restored.gameData.secretRealmSession
    }

    @Test
    fun `ai sect encounter event round-trips params and ai members`() {
        val session = SecretRealmExplorationSession(
            secretRealmId = "realm_4",
            members = listOf(
                SecretRealmMemberState(discipleId = "1", name = "张三", currentHp = -1)
            ),
            stamina = 10,
            currentEvent = SecretRealmEventRecord(
                eventType = SecretRealmEventType.AI_SECT_ENCOUNTER.name,
                title = "遭遇青云宗探索队伍",
                description = "前方发现青云宗的探索队伍，狭路相逢",
                options = listOf(
                    SecretRealmOption("向左避让", ""),
                    SecretRealmOption("与之交战", ""),
                    SecretRealmOption("向右避让", "")
                ),
                params = SecretRealmEventParams(
                    aiSectId = "sect1",
                    aiSectName = "青云宗",
                    // 非默认值 2 往返保持（@EncodeDefault(ALWAYS) 守卫）
                    aiSectLevel = 2,
                    aiMembers = listOf(
                        com.xianxia.sect.core.model.SecretRealmAIMember(
                            discipleId = "a1", name = "剑尘",
                            portraitRes = "male_disciple_1", realm = 3
                        )
                    )
                )
            )
        )
        val restored = roundTrip(session)
        val event = restored.currentEvent ?: return
        assertEquals(SecretRealmEventType.AI_SECT_ENCOUNTER.name, event.eventType)
        assertEquals("遭遇青云宗探索队伍", event.title)
        assertEquals("sect1", event.params.aiSectId)
        assertEquals("青云宗", event.params.aiSectName)
        assertEquals(2, event.params.aiSectLevel)
        assertEquals(1, event.params.aiMembers.size)
        assertEquals("剑尘", event.params.aiMembers.first().name)
        assertEquals(3, event.params.aiMembers.first().realm)
        // 三选项默认体力 1 往返保持
        assertTrue(event.options.all { it.staminaCost == 1 })
    }

    @Test
    fun `ai team round-trips sectLevel`() {
        val aiTeams = listOf(
            SecretRealmAITeam(
                id = "team_1", sectId = "sect1", sectName = "青云宗",
                // 非默认值 3（顶级）往返保持（@EncodeDefault(ALWAYS) 守卫）
                sectLevel = 3,
                members = listOf(
                    com.xianxia.sect.core.model.SecretRealmAIMember(
                        discipleId = "a1", name = "剑尘", realm = 5
                    )
                )
            )
        )
        val save = SaveData(
            gameData = com.xianxia.sect.core.model.GameData(secretRealmAITeams = aiTeams),
            disciples = emptyList(), pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(), seeds = emptyList()
        )
        val bytes = NullSafeProtoBuf.protoBuf.encodeToByteArray(serializer<SaveData>(), save)
        val restored = NullSafeProtoBuf.protoBuf.decodeFromByteArray(serializer<SaveData>(), bytes)
        val team = restored.gameData.secretRealmAITeams.first()
        assertEquals("sect1", team.sectId)
        assertEquals("青云宗", team.sectName)
        assertEquals(3, team.sectLevel)
        assertEquals(1, team.members.size)
        // 缺省档 sectLevel 默认 0（小型），旧档兼容
        assertTrue(SecretRealmAITeam(sectId = "sect2", sectName = "万剑宗").sectLevel == 0)
    }

    @Test
    fun `old save without secret realm fields decodes to empty defaults`() {
        // 旧存档没有秘境字段 → 解码为默认空状态
        val save = SaveData(
            gameData = com.xianxia.sect.core.model.GameData(),
            disciples = emptyList(), pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(), seeds = emptyList()
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
