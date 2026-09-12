package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.model.BloodRefinementBonusTotal
import com.xianxia.sect.core.model.BloodRefinementPctTotal
import com.xianxia.sect.core.model.BloodRefinementProgress
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualProficiencyData
import com.xianxia.sect.core.model.Pill
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffNestedTypesTest — 低频嵌套类型快照往返对拍。
 *
 * 守护目标：C++ models.h 新增嵌套类型（血炼三件套/功法精通/矿脉槽位）与
 * Kotlin @Serializable 模型 JSON 快照**逐字段一致**（导出/导入往返）。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffNestedTypesTest {

    private val json = Json { encodeDefaults = true }

    private fun roundTrip(sample: NativeGameState): NativeGameState {
        val encoded = json.encodeToString(NativeGameState.serializer(), sample)
        assertTrue("C++ 导入失败", DiffRngBridge.nativeCoreImportState(encoded.encodeToByteArray()))
        val exported = DiffRngBridge.nativeCoreExportState()
        return json.decodeFromString(NativeGameState.serializer(), exported.decodeToString())
    }

    @Test
    fun `blood refinement nested types round trip`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val gd = GameData().apply {
            bloodRefinementBonusTotals = mapOf(
                "d-1" to BloodRefinementBonusTotal(
                    discipleId = "d-1", hpBonus = 100, physicalAttackBonus = 50,
                    magicAttackBonus = 30, speedBonus = 10
                )
            )
            bloodRefinementPctTotals = mapOf(
                "d-1" to BloodRefinementPctTotal(
                    discipleId = "d-1", hpBonusPct = 0.25,
                    physicalAttackBonusPct = 0.15, speedBonusPct = 0.05
                )
            )
            activeBloodRefinements = mapOf(
                "b-1" to BloodRefinementProgress(
                    discipleId = "d-2", discipleName = "李四", materialId = "mat-1",
                    materialName = "妖兽精血", startYear = 2, startMonth = 3,
                    durationMonths = 6, selectedStat = "hp", bonusPercent = 0.1
                )
            )
        }
        val decoded = roundTrip(NativeGameState(gameData = gd))
        val d = decoded.gameData
        assertEquals(1, d.bloodRefinementBonusTotals.size)
        assertEquals(100, d.bloodRefinementBonusTotals["d-1"]?.hpBonus)
        assertEquals(0.25, d.bloodRefinementPctTotals["d-1"]?.hpBonusPct ?: 0.0, 1e-12)
        assertEquals(1, d.activeBloodRefinements.size)
        assertEquals("妖兽精血", d.activeBloodRefinements["b-1"]?.materialName)
        assertEquals("hp", d.activeBloodRefinements["b-1"]?.selectedStat)
    }

    @Test
    fun `manual proficiency and mine slots round trip`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val gd = GameData().apply {
            manualProficiencies = mapOf(
                "d-1" to listOf(
                    ManualProficiencyData(
                        manualId = "m-1", manualName = "基础吐纳功",
                        proficiency = 55.5, maxProficiency = 100, level = 2, masteryLevel = 1
                    )
                )
            )
            spiritMineSlots = listOf(
                com.xianxia.sect.core.model.SpiritMineSlot(
                    index = 0, discipleId = "d-3", discipleName = "王五",
                    output = 170, buildingInstanceId = "mine-1"
                )
            )
        }
        val decoded = roundTrip(NativeGameState(gameData = gd))
        val d = decoded.gameData
        assertEquals(1, d.manualProficiencies.size)
        assertEquals(55.5, d.manualProficiencies["d-1"]?.get(0)?.proficiency ?: 0.0, 1e-12)
        assertEquals(2, d.manualProficiencies["d-1"]?.get(0)?.level)
        assertEquals(1, d.spiritMineSlots.size)
        assertEquals("王五", d.spiritMineSlots[0].discipleName)
        assertTrue(d.spiritMineSlots[0].isActive)
    }

    @Test
    fun `empty nested fields round trip`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val decoded = roundTrip(NativeGameState(gameData = GameData()))
        assertTrue(decoded.gameData.bloodRefinementPctTotals.isEmpty())
        assertTrue(decoded.gameData.manualProficiencies.isEmpty())
        assertTrue(decoded.gameData.spiritMineSlots.isEmpty())
    }

    @Test
    fun `secret realm state machine round trip`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        val decoded = roundTrip(NativeGameState(gameData = secretRealmGameData()))
        val d = decoded.gameData
        assertEquals("sr-1", d.secretRealmState.id)
        assertEquals(12.5f, d.secretRealmState.x)
        assertEquals(3, d.secretRealmState.spawnYear)
        assertEquals("sr-1", d.secretRealmSession.secretRealmId)
        assertEquals(12, d.secretRealmSession.stamina)
        assertEquals("击退妖兽", d.secretRealmSession.resultMessage)
        assertEquals(1, d.secretRealmSession.members.size)
        assertEquals("张三", d.secretRealmSession.members[0].name)
        assertTrue(d.secretRealmSession.members[0].isDying)
        assertEquals(500L, d.secretRealmSession.backpack.spiritStones)
        assertEquals(3, d.secretRealmSession.backpack.pills[0].quantity)
        assertEquals("BEAST_ENCOUNTER", d.secretRealmSession.currentEvent?.eventType)
        assertEquals("烈焰虎", d.secretRealmSession.currentEvent?.params?.beastTypeName)
        assertTrue(d.secretRealmSession.currentEvent?.params?.ambushSucceeded == true)
        assertEquals("兽骨", d.secretRealmSession.currentEvent?.params?.itemRewards?.get(0)?.name)
        assertEquals(1, d.secretRealmAITeams.size)
        assertEquals("落霞宗", d.secretRealmAITeams[0].sectName)
        assertEquals("ai-d-1", d.secretRealmAITeams[0].members[0].discipleId)
    }

    /** 构造完整秘境状态机样本（secret realm round trip 用例专用）。 */
    private fun secretRealmGameData(): GameData = GameData().apply {
        secretRealmState = com.xianxia.sect.core.model.SecretRealmState(
            id = "sr-1", name = "远古秘境", x = 12.5f, y = -3f,
            spawnYear = 3, spawnMonth = 7, spriteIndex = 2
        )
        secretRealmSession = com.xianxia.sect.core.model.SecretRealmExplorationSession(
            secretRealmId = "sr-1",
            members = listOf(
                com.xianxia.sect.core.model.SecretRealmMemberState(
                    discipleId = "d-1", name = "张三", portraitRes = "",
                    realm = 5, currentHp = 80, isDying = true, maxHp = 120
                )
            ),
            stamina = 12,
            backpack = com.xianxia.sect.core.model.SecretRealmBackpack(
                spiritStones = 500,
                pills = listOf(Pill(id = "pill-1", name = "回气丹", rarity = 1, quantity = 3))
            ),
            currentEvent = com.xianxia.sect.core.model.SecretRealmEventRecord(
                eventType = "BEAST_ENCOUNTER",
                title = "遭遇妖兽",
                options = listOf(
                    com.xianxia.sect.core.model.SecretRealmOption(label = "战斗", staminaCost = 1)
                ),
                chosenOptionIndex = 1,
                params = com.xianxia.sect.core.model.SecretRealmEventParams(
                    beastTypeName = "烈焰虎", beastRealm = 6, beastCount = 2,
                    ambushSucceeded = true, spiritStones = 100,
                    itemRewards = listOf(
                        com.xianxia.sect.core.model.SecretRealmRewardItem(
                            type = "material", name = "兽骨", rarity = 2, quantity = 2
                        )
                    )
                ),
                absoluteMonth = 43
            ),
            startYear = 3, startMonth = 7,
            resultMessage = "击退妖兽"
        )
        secretRealmAITeams = listOf(
            com.xianxia.sect.core.model.SecretRealmAITeam(
                id = "ai-1", sectId = "s-9", sectName = "落霞宗", sectLevel = 2,
                members = listOf(
                    com.xianxia.sect.core.model.SecretRealmAIMember(
                        discipleId = "ai-d-1", name = "赵四", realm = 4
                    )
                )
            )
        )
    }
}
