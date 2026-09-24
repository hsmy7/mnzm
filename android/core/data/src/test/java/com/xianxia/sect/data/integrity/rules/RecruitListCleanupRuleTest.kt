package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.data.integrity.IntegrityResult
import com.xianxia.sect.data.integrity.SaveValidator
import com.xianxia.sect.data.model.SaveData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * 招募列表恒空规则测试 — 覆盖 [RecruitListCleanupRule] 的恒空契约：
 * 招募链已下线，规则**恒**返回 `Repaired(recruitList = emptyList(), 恒空文案)`——
 * 无论输入列表是否为空（恒 Repaired 以触发落盘清空），且注册表默认仍含
 * `id = recruit_list_cleanup`。
 */
class RecruitListCleanupRuleTest {

    private val emptyNotice = "招募链已下线，招募列表清空"

    @Before
    fun setup() {
        SaveValidationRuleRegistry.clear()
        SaveValidationRuleRegistry.register(RecruitListCleanupRule)
    }

    @After
    fun teardown() {
        SaveValidationRuleRegistry.clear()
    }

    @Test
    fun `validate - 非空招募列表 恒 Repaired 且整表清空`() {
        val gd = GameData(
            sectName = "宗", gameYear = 1, gameMonth = 1,
            recruitList = listOf(
                createRecruit(name = "张三"),
                createRecruit(name = "")
            )
        )
        val result = SaveValidator.validate(saveData(gd))

        assertTrue("非空输入必须 Repaired", result is IntegrityResult.Repaired)
        result as IntegrityResult.Repaired
        assertTrue("recruitList 必须整表清空", result.data.gameData.recruitList.isEmpty())
        assertEquals(listOf(emptyNotice), result.details)
    }

    @Test
    fun `validate - 空招募列表 仍 Repaired（恒空触发落盘）`() {
        val gd = GameData(
            sectName = "宗", gameYear = 1, gameMonth = 1,
            recruitList = emptyList()
        )
        val result = SaveValidator.validate(saveData(gd))

        assertTrue("空输入同样必须 Repaired（恒空语义，触发落盘）", result is IntegrityResult.Repaired)
        result as IntegrityResult.Repaired
        assertTrue(result.data.gameData.recruitList.isEmpty())
        assertEquals(listOf(emptyNotice), result.details)
    }

    @Test
    fun `validate - 同id重复与已入宗残留 输入一律清空`() {
        val gd = GameData(
            sectName = "宗", gameYear = 1, gameMonth = 1,
            recruitList = listOf(
                createRecruit(id = "dup", name = "张三"),
                createRecruit(id = "dup", name = "李四"),
                createRecruit(name = "王五", realm = -1)
            )
        )
        val result = SaveValidator.validate(saveData(gd))

        assertTrue(result is IntegrityResult.Repaired)
        result as IntegrityResult.Repaired
        assertTrue("任何形态的输入都不得保留条目", result.data.gameData.recruitList.isEmpty())
        assertEquals(listOf(emptyNotice), result.details)
    }

    @Test
    fun `validate - 二次校验仍 Repaired（恒空非幂等 Passed）`() {
        val gd = GameData(
            sectName = "宗", gameYear = 1, gameMonth = 1,
            recruitList = listOf(createRecruit(name = "张三"))
        )
        val first = SaveValidator.validate(saveData(gd))
        assertTrue(first is IntegrityResult.Repaired)
        val cleaned = (first as IntegrityResult.Repaired).data

        val second = SaveValidator.validate(cleaned)
        assertTrue("清空后的存档再次校验仍应 Repaired（恒空语义）", second is IntegrityResult.Repaired)
        second as IntegrityResult.Repaired
        assertTrue(second.data.gameData.recruitList.isEmpty())
        assertEquals(listOf(emptyNotice), second.details)
    }

    @Test
    fun `registry - 默认注册表仍含 recruit_list_cleanup`() {
        SaveValidationRuleRegistry.clear()
        SaveValidationRuleRegistry.registerDefaults()

        val rule = SaveValidationRuleRegistry.findById("recruit_list_cleanup")
        assertNotNull("registerDefaults 必须包含 id=recruit_list_cleanup", rule)
        assertEquals(20, rule!!.order)
        assertTrue(rule === RecruitListCleanupRule)
    }

    private fun saveData(
        gd: GameData,
        disciples: List<Disciple> = emptyList()
    ): SaveData = SaveData(
        gameData = gd, disciples = disciples, pills = emptyList(),
        materials = emptyList(), herbs = emptyList(), seeds = emptyList(),
            )

    private fun createRecruit(
        name: String = "弟子",
        realm: Int = 9,
        id: String = UUID.randomUUID().toString()
    ): Disciple = Disciple(
        id = id,
        name = name,
        realm = realm,
        spiritRootType = "金"
    )
}
