package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.mockSmart
import com.xianxia.sect.core.model.MailAttachment
import com.xianxia.sect.core.model.MailEntity
import com.xianxia.sect.core.overflow.OverflowMailDraft
import com.xianxia.sect.core.overflow.PersistedDirectMailDraft
import com.xianxia.sect.core.overflow.PersistedOverflowDraft
import com.xianxia.sect.core.repository.MailRepository
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.CoroutineScopeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.after
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.timeout
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File


/**
 * OverflowMailSender 单元测试（D-01 事务化根治）：
 *
 * - 邮件构建（标题/内容/附件/来源映射/有效期）——不变行为
 * - 来源名映射表覆盖守卫（withTrackingSource 字面量全注册）
 * - **事务世代号**：gen>0 入 staging、提交钩子恰一次落盘、回滚钩子丢弃；
 *   gen==0（事务外）立即落盘
 * - 落盘失败 → unpublished 队列 → drain 补落盘（宁可延迟不丢资产）
 * - drain 读 DB 草稿分组构建邮件、幂等 mailId（同组草稿重放同 id）、
 *   mails 写入 + 草稿删除原子事务
 * - 直发草稿：事务化落盘 + drain 原样还原（payload JSON 保真）
 */
class OverflowMailSenderTest {

    private lateinit var mailRepo: MailRepository
    private lateinit var stateStore: GameStateStore
    private lateinit var sender: OverflowMailSender

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Before
    fun setUp() {
        // mockSmart + 显式 stub：currentTransactionGeneration 需在用例内切换世代号
        // （Fake 继承接口默认实现不可改），warehouseFullEvent/落盘桩语义依赖 stub
        mailRepo = mockSmart<MailRepository>()
        stateStore = mockSmart<GameStateStore>()
        whenever(stateStore.warehouseFullEvent).thenReturn(MutableSharedFlow())
        // 默认：无进行中事务（gen=0）→ 立即落盘路径；drain 读 DB 返回空
        whenever(stateStore.currentTransactionGeneration).thenReturn(0L)
        whenever(mailRepo.getPersistedOverflowDraftsBlocking()).thenReturn(emptyList())
        whenever(mailRepo.getPersistedDirectMailDraftsBlocking()).thenReturn(emptyList())
        // 默认落盘成功（测试主路径）；失败场景单独 stub
        whenever(mailRepo.insertOverflowDraftsBlocking(any())).thenReturn(1)
        whenever(mailRepo.insertDirectMailDraftBlocking(any())).thenReturn(true)
        sender = OverflowMailSender(mailRepo, stateStore, TestScopeProvider())
    }

    private class TestScopeProvider : CoroutineScopeProvider {
        override val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        override val ioScope = scope
    }

    private fun sampleDrafts(n: Int = 1, source: String = "battle"): List<OverflowMailDraft> =
        (1..n).map { i ->
            OverflowMailDraft(
                slotId = 1, source = source, itemType = "material",
                itemName = "玄铁精$i", rarity = 2, quantity = i
            )
        }

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

    // ═══════════════════════════════════════════════════════════════
    // 邮件构建（不变行为）
    // ═══════════════════════════════════════════════════════════════

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
        assertEquals("【仓库已满】宗门战奖励转入邮件", mail.title)
        assertTrue(mail.content.contains("宗门战奖励"))
        assertTrue(mail.content.contains("玄铁精 ×3"))
        assertTrue(mail.content.contains("下品培元丹 ×2"))
        assertTrue(mail.attachments.contains("玄铁精"))
        assertTrue(mail.attachments.contains("下品培元丹"))
        assertEquals(1, mail.slotId)
        assertEquals("overflow", mail.mailType)
        assertEquals("天道意志", mail.senderName)
        assertTrue(mail.hasAttachment)
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

