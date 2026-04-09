package com.xianxia.sect.domain.usecase

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*

class DiscipleManagementUseCaseTest {

    @Mock
    private lateinit var gameEngine: GameEngine

    private lateinit var useCase: DiscipleManagementUseCase

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        useCase = DiscipleManagementUseCase(gameEngine)
    }

    private fun createValidDisciple(
        id: String = "d1",
        name: String = "TestDisciple",
        age: Int = 16,
        lifespan: Int = 80,
        loyalty: Int = 50,
        realm: Int = 9,
        status: DiscipleStatus = DiscipleStatus.IDLE
    ): Disciple {
        return Disciple(
            id = id,
            name = name,
            age = age,
            lifespan = lifespan,
            status = status,
            skills = com.xianxia.sect.core.model.SkillStats(loyalty = loyalty),
            realm = realm
        )
    }

    // ========== recruitDisciple 测试 ==========

    @Test
    fun `recruitDisciple - 弟子不存在于 recruitList 返回失败`() {
        val result = useCase.recruitDisciple("nonexistent", emptyList(), 0)

        assertFalse(result.success)
        assertTrue(result.message!!.contains("不存在"))
        assertNull(result.disciple)
    }

    @Test
    fun `recruitDisciple - 当前弟子数已达上限返回失败`() {
        val disciple = createValidDisciple()
        val recruitList = listOf(disciple)

        val result = useCase.recruitDisciple("d1", recruitList, 1000)

        assertFalse(result.success)
        assertTrue(result.message!!.contains("上限"))
    }

    @Test
    fun `recruitDisciple - id为空返回数据异常`() {
        val disciple = createValidDisciple(id = "")
        val recruitList = listOf(disciple)

        val result = useCase.recruitDisciple("", recruitList, 0)

        assertFalse(result.success)
        assertTrue(result.message!!.contains("异常"))
    }

    @Test
    fun `recruitDisciple - name为空返回数据异常`() {
        val disciple = createValidDisciple(name = "")
        val recruitList = listOf(disciple)

        val result = useCase.recruitDisciple("d1", recruitList, 0)

        assertFalse(result.success)
        assertTrue(result.message!!.contains("异常"))
    }

    @Test
    fun `recruitDisciple - age小于最小值返回数据异常`() {
        val disciple = createValidDisciple(age = 3)
        val recruitList = listOf(disciple)

        val result = useCase.recruitDisciple("d1", recruitList, 0)

        assertFalse(result.success)
        assertTrue(result.message!!.contains("异常"))
    }

    @Test
    fun `recruitDisciple - age大于最大值返回数据异常`() {
        val disciple = createValidDisciple(age = 101)
        val recruitList = listOf(disciple)

        val result = useCase.recruitDisciple("d1", recruitList, 0)

        assertFalse(result.success)
        assertTrue(result.message!!.contains("异常"))
    }

    @Test
    fun `recruitDisciple - lifespan等于0返回数据异常`() {
        val disciple = createValidDisciple(lifespan = 0)
        val recruitList = listOf(disciple)

        val result = useCase.recruitDisciple("d1", recruitList, 0)

        assertFalse(result.success)
        assertTrue(result.message!!.contains("异常"))
    }

    @Test
    fun `recruitDisciple - lifespan小于0返回数据异常`() {
        val disciple = createValidDisciple(lifespan = -5)
        val recruitList = listOf(disciple)

        val result = useCase.recruitDisciple("d1", recruitList, 0)

        assertFalse(result.success)
        assertTrue(result.message!!.contains("异常"))
    }

    @Test
    fun `recruitDisciple - loyalty低于最小值返回数据异常`() {
        val disciple = createValidDisciple(loyalty = -1)
        val recruitList = listOf(disciple)

        val result = useCase.recruitDisciple("d1", recruitList, 0)

        assertFalse(result.success)
        assertTrue(result.message!!.contains("异常"))
    }

    @Test
    fun `recruitDisciple - loyalty高于最大值返回数据异常`() {
        val disciple = createValidDisciple(loyalty = 101)
        val recruitList = listOf(disciple)

        val result = useCase.recruitDisciple("d1", recruitList, 0)

        assertFalse(result.success)
        assertTrue(result.message!!.contains("异常"))
    }

    @Test
    fun `recruitDisciple - realm低于0返回数据异常`() {
        val disciple = createValidDisciple(realm = -1)
        val recruitList = listOf(disciple)

        val result = useCase.recruitDisciple("d1", recruitList, 0)

        assertFalse(result.success)
        assertTrue(result.message!!.contains("异常"))
    }

    @Test
    fun `recruitDisciple - realm高于9返回数据异常`() {
        val disciple = createValidDisciple(realm = 10)
        val recruitList = listOf(disciple)

        val result = useCase.recruitDisciple("d1", recruitList, 0)

        assertFalse(result.success)
        assertTrue(result.message!!.contains("异常"))
    }

    @Test
    fun `recruitDisciple - 正常招募成功`() {
        val disciple = createValidDisciple()
        val recruitList = listOf(disciple)

        val result = useCase.recruitDisciple("d1", recruitList, 0)

        assertTrue(result.success)
        assertNotNull(result.disciple)
        assertEquals("d1", result.disciple!!.id)
        verify(gameEngine).recruitDiscipleFromList("d1")
    }

    // ========== recruitAllDisciples 测试 ==========

    @Test
    fun `recruitAllDisciples - 已达上限返回失败`() {
        val disciples = listOf(createValidDisciple(), createValidDisciple(id = "d2"))

        val result = useCase.recruitAllDisciples(disciples, 50000L, 1000)

        assertFalse(result.success)
        assertTrue(result.message!!.contains("上限"))
    }

    @Test
    fun `recruitAllDisciples - 空列表返回成功招募0人`() {
        val result = useCase.recruitAllDisciples(emptyList(), 50000L, 0)

        assertTrue(result.success)
        verify(gameEngine, never()).recruitDiscipleFromList(any())
    }

    @Test
    fun `recruitAllDisciples - 部分数据异常只招募有效弟子`() {
        val valid1 = createValidDisciple(id = "valid1")
        val invalid = createValidDisciple(id = "invalid", name = "")
        val valid2 = createValidDisciple(id = "valid2")
        val recruitList = listOf(valid1, invalid, valid2)

        val result = useCase.recruitAllDisciples(recruitList, 50000L, 0)

        assertTrue(result.success)
        verify(gameEngine).recruitDiscipleFromList("valid1")
        verify(gameEngine, never()).recruitDiscipleFromList("invalid")
        verify(gameEngine).recruitDiscipleFromList("valid2")
    }

    @Test
    fun `recruitAllDisciples - 全部有效全部招募`() {
        val d1 = createValidDisciple(id = "d1")
        val d2 = createValidDisciple(id = "d2")
        val d3 = createValidDisciple(id = "d3")
        val recruitList = listOf(d1, d2, d3)

        val result = useCase.recruitAllDisciples(recruitList, 50000L, 997)

        assertTrue(result.success)
        verify(gameEngine).recruitDiscipleFromList("d1")
        verify(gameEngine).recruitDiscipleFromList("d2")
        verify(gameEngine).recruitDiscipleFromList("d3")
    }

    // ========== expelDisciple 测试 ==========

    @Test
    fun `expelDisciple - 弟子不存在返回失败`() {
        val result = useCase.expelDisciple("nonexistent", emptyList())

        assertFalse(result.success)
        assertTrue(result.message!!.contains("不存在"))
    }

    @Test
    fun `expelDisciple - 存在的弟子驱逐成功`() {
        val disciple = createValidDisciple()
        val disciples = listOf(disciple)

        val result = useCase.expelDisciple("d1", disciples)

        assertTrue(result.success)
        verify(gameEngine).expelDisciple("d1")
    }

    // ========== getAvailableDisciplesForPosition 测试 ==========

    @Test
    fun `getAvailableDisciplesForPosition - 按 realm 过滤只返回 realm 小于等于 minRealm 的`() {
        val d1 = createValidDisciple(id = "d1", realm = 9)
        val d2 = createValidDisciple(id = "d2", realm = 7)
        val d3 = createValidDisciple(id = "d3", realm = 5)
        val disciples = listOf(d1, d2, d3)

        val result = useCase.getAvailableDisciplesForPosition(disciples, minRealm = 7)

        assertEquals(2, result.size)
        assertTrue(result.all { it.realm <= 7 })
    }

    @Test
    fun `getAvailableDisciplesForPosition - 按 status 过滤只返回 IDLE 状态的`() {
        val idle = createValidDisciple(id = "idle", status = DiscipleStatus.IDLE)
        val onMission = createValidDisciple(id = "onMission", status = DiscipleStatus.ON_MISSION)
        val inTeam = createValidDisciple(id = "inTeam", status = DiscipleStatus.IN_TEAM)
        val disciples = listOf(idle, onMission, inTeam)

        val result = useCase.getAvailableDisciplesForPosition(disciples, minRealm = 9)

        assertEquals(1, result.size)
        assertEquals(DiscipleStatus.IDLE, result.first().status)
    }

    @Test
    fun `getAvailableDisciplesForPosition - 按 excludeIds 排除指定 ID`() {
        val d1 = createValidDisciple(id = "d1")
        val d2 = createValidDisciple(id = "d2")
        val d3 = createValidDisciple(id = "d3")
        val disciples = listOf(d1, d2, d3)

        val result = useCase.getAvailableDisciplesForPosition(
            disciples,
            minRealm = 9,
            excludeIds = setOf("d2")
        )

        assertEquals(2, result.size)
        assertTrue(result.none { it.id == "d2" })
    }

    @Test
    fun `getAvailableDisciplesForPosition - 空列表返回空列表`() {
        val result = useCase.getAvailableDisciplesForPosition(emptyList(), minRealm = 9)

        assertTrue(result.isEmpty())
    }
}
