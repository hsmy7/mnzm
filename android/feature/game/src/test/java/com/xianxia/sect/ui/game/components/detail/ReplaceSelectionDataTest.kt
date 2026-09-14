package com.xianxia.sect.ui.game.components.detail

import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.ManualType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更换界面纯逻辑测试：
 * 列表构建（排序/过滤/心法置底禁用）与详情构建（四区域数据）全覆盖。
 */
class ReplaceSelectionDataTest {

    // ==================== 夹具 ====================

    private fun manualStack(
        id: String,
        name: String,
        rarity: Int,
        type: ManualType = ManualType.ATTACK,
        minRealm: Int = 9
    ) = ManualStack(id = id, name = name, rarity = rarity, type = type, minRealm = minRealm)

    private fun equipmentStack(
        id: String,
        name: String,
        rarity: Int,
        slot: EquipmentSlot = EquipmentSlot.WEAPON,
        minRealm: Int = 9
    ) = EquipmentStack(id = id, name = name, rarity = rarity, slot = slot, minRealm = minRealm)

    private fun equipmentInstance(
        id: String,
        name: String,
        rarity: Int,
        slot: EquipmentSlot = EquipmentSlot.WEAPON,
        ownerId: String? = null,
        minRealm: Int = 9
    ) = EquipmentInstance(id = id, name = name, rarity = rarity, slot = slot, ownerId = ownerId, minRealm = minRealm)

    // ==================== buildManualReplaceItems ====================

    @Test
    fun `buildManualReplaceItems - 品阶降序排列`() {
        val items = buildManualReplaceItems(
            stacks = listOf(
                manualStack("a", "甲", 2),
                manualStack("b", "乙", 6),
                manualStack("c", "丙", 1),
                manualStack("d", "丁", 4)
            ),
            learnedNames = emptySet(),
            discipleRealm = 9,
            mindItemsDisabled = false
        )
        assertEquals(listOf("乙", "丁", "甲", "丙"), items.map { it.name })
    }

    @Test
    fun `buildManualReplaceItems - 同品阶按名称升序`() {
        val items = buildManualReplaceItems(
            stacks = listOf(
                manualStack("a", "B法", 3),
                manualStack("b", "A法", 3),
                manualStack("c", "C法", 3)
            ),
            learnedNames = emptySet(),
            discipleRealm = 9,
            mindItemsDisabled = false
        )
        // 名称按字符串字典序升序（UTF-16 序，与既有 sortedByWatchedThenRarity 语义一致）
        assertEquals(listOf("A法", "B法", "C法"), items.map { it.name })
    }

    @Test
    fun `buildManualReplaceItems - 关注优先于品阶`() {
        val items = buildManualReplaceItems(
            stacks = listOf(
                manualStack("a", "高品未关注", 6),
                manualStack("b", "低品已关注", 1)
            ),
            learnedNames = emptySet(),
            discipleRealm = 9,
            mindItemsDisabled = false,
            watchedKeys = setOf("manual:低品已关注")
        )
        // 已关注（低品）排在前，未关注（高品）排在后（与原 sortedByWatchedThenRarity 一致）
        assertEquals(listOf("低品已关注", "高品未关注"), items.map { it.name })
    }

    @Test
    fun `buildManualReplaceItems - 关注心法仍在禁用组置底`() {
        val items = buildManualReplaceItems(
            stacks = listOf(
                manualStack("a", "普通功法", 3),
                manualStack("m1", "已关注心法", 6, type = ManualType.MIND),
                manualStack("m2", "未关注心法", 5, type = ManualType.MIND)
            ),
            learnedNames = emptySet(),
            discipleRealm = 9,
            mindItemsDisabled = true,
            watchedKeys = setOf("manual:已关注心法")
        )
        // 普通功法在前；心法整体置底（不受关注影响），组内已关注心法在前
        assertEquals(listOf("普通功法", "已关注心法", "未关注心法"), items.map { it.name })
        assertFalse(items[0].isDisabled)
        assertTrue(items[1].isDisabled)
        assertTrue(items[2].isDisabled)
    }

    @Test
    fun `buildManualReplaceItems - 心法禁用时整体置底且不可选`() {
        val items = buildManualReplaceItems(
            stacks = listOf(
                manualStack("a", "攻击诀", 1),
                manualStack("m1", "心法甲", 6, type = ManualType.MIND),
                manualStack("b", "防御诀", 4),
                manualStack("m2", "心法乙", 3, type = ManualType.MIND)
            ),
            learnedNames = emptySet(),
            discipleRealm = 9,
            mindItemsDisabled = true
        )
        // 普通功法在前（品阶降序），心法整体置底
        assertEquals(listOf("防御诀", "攻击诀", "心法甲", "心法乙"), items.map { it.name })
        assertFalse(items[0].isDisabled)
        assertFalse(items[1].isDisabled)
        assertTrue(items[2].isDisabled)
        assertTrue(items[3].isDisabled)
        // 置底心法内部仍品阶降序（心法甲 6 品在前）
        assertEquals("心法甲", items[2].name)
        assertEquals("心法乙", items[3].name)
    }

