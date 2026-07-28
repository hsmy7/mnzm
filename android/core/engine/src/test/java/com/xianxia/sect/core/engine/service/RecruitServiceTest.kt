package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.engine.domain.disciple.DiscipleFactory
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.util.DeterministicRng
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.UUID

/**
 * 招募服务单元测试 — 覆盖 [RecruitService] 的批量/自动招募逻辑。
 *
 * 注：refreshRecruitList 的完整测试依赖真实 GameStateStore，当前聚焦
 * processAutoRecruit 的边界条件和 calcRecruitBonusCap 的算术正确性。
 */
class RecruitServiceTest {

    // ==================== calcRecruitBonusCap ====================

    @Test
    fun `calcRecruitBonusCap - charm below 80 returns 0`() {
        assertEquals(0, RecruitService.calcRecruitBonusCap(50))
        assertEquals(0, RecruitService.calcRecruitBonusCap(79))
    }

    @Test
    fun `calcRecruitBonusCap - charm at 80 returns 0`() {
        assertEquals(0, RecruitService.calcRecruitBonusCap(80))
    }

    @Test
    fun `calcRecruitBonusCap - charm 84 returns 1`() {
        assertEquals(1, RecruitService.calcRecruitBonusCap(84))
    }

    @Test
    fun `calcRecruitBonusCap - charm 100 returns 5`() {
        assertEquals(5, RecruitService.calcRecruitBonusCap(100))
    }

    @Test
    fun `calcRecruitBonusCap - boundary rounding`() {
        assertEquals(0, RecruitService.calcRecruitBonusCap(83)) // (83-80)/4 = 0
        assertEquals(1, RecruitService.calcRecruitBonusCap(84)) // (84-80)/4 = 1
        assertEquals(1, RecruitService.calcRecruitBonusCap(87)) // (87-80)/4 = 1
    }

    @Test
    fun `calcRecruitBonusCap - very high charm caps at MAX_RECRUIT_BONUS_CAP 20`() {
        assertEquals(20, RecruitService.calcRecruitBonusCap(200)) // (200-80)/4 = 30 → 上限 20
        assertEquals(20, RecruitService.calcRecruitBonusCap(1000)) // (1000-80)/4 = 230 → 上限 20
    }

    // ==================== processAutoRecruit ====================

    /** 创建测试用 MutableGameState，含空的 DiscipleTables 并开放写权限。 */
    private fun createAutoRecruitState(
        recruitList: List<Disciple>,
        filter: Set<Int> = emptySet()
    ): MutableGameState {
        val tables = DiscipleTables()
        tables.writeAllowed = true
        return MutableGameState(
            gameData = GameData(
                recruitList = recruitList,
                autoRecruitSpiritRootFilter = filter
            ),
            discipleTables = tables,
            equipmentStacks = EntityStore(),
            equipmentInstances = EntityStore(),
            manualStacks = EntityStore(),
            manualInstances = EntityStore(),
            pills = EntityStore(),
            materials = EntityStore(),
            herbs = EntityStore(),
            seeds = EntityStore(),
            storageBags = EntityStore(),
            teams = emptyList(),
            battleLogs = emptyList(),
            isPaused = false,
            isLoading = false,
            isSaving = false
        )
    }

    /** 创建测试用弟子 */
    private fun makeRecruit(
        id: String = "test_${UUID.randomUUID()}",
        name: String = "测试弟子",
        age: Int = 20,
        realm: Int = 9,
        spiritRootType: String = "金,木,水"
    ): Disciple = Disciple(
        id = id,
        name = name,
        age = age,
        realm = realm,
        spiritRootType = spiritRootType
    )

    @Test
    fun `processAutoRecruit with matching filter recruits matching disciples`() {
        val disciple = makeRecruit(spiritRootType = "金,木,水")  // 3 roots
        val state = createAutoRecruitState(
            recruitList = listOf(disciple),
            filter = setOf(3)  // 三灵根
        )

        val count = RecruitService.processAutoRecruit(state)

        assertEquals(1, count)
        assertTrue("自动招募后 recruitList 应为空", state.gameData.recruitList.isEmpty())
        assertEquals("弟子应已加入 discipleTables", 1, state.discipleTables.ids.size)
        val recruitedId = state.discipleTables.ids.first()
        assertEquals("弟子境界应匹配", disciple.realm, state.discipleTables.realms[recruitedId])
    }

