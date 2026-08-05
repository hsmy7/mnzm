package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.system.TimeSystem
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.state.MutableGameState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P1-A（G 项）确定性对照守卫测试：多旬结算"每旬独立事务"与"单事务合并"语义等价。
 *
 * 背景：processTickPhases 掉帧追旬场景原为 N 次独立 stateStore.update（N-1 次
 * COW deepCopy + 锁竞争），P1-A 合并为单事务内循环。合并的前提是每旬结算
 * 为纯 state 函数（state 直传，不依赖中间提交），本守卫锁定该前提：
 * A/B 双路径跑相同旬数（跨事务持久化），断言 gameData（年/月/旬）与弟子
 * 数据逐字段一致。若未来在每旬结算中引入"读上次提交状态"的非纯逻辑，
 * 本测试立即失败。
 *
 * 配合 P0-1（RNG 事务快照/恢复）：合并事务异常时整批回滚，与独立事务的
 * "已提交旬不回滚"语义不同——异常路径由 P0-1 保证 RNG 一致性，本测试
 * 守护无异常路径的逐位等价。
 */
class SettlementTransactionMergeTest {

    private fun newStore(): FakeAtomicStateStore = FakeAtomicStateStore().apply {
        update {
            discipleTables.insert(Disciple(id = "1", name = "甲", realm = 1))
            discipleTables.cultivations[1] = 100.0
        }
    }

    /** 模拟一旬结算：时间推进 + 修炼累积 + 年龄增长（纯 state 函数，同 processTickPhases 形态） */
    private fun MutableGameState.settlePhase(timeSystem: TimeSystem) {
        timeSystem.onPhaseTick(this, phasesToSettle = 1)
        val id = 1
        val cultivations = discipleTables.cultivations.getOrDefault(id, 0.0)
        discipleTables.cultivations[id] = cultivations + 10.0
        val age = discipleTables.ages.getOrDefault(id, 0)
        discipleTables.ages[id] = age + 1
    }

    @Test
    fun `merged multi-phase settlement equals per-phase settlement`() {
        // A：每旬独立事务（合并前形态）
        val storeA = newStore()
        val timeA = TimeSystem(FakeAtomicStateStore())
        repeat(5) {
            storeA.update { settlePhase(timeA) }
        }

        // B：单事务合并（P1-A 形态）
        val storeB = newStore()
        val timeB = TimeSystem(FakeAtomicStateStore())
        storeB.update {
            repeat(5) { settlePhase(timeB) }
        }

        // 等价断言：游戏时间 + 弟子数据逐字段一致
        val dataA = storeA.gameDataSnapshot
        val dataB = storeB.gameDataSnapshot
        assertEquals("游戏年", dataA.gameYear, dataB.gameYear)
        assertEquals("游戏月", dataA.gameMonth, dataB.gameMonth)
        assertEquals("游戏旬", dataA.gamePhase, dataB.gamePhase)
        assertEquals(
            "修炼值",
            storeA.discipleTables.cultivations[1],
            storeB.discipleTables.cultivations[1],
            0.001
        )
        assertEquals("年龄", storeA.discipleTables.ages[1], storeB.discipleTables.ages[1])
    }

    @Test
    fun `merged settlement crosses month boundary identically`() {
        // 跨月边界：从旬 2 开始跑 5 旬（月变判定逐旬捕获 prevMonth——P1-A 保留语义）
        val storeA = newStore().apply { update { gameData = gameData.copy(gamePhase = 2) } }
        val timeA = TimeSystem(FakeAtomicStateStore())
        repeat(5) {
            storeA.update { settlePhase(timeA) }
        }

        val storeB = newStore().apply { update { gameData = gameData.copy(gamePhase = 2) } }
        val timeB = TimeSystem(FakeAtomicStateStore())
        storeB.update {
            repeat(5) { settlePhase(timeB) }
        }

        assertEquals(
            "跨月合并应与独立事务一致",
            storeA.gameDataSnapshot.gameMonth, storeB.gameDataSnapshot.gameMonth
        )
        assertEquals(
            "跨月旬推进一致",
            storeA.gameDataSnapshot.gamePhase, storeB.gameDataSnapshot.gamePhase
        )
    }
}