    // ═══════════════════════════════════════════════════════════════
    // 事务世代号：事务内入 staging → 提交恰一次落盘 / 回滚丢弃
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `transaction staging - committed callback persists exactly once`() {
        whenever(stateStore.currentTransactionGeneration).thenReturn(7L)
        sender.sendOverflowMails(sampleDrafts(2))
        // 事务内：仅 staging，不落盘
        verify(mailRepo, never()).insertOverflowDraftsBlocking(any())
        verify(mailRepo, never()).insertDirectMailDraftBlocking(any())

        sender.onTransactionCommitted(7)

        verify(mailRepo, times(1)).insertOverflowDraftsBlocking(any())
        val captor = argumentCaptor<List<PersistedOverflowDraft>>()
        verify(mailRepo).insertOverflowDraftsBlocking(captor.capture())
        val persisted = captor.firstValue
        assertEquals("2 条草稿一次落盘", 2, persisted.size)
        assertEquals("id 已分配（UUID）", persisted.size, persisted.map { it.id }.toSet().size)
        assertEquals("来源/槽位保真", "battle", persisted.first().source)
        assertEquals("slotId 保真", 1, persisted.first().slotId)
        assertTrue("createdAt 已打时间戳", persisted.first().createdAt > 0)
    }

    @Test
    fun `transaction staging - rolled back callback discards drafts`() {
        whenever(stateStore.currentTransactionGeneration).thenReturn(3L)
        sender.sendOverflowMails(sampleDrafts(2))
        sender.sendDirectMail(sampleDirectMail())

        sender.onTransactionRolledBack(3)

        verify(mailRepo, never()).insertOverflowDraftsBlocking(any())
        verify(mailRepo, never()).insertDirectMailDraftBlocking(any())
    }

    @Test
    fun `nested drafts of distinct generations persist under their own generation`() {
        // 世代 1 提交落盘，世代 2 回滚丢弃——互不干扰
        whenever(stateStore.currentTransactionGeneration).thenReturn(1L)
        sender.sendOverflowMails(sampleDrafts(1, source = "battle"))
        sender.onTransactionCommitted(1)

        whenever(stateStore.currentTransactionGeneration).thenReturn(2L)
        sender.sendOverflowMails(sampleDrafts(1, source = "forge"))
        sender.onTransactionRolledBack(2)

        verify(mailRepo, times(1)).insertOverflowDraftsBlocking(any())
        val captor = argumentCaptor<List<PersistedOverflowDraft>>()
        verify(mailRepo).insertOverflowDraftsBlocking(captor.capture())
        assertEquals("仅世代 1 落盘", "battle", captor.firstValue.first().source)
    }

    // ═══════════════════════════════════════════════════════════════
    // 事务外（gen=0）：立即落盘，不依赖 drain
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `outside transaction - overflow drafts persisted immediately`() {
        sender.sendOverflowMails(sampleDrafts(2))
        verify(mailRepo, times(1)).insertOverflowDraftsBlocking(any())
    }

    @Test
    fun `outside transaction - direct mail persisted immediately with payload`() {
        val mail = sampleDirectMail("direct_1")
        sender.sendDirectMail(mail)
        val captor = argumentCaptor<PersistedDirectMailDraft>()
        verify(mailRepo).insertDirectMailDraftBlocking(captor.capture())
        assertEquals("id 即邮件 id（幂等）", "direct_1", captor.firstValue.id)
        assertEquals("payload 保真", mail, json.decodeFromString<MailEntity>(captor.firstValue.payload))
    }

    @Test
    fun `empty id direct mail refused`() {
        sender.sendDirectMail(sampleDirectMail(id = ""))
        verify(mailRepo, never()).insertDirectMailDraftBlocking(any())
    }

