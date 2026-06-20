package com.xianxia.sect.core.config

import com.xianxia.sect.core.model.MailAttachment
import org.junit.Assert.*
import org.junit.Test

class BuiltinMailConfigTest {

    // ==================== BuiltinMail ====================

    @Test
    fun builtinMail_dataClassConstruction() {
        val mail = BuiltinMailConfig.BuiltinMail(
            id = "test_mail",
            title = "测试邮件",
            content = "测试内容",
            mailType = "reward",
            minVersion = 1000,
            deadlineMs = 0L,
            attachments = emptyList()
        )
        assertEquals("test_mail", mail.id)
        assertEquals("测试邮件", mail.title)
        assertEquals("测试内容", mail.content)
        assertEquals("reward", mail.mailType)
        assertEquals(1000, mail.minVersion)
        assertEquals(0L, mail.deadlineMs)
        assertTrue(mail.attachments.isEmpty())
    }

    @Test
    fun builtinMail_defaultDeadline() {
        val mail = BuiltinMailConfig.BuiltinMail(
            id = "x",
            title = "y",
            content = "z",
            mailType = "info",
            minVersion = 1,
            attachments = emptyList()
        )
        assertEquals(0L, mail.deadlineMs)
    }

    @Test
    fun builtinMail_withAttachments() {
        val attachment = MailAttachment(
            type = "beastMaterial",
            name = "灵虎血",
            quantity = 200,
            rarity = 2,
            itemId = "tigerBlood1"
        )
        val mail = BuiltinMailConfig.BuiltinMail(
            id = "mail_test",
            title = "奖励",
            content = "领取奖励",
            mailType = "reward",
            minVersion = 3204,
            attachments = listOf(attachment)
        )
        assertEquals(1, mail.attachments.size)
        assertEquals("灵虎血", mail.attachments[0].name)
        assertEquals(200, mail.attachments[0].quantity)
    }

    @Test
    fun builtinMail_equality() {
        val a = BuiltinMailConfig.BuiltinMail(
            id = "x", title = "y", content = "z",
            mailType = "reward", minVersion = 1, attachments = emptyList()
        )
        val b = BuiltinMailConfig.BuiltinMail(
            id = "x", title = "y", content = "z",
            mailType = "reward", minVersion = 1, attachments = emptyList()
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun builtinMail_copy() {
        val original = BuiltinMailConfig.BuiltinMail(
            id = "x", title = "y", content = "z",
            mailType = "reward", minVersion = 1, attachments = emptyList()
        )
        val modified = original.copy(minVersion = 2)
        assertEquals(2, modified.minVersion)
        assertEquals("x", modified.id)
    }

    // ==================== BuiltinMailConfig.mails ====================

    @Test
    fun builtinMailConfig_mailsNotEmpty() {
        assertTrue(BuiltinMailConfig.mails.isNotEmpty())
    }

    @Test
    fun builtinMailConfig_mailsHaveUniqueIds() {
        val ids = BuiltinMailConfig.mails.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun builtinMailConfig_mailsAllHaveRequiredFields() {
        for (mail in BuiltinMailConfig.mails) {
            assertTrue("Mail id should not be blank", mail.id.isNotBlank())
            assertTrue("Mail title should not be blank", mail.title.isNotBlank())
            assertTrue("Mail content should not be blank", mail.content.isNotBlank())
            assertTrue("Mail type should not be blank", mail.mailType.isNotBlank())
            assertTrue("Mail minVersion should be positive", mail.minVersion > 0)
        }
    }

    @Test
    fun builtinMailConfig_mailsWithDeadlineHaveFutureTimestamp() {
        // deadlineMs > 0 means it's a timed mail; 0 means no deadline
        for (mail in BuiltinMailConfig.mails) {
            if (mail.deadlineMs > 0) {
                assertTrue("Deadline should be a positive timestamp", mail.deadlineMs > 0)
            }
        }
    }

    @Test
    fun builtinMailConfig_mailsAttachmentsHaveValidQuantities() {
        for (mail in BuiltinMailConfig.mails) {
            for (attachment in mail.attachments) {
                assertTrue("Attachment quantity should be positive", attachment.quantity > 0)
                assertTrue("Attachment rarity should be >= 0", attachment.rarity >= 0)
            }
        }
    }

    @Test
    fun builtinMailConfig_qqGroupMailExists() {
        val mail = BuiltinMailConfig.mails.find { it.id == "mail_qq_group_v4_0_03" }
        assertNotNull("QQ群邮件应存在", mail)
        assertEquals("reward", mail!!.mailType)
        assertTrue(mail.attachments.isNotEmpty())
    }

    @Test
    fun builtinMailConfig_holidayMailExists() {
        val mail = BuiltinMailConfig.mails.find { it.id == "mail_new_year_2026" }
        assertNotNull("元旦邮件应存在", mail)
        assertEquals("reward", mail!!.mailType)
        assertTrue(mail.attachments.isNotEmpty())
    }

    // ==================== MailAttachment ====================

    @Test
    fun mailAttachment_defaultValues() {
        val attachment = MailAttachment(
            type = "pill",
            name = "丹药",
            quantity = 1
        )
        assertEquals(0, attachment.rarity)
        assertNull(attachment.itemId)
        assertTrue(attachment.extra.isEmpty())
    }

    @Test
    fun mailAttachment_equality() {
        val a = MailAttachment(type = "pill", name = "丹药", quantity = 1, rarity = 2)
        val b = MailAttachment(type = "pill", name = "丹药", quantity = 1, rarity = 2)
        assertEquals(a, b)
    }
}
