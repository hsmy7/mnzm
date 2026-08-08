package com.xianxia.sect.core.engine.domain.disciple

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.RecruitIntegrity
import com.xianxia.sect.core.model.recruitedMonth
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
 * 手动招募流程测试（模拟 [DiscipleFacadeImpl.recruitDiscipleFromList] 的核心逻辑）。
 *
 * 直接在 [MutableGameState] 上操作，验证：
 * - 招募成功时 disciple 从 recruitList 移除 → 进入 DiscipleTables
 * - 数据损坏时跳过
 * - 生命周期事件在事务内写入
 */
@RunWith(RobolectricTestRunner::class)
class DiscipleFacadeImplRecruitTest {

    @get:Rule val writeGuardRule = WriteGuardRule()

    /** 模拟 stateStore.update 内的事务逻辑 */
    private fun executeRecruitFlow(
        recruitId: String,
        state: MutableGameState
    ): String {
        val disciple = state.gameData.recruitList.toList().find { it.id == recruitId }
        if (disciple == null) return ""
        if (!RecruitIntegrity.isValidRecruit(disciple)) {
            // 与真实实现对齐：损坏条目同事务移除（幽灵不再残留）
            state.gameData = state.gameData.copy(
                recruitList = state.gameData.recruitList.filter { it.id != recruitId }
            )
            return ""
        }
        val currentMonth = state.gameData.gameYear * 12 + state.gameData.gameMonth
        val recruited = disciple.copy(
            usage = disciple.usage.copy(recruitedMonth = currentMonth)
        )
        val newId = state.discipleTables.allocateAndInsert(recruited)
        if (newId.isNotEmpty()) {
            val intId = newId.toIntOrNull()
            if (intId != null) {
                val events = state.discipleTables.lifeEvents.getOrDefault(intId, emptyList())
                state.discipleTables.lifeEvents[intId] = events + "${disciple.age}岁：加入宗门"
            }
        }
        state.gameData = state.gameData.copy(
            recruitList = state.gameData.recruitList.filter {
                it.id != recruitId && !RecruitIntegrity.isSamePerson(it, recruited)
            }
        )
        return newId
    }

