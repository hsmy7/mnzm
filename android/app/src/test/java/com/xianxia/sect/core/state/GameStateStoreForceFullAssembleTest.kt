package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.Disciple
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T4（2026-08-05）：changedIdTracker 容量拒绝 → 强制全量组装测试。
 *
 * 守卫契约：record 因 id ≥ MAX_SAFE_CAPACITY 被拒（crafted 存档大 id 弟子）时，
 * 即使同事务有其他弟子修改（changedIds 非空），dispatchAssemble 也必须走全量
 * 组装——否则大 id 弟子保留陈旧快照数据（旧实现仅 changedIds 完全为空时全量）。
 *
 * 说明：真实大 id 无法插入（组件表写入侧 require(id < MAX_SAFE_CAPACITY) 会抛），
 * 通过 markRejectedForTest 测试 seam 模拟容量拒绝（对齐 forceFullCopy 既有模式）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameStateStoreForceFullAssembleTest {

    private fun disciple(id: Int, cultivation: Double = 0.0): Disciple =
        Disciple(id = id.toString(), name = "弟子$id", realm = 5, realmLayer = 1)
            .copy(cultivation = cultivation)

    private fun store(): GameStateStoreImpl {
        val s = GameStateStoreImpl(
            com.xianxia.sect.di.ApplicationScopeProvider(),
            org.mockito.Mockito.mock(com.xianxia.sect.data.GameStateRepository::class.java)
        )
        s.unsafeAllowMainThreadUpdateForTest = true
        return s
    }

    @Test
    fun `rejected record forces full assemble despite changed ids`() {
        val store = store()
        store.update {
            gameData = gameData.copy(sectName = "宗")
            discipleTables.insert(disciple(1, 10.0))
        }
        TestPolling.awaitCondition("初始弟子组装完成") {
            store._disciplesFlow.value.size == 1
        }

        // 模拟容量拒绝（crafted 存档大 id 弟子被拒记录）→ 正常事务修改弟子 1
        store.discipleTables.changedIdTracker.markRejectedForTest()
        store.update {
            discipleTables.cultivations[1] = 999.0
        }

        // 强制全量路径：结果正确、无陈旧快照残留
        TestPolling.awaitCondition("容量拒绝后全量组装结果正确") {
            store._disciplesFlow.value.size == 1 &&
                store._disciplesFlow.value[0].cultivation == 999.0
        }
        assertEquals(1, store._disciplesFlow.value.size)
        assertEquals(999.0, store._disciplesFlow.value[0].cultivation, 0.0)
    }

    @Test
    fun `normal transaction without rejection still assembles correctly`() {
        val store = store()
        store.update {
            gameData = gameData.copy(sectName = "宗")
            discipleTables.insert(disciple(1, 10.0))
        }
        TestPolling.awaitCondition("初始弟子组装完成") {
            store._disciplesFlow.value.size == 1
        }

        // 无容量拒绝：增量路径回归（forceFull 恒 false，行为与 T4 前一致）
        store.update {
            discipleTables.cultivations[1] = 555.0
        }
        TestPolling.awaitCondition("增量路径结果正确") {
            store._disciplesFlow.value[0].cultivation == 555.0
        }
        assertTrue("无拒绝时不应置强制全量标志", !store.discipleTables.changedIdTracker.consumeRejectedRecord())
    }
}
