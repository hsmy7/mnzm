package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ResidenceSlot
import com.xianxia.sect.core.model.SlotCategory
import org.junit.Assert.*
import org.junit.Test

/**
 * 自动守卫：新增 [SlotCategory] 枚举值时，若忘记同步更新相关函数，测试将失败。
 *
 * ## 新增槽位系统的必改清单
 *
 * 当你在 [SlotCategory] 添加了新的枚举值，测试会在此文件中报错。
 * 请同步更新以下 4 处：
 *
 * 1. [DiscipleAssignmentGate.scanAndRegister] — 读档重建时扫描新系统
 * 2. [DiscipleSlotCleanup.clearAllSlots] — 死亡/释放时清理新系统
 *    - 注意：住所 (RESIDENCE_SLOT) 是条件清理，仅 `includeResidence=true` 时清理，
 *      工作分配路径不会清理住所。如果新增的槽位也类似"被动不互斥"，请同步处理。
 * 3. 分配入口 — 调用 `releaseDiscipleFromAllSlotsAtomic` + `confirmAssign`
 * 4. 本测试文件 — 将新 [SlotCategory] 加入下方对应的检查集合
 */
class SlotCategoryCoverageTest {

    @Test
    fun `all SlotCategory values are covered by scanAndRegister`() {
        val allCategories = SlotCategory.values().toSet()

        // scanAndRegister 覆盖的槽位类别
        val coveredByScan = setOf(
            SlotCategory.ELDER_POSITION,      // scanElderSlots()
            SlotCategory.PRODUCTION_SLOT,     // scanProductionSlots()
            SlotCategory.SPIRIT_MINE,         // scanListSlots()
            SlotCategory.LIBRARY_SLOT,        // scanListSlots()
            SlotCategory.WAREHOUSE_GARRISON,  // scanListSlots()
            SlotCategory.PATROL_SLOT,         // scanListSlots()
            SlotCategory.BLOOD_REFINEMENT,    // scanListSlots()
            SlotCategory.GARRISON_SLOT,       // scanListSlots()
            SlotCategory.BATTLE_TEAM,         // scanListSlots()
        )

        // EXPLORATION_TEAM 不持久化，UI 已主动过滤，无需扫描
        // RESIDENCE_SLOT 有意不在门卫中——住所与工作槽位共存，不互斥
        val intentionallyExcluded = setOf(
            SlotCategory.EXPLORATION_TEAM,
            SlotCategory.RESIDENCE_SLOT,
        )

        val missing = allCategories - coveredByScan - intentionallyExcluded
        assertTrue(
            """
            |新增 SlotCategory 未在 scanAndRegister 中覆盖！
            |
            |以下类别需要添加到 DiscipleAssignmentGate.scanAndRegister 的扫描逻辑中：
            |  $missing
            |
            |操作指引：
            |  1. 如果该槽位数据存放在 GameData 中 → 在 scanListSlots() 中添加 .forEach 扫描
            |  2. 如果该槽位数据独立存储 → 在 scanAndRegister() 中独立处理
            |  3. 如果故意不扫描（如 EXPLORATION_TEAM）→ 添加到本测试的 intentionallyExcluded 集合
            """.trimMargin(),
            missing.isEmpty()
        )
    }

    @Test
    fun `all SlotCategory values are covered by DiscipleSlotCleanup`() {
        val allCategories = SlotCategory.values().toSet()

        // DiscipleSlotCleanup.clearAllSlots 覆盖的槽位类别
        val coveredByCleanup = setOf(
            SlotCategory.ELDER_POSITION,      // clearElderSlots()
            SlotCategory.SPIRIT_MINE,         // spiritMineSlots.map
            SlotCategory.LIBRARY_SLOT,        // librarySlots.map
            SlotCategory.RESIDENCE_SLOT,      // residenceSlots.map（条件性：仅 includeResidence=true 时清理）
            SlotCategory.PATROL_SLOT,         // patrolSlots.map
            SlotCategory.WAREHOUSE_GARRISON,  // warehouseGarrisons.map
            SlotCategory.BATTLE_TEAM,         // battleTeams.map
            SlotCategory.GARRISON_SLOT,       // worldMapSects.map
            SlotCategory.BLOOD_REFINEMENT,    // activeBloodRefinements
            SlotCategory.PRODUCTION_SLOT,     // 独立 Repository 清理
        )

        // 同 scanAndRegister 的理由
        val intentionallyExcluded = setOf(
            SlotCategory.EXPLORATION_TEAM,
        )

        val missing = allCategories - coveredByCleanup - intentionallyExcluded
        assertTrue(
            """
            |新增 SlotCategory 未在 DiscipleSlotCleanup.clearAllSlots 中覆盖！
            |
            |以下类别需要添加到 DiscipleSlotCleanup.clearAllSlots 的清理逻辑中：
            |  $missing
            |
            |操作指引：
            |  1. 在 clearAllSlots() 中添加对应列表的 .map 清理
            |  2. 如果故意不清理（如 EXPLORATION_TEAM）→ 添加到本测试的 intentionallyExcluded 集合
            """.trimMargin(),
            missing.isEmpty()
        )
    }

    @Test
    fun `clearAllSlots respects includeResidence parameter`() {
        val gate = DiscipleAssignmentGate(DiscipleAssignmentRegistry())
        val cleanup = DiscipleSlotCleanup(gate)
        val discipleId = "1"
        val data = GameData(
            residenceSlots = listOf(
                ResidenceSlot(discipleId = discipleId, discipleName = "Test")
            )
        )

        // includeResidence=false（默认）：住所不清理
        val resultDefault = cleanup.clearAllSlots(data, discipleId)
        assertEquals("默认不清住所", discipleId, resultDefault.residenceSlots[0].discipleId)

        // includeResidence=true：住所清理
        val resultWithRes = cleanup.clearAllSlots(data, discipleId, includeResidence = true)
        assertEquals("includeResidence=true 应清住所", "", resultWithRes.residenceSlots[0].discipleId)
    }
}
