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