    // ═══════════════════════════════════════════════════════════════
    // 落盘失败 → unpublished 补落盘（宁可延迟不丢资产）
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `failed persist goes to unpublished and is retried on drain`() {
        whenever(mailRepo.insertOverflowDraftsBlocking(any())).thenReturn(0)
        sender.sendOverflowMails(sampleDrafts(1))
        verify(mailRepo, times(1)).insertOverflowDraftsBlocking(any())

        // 落盘恢复：drain 补落盘成功 → 随后 drain 读到 DB 草稿（此处 mock 返回空，
        // 只验证补落盘发生——由 InsertOverflowDraft 返回值 1 驱动）
        whenever(mailRepo.insertOverflowDraftsBlocking(any())).thenReturn(1)
        // 异步补落盘完成后即返回（原 sleep 600ms 固定等待，改轮询验证防抖动）
        verify(mailRepo, timeout(2_000).times(2)).insertOverflowDraftsBlocking(any())
    }

    // ═══════════════════════════════════════════════════════════════
    // drain：读 DB 草稿 → 分组构建邮件 → 原子写 mails + 删行
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `drain groups drafts by slot and source into single mails`() = kotlinx.coroutines.test.runTest {
        val drafts = listOf(
            PersistedOverflowDraft("a1", 1, "battle", "material", "玄铁精", 2, 3, 100L),
            PersistedOverflowDraft("a2", 1, "battle", "pill", "回气丹", 1, 5, 101L),
            PersistedOverflowDraft("a3", 2, "battle", "material", "玄铁精", 2, 1, 102L),
            PersistedOverflowDraft("a4", 1, "forge", "material", "精铁", 2, 2, 103L)
        )
        whenever(mailRepo.getPersistedOverflowDraftsBlocking()).thenReturn(drafts)
        sender.drainPersistedDrafts()

        // 3 组（slot=1/source=battle、slot=2/source=battle、slot=1/source=forge）→ 3 封邮件
        // timeout 轮询：异步 drain 完成即返回（原 sleep 600ms 固定等待）
        verify(mailRepo, timeout(2_000).times(3)).insertWithEnforceLimitAndDeleteDrafts(
            any(), eq(1000), any(), any()
        )
        // 每组草稿 id 一并删除（原子事务）
        val captor = argumentCaptor<List<String>>()
        verify(mailRepo, times(3)).insertWithEnforceLimitAndDeleteDrafts(
            any(), any(), captor.capture(), any()
        )
        val deletedIdSets = captor.allValues.map { it.sorted() }
        assertTrue("battle/slot1 组删 a1+a2", deletedIdSets.contains(listOf("a1", "a2")))
        assertTrue("battle/slot2 组删 a3", deletedIdSets.contains(listOf("a3")))
        assertTrue("forge/slot1 组删 a4", deletedIdSets.contains(listOf("a4")))
    }

    @Test
    fun `drain mail id is deterministic across replays`() = kotlinx.coroutines.test.runTest {
        val drafts = listOf(
            PersistedOverflowDraft("a1", 1, "battle", "material", "玄铁精", 2, 3, 100L),
            PersistedOverflowDraft("a2", 1, "battle", "pill", "回气丹", 1, 5, 101L)
        )
        whenever(mailRepo.getPersistedOverflowDraftsBlocking()).thenReturn(drafts)

        // 两段式触发两次独立 drain 周期：scheduleDrain 单飞去重（drainScheduled 标志）——
        // 复位窗口（insert 后 finally 复位）不可直接观察，用"轮询重试"代替固定 sleep：
        // 未复位时调用被合并丢弃（仅布尔判断，零开销），复位后调用即启动新周期。
        sender.drainPersistedDrafts()
        verify(mailRepo, timeout(2_000)).insertWithEnforceLimitAndDeleteDrafts(
            any(), any(), any(), any()
        )
        val replayDeadline = System.nanoTime() + REPLAY_WAIT_NANOS
        while (overflowInsertCount() < 2 && System.nanoTime() < replayDeadline) {
            sender.drainPersistedDrafts()
        }

        // 两次 drain 各构建一封（mock 无状态，草稿仍在）——mail id 必须相同（幂等 REPLACE 不重复）
        val captor = argumentCaptor<MailEntity>()
        verify(mailRepo, timeout(2_000).times(2)).insertWithEnforceLimitAndDeleteDrafts(
            captor.capture(), any(), any(), any()
        )
        val ids = captor.allValues.map { it.id }
        assertEquals("同组草稿重放生成同 mail id（幂等）", ids[0], ids[1])
    }

