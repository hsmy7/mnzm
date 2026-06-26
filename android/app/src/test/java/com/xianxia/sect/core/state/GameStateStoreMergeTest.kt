package com.xianxia.sect.core.state

import android.app.Application
import com.xianxia.sect.core.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 验证 mergeDiscipleTables 的三路合并正确性 —
 * 修复空闲模式下 processYearlyEvents 写入真实 store 后
 * 被 swapFromShadow 覆盖导致的年龄/状态回退问题。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class GameStateStoreMergeTest {

    // ── 辅助 ──────────────────────────────────────────────────────────

    private fun merge(
        originDisciples: List<Disciple>,
        shadow: DiscipleTables,
        current: DiscipleTables
    ): DiscipleTables = GameStateStoreImpl.mergeDiscipleTables(
        originDisciples, shadow, current
    )

    /** 创建一个基础弟子并插入到 tables 中 */
    private fun createDisciple(
        id: Int,
        age: Int = 20,
        cultivation: Double = 100.0,
        realm: Int = 9,
        realmLayer: Int = 1,
        isAlive: Boolean = true,
        status: DiscipleStatus = DiscipleStatus.IDLE,
        statusData: Map<String, String> = emptyMap(),
        morality: Int = 50,
        loyalty: Int = 50,
        partnerId: String? = null,
        griefEndYear: Int? = null,
        masterId: String? = null,
        lifespan: Int = 80
    ): Disciple {
        return Disciple(
            id = id.toString(),
            age = age,
            cultivation = cultivation,
            realm = realm,
            realmLayer = realmLayer,
            isAlive = isAlive,
            status = status,
            statusData = statusData,
            skills = SkillStats(morality = morality, loyalty = loyalty),
            social = SocialData(
                partnerId = partnerId,
                griefEndYear = griefEndYear,
                masterId = masterId
            ),
            lifespan = lifespan
        )
    }

    private fun DiscipleTables.insertDisciple(d: Disciple) {
        insert(d)
    }

    // ── 测试 1: 年龄保留 ──────────────────────────────────────────────

    @Test
    fun `mergeDiscipleTables - age from current preserved over shadow`() {

        // origin: 弟子 age=20
        val originTables = DiscipleTables()
        val oD = createDisciple(id = 1, age = 20, cultivation = 100.0)
        originTables.insertDisciple(oD)
        val originDisciples = originTables.assembleAll()

        // shadow: 修炼进度涨到 115，但 age=20（空闲进入时的快照）
        val shadow = originTables.deepCopy()
        shadow.cultivations[1] = 115.0

        // current: 年度事件老化到 21
        val current = originTables.deepCopy()
        current.ages[1] = 21

        val result = merge(originDisciples, shadow, current)

        assertEquals("年龄应取 current 的值", 21, result.ages.getOrDefault(1, 0))
        assertEquals("修炼进度应取 shadow 的值", 115.0, result.cultivations[1], 0.001)
    }

    // ── 测试 2: 单调递增不回退 ────────────────────────────────────────

    @Test
    fun `mergeDiscipleTables - age never regresses across multiple merges`() {

        val originTables = DiscipleTables()
        originTables.insertDisciple(createDisciple(id = 1, age = 20, cultivation = 100.0))
        val originDisciples = originTables.assembleAll()

        var shadow = originTables.deepCopy()
        var current = originTables.deepCopy()

        // 模拟三轮年度老化 + 批量结算
        val ages = mutableListOf<Int>()
        for (year in 1..3) {
            // 年度事件: 年龄 +1
            current.ages[1] = 20 + year
            // 批量结算: 修炼累积
            shadow.cultivations[1] = shadow.cultivations.getOrDefault(1, 0.0) + 15.0

            shadow = merge(originDisciples, shadow, current).deepCopy()
            // 重建当前表（模拟 swap 后真实 store 状态）
            current = shadow.deepCopy()
            ages.add(shadow.ages.getOrDefault(1, 0))
        }

        assertEquals("年龄应单调递增", listOf(21, 22, 23), ages)
        assertEquals("修炼进度应持续累积", 145.0, shadow.cultivations[1], 0.001)
    }

    // ── 测试 3: 修炼进度保留 ──────────────────────────────────────────

    @Test
    fun `mergeDiscipleTables - cultivation from shadow preserved`() {

        val originTables = DiscipleTables()
        originTables.insertDisciple(createDisciple(id = 1, age = 20, cultivation = 100.0))
        val originDisciples = originTables.assembleAll()

        val shadow = originTables.deepCopy()
        shadow.cultivations[1] = 150.0  // 批量修炼累积
        shadow.currentHps[1] = 80       // HP 也改变了

        val current = originTables.deepCopy()
        current.ages[1] = 21            // 年度老化

        val result = merge(originDisciples, shadow, current)

        assertEquals("修炼进度取 shadow", 150.0, result.cultivations[1], 0.001)
        assertEquals("HP 取 shadow", 80, result.currentHps.getOrDefault(1, 0))
        assertEquals("年龄取 current", 21, result.ages.getOrDefault(1, 0))
    }

    // ── 测试 4: 死亡弟子移除 ──────────────────────────────────────────

    @Test
    fun `mergeDiscipleTables - dead disciple removed from result`() {

        val originTables = DiscipleTables()
        originTables.insertDisciple(createDisciple(id = 1, age = 20))
        originTables.insertDisciple(createDisciple(id = 2, age = 80))  // 即将死亡
        val originDisciples = originTables.assembleAll()

        val shadow = originTables.deepCopy()

        // current: 弟子 2 已死亡（被 processDiscipleAging 移除）
        val current = originTables.deepCopy()
        current.remove(2)
        current.ages[1] = 21  // 弟子 1 老化

        val result = merge(originDisciples, shadow, current)

        assertTrue("弟子 1 存活", 1 in result.ids)
        assertFalse("弟子 2 已死亡移除", 2 in result.ids)
        assertEquals("弟子 1 年龄正确", 21, result.ages.getOrDefault(1, 0))
    }

    // ── 测试 5: 新生儿保留 ─────────────────────────────────────────────

    @Test
    fun `mergeDiscipleTables - child birth disciple preserved`() {

        val originTables = DiscipleTables()
        originTables.insertDisciple(createDisciple(id = 1, age = 20))
        val originDisciples = originTables.assembleAll()

        // shadow: 子嗣出生新增弟子 3
        val shadow = originTables.deepCopy()
        shadow.insertDisciple(createDisciple(id = 3, age = 0, cultivation = 0.0))

        val current = originTables.deepCopy()
        current.ages[1] = 21

        val result = merge(originDisciples, shadow, current)

        assertTrue("新生儿应保留", 3 in result.ids)
        assertEquals("新生儿年龄正确", 0, result.ages.getOrDefault(3, 0))
        assertEquals("老弟子年龄正确", 21, result.ages.getOrDefault(1, 0))
    }

    // ── 测试 6: 反思释放 ──────────────────────────────────────────────

    @Test
    fun `mergeDiscipleTables - reflection release status and morality preserved`() {

        val originTables = DiscipleTables()
        originTables.insertDisciple(createDisciple(
            id = 1, age = 20,
            status = DiscipleStatus.REFLECTING,
            statusData = mapOf("reflectionStartYear" to "5", "reflectionEndYear" to "6"),
            morality = 50, loyalty = 50
        ))
        val originDisciples = originTables.assembleAll()

        val shadow = originTables.deepCopy()

        // current: 年度事件 processReflectionRelease 释放了反思
        val current = originTables.deepCopy()
        current.ages[1] = 21
        current.statuses[1] = DiscipleStatus.IDLE
        current.statusData[1] = emptyMap()
        current.moralities[1] = 55  // +5
        current.loyalties[1] = 55   // +5

        val result = merge(originDisciples, shadow, current)

        assertEquals("状态应变为 IDLE", DiscipleStatus.IDLE, result.statuses[1])
        assertEquals("statusData 应清空", emptyMap<String, String>(), result.statusData.getOrDefault(1, emptyMap()))
        assertEquals("morality 应取 current", 55, result.moralities.getOrDefault(1, 0))
        assertEquals("loyalty 应取 current", 55, result.loyalties.getOrDefault(1, 0))
        assertEquals("年龄应取 current", 21, result.ages.getOrDefault(1, 0))
    }

    // ── 测试 7: 哀悼到期 ──────────────────────────────────────────────

    @Test
    fun `mergeDiscipleTables - grief expiry preserved`() {

        val originTables = DiscipleTables()
        originTables.insertDisciple(createDisciple(
            id = 1, age = 20, griefEndYear = 6
        ))
        val originDisciples = originTables.assembleAll()

        val shadow = originTables.deepCopy()

        // current: 年度事件 processGriefExpiry 清除了哀悼
        val current = originTables.deepCopy()
        current.ages[1] = 21
        current.griefEndYears[1] = null  // 哀悼到期

        val result = merge(originDisciples, shadow, current)

        assertNull("哀悼应到期清除", result.griefEndYears.getOrNull(1))
        assertEquals("年龄应取 current", 21, result.ages.getOrDefault(1, 0))
    }

    // ── 测试 8: 连锁死亡 — 伴侣/师徒关系清除 ──────────────────────────

    @Test
    fun `mergeDiscipleTables - death cascade clears partner and master references`() {

        val originTables = DiscipleTables()
        originTables.insertDisciple(createDisciple(id = 1, age = 20, partnerId = "2"))
        originTables.insertDisciple(createDisciple(id = 2, age = 22, partnerId = "1"))
        originTables.insertDisciple(createDisciple(id = 3, age = 18, masterId = "2"))  // 弟子2是弟子3的师父
        val originDisciples = originTables.assembleAll()

        val shadow = originTables.deepCopy()

        // current: 弟子 2 死亡，弟子 1 的 partnerId 和弟子 3 的 masterId 被清除
        val current = originTables.deepCopy()
        current.remove(2)
        current.ages[1] = 21
        current.partnerIds[1] = null  // handleDiscipleDeath 连锁清除
        current.ages[3] = 19
        current.masterIds[3] = null   // 师父死亡解除师徒关系

        val result = merge(originDisciples, shadow, current)

        // 弟子 2 死亡
        assertFalse("弟子 2 应被移除", 2 in result.ids)
        // 弟子 1 的伴侣引用清除
        assertNull("弟子 1 伴侣引用应清除", result.partnerIds.getOrNull(1))
        assertEquals("弟子 1 年龄正确", 21, result.ages.getOrDefault(1, 0))
        // 弟子 3 的师父引用清除
        assertNull("弟子 3 师父引用应清除", result.masterIds.getOrNull(3))
        assertEquals("弟子 3 年龄正确", 19, result.ages.getOrDefault(3, 0))
    }

    // ── 测试 9: 寿命可能随境界/天赋改变 ────────────────────────────────

    @Test
    fun `mergeDiscipleTables - lifespan change preserved`() {

        val originTables = DiscipleTables()
        originTables.insertDisciple(createDisciple(id = 1, age = 20, lifespan = 80))
        val originDisciples = originTables.assembleAll()

        val shadow = originTables.deepCopy()

        // current: 年龄增长时重新计算了 lifespan（天赋加成可能改变）
        val current = originTables.deepCopy()
        current.ages[1] = 21
        current.lifespans[1] = 85  // 天赋加成后最大寿命更大

        val result = merge(originDisciples, shadow, current)

        assertEquals("寿命应取 current", 85, result.lifespans.getOrDefault(1, 0))
    }

    // ── 测试 10: 多字段同时变化 ────────────────────────────────────────

    @Test
    fun `mergeDiscipleTables - multiple lifecycle fields change simultaneously`() {

        val originTables = DiscipleTables()
        originTables.insertDisciple(createDisciple(
            id = 1, age = 20,
            status = DiscipleStatus.REFLECTING,
            statusData = mapOf("reflectionEndYear" to "7"),
            morality = 50, loyalty = 50,
            griefEndYear = 6
        ))
        val originDisciples = originTables.assembleAll()

        val shadow = originTables.deepCopy()
        shadow.cultivations[1] = 120.0

        // current: 年度事件一次性改变了多个字段
        val current = originTables.deepCopy()
        current.ages[1] = 21
        current.statuses[1] = DiscipleStatus.IDLE
        current.statusData[1] = emptyMap()
        current.moralities[1] = 55
        current.loyalties[1] = 55
        current.griefEndYears[1] = null

        val result = merge(originDisciples, shadow, current)

        // 全部生命周期字段应取 current
        assertEquals(21, result.ages.getOrDefault(1, 0))
        assertEquals(DiscipleStatus.IDLE, result.statuses[1])
        assertEquals(emptyMap<String, String>(), result.statusData.getOrDefault(1, emptyMap()))
        assertEquals(55, result.moralities.getOrDefault(1, 0))
        assertEquals(55, result.loyalties.getOrDefault(1, 0))
        assertNull(result.griefEndYears.getOrNull(1))
        // 修炼进度取 shadow
        assertEquals(120.0, result.cultivations[1], 0.001)
    }

    // ── 测试 11: 实时轨修炼取 max ────────────────────────────────────

    @Test
    fun `mergeDiscipleTables - cultivation takes max when current is higher`() {
        val originTables = DiscipleTables()
        originTables.insertDisciple(createDisciple(id = 1, age = 20, cultivation = 100.0))
        val originDisciples = originTables.assembleAll()

        val shadow = originTables.deepCopy()
        shadow.cultivations[1] = 105.0

        val current = originTables.deepCopy()
        current.ages[1] = 21
        current.cultivations[1] = 110.0

        val result = merge(originDisciples, shadow, current)

        assertEquals("修炼取 max=110", 110.0, result.cultivations[1], 0.001)
        assertEquals("年龄取 current", 21, result.ages.getOrDefault(1, 0))
    }

    @Test
    fun `mergeDiscipleTables - cultivation keeps shadow when shadow is higher`() {
        val originTables = DiscipleTables()
        originTables.insertDisciple(createDisciple(id = 1, age = 20, cultivation = 100.0))
        val originDisciples = originTables.assembleAll()

        val shadow = originTables.deepCopy()
        shadow.cultivations[1] = 115.0

        val current = originTables.deepCopy()
        current.ages[1] = 21
        current.cultivations[1] = 108.0

        val result = merge(originDisciples, shadow, current)

        assertEquals("修炼取 shadow=115", 115.0, result.cultivations[1], 0.001)
        assertEquals("年龄取 current", 21, result.ages.getOrDefault(1, 0))
    }
}
