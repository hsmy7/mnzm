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
        shadow: DiscipleTables,
        current: DiscipleTables,
        originAliveIds: Set<Int>? = null
    ): DiscipleTables = GameStateStoreImpl.mergeDiscipleTables(
        shadow, current, originAliveIds
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

    // ── 测试 1: 影子保留 cultivation 结算结果 ──────────────────────

    @Test
    fun `mergeDiscipleTables - cultivation from shadow preserved`() {

        val shadow = DiscipleTables()
        shadow.insertDisciple(createDisciple(id = 1, age = 20, cultivation = 100.0))
        shadow.cultivations[1] = 150.0  // 批量结算累积

        val current = DiscipleTables()
        current.insertDisciple(createDisciple(id = 1, age = 21, cultivation = 100.0))  // 老化

        val result = merge(shadow, current)

        assertEquals("修炼进度取 shadow（结算结果）", 150.0, result.cultivations[1], 0.001)
    }

    // ── 测试 2: current 生命周期字段覆盖 shadow ─────────────────────

    @Test
    fun `mergeDiscipleTables - lifecycle fields from current overwrite shadow`() {

        val shadow = DiscipleTables()
        shadow.insertDisciple(createDisciple(
            id = 1, age = 20, cultivation = 100.0,
            status = DiscipleStatus.REFLECTING, morality = 50, loyalty = 50,
            griefEndYear = 6, partnerId = "2", masterId = "3"
        ))

        val current = DiscipleTables()
        current.insertDisciple(createDisciple(
            id = 1, age = 21, cultivation = 100.0,
            status = DiscipleStatus.IDLE, morality = 55, loyalty = 55,
            griefEndYear = null, partnerId = null, masterId = null, lifespan = 85
        ))

        val result = merge(shadow, current)

        assertEquals("年龄取 current", 21, result.ages.getOrDefault(1, 0))
        assertEquals("状态取 current", DiscipleStatus.IDLE, result.statuses[1])
        assertEquals("morality 取 current", 55, result.moralities.getOrDefault(1, 0))
        assertEquals("loyalty 取 current", 55, result.loyalties.getOrDefault(1, 0))
        assertNull("griefEndYear 取 current", result.griefEndYears.getOrNull(1))
        assertNull("partnerId 取 current", result.partnerIds.getOrNull(1))
        assertNull("masterId 取 current", result.masterIds.getOrNull(1))
        assertEquals("lifespan 取 current", 85, result.lifespans.getOrDefault(1, 0))
    }

    // ── 测试 3: 新生儿从 shadow 保留 ───────────────────────────────

    @Test
    fun `mergeDiscipleTables - child birth disciple preserved from shadow`() {

        val shadow = DiscipleTables()
        shadow.insertDisciple(createDisciple(id = 1, age = 20))
        shadow.insertDisciple(createDisciple(id = 3, age = 0))  // 新生儿

        val current = DiscipleTables()
        current.insertDisciple(createDisciple(id = 1, age = 21))

        // originAliveIds = {1} → 3 是新生儿，不在 origin 中 → 保留
        val result = merge(shadow, current, originAliveIds = setOf(1))

        assertTrue("新生儿应从 shadow 保留", 3 in result.ids)
        assertEquals("已有弟子年龄取 current", 21, result.ages.getOrDefault(1, 0))
    }

    // ── 测试 4: current 独有的弟子保留（新招募） ──────────────────

    @Test
    fun `mergeDiscipleTables - current exclusive disciples preserved`() {

        val shadow = DiscipleTables()
        shadow.insertDisciple(createDisciple(id = 1, age = 20))

        val current = DiscipleTables()
        current.insertDisciple(createDisciple(id = 1, age = 21))
        current.insertDisciple(createDisciple(id = 2, age = 18))  // 新招募

        val result = merge(shadow, current)

        assertTrue("弟子 1 存在", 1 in result.ids)
        assertTrue("弟子 2（current 独有）保留", 2 in result.ids)
    }

    // ── 测试 5: 死亡弟子移除 ───────────────────────────────────────

    @Test
    fun `mergeDiscipleTables - dead disciple removed`() {

        val shadow = DiscipleTables()
        shadow.insertDisciple(createDisciple(id = 1, age = 20))
        shadow.insertDisciple(createDisciple(id = 2, age = 80))

        val current = DiscipleTables()
        current.insertDisciple(createDisciple(id = 1, age = 21))  // 弟子 2 已死亡

        // originAliveIds = {1, 2} → 2 在 origin 中但不在 current → 死亡
        val result = merge(shadow, current, originAliveIds = setOf(1, 2))

        assertTrue("弟子 1 存活", 1 in result.ids)
        assertFalse("弟子 2 已死亡移除", 2 in result.ids)
    }

    // ── 测试 6: 不含 originAliveIds 时，shadow-only ID 保守保留 ──

    @Test
    fun `mergeDiscipleTables - without originIds shadow-only disciples are kept`() {

        val shadow = DiscipleTables()
        shadow.insertDisciple(createDisciple(id = 1, age = 20))
        shadow.insertDisciple(createDisciple(id = 2, age = 80))

        val current = DiscipleTables()
        current.insertDisciple(createDisciple(id = 1, age = 21))

        // 无 originAliveIds：无法区分死亡/新生儿，保守保留 shadow-only ID
        val result = merge(shadow, current)

        assertTrue("弟子 1 存在", 1 in result.ids)
        assertTrue("弟子 2（shadow only，无 origin 时保守保留）", 2 in result.ids)
    }

    // ── 测试 7: realm/realmLayer/isAlive 取 current（生命周期字段）────

    @Test
    fun `mergeDiscipleTables - realm realmLayer isAlive from current`() {

        val shadow = DiscipleTables()
        shadow.insertDisciple(createDisciple(id = 1, age = 20, realm = 9, realmLayer = 5))

        val current = DiscipleTables()
        current.insertDisciple(createDisciple(id = 1, age = 21, realm = 8, realmLayer = 1))

        val result = merge(shadow, current)

        assertEquals("realm 取 current", 8, result.realms.getOrDefault(1, 0))
        assertEquals("realmLayer 取 current", 1, result.realmLayers.getOrDefault(1, 0))
        assertEquals("isAlive 取 current", 1, result.isAlive.getOrDefault(1, 0))
    }

    // ── 测试 8: 混合场景 — shadow 结算结果 + current 生命周期 ────

    @Test
    fun `mergeDiscipleTables - mixed shadow cultivation and current lifecycle`() {

        val shadow = DiscipleTables()
        shadow.insertDisciple(createDisciple(
            id = 1, age = 20, cultivation = 100.0,
            realm = 9, realmLayer = 5, lifespan = 80,
            status = DiscipleStatus.IDLE, morality = 50
        ))
        shadow.cultivations[1] = 200.0    // 批量结算
        shadow.insertDisciple(createDisciple(id = 3, age = 0))  // 新生儿

        val current = DiscipleTables()
        current.insertDisciple(createDisciple(
            id = 1, age = 21, cultivation = 100.0,
            realm = 8, realmLayer = 2,             // 突破
            status = DiscipleStatus.PREACHING, morality = 60
        ))
        current.insertDisciple(createDisciple(id = 2, age = 18))  // 新招募

        // originAliveIds = {1} → 3 是新生儿，保留
        val result = merge(shadow, current, originAliveIds = setOf(1))

        // Shadow 的结算结果
        assertEquals("修炼取 shadow", 200.0, result.cultivations[1], 0.001)
        assertTrue("新生儿保留", 3 in result.ids)

        // Current 的生命周期（HP/MP/realm 等也取 current）
        assertEquals("年龄取 current", 21, result.ages.getOrDefault(1, 0))
        assertEquals("realm 取 current", 8, result.realms.getOrDefault(1, 0))
        assertEquals("realmLayer 取 current", 2, result.realmLayers.getOrDefault(1, 0))
        assertEquals("status 取 current", DiscipleStatus.PREACHING, result.statuses[1])
        assertEquals("morality 取 current", 60, result.moralities.getOrDefault(1, 0))

        // Current 的独有弟子
        assertTrue("新招募保留", 2 in result.ids)
        assertEquals("弟子数 = 3", 3, result.ids.size)
    }
}