    @Test
    fun `buildManualReplaceItems - 心法未禁用时按品阶正常混排`() {
        val items = buildManualReplaceItems(
            stacks = listOf(
                manualStack("a", "攻击诀", 1),
                manualStack("m1", "心法甲", 6, type = ManualType.MIND),
                manualStack("b", "防御诀", 4)
            ),
            learnedNames = emptySet(),
            discipleRealm = 9,
            mindItemsDisabled = false
        )
        assertEquals(listOf("心法甲", "防御诀", "攻击诀"), items.map { it.name })
        assertTrue(items.all { !it.isDisabled })
    }

    @Test
    fun `buildManualReplaceItems - 心法规则三场景推演`() {
        val mindStack = manualStack("m", "心法", 3, type = ManualType.MIND)

        // 场景1：弟子已有心法且更换原功法非心法 → 心法置底禁用
        val disabled = buildManualReplaceItems(
            stacks = listOf(mindStack),
            learnedNames = emptySet(),
            discipleRealm = 9,
            mindItemsDisabled = true
        )
        assertTrue(disabled.single().isDisabled)

        // 场景2：更换原功法为弟子心法 → 心法正常
        val enabledReplacingMind = buildManualReplaceItems(
            stacks = listOf(mindStack),
            learnedNames = emptySet(),
            discipleRealm = 9,
            mindItemsDisabled = false
        )
        assertFalse(enabledReplacingMind.single().isDisabled)

        // 场景3：弟子无心法 → 心法正常
        val enabledNoMind = buildManualReplaceItems(
            stacks = listOf(mindStack),
            learnedNames = emptySet(),
            discipleRealm = 9,
            mindItemsDisabled = false
        )
        assertFalse(enabledNoMind.single().isDisabled)
    }

    @Test
    fun `buildManualReplaceItems - 已学名称排除`() {
        val items = buildManualReplaceItems(
            stacks = listOf(
                manualStack("a", "已学功法", 5),
                manualStack("b", "未学功法", 2)
            ),
            learnedNames = setOf("已学功法"),
            discipleRealm = 9,
            mindItemsDisabled = false
        )
        assertEquals(listOf("未学功法"), items.map { it.name })
    }

    @Test
    fun `buildManualReplaceItems - 境界不足排除`() {
        val items = buildManualReplaceItems(
            stacks = listOf(
                manualStack("a", "高级功法", 5, minRealm = 5),
                manualStack("b", "基础功法", 1, minRealm = 9)
            ),
            learnedNames = emptySet(),
            discipleRealm = 8,
            mindItemsDisabled = false
        )
        assertEquals(listOf("基础功法"), items.map { it.name })
    }

    @Test
    fun `buildManualReplaceItems - 空输入返回空列表`() {
        val items = buildManualReplaceItems(
            stacks = emptyList(),
            learnedNames = emptySet(),
            discipleRealm = 9,
            mindItemsDisabled = false
        )
        assertTrue(items.isEmpty())
    }

    // ==================== buildEquipmentReplaceItems ====================

    @Test
    fun `buildEquipmentReplaceItems - 槽位过滤`() {
        val items = buildEquipmentReplaceItems(
            stacks = listOf(
                equipmentStack("w1", "青锋剑", 3, EquipmentSlot.WEAPON),
                equipmentStack("a1", "玄铁甲", 5, EquipmentSlot.ARMOR)
            ),
            instances = emptyList(),
            slot = EquipmentSlot.WEAPON,
            currentEquipmentId = null,
            currentDiscipleId = "d1",
            discipleRealm = 9
        )
        assertEquals(listOf("青锋剑"), items.map { it.name })
    }

    @Test
    fun `buildEquipmentReplaceItems - 境界不足排除`() {
        val items = buildEquipmentReplaceItems(
            stacks = listOf(
                equipmentStack("w1", "神兵", 6, minRealm = 3),
                equipmentStack("w2", "凡铁剑", 1)
            ),
            instances = emptyList(),
            slot = EquipmentSlot.WEAPON,
            currentEquipmentId = null,
            currentDiscipleId = "d1",
            discipleRealm = 5
        )
        assertEquals(listOf("凡铁剑"), items.map { it.name })
    }

    @Test
    fun `buildEquipmentReplaceItems - 排除当前装备实例`() {
        // 当前穿着的装备为实例（ownerId 绑定），应从可选列表排除
        val items = buildEquipmentReplaceItems(
            stacks = emptyList(),
            instances = listOf(equipmentInstance("w1", "当前武器", 4, ownerId = "d1")),
            slot = EquipmentSlot.WEAPON,
            currentEquipmentId = "w1",
            currentDiscipleId = "d1",
            discipleRealm = 9
        )
        assertTrue(items.isEmpty())
    }

