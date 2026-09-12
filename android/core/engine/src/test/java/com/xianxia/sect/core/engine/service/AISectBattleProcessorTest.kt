package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.engine.mockSmart
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.SectDetail
import com.xianxia.sect.core.model.SectWarehouse
import com.xianxia.sect.core.model.WarehouseItem
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.perf.ThermalMonitor
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner


/**
 * AISectBattleProcessor 核心行为防漂移守卫。
 *
 * 覆盖：AI 宗门升级链 / 玩家宗门不升级 / 非玩家宗门仓库清理 / 热控分批路径。
 * 说明：战斗编排面（AI-vs-AI 征伐/玩家防守）已下沉 C++ AUTHORITATIVE 月结
 * 子事件 6b/6c（P2-18，sect_conquest.h / sect_defense_battle.h）——
 * 行为守卫随迁至桌面 C++ 测试 SectConquestTest / SectDefenseBattleTest。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class AISectBattleProcessorTest {

    @Test
    fun `processAISectOperations - AI宗门升级链生效 玩家宗门不自动升级`() {
        val processor = createProcessorWith(thermalWith(false, false))

        val aiSect = WorldSect(id = "ai1", name = "AI宗", level = SectLevel.SMALL, isPlayerSect = false)
        val playerSect = WorldSect(id = "player", name = "玩家宗", level = SectLevel.SMALL, isPlayerSect = true)
        val data = GameData(
            worldMapSects = listOf(playerSect, aiSect),
            aiSectDisciples = mapOf("ai1" to listOf(makeDisciple("d1", realm = 5))),
            sectDetails = mapOf("ai1" to SectDetail(sectId = "ai1"))
        )
        val state = makeState(data)

        processor.processAISectOperations(2026, 1, state)

        val synced = state.gameData.worldMapSects
        assertEquals("AI 宗门（有 realm≤5 弟子）应升级到 MEDIUM", SectLevel.MEDIUM, synced.find { it.id == "ai1" }?.level)
        assertEquals("玩家宗门不得由月度 tick 自动升级", SectLevel.SMALL, synced.find { it.id == "player" }?.level)
    }

    @Test
    fun `processAISectOperations - 非玩家宗门仓库清空 玩家宗门仓库保留`() {
        val processor = createProcessorWith(thermalWith(false, false))

        val aiSect = WorldSect(id = "ai1", name = "AI宗", level = SectLevel.SMALL, isPlayerSect = false)
        val playerSect = WorldSect(id = "player", name = "玩家宗", level = SectLevel.SMALL, isPlayerSect = true)
        val data = GameData(
            worldMapSects = listOf(playerSect, aiSect),
            aiSectDisciples = mapOf("ai1" to listOf(makeDisciple("d1", realm = 9))),
            sectDetails = mapOf(
                "ai1" to SectDetail(
                    sectId = "ai1",
                    warehouse = SectWarehouse(items = listOf(WarehouseItem(itemId = "x")))
                ),
                "player" to SectDetail(
                    sectId = "player",
                    warehouse = SectWarehouse(items = listOf(WarehouseItem(itemId = "y")))
                )
            )
        )
        val state = makeState(data)

        processor.processAISectOperations(2026, 1, state)

        assertTrue("非玩家宗门仓库应清空", state.gameData.sectDetails.getValue("ai1").warehouse.items.isEmpty())
        assertEquals("玩家宗门仓库不得被清理", 1, state.gameData.sectDetails.getValue("player").warehouse.items.size)
    }

    @Test
    fun `processAISectOperations - 热控紧急模式走紧急分批路径`() {
        val thermal = thermalWith(emergency = true, reduce = false)
        val processor = createProcessorWith(thermal)

        val aiSect = WorldSect(id = "ai1", name = "AI宗", level = SectLevel.TOP, isPlayerSect = false)
        val data = GameData(
            worldMapSects = listOf(WorldSect(id = "player", isPlayerSect = true), aiSect),
            aiSectDisciples = mapOf("ai1" to listOf(makeDisciple("d1", realm = 9)))
        )
        val state = makeState(data)

        // 首次调用仅初始化分批基准月（不查热控）；跨 12 个月后再调用触发紧急分批判定
        processor.processAISectOperations(2026, 1, state)
        processor.processAISectOperations(2027, 1, state)

        verify(thermal).shouldEmergencySave()
        // 顶级宗门不降级、流程无异常
        assertEquals(SectLevel.TOP, state.gameData.worldMapSects.find { it.id == "ai1" }?.level)
    }

    private fun thermalWith(emergency: Boolean, reduce: Boolean): ThermalMonitor {
        val thermal = mockSmart<ThermalMonitor>()
        whenever(thermal.shouldEmergencySave()).thenReturn(emergency)
        whenever(thermal.shouldReduceWorkload()).thenReturn(reduce)
        return thermal
    }

    private fun createProcessorWith(thermal: ThermalMonitor): AISectBattleProcessor =
        AISectBattleProcessor(thermal)

    private fun makeState(
        data: GameData = GameData(),
        tables: DiscipleTables = DiscipleTables().apply { writeAllowed = true }
    ): MutableGameState {
        return MutableGameState(
            gameData = data,
            discipleTables = tables,
            equipmentStacks = EntityStore(),
            equipmentInstances = EntityStore(),
            manualStacks = EntityStore(),
            manualInstances = EntityStore(),
            pills = EntityStore(),
            materials = EntityStore(),
            herbs = EntityStore(),
            seeds = EntityStore(),
            storageBags = EntityStore(),
            battleLogs = emptyList(),
            isPaused = false, isLoading = false, isSaving = false
        )
    }

    private fun makeDisciple(id: String, realm: Int = 9, isAlive: Boolean = true): Disciple =
        Disciple(id = id, realm = realm, isAlive = isAlive)

    // ── L2 AI 降频：热控相位测试（settle 月 = 3/6/9/12，1 月跳过）──

    @Test
    fun `L2 NORMAL相位 - 首次对齐后 1月跳过 季度节奏 3-6-9-12`() {
        val processor = createProcessorWith(thermalWith(false, false))
        val state = makeState(GameData())

        // 首次调用（2026-1）：基准 = 2025-12，batch=0
        processor.processAISectOperations(2026, 1, state)
        assertEquals("首次调用 1 月跳过", 0, processor.currentAIBatchMonths())

        processor.processAISectOperations(2026, 2, state)
        assertEquals("2 月距基准 2 个月 < 3", 0, processor.currentAIBatchMonths())

        // 2026-3：距基准 3 个月 → 首个 settle 月，batch=3
        processor.processAISectOperations(2026, 3, state)
        assertEquals("2026-3 settle", 3, processor.currentAIBatchMonths())

        // 季度节奏：6/9/12 各距上次 settle 3 个月
        processor.processAISectOperations(2026, 6, state)
        assertEquals("2026-6 settle", 3, processor.currentAIBatchMonths())
        processor.processAISectOperations(2026, 9, state)
        assertEquals("2026-9 settle", 3, processor.currentAIBatchMonths())
        processor.processAISectOperations(2026, 12, state)
        assertEquals("2026-12 settle", 3, processor.currentAIBatchMonths())

        // 2027-1：距 2026-12 仅 1 个月 < 3 → 跳过（年变叠加月不再触发 AI 修炼）
        processor.processAISectOperations(2027, 1, state)
        assertEquals("2027-1 跳过", 0, processor.currentAIBatchMonths())
    }

    @Test
    fun `L2 REDUCE相位 - 6月节奏 1月仍跳过`() {
        val processor = createProcessorWith(thermalWith(emergency = false, reduce = true))
        val state = makeState(GameData())

        processor.processAISectOperations(2026, 1, state)
        assertEquals("首次 1 月跳过", 0, processor.currentAIBatchMonths())

        // 2026-6：距基准 6 个月 → settle（batch=6，6 月修炼一次性结算）
        processor.processAISectOperations(2026, 6, state)
        assertEquals("2026-6 settle", 6, processor.currentAIBatchMonths())

        // 2026-12：距 6 月 6 个月 → settle
        processor.processAISectOperations(2026, 12, state)
        assertEquals("2026-12 settle", 6, processor.currentAIBatchMonths())

        // 2027-1：距 2026-12 仅 1 个月 < 6 → 跳过
        processor.processAISectOperations(2027, 1, state)
        assertEquals("2027-1 跳过", 0, processor.currentAIBatchMonths())
    }

    @Test
    fun `L2 EMERGENCY相位 - 跨年批量一次性结算`() {
        val processor = createProcessorWith(thermalWith(emergency = true, reduce = false))
        val state = makeState(GameData())

        processor.processAISectOperations(2026, 1, state)
        assertEquals("首次 1 月跳过", 0, processor.currentAIBatchMonths())

        // 2027-1：距基准 13 个月 ≥ 12 → 跨年批量一次性 settle（batch=13，13 个月修炼一次结算）
        processor.processAISectOperations(2027, 1, state)
        assertEquals("2027-1 跨年批量", 13, processor.currentAIBatchMonths())
    }

    @Test
    fun `L2 相位 - 2月读档首次对齐 基准取3的倍数 settle月仍为3-6-9-12 1月跳过`() {
        // 首次调用在 2/5/8/11 月时，基准须取 (当前月-1) 向下取 3 的倍数——
        // 否则 settle 月会包含 1 月（年变叠加月触发 AI 修炼，降频目标失效）；
        // 基准对齐后 settle 月恒为 3/6/9/12。
        val processor = createProcessorWith(thermalWith(false, false))
        val state = makeState(GameData())

        // 模拟 2 月读档后首次调用：基准对齐到 2025-12（mod 3 = 0）
        processor.processAISectOperations(2026, 2, state)
        assertEquals("2 月首次调用距基准 2 个月 < 3", 0, processor.currentAIBatchMonths())

        // 2026-3：首个 settle 月（3 mod 3 = 0）
        processor.processAISectOperations(2026, 3, state)
        assertEquals("2026-3 settle", 3, processor.currentAIBatchMonths())

        // 2026-12（mod 3 = 0，仍是 settle 月）：距 2026-3 九个月 → batch=9（一次结算）
        processor.processAISectOperations(2026, 12, state)
        assertEquals("2026-12 settle（距上次 9 个月）", 9, processor.currentAIBatchMonths())
        // 2027-1 距 2026-12 仅 1 个月 → 跳过（1 月永不 settle）
        processor.processAISectOperations(2027, 1, state)
        assertEquals("2027-1 跳过（1 月永不 settle）", 0, processor.currentAIBatchMonths())
    }

    @Test
    fun `L2 相位 - 时钟回退同月重复调用 跳过而非叠加修炼`() {
        // monthsSince <= 0（读档到更早月份/同月重复调用）时跳过（0），不叠加修炼。
        val processor = createProcessorWith(thermalWith(false, false))
        val state = makeState(GameData())

        processor.processAISectOperations(2026, 1, state)
        assertEquals("首次 1 月跳过", 0, processor.currentAIBatchMonths())
        processor.processAISectOperations(2026, 3, state)
        assertEquals("2026-3 settle 后 lastSettle=24315", 3, processor.currentAIBatchMonths())

        // 模拟读档回 2026-1（时钟回退）：monthsSince = -2 <= 0 → 跳过（不得叠加修炼）
        processor.processAISectOperations(2026, 1, state)
        assertEquals("时钟回退跳过", 0, processor.currentAIBatchMonths())

        // 同月重复调用：monthsSince = 1 > 0 但未达批次阈值 → 同样跳过
        processor.processAISectOperations(2026, 1, state)
        assertEquals("同月重复调用跳过", 0, processor.currentAIBatchMonths())
    }
}
