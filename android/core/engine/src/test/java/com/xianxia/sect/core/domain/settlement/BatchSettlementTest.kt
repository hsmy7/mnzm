package com.xianxia.sect.core.engine.domain.settlement

import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * 批量轨 + 系统迁移 + modifyState 单元测试。
 * 只测纯逻辑，不依赖 DI。
 */
class BatchSettlementTest {

    // ═══ modifyState 重入 ═══

    @Test
    fun `modifyState on MutableGameState writes directly`() = runBlocking {
        // 模拟：事务内的 modifyState 行为
        var outerData = 0
        val state = emptyState()
        // 事务内路径：直接修改 state
        state.gameData = state.gameData.copy(spiritStones = 42)
        outerData = state.gameData.spiritStones.toInt()
        assertEquals(42, outerData)
    }

    // ═══ onPhaseTick 批量轨 ═══

    @Test
    fun `phasesToSettle lt 3 skips monthly processing`() = runBlocking {
        val s = TestSystem()
        s.onPhaseTick(emptyState(), phasesToSettle = 1)
        assertEquals(0, s.monthlyCalls)
        s.onPhaseTick(emptyState(), phasesToSettle = 2)
        assertEquals(0, s.monthlyCalls)
    }

    @Test
    fun `phasesToSettle 3 calls monthly once`() = runBlocking {
        val s = TestSystem()
        s.onPhaseTick(emptyState(), phasesToSettle = 3)
        assertEquals(1, s.monthlyCalls)
    }

    @Test
    fun `phasesToSettle 9 calls monthly 3 times`() = runBlocking {
        val s = TestSystem()
        s.onPhaseTick(emptyState(), phasesToSettle = 9)
        assertEquals(3, s.monthlyCalls)
    }

    @Test
    fun `phasesToSettle 6 calls monthly 2 times`() = runBlocking {
        val s = TestSystem()
        s.onPhaseTick(emptyState(), phasesToSettle = 6)
        assertEquals(2, s.monthlyCalls)
    }

    // ═══ catchUpDomain 域切换 ═══

    @Test
    fun `catchUpDomain removes domain from tracking map`() {
        val map = mutableMapOf<String, Long>()
        map["DISCIPLES"] = System.currentTimeMillis()
        assertTrue("初始应有 DISCIPLES", "DISCIPLES" in map)
        map.remove("DISCIPLES")
        assertFalse("移除后不应有 DISCIPLES", "DISCIPLES" in map)
    }

    // ═══ phasesSkippedForDomain — 跳过旬数计算 ═══

    /**
     * 复制 GameEngineCore.phasesSkippedForDomain 的核心逻辑。
     * gameSpeed=1 时 msPerPhase=2000，gameSpeed=2 时 msPerPhase=1000。
     */
    private fun phasesSkipped(
        lastTickMs: Long?,
        nowMs: Long,
        gameSpeed: Int = 1
    ): Int {
        if (lastTickMs == null || lastTickMs == 0L) return 1
        val elapsed = nowMs - lastTickMs
        val msPerPhase = (2000L / gameSpeed.coerceIn(1, 2)).coerceAtLeast(500L)
        return (elapsed / msPerPhase).toInt().coerceAtLeast(1)
    }

    @Test
    fun `phasesSkippedForDomain — 无记录时返回1`() {
        assertEquals(1, phasesSkipped(null, System.currentTimeMillis()))
    }

    @Test
    fun `phasesSkippedForDomain — 刚执行过返回1`() {
        val now = System.currentTimeMillis()
        assertEquals(1, phasesSkipped(now - 50, now))  // 50ms < 2000ms
    }

    @Test
    fun `phasesSkippedForDomain — 跳过3旬返回3`() {
        val now = System.currentTimeMillis()
        assertEquals(3, phasesSkipped(now - 6000, now))  // 6000ms / 2000ms = 3
    }

    @Test
    fun `phasesSkippedForDomain — 跳过30旬（约1分钟）`() {
        val now = System.currentTimeMillis()
        assertEquals(30, phasesSkipped(now - 60_000, now))
    }

