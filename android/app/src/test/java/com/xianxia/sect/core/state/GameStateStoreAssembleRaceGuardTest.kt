package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 竞态回归守卫：交替 update 与 load 的组装竞态压力测试。
 *
 * dispatchAssemble 增量/全量分支与 load 投递均在 publish 前做二次版本检查，
 * 防止 load 锁内替换表后陈旧 assemble 任务 publish 旧列表；二次检查本身
 * 无法时序注入单测（需在 assemble 执行中递增版本号），由本压力
 * 守卫（30 轮交替）+ 全量回归兜底。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameStateStoreAssembleRaceGuardTest {

    private fun disciple(id: Int, name: String = "弟子$id"): Disciple =
        Disciple(id = id.toString(), name = name, realm = 5, realmLayer = 1)

    private fun store(): GameStateStoreImpl {
        val s = GameStateStoreImpl(ApplicationScopeProvider(), testGameStateRepository())
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
