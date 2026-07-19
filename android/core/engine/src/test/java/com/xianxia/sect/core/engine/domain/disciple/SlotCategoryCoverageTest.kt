package com.xianxia.sect.core.engine.domain.disciple

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
            SlotCategory.RESIDENCE_SLOT,      // scanListSlots()
            SlotCategory.WAREHOUSE_GARRISON,  // scanListSlots()
            SlotCategory.PATROL_SLOT,         // scanListSlots()
            SlotCategory.BLOOD_REFINEMENT,    // scanListSlots()
            SlotCategory.GARRISON_SLOT,       // scanListSlots()
            SlotCategory.BATTLE_TEAM,         // scanListSlots()
        )

        // EXPLORATION_TEAM 不持久化，UI 已主动过滤，无需扫描
        val intentionallyExcluded = setOf(
            SlotCategory.EXPLORATION_TEAM,
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
            SlotCategory.RESIDENCE_SLOT,      // residenceSlots.map
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
}
