package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.data.GameStateRepository
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P-14 竞态回归守卫：交替 update 与 load 的组装竞态压力测试（2026-08-05）。
 *
 * 背景：LoadRaceTest/RollbackTest 全量跑偶发 flaky（P-14 登记）。H1 假设
 * （assemble 任务版本检查通过后、load 锁内替换表 → 陈旧任务 publish 旧列表）
 * 300 轮压力实证 0 失败；H3（statsProvider 静态污染）经枚举排除；最可能根因
 * 为 H2（TestPolling 5s 超时在慢 CI 上不足，已提升至 15s）。
 * 本测试保留 30 轮交替压力作为竞态回归防护。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameStateStoreAssembleRaceGuardTest {

    private fun disciple(id: Int, name: String = "弟子$id"): Disciple =
        Disciple(id = id.toString(), name = name, realm = 5, realmLayer = 1)

    private fun store(): GameStateStoreImpl {
        val s = GameStateStoreImpl(ApplicationScopeProvider(), mock(GameStateRepository::class.java))
        s.unsafeAllowMainThreadUpdateForTest = true
        return s
    }

    @Test
    fun `交替 update 与 load 30 轮 - 最终列表恒为新列表`() = runBlocking {
        var failures = 0
        repeat(30) { round ->
            val store = store()
            store.update {
                discipleTables.insert(disciple(1))
                discipleTables.insert(disciple(2))
                discipleTables.insert(disciple(3))
            }
            store.loadFromSnapshot(
                gameData = GameData(),
                disciples = listOf(disciple(10, "新弟子10"), disciple(11, "新弟子11")),
                equipmentStacks = emptyList(),
                equipmentInstances = emptyList(),
                manualStacks = emptyList(),
                manualInstances = emptyList(),
                pills = emptyList(),
                materials = emptyList(),
                herbs = emptyList(),
                seeds = emptyList(),
                storageBags = emptyList(),
                teams = emptyList(),
                battleLogs = emptyList(),
                isPaused = true,
                isLoading = false,
                isSaving = false
            )
            val deadline = System.currentTimeMillis() + 10_000
            var reached = false
            var lastSnapshot = ""
            while (System.currentTimeMillis() < deadline) {
                lastSnapshot = store.disciples.value.map { it.id }.toString()
                if (lastSnapshot == "[10, 11]") {
                    reached = true
                    break
                }
                Thread.sleep(20)
            }
            if (!reached) {
                failures++
                println("ROUND $round FAILED: last=$lastSnapshot")
            }
        }
        println("TOTAL FAILURES: $failures / 30")
        assertEquals("30 轮交替压力应全部收敛到新列表", 0, failures)
    }
}