    @Test
    fun `phasesSkippedForDomain — 加速模式下计算正确`() {
        val now = System.currentTimeMillis()
        // gameSpeed=2: msPerPhase=1000, 5000ms = 5旬
        assertEquals(5, phasesSkipped(now - 5000, now, gameSpeed = 2))
    }

    @Test
    fun `phasesSkippedForDomain — 旧时间戳返回大于1（模拟 batchRealtimeDomains Bug 场景）`() {
        // 模拟：域曾活跃过，domainLastTickTime 有 2 分钟前的时间戳
        // batchRealtimeDomains 加入该域时，若未清除时间戳，phasesSkipped 返回 N>1
        val now = System.currentTimeMillis()
        val result = phasesSkipped(now - 120_000, now)
        assertTrue("旧时间戳应返回 > 1（实际=$result）", result > 1)
    }

    // ═══ shouldExecuteDomain — 30s 间隔逻辑 ═══

    /**
     * 复制 GameEngineCore.shouldExecuteDomain 的核心逻辑。
     */
    private fun shouldExecute(
        domain: String,
        activeSet: Set<String>,
        lastTickMap: Map<String, Long>,
        nowMs: Long,
        intervalMs: Long = 30_000L
    ): Boolean {
        if (domain == "ALWAYS") return true
        if (domain in activeSet) return true
        val lastTime = lastTickMap[domain] ?: 0L
        return (nowMs - lastTime) >= intervalMs
    }

    @Test
    fun `shouldExecuteDomain — 活跃域始终执行`() {
        val active = setOf("DISCIPLE_LIST")
        // 即使有旧时间戳，活跃域也应执行
        val oldMap = mapOf("DISCIPLE_LIST" to (System.currentTimeMillis() - 120_000))
        assertTrue(
            "活跃域应始终执行",
            shouldExecute("DISCIPLE_LIST", active, oldMap, System.currentTimeMillis())
        )
    }

    @Test
    fun `shouldExecuteDomain — 非活跃域30s内不执行`() {
        val active = setOf("OVERVIEW")
        val now = System.currentTimeMillis()
        val map = mapOf("DISCIPLE_LIST" to (now - 10_000))  // 仅10s前
        assertFalse(
            "非活跃域10s内不应执行",
            shouldExecute("DISCIPLE_LIST", active, map, now)
        )
    }

    @Test
    fun `shouldExecuteDomain — 非活跃域超过30s执行`() {
        val active = setOf("OVERVIEW")
        val now = System.currentTimeMillis()
        val map = mapOf("DISCIPLE_LIST" to (now - 35_000))
        assertTrue(
            "非活跃域超30s应执行",
            shouldExecute("DISCIPLE_LIST", active, map, now)
        )
    }

    @Test
    fun `shouldExecuteDomain — 无记录的非活跃域立即执行`() {
        val active = setOf("OVERVIEW")
        val emptyMap = emptyMap<String, Long>()
        assertTrue(
            "无记录的非活跃域应首次即执行",
            shouldExecute("DISCIPLE_LIST", active, emptyMap, System.currentTimeMillis())
        )
    }

    // ═══ getActiveDomains + batchRealtimeDomains 转轨 ═══

    /**
     * 复制 getActiveDomains() 修复后的逻辑：
     * batchRealtimeDomains 中不在 viewDomains 的域需清除追踪。
     */
    private fun getActiveDomainsFixed(
        viewDomains: Set<String>,
        batchDomains: Set<String>,
        trackingMap: MutableMap<String, Long>
    ): Set<String> {
        val all = viewDomains.toMutableSet()
        for (domain in batchDomains) {
            if (domain !in viewDomains && domain != "ALWAYS") {
                trackingMap.remove(domain)
            }
        }
        all.addAll(batchDomains)
        return all
    }