    @Test
    fun `processAutoRecruit with empty filter recruits nothing`() {
        val disciple = makeRecruit(spiritRootType = "金,木,水")
        val state = createAutoRecruitState(
            recruitList = listOf(disciple),
            filter = emptySet()
        )

        val count = RecruitService.processAutoRecruit(state)

        assertEquals(0, count)
        assertTrue("不应有弟子上架", state.discipleTables.ids.isEmpty())
        assertEquals("recruitList 应保持不变", 1, state.gameData.recruitList.size)
    }

    @Test
    fun `processAutoRecruit filters non-matching root counts`() {
        val disciple = makeRecruit(spiritRootType = "金,木,水", id = "id1")  // 3 roots
        val state = createAutoRecruitState(
            recruitList = listOf(disciple),
            filter = setOf(1, 5)  // 只收单灵根/五灵根
        )

        val count = RecruitService.processAutoRecruit(state)

        assertEquals(0, count)
        assertTrue("不应有弟子上架", state.discipleTables.ids.isEmpty())
        assertEquals("弟子应留在 recruitList", 1, state.gameData.recruitList.size)
    }

    @Test
    fun `processAutoRecruit handles mixed matches and non-matches`() {
        val match = makeRecruit("id1", "单灵根弟子", spiritRootType = "金")
        val noMatch = makeRecruit("id2", "三灵根弟子", spiritRootType = "金,木,水")
        val state = createAutoRecruitState(
            recruitList = listOf(match, noMatch),
            filter = setOf(1)
        )

        val count = RecruitService.processAutoRecruit(state)

        assertEquals(1, count)
        assertTrue("recruitList 应只剩 1 人", state.gameData.recruitList.size == 1)
        assertEquals("剩下的是不匹配的弟子", "三灵根弟子", state.gameData.recruitList.first().name)
    }

    @Test
    fun `processAutoRecruit skips corrupted disciples with blank name`() {
        val state = createAutoRecruitState(
            recruitList = listOf(makeRecruit(name = "")),
            filter = setOf(3)
        )
        assertEquals(0, RecruitService.processAutoRecruit(state))
        assertTrue(state.discipleTables.ids.isEmpty())
    }

    @Test
    fun `processAutoRecruit skips corrupted disciples with age zero`() {
        val state = createAutoRecruitState(
            recruitList = listOf(makeRecruit(age = 0)),
            filter = setOf(3)
        )
        assertEquals(0, RecruitService.processAutoRecruit(state))
        assertTrue(state.discipleTables.ids.isEmpty())
    }

    @Test
    fun `processAutoRecruit skips corrupted disciples with realm out of range`() {
        val state = createAutoRecruitState(
            recruitList = listOf(makeRecruit(realm = -1)),
            filter = setOf(3)
        )
        assertEquals(0, RecruitService.processAutoRecruit(state))
        assertTrue(state.discipleTables.ids.isEmpty())
    }

    @Test
    fun `processAutoRecruit with empty recruitList returns 0`() {
        val state = createAutoRecruitState(emptyList(), filter = setOf(1, 2, 3))
        assertEquals(0, RecruitService.processAutoRecruit(state))
    }

    @Test
    fun `processAutoRecruit recruits newborn age 1 disciple`() {
        val state = createAutoRecruitState(
            recruitList = listOf(makeRecruit(age = 1, spiritRootType = "金")),
            filter = setOf(1)
        )
        assertEquals(1, RecruitService.processAutoRecruit(state))
        assertTrue("新生儿应被自动招募", state.discipleTables.ids.isNotEmpty())
    }

    @Test
    fun `processAutoRecruit with invalid filter values filters them out`() {
        // filter 中混入 0 和 6（无效值），应被剔除
        val disciple = makeRecruit(spiritRootType = "金,木,水") // 3 roots
        val state = createAutoRecruitState(
            recruitList = listOf(disciple),
            filter = setOf(0, 3, 6)
        )
        assertEquals(1, RecruitService.processAutoRecruit(state))
    }

    @Test
    fun `processAutoRecruit with only invalid filter values returns 0`() {
        val state = createAutoRecruitState(
            recruitList = listOf(makeRecruit()),
            filter = setOf(0, 6, 999)
        )
        assertEquals(0, RecruitService.processAutoRecruit(state))
    }
}
