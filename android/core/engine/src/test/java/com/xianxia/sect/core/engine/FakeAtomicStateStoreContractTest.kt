package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.SectLevelClaimRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * FakeAtomicStateStoreContractTest — [FakeAtomicStateStore] 事务缓冲契约守卫（W4-B/B0）。
 *
 * ## 为什么存在（handover §2.50 "Jade 凭据持久化环境缺陷" 的契约考古结论）
 *
 * batch-19 当年把「首领后凭据未持久化」归因为"FakeAtomicStateStore 事务缓冲与
 * sectLevelClaimRecords 交互的环境缺陷"。W4-B/B0 契约考古（本测试类 + 改后的
 * JadeNativeTxGateTest）实证：**fake 无缺陷**——真根因是 `JadeNativeTxGateTest`
 * 对 `InventorySystem` 的 plain mock 把成员函数 `withOverflowMailSuppressed` /
 * `withTrackingSource` 整个 stub 掉，传入的 block 从不执行 ⇒ 凭据写入被静默跳过。
 *
 * 本类把 fake 的事务缓冲契约落成可执行断言（对齐真实 GameStateStoreImpl 的 COW 语义）：
 * 1. 顶层 update 提交后 gameData 对快照可见（跨事务持久化）；
 * 2. 嵌套 update 复用同一事务缓冲，外层提交时合并写入（内层不被覆盖丢失）；
 * 3. 事务内抛异常 ⇒ gameData 回滚（快照保持原值）；
 * 4. **事务内读写必须同源**：事务内经 `gameDataSnapshot` 读到的是事务**前**的值
 *    （快照只反映已提交状态）——事务内写读必须经 MutableGameState 缓冲本体。
 *
 * 任何一条变红 ⇒ fake 的缓冲语义偏离真实 store，须根因修复 fake（禁止特判绕过）。
 */
class FakeAtomicStateStoreContractTest {

    @Test
    fun `update persists gameData across transaction boundary`() {
        val store = FakeAtomicStateStore()
        store.update {
            gameData = gameData.copy(
                sectLevelClaimRecords = listOf(
                    SectLevelClaimRecord(level = 0, claimedAtEpochMs = 1_000L)
                )
            )
        }
        assertEquals(
            "顶层事务提交后凭据必须对快照可见（§2.50 缺陷面回归守卫）",
            1,
            store.gameDataSnapshot.sectLevelClaimRecords.size
        )
        assertEquals(1, store.latestGameData.sectLevelClaimRecords.size)
    }

    @Test
    fun `nested update reuses transaction buffer and outer commit merges all writes`() {
        val store = FakeAtomicStateStore()
        store.update {
            gameData = gameData.copy(gameYear = 5)
            store.update {
                gameData = gameData.copy(gameMonth = 3)
            }
            // 内层提交不触发 syncFlows（writeDepth > 0）——此时快照应仍是旧值
            assertEquals("嵌套事务未提交前快照不得看到内层写入", 1, store.gameDataSnapshot.gameMonth)
        }
        assertEquals("外层提交必须合并嵌套写入", 5, store.gameDataSnapshot.gameYear)
        assertEquals(3, store.gameDataSnapshot.gameMonth)
    }

    @Test
    fun `exception inside update rolls gameData back to committed value`() {
        val store = FakeAtomicStateStore()
        store.setGameData(GameData(gameYear = 2))
        assertThrows(IllegalStateException::class.java) {
            store.update {
                gameData = gameData.copy(gameYear = 9)
                error("模拟事务中途失败")
            }
        }
        assertEquals("事务内异常 ⇒ gameData 回滚（失败零写入）", 2, store.gameDataSnapshot.gameYear)
    }

    @Test
    fun `snapshot inside transaction observes pre-transaction committed value`() {
        val store = FakeAtomicStateStore()
        store.setGameData(GameData(gameYear = 7))
        var observedInTx = -1
        store.update {
            gameData = gameData.copy(gameYear = 8)
            observedInTx = store.gameDataSnapshot.gameYear
        }
        assertEquals(
            "契约：事务内 gameDataSnapshot 读到的是事务前已提交值（事务内写读必须走缓冲本体）",
            7,
            observedInTx
        )
        assertEquals(8, store.gameDataSnapshot.gameYear)
    }
}
