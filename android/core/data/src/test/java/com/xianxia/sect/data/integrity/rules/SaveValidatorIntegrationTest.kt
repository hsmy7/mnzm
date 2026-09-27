package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.data.integrity.IntegrityResult
import com.xianxia.sect.data.integrity.SaveValidator

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.armorId
import com.xianxia.sect.core.model.weaponId
import com.xianxia.sect.data.model.SaveData
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test





class SaveValidatorIntegrationTest {

    @Before
    fun setup() {
        SaveValidationRuleRegistry.clear()
        SaveValidationRuleRegistry.registerDefaults()
    }

    @After
    fun teardown() {
        SaveValidationRuleRegistry.clear()
    }

    @Test
    fun `valid minimal data returns only recruit list repair`() {
        val data = SaveData(
            gameData = GameData(sectName = "测试宗", gameYear = 5, gameMonth = 6),
            disciples = listOf(makeDisciple()),
            pills = emptyList(), materials = emptyList(), herbs = emptyList(),
            seeds = emptyList()
        )
        val result = SaveValidator.validate(data)
        // 恒空契约：默认注册表下合法存档也恒 Repaired，修复项 = 招募清空 +
        // TimeAxisRule 旧档归一化回填（B3：elapsedGameMs==0 且日历非初值 →
        // calendarToGameMs 换算回填，行为见该规则测试）
        assertTrue("预期仅恒空修复的 Repaired，实际得到 $result", result is IntegrityResult.Repaired)
        val r = result as IntegrityResult.Repaired
        assertEquals(
            listOf(
                "招募链已下线，招募列表清空",
                "旧档归一化：elapsedGameMs 按日历回填为 318000"
            ),
            r.details
        )
        assertTrue(r.data.gameData.recruitList.isEmpty())
        // 回填值与日历投影一致：(5,6) 月初 = 4 年 5 月 × 36 旬 × 2000ms
        assertEquals(318000L, r.data.gameData.elapsedGameMs)
    }

    @Test
    fun `multiple issues across rules all fixed`() {
        val d1 = Disciple(
            id = "d-1", name = "甲", realm = 9, realmLayer = 1, cultivation = 999.0,
            isAlive = true,
            equipment = EquipmentSet(weaponId = "ghost-sword"))
        val d2 = Disciple(
            id = "d-1", name = "乙", realm = 9, realmLayer = 1, cultivation = 10.0,
            isAlive = false,
            equipment = EquipmentSet(armorId = "ghost-armor"))

        val data = SaveData(
            gameData = GameData(sectName = "", gameYear = 0, gameMonth = 0),
            disciples = listOf(d1, d2),
            pills = emptyList(), materials = emptyList(), herbs = emptyList(),
            seeds = emptyList()
        )
        val result = SaveValidator.validate(data)
        assertTrue("预期 Repaired（多项修复），实际得到 $result", result is IntegrityResult.Repaired)
        val r = result as IntegrityResult.Repaired
        assertTrue("应修复 sectName: ${r.details}", r.details.any { it.contains("sectName") })
        assertTrue("应修复日期: ${r.details}", r.details.any { it.contains("year=") || it.contains("时间") })
        assertTrue("应修复 cultivation: ${r.details}", r.details.any { it.contains("cultivation") })
        assertTrue("应涉及 ghost: ${r.details}", r.details.any { it.contains("ghost") })
    }

    @Test
    fun `default registered rules count`() {
        SaveValidationRuleRegistry.clear()
        SaveValidationRuleRegistry.registerDefaults()
        assertTrue("应注册至少 14 条规则", SaveValidationRuleRegistry.size >= 14)
    }

    private fun makeDisciple(id: String = "d-1", name: String = "弟子") = Disciple(
        id = id, name = name, realm = 9, realmLayer = 1, cultivation = 10.0,
        isAlive = true, equipment = EquipmentSet()
    )
}
