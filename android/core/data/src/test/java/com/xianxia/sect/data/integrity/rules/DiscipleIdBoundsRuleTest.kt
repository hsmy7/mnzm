package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.data.integrity.IntegrityResult
import com.xianxia.sect.data.integrity.SaveValidator
import com.xianxia.sect.data.model.SaveData
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * DiscipleIdBoundsRule 单元测试（C3-b，2026-08-05）。
 *
 * 守卫：crafted 大 id 弟子（恰低于 MAX_SAFE_CAPACITY）触发平铺表
 * 千万级扩容 → OOM 崩溃循环。本规则前置拦截判损坏（走备份恢复）。
 */
class DiscipleIdBoundsRuleTest {

    @Before
    fun setup() {
        SaveValidationRuleRegistry.clear()
        SaveValidationRuleRegistry.register(DiscipleIdBoundsRule)
    }

    @After
    fun teardown() {
        SaveValidationRuleRegistry.clear()
    }

    @Test
    fun `id at exact cap boundary passes`() {
        val data = saveData(listOf(disciple(id = "200000")))
        val result = SaveValidator.validate(data)
        assertTrue("id=200000 为合法边界，实际 $result", result is IntegrityResult.Passed)
    }

    @Test
    fun `id below cap passes`() {
        val data = saveData(listOf(disciple(id = "99999")))
        val result = SaveValidator.validate(data)
        assertTrue("id=99999 合法，实际 $result", result is IntegrityResult.Passed)
    }

    @Test
    fun `id one above cap is corrupted`() {
        val data = saveData(listOf(disciple(id = "200001")))
        val result = SaveValidator.validate(data)
        assertTrue("id=200001 应判损坏，实际 $result", result is IntegrityResult.Corrupted)
    }

    @Test
    fun `crafted 9999999 id is corrupted`() {
        // 根因场景：恰低于原 MAX_SAFE_CAPACITY=10M 的 crafted id
        val data = saveData(listOf(disciple(id = "9999999")))
        val result = SaveValidator.validate(data)
        assertTrue("crafted 大 id 应判损坏，实际 $result", result is IntegrityResult.Corrupted)
    }

    @Test
    fun `negative id is corrupted`() {
        val data = saveData(listOf(disciple(id = "-5")))
        val result = SaveValidator.validate(data)
        assertTrue("负 id 应判损坏，实际 $result", result is IntegrityResult.Corrupted)
    }

    @Test
    fun `non numeric id passes`() {
        // uuid / normalizeDiscipleIds 产物不受影响
        val data = saveData(listOf(disciple(id = "uuid-3f2a-9c1d")))
        val result = SaveValidator.validate(data)
        assertTrue("非数字 id 不误伤，实际 $result", result is IntegrityResult.Passed)
    }

    @Test
    fun `mixed valid and oversized ids - only oversized reported`() {
        val data = saveData(
            listOf(
                disciple(id = "12", name = "正常弟子"),
                disciple(id = "9000000", name = "crafted")
            )
        )
        val result = SaveValidator.validate(data)
        assertTrue("含大 id 应判损坏，实际 $result", result is IntegrityResult.Corrupted)
        val details = (result as IntegrityResult.Corrupted).details
        assertTrue("损坏详情应指明弟子与 id", details.any { it.contains("crafted") && it.contains("9000000") })
    }

    private fun disciple(id: String, name: String = "弟子") = Disciple(
        id = id, name = name, realm = 9, realmLayer = 1, cultivation = 10.0,
        age = 20, lifespan = 80, isAlive = true
    )

    private fun saveData(disciples: List<Disciple>) = SaveData(
        gameData = GameData(sectName = "宗", gameYear = 5, gameMonth = 6),
        disciples = disciples, pills = emptyList(), materials = emptyList(),
        herbs = emptyList(), seeds = emptyList(),
        battleLogs = emptyList()
    )
}
