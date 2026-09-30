package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipAffixSet
import com.xianxia.sect.core.model.EquipGrowth
import com.xianxia.sect.core.model.EquipInstanceMeta
import com.xianxia.sect.core.model.EquipStat
import com.xianxia.sect.core.model.EquipStatValue
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.data.integrity.IntegrityResult
import com.xianxia.sect.data.integrity.SaveValidator
import com.xianxia.sect.data.model.SaveData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 装备无硬上限守卫（0.2-2 拍板 / S18，方案 §6.1）。
 *
 * 装备实例 1 件 1 槽后容量模型 = **无硬上限**：EntityCountBoundsRule 对装备
 * 只告警不截断（warn 800 / 页面提示 1200），不走溢出邮件——溢出邮件的装备
 * 分支必须保持零调用（唯一合法装备键 = 存档补偿 equipment_legacy_compensation）。
 */
class EquipmentNoCapGuardTest {

    @Before
    fun setup() {
        SaveValidationRuleRegistry.clear()
        SaveValidationRuleRegistry.register(EntityCountBoundsRule)
    }

    @After
    fun teardown() {
        SaveValidationRuleRegistry.clear()
    }

    private fun instance(id: String) = EquipmentInstance(
        id = id,
        name = "部件$id",
        setId = "lietian",
        part = EquipmentSlot.WEAPON,
        growth = EquipGrowth(affix = EquipAffixSet(mainStat = EquipStatValue(EquipStat.ATTACK, 0.0))),
        meta = EquipInstanceMeta()
    )

    private fun saveData(instances: List<EquipmentInstance>): SaveData = SaveData(
        gameData = GameData(sectName = "宗", gameYear = 1, gameMonth = 1),
        disciples = listOf(Disciple(id = "d-1", name = "甲", realm = 9)),
        equipmentInstances = instances,
        pills = emptyList(),
        materials = emptyList(),
        herbs = emptyList(),
        seeds = emptyList()
    )

    @Test
    fun `警告阈值内通过`() {
        val data = saveData((1..800).map { instance("e$it") })
        assertEquals(IntegrityResult.Passed, SaveValidator.validate(data))
    }

    @Test
    fun `超警告阈值只告警不截断`() {
        val count = 801
        val data = saveData((1..count).map { instance("e$it") })
        val result = SaveValidator.validate(data)
        assertTrue("超阈值应产出告警（Repaired/Passed 带说明，而非截断）", result is IntegrityResult.Repaired)
        result as IntegrityResult.Repaired
        assertEquals("装备实例数量不得被截断", count, result.data.equipmentInstances.size)
        assertTrue(
            "告警详情应点名装备实例阈值",
            result.details.any { it.contains("装备实例") && it.contains("800") }
        )
    }

    @Test
    fun `超页面提示阈值仍不截断`() {
        val count = EntityCountBoundsRule.EQUIPMENT_INSTANCE_PAGE_HINT_THRESHOLD + 50
        val data = saveData((1..count).map { instance("e$it") })
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        result as IntegrityResult.Repaired
        assertEquals("无硬上限：装备数量原样保留", count, result.data.equipmentInstances.size)
        assertEquals("页面提示阈值应为 1200", 1200, EntityCountBoundsRule.EQUIPMENT_INSTANCE_PAGE_HINT_THRESHOLD)
    }

    @Test
    fun `弟子悬空引用清理不受装备无截断影响`() {
        // 功法堆叠截断的清理分支只处理 manualIds；装备实例无截断、弟子六槽不受影响
        val d = Disciple(
            id = "d-1", name = "甲", realm = 9, realmLayer = 1, cultivation = 10.0, isAlive = true,
            equipment = EquipmentSet(weaponId = "e1")
        )
        val data = SaveData(
            gameData = GameData(sectName = "宗", gameYear = 1, gameMonth = 1),
            disciples = listOf(d),
            equipmentInstances = (1..801).map { instance("e$it") },
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList()
        )
        val result = SaveValidator.validate(data)
        assertTrue(result is IntegrityResult.Repaired)
        result as IntegrityResult.Repaired
        assertEquals("装备引用不因数量告警被清除", "e1", result.data.disciples.single().equipment.weaponId)
    }

    @Test
    fun `溢出邮件装备分支零调用`() {
        // S18：装备容量不走溢出邮件。OverflowMailSender 的装备来源键白名单
        // = { equipment_legacy_compensation }（B3 存档补偿唯一通道），
        // 出现第二把装备键（如容量溢出分支复活）即红。
        val file = File(
            "../../core/engine/src/main/java/com/xianxia/sect/core/engine/service/OverflowMailSender.kt"
        )
        assertTrue("OverflowMailSender.kt 不可达：${file.absolutePath}", file.isFile)
        val source = file.readText()
        val equipmentKeys = Regex("\"(equipment[A-Za-z_]*)\"").findAll(source)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(
            "溢出邮件装备来源键白名单漂移（装备容量溢出分支不得复活，0.2-2 拍板）",
            setOf("equipment_legacy_compensation"), equipmentKeys
        )
    }
}
