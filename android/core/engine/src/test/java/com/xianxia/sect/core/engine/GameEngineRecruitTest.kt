package com.xianxia.sect.core.engine

import com.xianxia.sect.core.engine.service.RecruitService
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.RecruitIntegrity
import com.xianxia.sect.core.model.UsageTracking
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.EntityStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.WriteGuardRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * 批量招募测试（模拟 [GameEngine.recruitAllFromList] 的核心逻辑）。
 *
 * 直接在 [MutableGameState] 上操作，验证：
 * - 批量招募成功时全部进入 DiscipleTables
 * - 部分数据损坏时仅招募有效部分
 * - 完全损坏时全部跳过
 */
@RunWith(RobolectricTestRunner::class)
class GameEngineRecruitTest {

    @get:Rule val writeGuardRule = WriteGuardRule()

    /** 模拟 recruitAllFromList 的事务内逻辑（纯 [MutableGameState] 操作） */
    private fun executeRecruitAll(state: MutableGameState): Int {
        // 模拟事务开头净化：损坏/重复/残留条目移除（与真实实现一致）
        val sanitized = RecruitService.sanitizeRecruitList(state)
        val validRecruits = state.gameData.recruitList
            .filter(RecruitIntegrity::isValidRecruit)
        if (validRecruits.isEmpty()) return 0

        val currentMonth = state.gameData.gameYear * 12 + state.gameData.gameMonth
        validRecruits.forEach { disciple ->
            state.discipleTables.allocateAndInsert(
                disciple.copy(usage = disciple.usage.copy(recruitedMonth = currentMonth))
            )
        }
        state.gameData = state.gameData.copy(recruitList = emptyList())
        return validRecruits.size
    }

    private fun createRecruit(
        name: String = "测试弟子",
        age: Int = 20,
        realm: Int = 9,
        id: String = UUID.randomUUID().toString()
    ): Disciple = Disciple(
        id = id,
        name = name,
        age = age,
        realm = realm,
        spiritRootType = "金"
    )

    private fun createState(recruitList: List<Disciple> = emptyList()): MutableGameState {
        val tables = DiscipleTables()
        tables.writeAllowed = true
        return MutableGameState(
            gameData = GameData(recruitList = recruitList),
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

    @Test
    fun `recruit all - happy path`() {
        val state = createState(
            recruitList = (1..5).map { createRecruit(name = "弟子$it") }
        )

        val count = executeRecruitAll(state)

        assertEquals("应招募 5 人", 5, count)
        assertEquals("recruitList 清空", 0, state.gameData.recruitList.size)
        assertEquals("DiscipleTables 新增 5 人", 5, state.discipleTables.ids.size)
    }

    @Test
    fun `recruit all - empty list returns 0`() {
        val state = createState(emptyList())

        assertEquals(0, executeRecruitAll(state))
        assertEquals(0, state.discipleTables.ids.size)
    }

    @Test
    fun `recruit all - partial corruption recruits valid only`() {
        val validId = UUID.randomUUID().toString()
        val state = createState(recruitList = listOf(
            createRecruit(name = "有效弟子", id = validId),
            createRecruit(name = "", age = 20, realm = 9),           // blank name
            createRecruit(name = "零岁", age = 0, realm = 9),        // age 0
            createRecruit(name = "仙人", age = 20, realm = 0),       // realm 0 仙人合法
        ))

        val count = executeRecruitAll(state)

        assertEquals("应招募 2 人（有效者+仙人）", 2, count)
        assertEquals("recruitList 清空", 0, state.gameData.recruitList.size)
        assertEquals("DiscipleTables 新增 2 人", 2, state.discipleTables.ids.size)
    }

    @Test
    fun `recruit all - all corrupted returns 0`() {
        val state = createState(recruitList = listOf(
            createRecruit(name = "", id = UUID.randomUUID().toString()),  // 全部损坏
            createRecruit(name = "x", age = 0, id = UUID.randomUUID().toString()),
        ))

        val count = executeRecruitAll(state)

        assertEquals("应招募 0 人", 0, count)
        assertEquals("损坏条目应被事务开头净化移除", 0, state.gameData.recruitList.size)
        assertEquals("DiscipleTables 不变", 0, state.discipleTables.ids.size)
    }

    @Test
    fun `recruit all - 10 recruits in one batch`() {
        val state = createState(
            recruitList = (1..10).map { createRecruit(name = "弟子$it") }
        )

        val count = executeRecruitAll(state)

        assertEquals(10, count)
        assertEquals(0, state.gameData.recruitList.size)
        assertEquals(10, state.discipleTables.ids.size)
        val names = state.discipleTables.assembleAll().map { it.name }
            .sortedWith(compareBy({ it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }))
        assertEquals("弟子1", names.first())
        assertEquals("弟子10", names.last())
    }

    @Test
    fun `recruit all - recruitedMonth is set for each disciple`() {
        val state = createState(
            recruitList = (1..3).map { createRecruit(name = "弟子$it") }
        )
        val expectedMonth = state.gameData.gameYear * 12 + state.gameData.gameMonth

        val count = executeRecruitAll(state)

        assertEquals(3, count)
        for (id in state.discipleTables.ids) {
            assertEquals("recruitedMonth 正确", expectedMonth, state.discipleTables.recruitedMonths[id])
        }
    }

    @Test
    fun `recruit all - duplicate ids recruit once`() {
        val dupId = UUID.randomUUID().toString()
        val state = createState(recruitList = listOf(
            createRecruit(name = "张三", id = dupId),
            createRecruit(name = "李四", id = dupId)
        ))

        val count = executeRecruitAll(state)

        assertEquals("同 id 两条应只招募 1 人", 1, count)
        assertEquals("DiscipleTables 只新增 1 人", 1, state.discipleTables.ids.size)
        assertEquals("recruitList 应清空（同 id 残余一并移除）",
            0, state.gameData.recruitList.size)
    }

    @Test
    fun `recruit all - twin content different ids recruit once`() {
        val state = createState(recruitList = listOf(
            createRecruit(name = "张三", age = 20),
            createRecruit(name = "张三", age = 20)
        ))

        val count = executeRecruitAll(state)

        assertEquals("同内容双胞胎应只招募 1 人", 1, count)
        assertEquals("DiscipleTables 只新增 1 人", 1, state.discipleTables.ids.size)
    }

    @Test
    fun `recruit all - realm zero immortal is valid`() {
        val state = createState(recruitList = listOf(
            createRecruit(name = "仙人弟子", realm = 0)
        ))

        val count = executeRecruitAll(state)

        assertEquals("realm=0（仙人）应可招募", 1, count)
    }
}