    private fun createRecruitDisciple(
        name: String = "新弟子",
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
    fun `recruit from list - happy path`() {
        val recruitId = UUID.randomUUID().toString()
        val disciple = createRecruitDisciple(id = recruitId)
        val state = createState(recruitList = listOf(disciple))

        val newId = executeRecruitFlow(recruitId, state)

        assertTrue("新 ID 非空", newId.isNotEmpty())
        assertEquals("recruitList 清空", 0, state.gameData.recruitList.size)
        assertEquals("DiscipleTables 新增 1 人", 1, state.discipleTables.ids.size)
        assertEquals("姓名一致", "新弟子", state.discipleTables.assemble(newId.toInt()).name)
    }

    @Test
    fun `recruit from list - not found returns empty`() {
        val state = createState(recruitList = listOf(createRecruitDisciple(id = "real-id")))

        val newId = executeRecruitFlow("non-existent", state)

        assertEquals("空 ID", "", newId)
        assertEquals("DiscipleTables 不变", 0, state.discipleTables.ids.size)
        assertEquals("recruitList 不变", 1, state.gameData.recruitList.size)
    }

    @Test
    fun `recruit from list - corrupted data (blank name) returns empty`() {
        val recruitId = UUID.randomUUID().toString()
        val state = createState(recruitList = listOf(createRecruitDisciple(name = "", id = recruitId)))

        val newId = executeRecruitFlow(recruitId, state)

        assertEquals("空 ID", "", newId)
        assertEquals("DiscipleTables 不变", 0, state.discipleTables.ids.size)
    }

    @Test
    fun `recruit from list - corrupted data (age 0) returns empty`() {
        val recruitId = UUID.randomUUID().toString()
        val state = createState(recruitList = listOf(createRecruitDisciple(age = 0, id = recruitId)))

        val newId = executeRecruitFlow(recruitId, state)

        assertEquals("空 ID", "", newId)
        assertEquals("DiscipleTables 不变", 0, state.discipleTables.ids.size)
    }

    @Test
    fun `recruit from list - realm 0 immortal is recruitable`() {
        val recruitId = UUID.randomUUID().toString()
        val state = createState(recruitList = listOf(createRecruitDisciple(realm = 0, id = recruitId)))

        val newId = executeRecruitFlow(recruitId, state)

        assertTrue("仙人（realm=0）应可招募", newId.isNotEmpty())
        assertEquals(1, state.discipleTables.ids.size)
    }

    @Test
    fun `recruit from list - corrupted entry removed on failed recruit`() {
        val recruitId = UUID.randomUUID().toString()
        val state = createState(recruitList = listOf(createRecruitDisciple(name = "", id = recruitId)))

        val newId = executeRecruitFlow(recruitId, state)

        assertEquals("空 ID", "", newId)
        assertEquals("损坏条目应同事务移除", 0, state.gameData.recruitList.size)
        assertEquals("DiscipleTables 不变", 0, state.discipleTables.ids.size)
    }

    @Test
    fun `recruit from list - twin content entry removed with recruited one`() {
        val recruitId = UUID.randomUUID().toString()
        val twinId = UUID.randomUUID().toString()
        val state = createState(recruitList = listOf(
            createRecruitDisciple(name = "张三", age = 20, id = recruitId),
            createRecruitDisciple(name = "张三", age = 20, id = twinId)
        ))

        val newId = executeRecruitFlow(recruitId, state)

        assertTrue("新 ID 非空", newId.isNotEmpty())
        assertEquals("同内容双胞胎应一并移除", 0, state.gameData.recruitList.size)
        assertEquals("DiscipleTables 只新增 1 人", 1, state.discipleTables.ids.size)
    }

    @Test
    fun `recruit from list - life event is written inside transaction`() {
        val recruitId = UUID.randomUUID().toString()
        val disciple = createRecruitDisciple(name = "张三", age = 22, id = recruitId)
        val state = createState(recruitList = listOf(disciple))

        val newId = executeRecruitFlow(recruitId, state)

        val intId = newId.toInt()
        val events = state.discipleTables.lifeEvents[intId]
        assertNotNull("lifeEvents 应在事务内写入", events)
        assertTrue("事件包含年龄和加入宗门", events?.any { it.contains("22岁：加入宗门") } ?: false)
    }

    @Test
    fun `recruit from list - existing disciples preserved`() {
        val tables = DiscipleTables()
        tables.writeAllowed = true
        tables.allocateAndInsert(createRecruitDisciple(name = "在册弟子"))

        val recruitId = UUID.randomUUID().toString()
        val state = createState(
            recruitList = listOf(createRecruitDisciple(name = "新弟子", id = recruitId))
        )
        state.discipleTables = tables

        val newId = executeRecruitFlow(recruitId, state)

        assertTrue("新 ID 非空", newId.isNotEmpty())
        assertEquals("共有 2 名弟子", 2, state.discipleTables.ids.size)
        val names = state.discipleTables.assembleAll().map { it.name }.sorted()
        assertArrayEquals(arrayOf("在册弟子", "新弟子"), names.toTypedArray())
    }

    @Test
    fun `recruit from list - recruitMonth is set correctly`() {
        val recruitId = UUID.randomUUID().toString()
        val disciple = createRecruitDisciple(id = recruitId)
        val state = createState(recruitList = listOf(disciple))

        val newId = executeRecruitFlow(recruitId, state)
        val intId = newId.toInt()

        val expectedMonth = state.gameData.gameYear * 12 + state.gameData.gameMonth
        assertEquals("recruitedMonth 正确", expectedMonth, state.discipleTables.recruitedMonths[intId])
    }

    @Test
    fun `recruit from list - empty recruitList returns empty`() {
        val state = createState(emptyList())
        assertEquals("", executeRecruitFlow("any-id", state))
    }

    @Test
    fun `recruit from list - multiple recruits one by one`() {
        val id1 = UUID.randomUUID().toString()
        val id2 = UUID.randomUUID().toString()
        val d1 = createRecruitDisciple(name = "甲", id = id1)
        val d2 = createRecruitDisciple(name = "乙", id = id2)
        val state = createState(recruitList = listOf(d1, d2))

        val newId1 = executeRecruitFlow(id1, state)
        assertTrue("甲招募成功", newId1.isNotEmpty())
        assertEquals("乙仍在列表", 1, state.gameData.recruitList.size)
        assertEquals("乙的 ID 正确", id2, state.gameData.recruitList.first().id)

        val newId2 = executeRecruitFlow(id2, state)
        assertTrue("乙招募成功", newId2.isNotEmpty())
        assertEquals("列表清空", 0, state.gameData.recruitList.size)
        assertEquals("共 2 名弟子", 2, state.discipleTables.ids.size)
    }
}
