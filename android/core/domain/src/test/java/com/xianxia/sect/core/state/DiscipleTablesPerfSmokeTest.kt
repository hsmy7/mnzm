package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.SkillStats
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * COW deepCopy 性能冒烟基准（非严格基准，宽松上限防 CI 抖动）。
 *
 * 重构前：每次 deepCopy 约 100 列 × 100 行逐元素 putTo ≈ 10,000 次 SparseArray 写入。
 * 重构后：O(1)/列 共享 + 仅写 3 列私有化。
 * 通过 println 输出纳秒/次供人工对比（保存重构前的基准数据）。
 */
@RunWith(RobolectricTestRunner::class)
class DiscipleTablesPerfSmokeTest {

    @get:Rule val writeGuardRule = WriteGuardRule()

    private fun createTestDisciple(
        id: String = "1",
        name: String = "张三",
        realm: Int = 9,
        cultivation: Double = 100.0,
        loyalty: Int = 50
    ): Disciple {
        return Disciple(
            id = id,
            name = name,
            realm = realm,
            cultivation = cultivation,
            skills = SkillStats(loyalty = loyalty)
        )
    }

    @Test
    fun `100 disciples 1000 iterations deepCopy plus 3 column writes under 3 seconds`() {
        val tables = DiscipleTables()
        for (i in 1..100) {
            tables.insert(createTestDisciple(id = i.toString(), realm = i % 9 + 1, cultivation = 100.0))
        }

        // 预热（首次 deepCopy 含类加载/JIT）
        repeat(10) { tables.deepCopy() }

        val iterations = 1000
        val startNs = System.nanoTime()
        repeat(iterations) {
            val copy = tables.deepCopy()
            // 模拟每旬热点写：修为 + 忠诚 + 当前 HP
            copy.cultivations[1] = 100.0
            copy.loyalties[1] = 50
            copy.currentHps[1] = 100
        }
        val elapsedNs = System.nanoTime() - startNs
        val elapsedMs = elapsedNs / 1_000_000
        println(
            "COW deepCopy+3col write: ${elapsedMs}ms 总耗时 / $iterations 次 = " +
                "${elapsedNs / iterations} ns/次"
        )

        assertTrue(
            "1000 次 COW deepCopy + 写 3 列应 < 3000ms（当前 ${elapsedMs}ms）",
            elapsedMs < 3000
        )
    }
}