    @Test
    fun `deterministic mail id ignores draft order`() {
        val a = OverflowMailSender.deterministicOverflowMailId(1, "battle", listOf("a1", "a2"))
        val b = OverflowMailSender.deterministicOverflowMailId(1, "battle", listOf("a2", "a1"))
        val c = OverflowMailSender.deterministicOverflowMailId(2, "battle", listOf("a1", "a2"))
        assertEquals(a, b)
        assertTrue("不同槽位/来源 id 不同", a != c)
    }

    @Test
    fun `drain failure keeps draft rows for retry`() = kotlinx.coroutines.test.runTest {
        val drafts = listOf(
            PersistedOverflowDraft("a1", 1, "battle", "material", "玄铁精", 2, 3, 100L)
        )
        whenever(mailRepo.getPersistedOverflowDraftsBlocking()).thenReturn(drafts)
        whenever(mailRepo.insertWithEnforceLimitAndDeleteDrafts(any(), any(), any(), any()))
            .thenThrow(RuntimeException("DB 写入失败"))
        sender.drainPersistedDrafts()
        // 失败不删行（无 delete 调用）；行保留下次重试
        // after：等待异步 drain 完成后断言（语义等价原 sleep 600ms，窗口更足）
        verify(mailRepo, after(2_000).never()).deleteOverflowDraftsBlocking(any())
    }

    // ═══════════════════════════════════════════════════════════════
    // 直发 drain：payload 还原原样写入
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `drain restores direct mail from payload and deletes draft`() = kotlinx.coroutines.test.runTest {
        val mail = sampleDirectMail("direct_1")
        val draft = PersistedDirectMailDraft("direct_1", 1, json.encodeToString(mail), 100L)
        whenever(mailRepo.getPersistedDirectMailDraftsBlocking()).thenReturn(listOf(draft))
        sender.drainPersistedDrafts()
        // timeout 轮询：异步 drain 完成即返回（原 sleep 600ms）
        val captor = argumentCaptor<MailEntity>()
        verify(mailRepo, timeout(2_000)).insertWithEnforceLimitAndDeleteDrafts(
            captor.capture(), eq(1000), eq(emptyList<String>()), eq(listOf("direct_1"))
        )
        assertEquals("直发草稿原样还原", mail, captor.firstValue)
    }

    @Test
    fun `drain with corrupt direct mail payload deletes row to avoid infinite retry`() =
        kotlinx.coroutines.test.runTest {
        val draft = PersistedDirectMailDraft("corrupt_1", 1, "{not-json", 100L)
        whenever(mailRepo.getPersistedDirectMailDraftsBlocking()).thenReturn(listOf(draft))
        sender.drainPersistedDrafts()
        // timeout 等 delete 发生（= 异步 drain 完成）后再断言无插入（原 sleep 600ms）
        verify(mailRepo, timeout(2_000)).deleteDirectMailDraftsBlocking(listOf("corrupt_1"))
        verify(mailRepo, never()).insertWithEnforceLimitAndDeleteDrafts(any(), any(), any(), any())
    }

    /** 重放轮询上限：复位窗口最长等待（原 sleep 600ms ×2 的语义上限） */
    private companion object {
        const val REPLAY_WAIT_NANOS = 2_000_000_000L
    }

    /** insert 已发生调用数（Mockito 调用记录计数，供轮询"第二次 drain 周期已启动"） */
    private fun overflowInsertCount(): Int =
        mockingDetails(mailRepo).invocations.count {
            it.method.name == "insertWithEnforceLimitAndDeleteDrafts" && !it.isIgnoredForVerification
        }
}
