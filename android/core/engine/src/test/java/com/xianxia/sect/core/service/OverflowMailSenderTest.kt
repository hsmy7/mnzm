package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.model.MailAttachment
import com.xianxia.sect.core.model.MailEntity
import com.xianxia.sect.core.overflow.OverflowMailDraft
import com.xianxia.sect.core.repository.MailRepository
import com.xianxia.sect.core.state.GameStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.io.File

/**
 * OverflowMailSender 单元测试：邮件构建（标题/内容/附件/来源映射/有效期）、
 * 来源名映射表覆盖守卫。
 */
class OverflowMailSenderTest {

    private lateinit var sender: OverflowMailSender

    @Before
    fun setUp() {
        val mailRepo = Mockito.mock(MailRepository::class.java)
        val stateStore = Mockito.mock(GameStateStore::class.java)
        Mockito.`when`(stateStore.warehouseFullEvent).thenReturn(kotlinx.coroutines.flow.MutableSharedFlow())
        sender = OverflowMailSender(mailRepo, stateStore, TestScopeProvider())
    }

    private class TestScopeProvider : com.xianxia.sect.core.util.CoroutineScopeProvider {
        override val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob()
        )
        override val ioScope = scope
    }

    @Test
    fun `buildOverflowMail - title content attachments and expiry`() {
        val now = 1_000_000L
        val mail = sender.buildOverflowMail(
            slotId = 1,
            source = "battle",
            attachments = listOf(
                MailAttachment(type = "material", name = "玄铁精", quantity = 3, rarity = 2),
                MailAttachment(type = "pill", name = "下品培元丹", quantity = 2, rarity = 1)
            ),
            now = now
        )
        // 标题含来源名
        assertEquals("【仓库已满】宗门战奖励转入邮件", mail.title)
        // 内容含来源说明与物品清单
        assertTrue(mail.content.contains("宗门战奖励"))
        assertTrue(mail.content.contains("玄铁精 ×3"))
        assertTrue(mail.content.contains("下品培元丹 ×2"))
        // 附件 JSON 含两种物品
        assertTrue(mail.attachments.contains("玄铁精"))
        assertTrue(mail.attachments.contains("下品培元丹"))
        assertEquals(1, mail.slotId)
        assertEquals("overflow", mail.mailType)
        assertEquals("天道意志", mail.senderName)
        assertTrue(mail.hasAttachment)
        // 溢出邮件不设短期过期（10 年有效期，玩家资产不因过期消失）
        assertTrue(mail.expireTime - now > 300L * 24 * 60 * 60 * 1000)
    }

    @Test
    fun `buildOverflowMail - unknown source falls back to display name`() {
        val mail = sender.buildOverflowMail(
            slotId = 1, source = "no_such_source",
            attachments = listOf(MailAttachment(type = "material", name = "玄铁精", quantity = 1, rarity = 2)),
            now = 1_000_000L
        )
        assertEquals("【仓库已满】未知奖励转入邮件", mail.title)
    }

    @Test
    fun `sourceDisplayName - known and unknown sources`() {
        assertEquals("宗门战", OverflowMailSender.sourceDisplayName("battle"))
        assertEquals("灵田", OverflowMailSender.sourceDisplayName("spirit_field"))
        assertEquals("未知", OverflowMailSender.sourceDisplayName("no_such_source"))
    }

    @Test
    fun `SOURCE_DISPLAY_NAMES covers all withTrackingSource literals in engine source`() {
        // 守卫测试：engine 源码中的所有 withTrackingSource("x") 字面量必须存在于映射表
        val engineSrc = File("src/main/java")
        val sourceLiterals = engineSrc.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                Regex("withTrackingSource\\(\"([^\"]+)\"\\)").findAll(file.readText()).map { it.groupValues[1] }
            }
            .toSet()
        val missing = sourceLiterals - OverflowMailSender.SOURCE_DISPLAY_NAMES.keys
        assertEquals(
            "新增 withTrackingSource 来源未在 OverflowMailSender.SOURCE_DISPLAY_NAMES 注册：$missing\n" +
                "（溢出邮件标题/内容将显示为\"未知\"，请补映射）",
            emptySet<String>(), missing
        )
    }

    @Test
    fun `OverflowMailDraft carries overflow fields`() {
        val draft = OverflowMailDraft(
            slotId = 1, source = "battle", itemType = "pill",
            itemName = "回气丹", rarity = 1, quantity = 5
        )
        assertEquals("battle", draft.source)
        assertEquals("回气丹", draft.itemName)
        assertEquals(5, draft.quantity)
    }

    // ── 直发邮件（秘境关闭返还等自定义标题/内容邮件） ─────────────────────

    private fun sampleDirectMail(id: String = "mail_1"): MailEntity = MailEntity(
        id = id,
        slotId = 1,
        source = "secret_realm",
        mailType = "secret_realm_close",
        title = "远古秘境已关闭",
        content = "远古秘境已关闭，这些物品是远古秘境中获得的物品：\n\n• 虎骨 ×1\n——天道意志",
        senderName = "天道意志",
        sendTime = 1_000L,
        expireTime = 2_000L,
        hasAttachment = true,
        attachments = "[]"
    )

    @Test
    fun `sendDirectMail - 自定义邮件经防抖 drain 原样落库`() = kotlinx.coroutines.test.runTest {
        val mailRepo = Mockito.mock(MailRepository::class.java)
        val stateStore = Mockito.mock(GameStateStore::class.java)
        Mockito.`when`(stateStore.warehouseFullEvent).thenReturn(kotlinx.coroutines.flow.MutableSharedFlow())
        val sender = OverflowMailSender(mailRepo, stateStore, TestScopeProvider())
        val mail = sampleDirectMail()
        sender.sendDirectMail(mail)
        // drain 防抖 300ms 后写入 Room；等真实时间（Default dispatcher，虚拟时间无法推进）
        Thread.sleep(600)
        Mockito.verify(mailRepo).insertWithEnforceLimit(mail, 1000)
    }

    @Test
    fun `sendDirectMail - 空 id 防御不入队不落库`() = kotlinx.coroutines.test.runTest {
        val mailRepo = Mockito.mock(MailRepository::class.java)
        val stateStore = Mockito.mock(GameStateStore::class.java)
        Mockito.`when`(stateStore.warehouseFullEvent).thenReturn(kotlinx.coroutines.flow.MutableSharedFlow())
        val sender = OverflowMailSender(mailRepo, stateStore, TestScopeProvider())
        sender.sendDirectMail(sampleDirectMail(id = ""))
        Thread.sleep(600)
        Mockito.verify(mailRepo, org.mockito.kotlin.never())
            .insertWithEnforceLimit(org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }
}
