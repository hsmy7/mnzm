package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.model.BloodRefinementBonusTotal
import com.xianxia.sect.core.model.BloodRefinementPctTotal
import com.xianxia.sect.core.model.BloodRefinementProgress
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ManualProficiencyData
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffNestedTypesTest — 低频嵌套类型快照往返对拍（批次 1 剩余验收核心）。
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
}