    @Test
    fun `buildEquipmentReplaceItems - 实例归属过滤`() {
        val items = buildEquipmentReplaceItems(
            stacks = emptyList(),
            instances = listOf(
                equipmentInstance("i1", "无主剑", 3, ownerId = null),
                equipmentInstance("i2", "自己剑", 2, ownerId = "d1"),
                equipmentInstance("i3", "他人剑", 6, ownerId = "d2")
            ),
            slot = EquipmentSlot.WEAPON,
            currentEquipmentId = null,
            currentDiscipleId = "d1",
            discipleRealm = 9
        )
        assertEquals(setOf("无主剑", "自己剑"), items.map { it.name }.toSet())
    }

    @Test
    fun `buildEquipmentReplaceItems - 堆叠与实例合并按品阶降序`() {
        val items = buildEquipmentReplaceItems(
            stacks = listOf(equipmentStack("w1", "堆叠剑", 2)),
            instances = listOf(equipmentInstance("i1", "实例剑", 5)),
            slot = EquipmentSlot.WEAPON,
            currentEquipmentId = null,
            currentDiscipleId = "d1",
            discipleRealm = 9
        )
        assertEquals(listOf("实例剑", "堆叠剑"), items.map { it.name })
    }

    @Test
    fun `buildEquipmentReplaceItems - 关注优先于品阶`() {
        val items = buildEquipmentReplaceItems(
            stacks = listOf(
                equipmentStack("w1", "高品未关注", 6),
                equipmentStack("w2", "低品已关注", 1)
            ),
            instances = emptyList(),
            slot = EquipmentSlot.WEAPON,
            currentEquipmentId = null,
            currentDiscipleId = "d1",
            discipleRealm = 9,
            watchedKeys = setOf("equipment:低品已关注")
        )
        assertEquals(listOf("低品已关注", "高品未关注"), items.map { it.name })
    }

    @Test
    fun `buildEquipmentReplaceItems - 空输入返回空列表`() {
        val items = buildEquipmentReplaceItems(
            stacks = emptyList(),
            instances = emptyList(),
            slot = EquipmentSlot.WEAPON,
            currentEquipmentId = null,
            currentDiscipleId = "d1",
            discipleRealm = 9
        )
        assertTrue(items.isEmpty())
    }

    // ==================== 详情构建 ====================

    @Test
    fun `manualStackDetail - 精灵图键与副标题`() {
        val stack = manualStack("m1", "烈焰诀", 4, type = ManualType.ATTACK)
        val detail = manualStackDetail(stack)
        assertEquals("manual_4", detail.spriteName)
        assertEquals("玄品 · 攻击型", detail.subtitle)
        assertEquals("技能描述", detail.skillTitle)
    }

    @Test
    fun `manualStackDetail - 属性加成与技能行`() {
        val stack = ManualStack(
            id = "m1", name = "烈焰诀", rarity = 3, type = ManualType.ATTACK,
            stats = mapOf("physicalAttack" to 10, "cultivationSpeedPercent" to 5),
            skillName = "烈焰斩",
            skillDescription = "对敌方造成火焰伤害",
            skillDamageMultiplier = 1.5,
            skillCooldown = 2
        )
        val detail = manualStackDetail(stack)
        assertTrue(detail.attributeLines.any { it.contains("物理攻击") && it.contains("+10") })
        assertTrue(detail.attributeLines.any { it.contains("修炼速度") && it.contains("+5%") })
        assertTrue(detail.skillLines.any { it.contains("烈焰斩") })
        assertTrue(detail.skillLines.any { it.contains("火焰伤害") })
    }

    @Test
    fun `manualStackDetail - 无技能时回退功法描述`() {
        val stack = ManualStack(
            id = "m1", name = "朴素功法", rarity = 1, type = ManualType.DEFENSE,
            description = "朴实无华的防御功法"
        )
        val detail = manualStackDetail(stack)
        assertTrue(detail.skillLines.any { it.contains("朴实无华") })
    }

    @Test
    fun `equipmentStackDetail - 属性行与描述`() {
        val stack = EquipmentStack(
            id = "w1", name = "青锋剑", rarity = 2, slot = EquipmentSlot.WEAPON,
            physicalAttack = 12, speed = 3, description = "锋利的宝剑"
        )
        val detail = equipmentStackDetail(stack)
        assertEquals("青锋剑", detail.spriteName)
        assertEquals("武器 · 灵品", detail.subtitle)
        assertEquals("装备描述", detail.skillTitle)
        assertTrue(detail.attributeLines.any { it.contains("物理攻击") && it.contains("+12") })
        assertTrue(detail.attributeLines.any { it.contains("速度") && it.contains("+3") })
        assertEquals(listOf("锋利的宝剑"), detail.skillLines)
    }

    @Test
    fun `equipmentInstanceDetail - 最终属性含孕养差值`() {
        val instance = EquipmentInstance(
            id = "i1", name = "传承剑", rarity = 4, slot = EquipmentSlot.WEAPON,
            physicalAttack = 100, nurtureLevel = 10
        )
        val detail = equipmentInstanceDetail(instance)
        assertTrue(detail.attributeLines.any { it.startsWith("  物理攻击 +") })
        // 孕养等级 >0 时最终属性大于基础属性，出现 (↑x) 差值标记
        assertTrue(detail.attributeLines.any { it.contains("(↑") })
    }
}
