package com.xianxia.sect.ui.game.components.detail

import com.xianxia.sect.core.model.EquipAffixSet
import com.xianxia.sect.core.model.EquipGrowth
import com.xianxia.sect.core.model.EquipInstanceMeta
import com.xianxia.sect.core.model.EquipStat
import com.xianxia.sect.core.model.EquipStatValue
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.ManualType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更换界面纯逻辑测试：
 * 列表构建（排序/过滤/心法置底禁用）与详情构建（四区域数据）全覆盖。
 * （B3 装备重构：更换候选 = 单轨实例；装备堆叠轨用例退役）
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

    /** B3 实例轨夹具（level/rarity 经 growth/meta 承载） */
    private fun equipmentInstance(
        id: String,
        name: String,
        rarity: Int,
        part: EquipmentSlot = EquipmentSlot.HANDS,
        ownerId: String? = null,
        minRealm: Int = 9,
        level: Int = 1,
        mainStat: EquipStat = EquipStat.ATTACK,
        mainValue: Double = 100.0
    ) = EquipmentInstance(
        id = id, name = name,
        part = part,
        growth = EquipGrowth(
            level = level,
            affix = EquipAffixSet(mainStat = EquipStatValue(mainStat, mainValue))
        ),
        meta = EquipInstanceMeta(rarity = rarity, minRealm = minRealm),
        ownerId = ownerId
    )

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

    // ==================== buildEquipmentReplaceItems（B3 单轨实例） ====================

    @Test
    fun `buildEquipmentReplaceItems - 部位过滤`() {
        val items = buildEquipmentReplaceItems(
            instances = listOf(
                equipmentInstance("w1", "青锋剑", 3, EquipmentSlot.HANDS),
                equipmentInstance("a1", "玄铁甲", 5, EquipmentSlot.BODY)
            ),
            slot = EquipmentSlot.HANDS,
            currentEquipmentId = null,
            currentDiscipleId = "d1",
            discipleRealm = 9
        )
        assertEquals(listOf("青锋剑"), items.map { it.name })
    }

    @Test
    fun `buildEquipmentReplaceItems - 境界不足排除`() {
        val items = buildEquipmentReplaceItems(
            instances = listOf(
                equipmentInstance("w1", "神兵", 6, minRealm = 3),
                equipmentInstance("w2", "凡铁剑", 1)
            ),
            slot = EquipmentSlot.HANDS,
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
            instances = listOf(equipmentInstance("w1", "当前武器", 4, ownerId = "d1")),
            slot = EquipmentSlot.HANDS,
            currentEquipmentId = "w1",
            currentDiscipleId = "d1",
            discipleRealm = 9
        )
        assertTrue(items.isEmpty())
    }

    @Test
    fun `buildEquipmentReplaceItems - 实例归属过滤`() {
        val items = buildEquipmentReplaceItems(
            instances = listOf(
                equipmentInstance("i1", "无主剑", 3, ownerId = null),
                equipmentInstance("i2", "自己剑", 2, ownerId = "d1"),
                equipmentInstance("i3", "他人剑", 6, ownerId = "d2")
            ),
            slot = EquipmentSlot.HANDS,
            currentEquipmentId = null,
            currentDiscipleId = "d1",
            discipleRealm = 9
        )
        assertEquals(setOf("无主剑", "自己剑"), items.map { it.name }.toSet())
    }

    @Test
    fun `buildEquipmentReplaceItems - 同部位实例按品阶降序`() {
        val items = buildEquipmentReplaceItems(
            instances = listOf(
                equipmentInstance("i1", "低品剑", 2),
                equipmentInstance("i2", "高品剑", 5)
            ),
            slot = EquipmentSlot.HANDS,
            currentEquipmentId = null,
            currentDiscipleId = "d1",
            discipleRealm = 9
        )
        assertEquals(listOf("高品剑", "低品剑"), items.map { it.name })
    }

    @Test
    fun `buildEquipmentReplaceItems - 关注优先于品阶`() {
        val items = buildEquipmentReplaceItems(
            instances = listOf(
                equipmentInstance("w1", "高品未关注", 6),
                equipmentInstance("w2", "低品已关注", 1)
            ),
            slot = EquipmentSlot.HANDS,
            currentEquipmentId = null,
            currentDiscipleId = "d1",
            discipleRealm = 9,
            watchedKeys = setOf("equipment:w2")
        )
        assertEquals(listOf("低品已关注", "高品未关注"), items.map { it.name })
    }

    @Test
    fun `buildEquipmentReplaceItems - 空输入返回空列表`() {
        val items = buildEquipmentReplaceItems(
            instances = emptyList(),
            slot = EquipmentSlot.HANDS,
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
    fun `equipmentInstanceDetail - 词条摘要与等级副标题`() {
        // B3：详情 = 词条摘要（主词条×等级成长 + 副词条×强化次数）；
        // 旧 7 项面板/孕养差值标注已退役
        val instance = equipmentInstance(
            "i1", "传承剑", 4,
            part = EquipmentSlot.HANDS,
            level = 3, mainValue = 100.0
        )
        val detail = equipmentInstanceDetail(instance)
        assertEquals("传承剑", detail.spriteName)
        // 副标题：部位 · 品阶 · LvN
        assertTrue(detail.subtitle.contains("手部"))
        assertTrue(detail.subtitle.contains("Lv3"))
        // 主词条按等级成长（Lv3 = ×1.2 → 120）出现在属性行
        assertTrue(detail.attributeLines.any { it.contains("120") })
        assertEquals("装备描述", detail.skillTitle)
        assertTrue("旧孕养差值标注已退役", detail.attributeLines.none { it.contains("(↑") })
    }
}
