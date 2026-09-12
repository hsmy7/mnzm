package com.xianxia.sect.ui.game.dialogs

import com.xianxia.sect.core.model.MailAttachment
import com.xianxia.sect.core.model.SpiritStoneGrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [mailAttachmentToItemCardData] 类型标志映射测试。
 *
 * 守卫：功法（manual）附件必须设置 isManual，否则邮件附件列表对功法
 * 显示"敬请期待"而非 manual_$rarity 精灵图——本测试锁定每个类型的标志映射，
 * 防止回归。
 */
class MailAttachmentToItemCardDataTest {

    private fun attachment(
        type: String,
        name: String = "物品",
        rarity: Int = 1,
        quantity: Int = 1
    ): MailAttachment = MailAttachment(
        type = type,
        name = name,
        quantity = quantity,
        rarity = rarity
    )

    @Test
    fun `manual attachment sets isManual - 功法不再落 equipment 分支`() {
        val data = mailAttachmentToItemCardData(
            attachment(type = "manual", name = "太乙剑诀", rarity = 3)
        )
        assertTrue(data.isManual)
        assertFalse(data.isPill)
        assertFalse(data.isMaterial)
        assertFalse(data.isHerb)
        assertFalse(data.isSeed)
        assertFalse(data.isBag)
        assertFalse(data.isDisciple)
    }

    @Test
    fun `disciple attachment sets isDisciple - 弟子显示通用头像`() {
        val data = mailAttachmentToItemCardData(
            attachment(type = "disciple", name = "单灵根弟子", rarity = 0)
        )
        assertTrue(data.isDisciple)
        assertFalse(data.isManual)
    }

    @Test
    fun `pill attachment sets isPill`() {
        val data = mailAttachmentToItemCardData(attachment(type = "pill", name = "聚气丹"))
        assertTrue(data.isPill)
        assertFalse(data.isManual)
        assertFalse(data.isDisciple)
    }

    @Test
    fun `equipment attachment keeps default flags - 走装备名精灵分支`() {
        val data = mailAttachmentToItemCardData(
            attachment(type = "equipment", name = "精铁剑", rarity = 1)
        )
        assertFalse(data.isManual)
        assertFalse(data.isPill)
        assertFalse(data.isMaterial)
        assertFalse(data.isHerb)
        assertFalse(data.isSeed)
        assertFalse(data.isBag)
        assertFalse(data.isDisciple)
    }

    @Test
    fun `material and beastMaterial set isMaterial`() {
        for (type in listOf("material", "beastMaterial")) {
            val data = mailAttachmentToItemCardData(attachment(type = type, name = "虎皮"))
            assertTrue("$type 应设置 isMaterial", data.isMaterial)
        }
    }

    @Test
    fun `herb and seed set corresponding flags`() {
        assertTrue(mailAttachmentToItemCardData(attachment(type = "herb", name = "聚灵草")).isHerb)
        assertTrue(mailAttachmentToItemCardData(attachment(type = "seed", name = "聚灵草种")).isSeed)
    }

    @Test
    fun `storageBag sets isBag`() {
        val data = mailAttachmentToItemCardData(
            attachment(type = "storageBag", name = "灵品储物袋", rarity = 2)
        )
        assertTrue(data.isBag)
    }

    @Test
    fun `spiritStones - name without grade resolves LOW`() {
        val data = mailAttachmentToItemCardData(attachment(type = "spiritStones", name = "灵石"))
        assertEquals(SpiritStoneGrade.LOW, data.spiritStoneGrade)
    }

    @Test
    fun `spiritStones - 上品灵石 resolves HIGH - 与发放侧品阶一致`() {
        val data = mailAttachmentToItemCardData(
            attachment(type = "spiritStones", name = "上品灵石", rarity = 3)
        )
        assertEquals(SpiritStoneGrade.HIGH, data.spiritStoneGrade)
    }

    @Test
    fun `spiritStones - 中品灵石 resolves MID`() {
        val data = mailAttachmentToItemCardData(
            attachment(type = "spiritStones", name = "中品灵石", rarity = 2)
        )
        assertEquals(SpiritStoneGrade.MID, data.spiritStoneGrade)
    }

    @Test
    fun `spiritHerbs sets isHerb - 灵草资源归入草药类`() {
        val data = mailAttachmentToItemCardData(
            attachment(type = "spiritHerbs", name = "灵草", rarity = 0)
        )
        assertTrue(data.isHerb)
        assertFalse(data.isManual)
        assertFalse(data.isDisciple)
    }

    @Test
    fun `field passthrough - id name rarity quantity`() {
        val data = mailAttachmentToItemCardData(
            MailAttachment(
                type = "pill",
                name = "培元丹",
                quantity = 3,
                rarity = 2,
                itemId = "pill_1"
            )
        )
        assertEquals("pill_1", data.id)
        assertEquals("培元丹", data.name)
        assertEquals(2, data.rarity)
        assertEquals(3, data.quantity)
    }
}