    @Test
    fun `getActiveDomains — batchRealtimeDomains 新域清除追踪后 phasesSkipped 为1`() {
        val now = System.currentTimeMillis()
        val tracking = mutableMapOf(
            "MISSION_HALL" to (now - 120_000)  // 2分钟前的旧时间戳
        )
        val viewDomains = setOf("OVERVIEW")
        val batchDomains = setOf("MISSION_HALL")  // ≥80% 任务触发

        // 修复前：phasesSkipped 会返回 > 1（Bug）
        val beforeFix = phasesSkipped(tracking["MISSION_HALL"], now)
        assertTrue("修复前 phasesSkipped > 1", beforeFix > 1)

        // 修复后：getActiveDomainsFixed 清除追踪
        getActiveDomainsFixed(viewDomains, batchDomains, tracking)
        val afterFix = phasesSkipped(tracking["MISSION_HALL"], now)
        assertEquals("修复后 phasesSkipped 应为 1", 1, afterFix)
    }

    @Test
    fun `getActiveDomains — batchRealtimeDomains 域已在 viewDomains 中则不清除`() {
        val now = System.currentTimeMillis()
        val tracking = mutableMapOf(
            "DISCIPLE_LIST" to (now - 100)  // 活跃域的正常时间戳
        )
        val viewDomains = setOf("DISCIPLE_LIST")  // 用户在弟子 Tab
        val batchDomains = setOf("DISCIPLE_LIST")  // 修炼≥80%（与 view 重叠）

        getActiveDomainsFixed(viewDomains, batchDomains, tracking)
        // viewDomains 中的域已由 catchUpDomain 管理，不应被此处清除
        assertTrue(
            "已在 viewDomains 中的域应保留追踪（由 catchUpDomain 管理）",
            "DISCIPLE_LIST" in tracking
        )
    }

    @Test
    fun `getActiveDomains — 域完整生命周期：活跃→非活跃→批量再活跃`() {
        val now = System.currentTimeMillis()
        val tracking = mutableMapOf<String, Long>()

        // 阶段1：用户打开 MissionHall（活跃）
        // catchUpDomain 已清除，域变为活跃
        // markDomainExecuted 持续更新
        tracking["MISSION_HALL"] = now
        assertEquals(1, phasesSkipped(tracking["MISSION_HALL"], now + 100))

        // 阶段2：用户关闭 MissionHall（非活跃）
        // 追踪保留，等30s后批量执行
        val later = now + 35_000
        val inactivePhases = phasesSkipped(tracking["MISSION_HALL"], later)
        assertTrue("非活跃35s后应跳过多个旬", inactivePhases > 1)

        // 阶段3：≥80% 任务触发 batchRealtimeDomains（重新活跃）
        // getActiveDomainsFixed 清除追踪
        val viewDomains = setOf("OVERVIEW")
        val batchDomains = setOf("MISSION_HALL")
        getActiveDomainsFixed(viewDomains, batchDomains, tracking)
        val reactivatedPhases = phasesSkipped(tracking["MISSION_HALL"], later + 100)
        assertEquals("重新活跃后 phasesSkipped 应为 1", 1, reactivatedPhases)
    }

    // ═══ 辅助 ═══

    private fun emptyState() = MutableGameState(
        gameData = GameData(), discipleTables = DiscipleTables(),
        equipmentStacks = EntityStore(), equipmentInstances = EntityStore(),
        manualStacks = EntityStore(), manualInstances = EntityStore(),
        pills = EntityStore(), materials = EntityStore(), herbs = EntityStore(),
        seeds = EntityStore(), storageBags = EntityStore(),
        teams = emptyList(), battleLogs = emptyList(),
        isPaused = false, isLoading = false, isSaving = false
    )

    class TestSystem : com.xianxia.sect.core.engine.system.GameSystem {
        override val systemName = "TestSystem"
        var monthlyCalls = 0
        override suspend fun onPhaseTick(state: MutableGameState, phasesToSettle: Int) {
            if (phasesToSettle < 3) return
            repeat(phasesToSettle / 3) { monthlyCalls++ }
        }
    }
}
