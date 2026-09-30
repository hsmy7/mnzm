package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.core.model.EquipmentStack
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.MailAttachment
import com.xianxia.sect.core.model.MailEntity
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.data.model.SaveData
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旧装备折算补偿守卫（装备重构 B3 / R2，方案 §5.4/§6.1——order=27）。
 *
 * 覆盖：折算公式（basePrice 100% × 实例孕养系数）、四路数据源
 * （堆叠载体/储物袋/秘境背包/邮件附件）、单档 1 亿上限截断、
 * 幂等（标记同事务置位、置位后恒 Passed）、无存量恒 Passed 不落盘。
 */
class EquipmentLegacyCompensationTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    // ==================== 折算公式 ====================

    @Test
    fun `实例孕养系数口径`() {
        assertEquals("未孕养不加成", 1.0, LegacyEquipmentCompensationRule.instanceMultiplier(0, 1), 1e-12)
        assertEquals("r1 满孕养 +50%", 1.5, LegacyEquipmentCompensationRule.instanceMultiplier(5, 1), 1e-12)
        assertEquals(
            "r2 孕养 3/9 档",
            1.0 + 0.5 * 3.0 / 9.0,
            LegacyEquipmentCompensationRule.instanceMultiplier(3, 2), 1e-12
        )
        assertTrue("孕养越界不放大", LegacyEquipmentCompensationRule.instanceMultiplier(999, 1) >= 1.5)
    }

    @Test
    fun `堆叠折算为basePrice乘数量乘系数`() {
        // 未知名称按品阶基准价：r1 = 4000
        val plain = EquipmentStack(id = "s1", name = "旧铁剑", rarity = 1, quantity = 2)
        assertEquals(8_000L, LegacyEquipmentCompensationRule.stackValue(plain))
        val nurtured = EquipmentStack(id = "s2", name = "旧铁剑", rarity = 1, quantity = 2, nurtureLevel = 5)
        assertEquals("孕养满档系数 1.5×", 12_000L, LegacyEquipmentCompensationRule.stackValue(nurtured))
    }

    // ==================== 四路数据源 ====================

    @Test
    fun `堆叠载体折算后清空并发放补偿邮件`() {
        val data = saveData(
            equipmentStacks = listOf(
                EquipmentStack(id = "s1", name = "旧铁剑", rarity = 1, quantity = 1)
            )
        )
        val result = LegacyEquipmentCompensationRule.execute(data, RuleContext(data))

        assertTrue(result is RuleOutcome.Repaired)
        val repaired = (result as RuleOutcome.Repaired).data
        assertEquals("载体读后即清", 0, repaired.equipmentStacks.size)
        assertTrue("幂等标记同事务置位", repaired.gameData.legacyEquipmentCompensated)
        assertEquals(4_000L, compensationAmount(repaired.mails))
        assertTrue(
            "补偿详情应含四路计数",
            (result as RuleOutcome.Repaired).details.any { it.contains("堆叠 1") }
        )
    }

    @Test
    fun `储物袋旧条目折算并摘除非旧条目保留`() {
        val disciple = Disciple(
            id = "1", name = "测试弟子", realm = 9, combat = CombatAttributes(),
            equipment = EquipmentSet(
                storageBagItems = listOf(
                    StorageBagItem(
                        itemId = "old-1", itemType = "equipment", name = "旧铁剑",
                        rarity = 1, quantity = 2
                    ),
                    StorageBagItem(
                        itemId = "pill-1", itemType = "pill", name = "丹药",
                        rarity = 1, quantity = 9
                    )
                )
            )
        )
        val data = saveData(disciples = listOf(disciple))
        val result = LegacyEquipmentCompensationRule.execute(data, RuleContext(data))

        assertTrue(result is RuleOutcome.Repaired)
        val repaired = (result as RuleOutcome.Repaired).data
        val keptBag = repaired.disciples.single().equipment.storageBagItems
        assertEquals("旧条目摘除、新条目保留", listOf("pill-1"), keptBag.map { it.itemId })
        assertEquals("储物袋 2 件 r1 = 8_000", 8_000L, compensationAmount(repaired.mails))
    }

    @Test
    fun `秘境背包装备折算并清空`() {
        val gd = gameData().let {
            it.copy(
                secretRealmSession = it.secretRealmSession.copy(
                    backpack = it.secretRealmSession.backpack.copy(
                        equipment = listOf(
                            EquipmentStack(id = "r1", name = "旧法袍", rarity = 2, quantity = 1)
                        )
                    )
                )
            )
        }
        val data = saveData(gameData = gd)
        val result = LegacyEquipmentCompensationRule.execute(data, RuleContext(data))

        assertTrue(result is RuleOutcome.Repaired)
        val repaired = (result as RuleOutcome.Repaired).data
        assertTrue("秘境背包应清空", repaired.gameData.secretRealmSession.backpack.equipment.isEmpty())
        assertEquals("r2 基准价 16_000", 16_000L, compensationAmount(repaired.mails))
    }

    @Test
    fun `邮件装备附件折算并摘除非装备附件保留`() {
        val attachments = listOf(
            MailAttachment(type = "equipment", name = "旧铁剑", quantity = 1),
            MailAttachment(type = "spiritStones", name = "下品灵石", quantity = 100)
        )
        val mail = MailEntity(
            id = "m1", slotId = 1, hasAttachment = true,
            attachments = json.encodeToString(ListSerializer(MailAttachment.serializer()), attachments)
        )
        val data = saveData(mails = listOf(mail))
        val result = LegacyEquipmentCompensationRule.execute(data, RuleContext(data))

        assertTrue(result is RuleOutcome.Repaired)
        val repaired = (result as RuleOutcome.Repaired).data
        assertEquals(4_000L, compensationAmount(repaired.mails))
        val kept = json.decodeFromString(
            ListSerializer(MailAttachment.serializer()),
            repaired.mails.first { it.id == "m1" }.attachments
        )
        assertEquals(listOf("spiritStones"), kept.map { it.type })
    }

    // ==================== 上限与幂等 ====================

    @Test
    fun `超一亿上限按截断发放`() {
        // r6 基准价 26_880_000 × 4 件 = 107_520_000 > 1 亿上限
        val data = saveData(
            equipmentStacks = listOf(
                EquipmentStack(id = "s1", name = "旧神兵", rarity = 6, quantity = 4)
            )
        )
        val result = LegacyEquipmentCompensationRule.execute(data, RuleContext(data))
        assertTrue(result is RuleOutcome.Repaired)
        assertEquals(
            LegacyEquipmentCompensationRule.COMPENSATION_CAP,
            compensationAmount((result as RuleOutcome.Repaired).data.mails)
        )
        assertEquals("上限常量 = 1 亿", 100_000_000L, LegacyEquipmentCompensationRule.COMPENSATION_CAP)
    }

    @Test
    fun `标记置位后恒Passed零重复补偿`() {
        val data = saveData(
            equipmentStacks = listOf(EquipmentStack(id = "s1", name = "旧铁剑", rarity = 1)),
            gameData = gameData().copy(legacyEquipmentCompensated = true)
        )
        val result = LegacyEquipmentCompensationRule.execute(data, RuleContext(data))
        assertTrue("置位后应恒 Passed", result is RuleOutcome.Passed)
    }

    @Test
    fun `跑两遍只补偿一次`() {
        val data = saveData(
            equipmentStacks = listOf(EquipmentStack(id = "s1", name = "旧铁剑", rarity = 1))
        )
        val first = LegacyEquipmentCompensationRule.execute(data, RuleContext(data))
        assertTrue(first is RuleOutcome.Repaired)
        val once = (first as RuleOutcome.Repaired).data
        assertEquals(1, once.mails.count { it.source == LegacyEquipmentCompensationRule.SOURCE_KEY })

        val second = LegacyEquipmentCompensationRule.execute(once, RuleContext(once))
        assertTrue("二次执行应恒 Passed", second is RuleOutcome.Passed)
    }

    @Test
    fun `无存量恒Passed且不置标记`() {
        val result = LegacyEquipmentCompensationRule.execute(saveData(), RuleContext(saveData()))
        assertEquals("无存量资产应恒 Passed（B2 先例）", RuleOutcome.Passed, result)
    }

    @Test
    fun `补偿邮件为永久有效单封`() {
        val data = saveData(
            equipmentStacks = listOf(EquipmentStack(id = "s1", name = "旧铁剑", rarity = 1))
        )
        val result = LegacyEquipmentCompensationRule.execute(data, RuleContext(data))
        val repaired = (result as RuleOutcome.Repaired).data
        val mails = repaired.mails.filter { it.source == LegacyEquipmentCompensationRule.SOURCE_KEY }
        assertEquals("恰好单封补偿邮件", 1, mails.size)
        val mail = mails.single()
        assertEquals("永久有效", Long.MAX_VALUE, mail.expireTime)
        assertEquals("装备体系更新补偿", mail.title)
        assertTrue(mail.hasAttachment)
    }

    // ==================== 辅助 ====================

    private fun gameData() = GameData(sectName = "宗", gameYear = 1, gameMonth = 1)

    private fun saveData(
        disciples: List<Disciple> = listOf(
            Disciple(id = "1", name = "测试弟子", realm = 9, combat = CombatAttributes())
        ),
        equipmentStacks: List<EquipmentStack> = emptyList(),
        mails: List<MailEntity> = emptyList(),
        gameData: GameData = gameData()
    ) = SaveData(
        gameData = gameData,
        disciples = disciples,
        equipmentStacks = equipmentStacks,
        pills = emptyList(),
        materials = emptyList(),
        herbs = emptyList(),
        seeds = emptyList(),
        mails = mails
    )

    /** 补偿邮件（source=equipment_legacy_compensation）的灵石附件金额；无邮件返回 -1。 */
    private fun compensationAmount(mails: List<MailEntity>): Long {
        val mail = mails.firstOrNull {
            it.source == LegacyEquipmentCompensationRule.SOURCE_KEY
        } ?: return -1L
        val attachments = json.decodeFromString(
            ListSerializer(MailAttachment.serializer()), mail.attachments
        )
        assertEquals(1, attachments.size)
        assertEquals("spiritStones", attachments[0].type)
        assertFalse(mail.expireTime == 0L)
        return attachments[0].quantity.toLong()
    }
}
