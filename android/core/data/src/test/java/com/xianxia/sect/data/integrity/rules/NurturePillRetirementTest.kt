package com.xianxia.sect.data.integrity.rules

import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.EquipmentSet
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.MailAttachment
import com.xianxia.sect.core.model.MailEntity
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.model.PillEffect
import com.xianxia.sect.core.model.PillGrade
import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.data.model.SaveData
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 孕养类加成丹药（R11）退役测试（装备重构 B2，方案 §5.7 / 派发件验收判据 ②）。
 *
 * 覆盖：
 * - **折算公式**：仓库堆叠 / 储物袋 / 邮件附件三类资产按退役时刻价格快照
 *   100% 折算（`tier 基价 × grade 乘数 × quantity`），汇总单封补偿邮件发放；
 * - **配方回滚**：`unlockedRecipes` 内孕养配方移除，非孕养保留；
 * - **幂等**：标记置位后二次执行恒 Passed，无重复补偿；
 * - **单档 2000 万上限截断**：超出按比例截断发放并落日志；
 * - 无存量资产时也落幂等标记（防后入存量重复走补偿）。
 */
class NurturePillRetirementTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    // ==================== 折算公式 ====================

    @Test
    fun `retired pill price snapshot matches retired template pricing`() {
        // 快照 = tier 基价（Rarity.pillBasePrice）× grade 乘数（0.5/1.0/2.0）
        assertEquals(2_000L, NurturePillRetirementRule.retiredPillPrice("nurtureSpeed_1_low"))
        assertEquals(4_000L, NurturePillRetirementRule.retiredPillPrice("nurtureSpeed_1_medium"))
        assertEquals(8_000L, NurturePillRetirementRule.retiredPillPrice("nurtureSpeed_1_high"))
        assertEquals(13_440_000L, NurturePillRetirementRule.retiredPillPrice("nurtureAdd_6_low"))
        assertEquals(26_880_000L, NurturePillRetirementRule.retiredPillPrice("nurtureAdd_6_medium"))
        assertEquals(53_760_000L, NurturePillRetirementRule.retiredPillPrice("nurtureAdd_6_high"))
        // 非孕养 id / 非法 id 一律 null
        assertEquals(null, NurturePillRetirementRule.retiredPillPrice("cultivationSpeed_1_low"))
        assertEquals(null, NurturePillRetirementRule.retiredPillPrice("nurtureSpeed_9_high"))
        assertEquals(null, NurturePillRetirementRule.retiredPillPrice("nurtureSpeed_1_mid"))
    }

    @Test
    fun `warehouse stacks and bag items are refunded at full price and removed`() {
        val pills = listOf(
            pill("nurtureSpeed_2_high", quantity = 3),     // 32_000 × 3 = 96_000
            pill("cultivationSpeed_1_low", quantity = 5)   // 非孕养保留
        )
        val disciple = makeDisciple(
            bag = listOf(
                bagItem("nurtureAdd_3_medium", quantity = 2),  // 80_000 × 2 = 160_000
                bagItem("herb_x", quantity = 9)                // 非孕养保留
            )
        )
        val data = saveData(
            pills = pills,
            disciples = listOf(disciple),
            gameData = gameData(unlockedRecipes = listOf("cultivationAdd_1_low"))
        )

        val result = NurturePillRetirementRule.execute(data, RuleContext(data))

        assertTrue(result is RuleOutcome.Repaired)
        val repaired = (result as RuleOutcome.Repaired).data
        // 仓库只剩非孕养丹；储物袋只剩非孕养条目
        assertEquals(listOf("cultivationSpeed_1_low"), repaired.pills.map { it.id })
        assertEquals(
            listOf("herb_x"),
            repaired.disciples.first().equipment.storageBagItems.map { it.itemId }
        )
        // 补偿邮件金额 = 96_000 + 160_000 = 256_000
        assertEquals(256_000L, compensationAmount(repaired.mails))
        // 幂等标记同事务置位
        assertTrue(repaired.gameData.nurturePillsRetired)
    }

    @Test
    fun `mail pill attachments are refunded and stripped`() {
        val attachments = listOf(
            // 金额取小档（32_000 < 2000 万上限）：足额折算语义由本用例验证，上限截断
            // 由 `compensation beyond 20M cap is truncated` 单独覆盖
            MailAttachment(type = "pill", name = "灵养丹", quantity = 1, itemId = "nurtureSpeed_2_high"),
            MailAttachment(type = "spiritStones", name = "下品灵石", quantity = 100),
            MailAttachment(type = "pill", name = "引灵丹", quantity = 2, itemId = "cultivationSpeed_1_low")
        )
        val mail = MailEntity(
            id = "m1",
            slotId = 1,
            hasAttachment = true,
            attachments = json.encodeToString(ListSerializer(MailAttachment.serializer()), attachments)
        )
        val data = saveData(mails = listOf(mail))

        val result = NurturePillRetirementRule.execute(data, RuleContext(data))

        assertTrue(result is RuleOutcome.Repaired)
        val repaired = (result as RuleOutcome.Repaired).data
        // 补偿 = 附件丹折算 32_000（nurtureSpeed_2_high 单价 × 1）
        assertEquals(32_000L, compensationAmount(repaired.mails))
        // 原邮件附件串只剩两条非孕养附件
        val keptMail = repaired.mails.first { it.id == "m1" }
        val kept = json.decodeFromString(
            ListSerializer(MailAttachment.serializer()), keptMail.attachments
        )
        assertEquals(listOf("spiritStones", "pill"), kept.map { it.type })
        assertEquals("cultivationSpeed_1_low", kept[1].itemId)
        assertTrue(keptMail.hasAttachment)
    }

    // ==================== 配方回滚 ====================

    @Test
    fun `unlocked nurture recipes are rolled back and others kept`() {
        val data = saveData(
            gameData = gameData(
                unlockedRecipes = listOf(
                    "nurtureSpeed_1_low", "cultivationAdd_2_medium", "nurtureAdd_3_high"
                )
            )
        )

        val result = NurturePillRetirementRule.execute(data, RuleContext(data))

        assertTrue(result is RuleOutcome.Repaired)
        val repaired = (result as RuleOutcome.Repaired).data
        assertEquals(listOf("cultivationAdd_2_medium"), repaired.gameData.unlockedRecipes)
    }

    // ==================== 幂等 ====================

    @Test
    fun `second run after flag set passes without re-compensation`() {
        val data = saveData(
            pills = listOf(pill("nurtureSpeed_1_low", quantity = 1)),
            gameData = gameData().copy(nurturePillsRetired = true)
        )

        val result = NurturePillRetirementRule.execute(data, RuleContext(data))

        assertTrue("标记置位后应恒 Passed", result is RuleOutcome.Passed)
    }

    @Test
    fun `running twice issues compensation only once`() {
        val data = saveData(pills = listOf(pill("nurtureSpeed_1_low", quantity = 1)))

        val first = NurturePillRetirementRule.execute(data, RuleContext(data))
        assertTrue(first is RuleOutcome.Repaired)
        val once = (first as RuleOutcome.Repaired).data
        assertEquals(1, once.mails.count { it.source == "nurture_pill_retirement" })

        val second = NurturePillRetirementRule.execute(once, RuleContext(once))
        assertTrue(second is RuleOutcome.Passed)
    }

    @Test
    fun `no legacy assets passes without flag or compensation`() {
        // 无存量不落盘、不置标记：退役后新代码不再产出孕养丹，扫描恒空 ⇒ 每次
        // Passed 零成本；报 Repaired 只会让每次读档无谓整表写盘（先例语义）
        val data = saveData()

        val result = NurturePillRetirementRule.execute(data, RuleContext(data))

        assertTrue("无存量资产应恒 Passed", result is RuleOutcome.Passed)
    }

    // ==================== 2000 万上限截断 ====================

    @Test
    fun `compensation beyond 20M cap is truncated`() {
        // nurtureAdd_6_medium 单价 26_880_000 > 20_000_000 上限
        val data = saveData(pills = listOf(pill("nurtureAdd_6_medium", quantity = 2)))

        val result = NurturePillRetirementRule.execute(data, RuleContext(data))

        assertTrue(result is RuleOutcome.Repaired)
        val repaired = (result as RuleOutcome.Repaired).data
        assertEquals(20_000_000L, compensationAmount(repaired.mails))
    }

    @Test
    fun `compensation at cap boundary pays exact amount`() {
        // 上限值本身不截断：20_000_000 / 4_000 = 5000 颗 nurtureSpeed_1_medium
        val data = saveData(pills = listOf(pill("nurtureSpeed_1_medium", quantity = 5_000)))

        val result = NurturePillRetirementRule.execute(data, RuleContext(data))

        assertTrue(result is RuleOutcome.Repaired)
        assertEquals(
            20_000_000L,
            compensationAmount((result as RuleOutcome.Repaired).data.mails)
        )
    }

    // ==================== 辅助 ====================

    private fun gameData(unlockedRecipes: List<String> = emptyList()) = GameData(
        sectName = "宗", gameYear = 1, gameMonth = 1, unlockedRecipes = unlockedRecipes
    )

    private fun makeDisciple(bag: List<StorageBagItem> = emptyList()) = Disciple(
        id = "1",
        name = "测试弟子",
        realm = 9,
        combat = CombatAttributes(),
        equipment = EquipmentSet(storageBagItems = bag)
    )

    private fun pill(id: String, quantity: Int) = Pill(
        id = id,
        name = "丹药$id",
        rarity = 1,
        quantity = quantity,
        category = PillCategory.CULTIVATION,
        grade = PillGrade.LOW,
        effects = PillEffect()
    )

    private fun bagItem(itemId: String, quantity: Int) = StorageBagItem(
        itemId = itemId, itemType = "pill", name = "物品$itemId",
        rarity = 1, quantity = quantity
    )

    private fun saveData(
        pills: List<Pill> = emptyList(),
        disciples: List<Disciple> = listOf(makeDisciple()),
        mails: List<MailEntity> = emptyList(),
        gameData: GameData = gameData()
    ) = SaveData(
        gameData = gameData, disciples = disciples, pills = pills,
        materials = emptyList(), herbs = emptyList(), seeds = emptyList(),
        mails = mails
    )

    /** 补偿邮件（source=nurture_pill_retirement）的灵石附件金额；无邮件返回 -1。 */
    private fun compensationAmount(mails: List<MailEntity>): Long {
        val mail = mails.firstOrNull { it.source == "nurture_pill_retirement" } ?: return -1L
        val attachments = json.decodeFromString(
            ListSerializer(MailAttachment.serializer()), mail.attachments
        )
        assertEquals(1, attachments.size)
        assertEquals("spiritStones", attachments[0].type)
        assertFalse(mail.expireTime == 0L)
        return attachments[0].quantity.toLong()
    }
}
