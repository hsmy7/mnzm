package com.xianxia.sect.core.model

import org.junit.Assert.*
import org.junit.Test

class MailEntityTest {

    // ==================== MailAttachment ====================

    @Test
    fun mailAttachment_defaultConstruction() {
        val attachment = MailAttachment(type = "pill", name = "丹药", quantity = 1)
        assertEquals("pill", attachment.type)
        assertEquals("丹药", attachment.name)
        assertEquals(1, attachment.quantity)
        assertEquals(0, attachment.rarity)
        assertNull(attachment.itemId)
        assertTrue(attachment.extra.isEmpty())
    }

    @Test
    fun mailAttachment_fullConstruction() {
        val attachment = MailAttachment(
            type = "equipment",
            name = "灵剑",
            quantity = 2,
            rarity = 3,
            itemId = "item1",
            extra = mapOf("key" to "value")
        )
        assertEquals("equipment", attachment.type)
        assertEquals("灵剑", attachment.name)
        assertEquals(2, attachment.quantity)
        assertEquals(3, attachment.rarity)
        assertEquals("item1", attachment.itemId)
        assertEquals(mapOf("key" to "value"), attachment.extra)
    }

    @Test
    fun mailAttachment_copy() {
        val original = MailAttachment(type = "material", name = "灵矿", quantity = 5)
        val copied = original.copy(quantity = 10)
        assertEquals(5, original.quantity)
        assertEquals(10, copied.quantity)
        assertEquals("material", copied.type)
    }

    @Test
    fun mailAttachment_equality() {
        val a1 = MailAttachment(type = "pill", name = "丹", quantity = 1)
        val a2 = MailAttachment(type = "pill", name = "丹", quantity = 1)
        assertEquals(a1, a2)
    }

    @Test
    fun mailAttachment_inequality() {
        val a1 = MailAttachment(type = "pill", name = "丹", quantity = 1)
        val a2 = MailAttachment(type = "pill", name = "丹", quantity = 2)
        assertNotEquals(a1, a2)
    }

    // ==================== MailEntity 默认构造 ====================

    @Test
    fun mailEntity_defaultConstruction() {
        val mail = MailEntity()
        assertNotNull(mail.id)
        assertEquals(0, mail.slotId)
        assertEquals("builtin", mail.source)
        assertEquals("reward", mail.mailType)
        assertEquals("", mail.title)
        assertEquals("", mail.content)
        assertEquals("天道意志", mail.senderName)
        assertEquals(0, mail.sendTime)
        assertEquals(0, mail.expireTime)
        assertFalse(mail.isRead)
        assertFalse(mail.attachmentClaimed)
        assertFalse(mail.hasAttachment)
        assertEquals("[]", mail.attachments)
        assertNull(mail.remoteMailId)
    }

    // ==================== MailEntity 带参构造 ====================

    @Test
    fun mailEntity_parameterizedConstruction() {
        val mail = MailEntity(
            id = "mail1",
            slotId = 3,
            source = "system",
            mailType = "notice",
            title = "系统通知",
            content = "欢迎回来",
            senderName = "系统",
            sendTime = 1000L,
            expireTime = 2000L,
            isRead = true,
            attachmentClaimed = true,
            hasAttachment = true,
            attachments = """[{"type":"pill","name":"丹药","quantity":1}]""",
            remoteMailId = "remote1"
        )
        assertEquals("mail1", mail.id)
        assertEquals(3, mail.slotId)
        assertEquals("system", mail.source)
        assertEquals("notice", mail.mailType)
        assertEquals("系统通知", mail.title)
        assertEquals("欢迎回来", mail.content)
        assertEquals("系统", mail.senderName)
        assertEquals(1000L, mail.sendTime)
        assertEquals(2000L, mail.expireTime)
        assertTrue(mail.isRead)
        assertTrue(mail.attachmentClaimed)
        assertTrue(mail.hasAttachment)
        assertNotNull(mail.attachments)
        assertEquals("remote1", mail.remoteMailId)
    }

    // ==================== MailEntity copy ====================

    @Test
    fun mailEntity_copy_changesIsRead() {
        val mail = MailEntity(isRead = false)
        val copied = mail.copy(isRead = true)
        assertFalse(mail.isRead)
        assertTrue(copied.isRead)
    }

    @Test
    fun mailEntity_copy_changesAttachmentClaimed() {
        val mail = MailEntity(attachmentClaimed = false, hasAttachment = true)
        val copied = mail.copy(attachmentClaimed = true)
        assertFalse(mail.attachmentClaimed)
        assertTrue(copied.attachmentClaimed)
        assertTrue(copied.hasAttachment)
    }

    @Test
    fun mailEntity_copy_preservesAllFields() {
        val mail = MailEntity(
            id = "m1",
            title = "标题",
            content = "内容",
            senderName = "发送者"
        )
        val copied = mail.copy()
        assertEquals(mail, copied)
    }

    // ==================== MailEntity 相等性 ====================

    @Test
    fun mailEntity_equality_sameId_sameData() {
        val m1 = MailEntity(id = "same", title = "标题")
        val m2 = MailEntity(id = "same", title = "标题")
        assertEquals(m1, m2)
    }

    @Test
    fun mailEntity_inequality_differentId() {
        val m1 = MailEntity(id = "id1")
        val m2 = MailEntity(id = "id2")
        assertNotEquals(m1, m2)
    }

    // ==================== MailEntity 默认值边界 ====================

    @Test
    fun mailEntity_defaultSenderName() {
        val mail = MailEntity()
        assertEquals("天道意志", mail.senderName)
    }

    @Test
    fun mailEntity_defaultAttachments() {
        val mail = MailEntity()
        assertEquals("[]", mail.attachments)
    }

    @Test
    fun mailEntity_defaultSource() {
        val mail = MailEntity()
        assertEquals("builtin", mail.source)
    }

    @Test
    fun mailEntity_defaultMailType() {
        val mail = MailEntity()
        assertEquals("reward", mail.mailType)
    }
}
