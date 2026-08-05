package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.ResidenceSlot
import com.xianxia.sect.core.model.SlotCategory
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * 自动守卫：新增 [SlotCategory] 枚举值时，若忘记同步更新相关函数，测试将失败。
 *
 * ## 新增槽位系统的必改清单（2026-08-05 多槽位互斥根治后扩充为 8 处）
 *
 * 当你在 [SlotCategory] 添加了新的枚举值，测试会在此文件中报错。
 * 请同步更新：
 *
 * 1. [DiscipleAssignmentGate.scanAndRegister] — 读档重建时扫描新系统
 * 2. [DiscipleSlotCleanup.clearAllSlots] — 死亡/释放/换岗时清理新系统
 *    - 注意：住所 (RESIDENCE_SLOT) 是条件清理，仅 `includeResidence=true` 时清理，
 *      工作分配路径不会清理住所。如果新增的槽位也类似"被动不互斥"，请同步处理。
 * 3. 分配入口 — 事务内 `clearAllSlotsDataOnly`（防双槽位）+ 事务外
 *    `releaseDiscipleFromAllSlotsAtomic`/`confirmAssign` + 旧 occupant release/sync
 *    （参照 assignPatrolAtomic 的 pendingReleases 模式；清单式守卫会检查新入口文件）
 * 4. 本测试文件 — 将新 [SlotCategory] 加入下方对应的检查集合；若分配入口在新文件，
 *    加入清单式守卫的 entriesRequiringCleanup
 * 5. [DiscipleStatusService.buildSlotFlagsFor] + SlotFlags — 若槽位影响弟子状态推导，
 *    加标志并排入 deriveDiscipleStatus 优先级（有既有单测模式可仿照）
 * 6. [DiscipleStatusService.clearSlotsForReset] — 重置所有弟子时清理（若槽位持久化在 GameData）
 * 7. [com.xianxia.sect.core.engine.GameEngineSelfHealOps] — 读档双槽位自愈的
 *    collectSlotWinners 扫描 + rewriteWinnerInGameData 按赢家重写
 * 8. 若新槽位与工作共存（住所式被动不互斥）— 在下方 intentionalExcluded 显式声明并注释理由
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
            SlotCategory.PRODUCTION_SLOT,     // productionSlots.map（clearAllSlotsDataOnly 直接清理）
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

    /**
     * 清单式守卫：所有已知"任命弟子到槽位"的分配入口文件必须引用槽位清理调用。
     *
     * 背景：双槽位 Bug 的根因是多个分配入口写槽位前不做清理（Gate 只登记不阻止）。
     * 新增分配入口时必须接入清理（clearAllSlotsDataOnly / clearAllSlots /
     * releaseDiscipleFromAllSlotsAtomic / releaseDiscipleToIdleInside），
     * 否则本测试失败并列出缺清理的文件。
     *
     * 住所（RESIDENCE_SLOT）与工作共存是有意设计，不在清单内；
     * 战后自动填 garrison（occupySectRewards）非玩家任命入口，不在清单内。
     */
    @Test
    fun `all known assignment entries reference slot cleanup`() {
        val entriesRequiringCleanup = listOf(
            "com/xianxia/sect/core/GameEngineAtomicAssign.kt",          // 巡逻 3 入口
            "com/xianxia/sect/core/GameEngineCoordination.kt",           // 任务/血炼
            "com/xianxia/sect/core/engine/GameEngineSecretRealmOps.kt",  // 秘境出发
            "com/xianxia/sect/core/GameEngineBattleOps.kt",              // 世界驻守
            "com/xianxia/sect/core/engine/GameEngineWarehouseOps.kt",    // 仓库驻守
            "com/xianxia/sect/core/domain/disciple/DiscipleFacadeImpl.kt", // 亲传/藏经阁
            "com/xianxia/sect/core/domain/building/BuildingFacadeImpl.kt", // 生产槽新 API
            "com/xianxia/sect/core/domain/building/BuildingService.kt"     // 生产槽旧 API
        )
        val cleanupMarkers = listOf(
            "clearAllSlotsDataOnly", "clearAllSlots(", "releaseDiscipleFromAllSlotsAtomic",
            "releaseDiscipleToIdleInside"
        )

        val missing = entriesRequiringCleanup.filter { entry ->
            val file = File(ENGINE_SRC_MAIN_DIR, entry)
            assertTrue("源码文件不存在: $entry", file.exists())
            val content = file.readText()
            cleanupMarkers.none { it in content }
        }
        assertTrue(
            """
            |以下分配入口未引用任何槽位清理调用
            |（新增/修改分配入口必须接入清理，防双槽位）：
            |  $missing
            |
            |操作指引：
            |  1. 在写槽位的事务内调用 clearAllSlotsDataOnly（引擎层）/ clearAllSlots（Facade 层）
            |  2. 或调用 releaseDiscipleFromAllSlotsAtomic / releaseDiscipleToIdleInside
            |  3. 事务成功后释放/登记 gate（参照 assignPatrolAtomic 的 pendingReleases 模式）
            |  4. 若故意不互斥（如住所）→ 不要加入本清单，并在类注释说明理由
            """.trimMargin(),
            missing.isEmpty()
        )
    }

    companion object {
        /** Gradle 测试工作目录 = 模块根目录（core/engine/） */
        private val ENGINE_SRC_MAIN_DIR = File("src/main/java")
    }
}
