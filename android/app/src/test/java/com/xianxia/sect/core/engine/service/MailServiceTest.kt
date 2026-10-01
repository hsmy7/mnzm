package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.AdFreeWhitelist
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.model.MailClaimRecord
import com.xianxia.sect.core.model.MailEntity
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.repository.MailRepository
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.GameStateStoreImpl
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq

/**
 * MailService 邮件领取核心逻辑测试
 *
 * 覆盖修复：
 * - claimAttachment 检测 mailRecords 不一致时自愈 Room 状态
 * - claimAttachmentInternal 被 mailRecords 拦截不重复发放物品
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MailServiceTest {

    private lateinit var service: MailService
    private lateinit var mailRepo: MailRepository
    private lateinit var stateStore: GameStateStore
    private lateinit var inventoryConfig: InventoryConfig
    private lateinit var scopeProvider: ApplicationScopeProvider
    private lateinit var serviceScopeProvider: com.xianxia.sect.core.util.CoroutineScopeProvider
    private val spiritStoneWallet = mock(SpiritStoneWallet::class.java)

    // 测试常量
    private val testMailId = "online_test_001"
    private val now = System.currentTimeMillis()
    private val futureExpire = now + 30L * 24 * 60 * 60 * 1000 // 30天后过期

    /**
     * 创建一个未领取的测试邮件
     */
    private fun createUnclaimedMail(
        id: String = testMailId,
        hasAttachments: Boolean = true
    ): MailEntity {
        val attachmentsJson = if (hasAttachments) {
            """[{"type":"spiritStones","name":"灵石","quantity":100,"rarity":1}]"""
        } else "[]"
        return MailEntity(
            id = id,
                source = "online",
            mailType = "reward",
            title = "测试邮件",
            content = "测试内容",
            senderName = "天道意志",
            sendTime = now,
            expireTime = futureExpire,
            isRead = false,
            attachmentClaimed = false,
            hasAttachment = hasAttachments,
            attachments = attachmentsJson,
            remoteMailId = "test_001"
        )
    }

    @Before
    fun setUp() {
        scopeProvider = ApplicationScopeProvider()
        mailRepo = mock(MailRepository::class.java)
        inventoryConfig = mock(InventoryConfig::class.java)
        // E5 守卫：maxStack<=0 时 addXxx 直接失败——储物袋/材料等附件发放
        // 需要真实堆叠上限（默认 9999），裸 mock 默认返回 0 会导致发放失败
        `when`(inventoryConfig.getMaxStackSize(any())).thenReturn(9999)
        stateStore = GameStateStoreImpl(
            ApplicationScopeProvider()
        )
        (stateStore as GameStateStoreImpl).unsafeAllowMainThreadUpdateForTest = true

        // 设置默认 mock 行为
        `when`(mailRepo.getActiveMails()).thenReturn(flowOf(emptyList()))
        val gameRngManager = mock(com.xianxia.sect.core.util.GameRngManager::class.java)
        `when`(gameRngManager.getRng(any())).thenReturn(DeterministicRng(42))

        val inventorySystem = com.xianxia.sect.core.engine.system.InventorySystem(
            stateStore,
            inventoryConfig,
        )
        serviceScopeProvider = mock(com.xianxia.sect.core.util.CoroutineScopeProvider::class.java)
        service = MailService(
            mailRepo = mailRepo,
            stateStore = stateStore,
            spiritStoneWallet = spiritStoneWallet,
            scopeProvider = serviceScopeProvider,
            gameRngManager = gameRngManager,
            gameConfigProvider = mock(com.xianxia.sect.core.engine.config.GameConfigProvider::class.java),
            inventorySystem = inventorySystem
        )

        runBlocking { stateStore.reset() }
    }

    @After
    fun tearDown() {
        // 清除白名单状态，防止污染其他测试
        AdFreeWhitelist.initialize(null)
        (stateStore as GameStateStoreImpl).unsafeAllowMainThreadUpdateForTest = false
        runBlocking { stateStore.reset() }
    }

    // ============================================================
    // claimAttachment — AlreadyClaimed via mailRecords
    // ============================================================

    @Test
    fun `claimAttachment - mailRecords has entry and Room not synced, heals Room and returns AlreadyClaimed`() =
        runBlocking {
            // Arrange: Room 中邮件未标记已领，但 mailRecords 已有记录
            val mail = createUnclaimedMail()
            `when`(mailRepo.getById(eq(testMailId))).thenReturn(mail)

            // 预置 mailRecord（模拟 Room 更新失败后重进场景）
            stateStore.update {
                gameData = gameData.copy(
                    mailRecords = listOf(
                        MailClaimRecord(
                            mailId = testMailId,
                            claimedAt = now - 86400000, // 昨天领取
                            source = "online"
                        )
                    )
                )
            }

            // Act
            val result = service.claimAttachment(testMailId)

            // Assert: 应返回 AlreadyClaimed
            assertTrue(
                "mailRecords 已有记录时应返回 AlreadyClaimed",
                result is ClaimResult.AlreadyClaimed
            )

            // Assert: 应调用了自愈 Room 的 update
            verify(mailRepo).update(argThat { entity ->
                entity.id == testMailId &&
                    entity.attachmentClaimed &&
                    entity.isRead
            })
        }

    @Test
    fun `claimAttachment - mailRecords has entry but heal fails, still returns AlreadyClaimed`() =
        runBlocking {
            // Arrange: Room update 会失败（模拟磁盘满）
            val mail = createUnclaimedMail()
            `when`(mailRepo.getById(eq(testMailId))).thenReturn(mail)
            `when`(mailRepo.update(any())).thenThrow(RuntimeException("Disk full"))

            stateStore.update {
                gameData = gameData.copy(
                    mailRecords = listOf(
                        MailClaimRecord(testMailId, now, "online")
                    )
                )
            }

            // Act: 不应因自愈失败而崩溃
            val result = service.claimAttachment(testMailId)

            // Assert: 即使自愈失败，仍应返回 AlreadyClaimed（不重复发物）
            assertTrue(
                "自愈失败时仍应返回 AlreadyClaimed 防止重复发物",
                result is ClaimResult.AlreadyClaimed
            )
        }

    @Test
    fun `claimAttachment - fresh mail without mailRecord, claims normally`() = runBlocking {
        // Arrange: 正常未领取邮件，mailRecords 中无记录
        val mail = createUnclaimedMail()
        `when`(mailRepo.getById(eq(testMailId))).thenReturn(mail)

        // Act
        val result = service.claimAttachment(testMailId)

        // Assert: 应成功领取
        assertTrue(
            "mailRecords 无记录且邮件未领时应成功",
            result is ClaimResult.Success
        )

        // 验证 mailRecord 已写入
        val finalState = stateStore.gameData.value
        assertTrue(
            "领取后 mailRecords 应包含该邮件",
            finalState.mailRecords.any { it.mailId == testMailId }
        )
    }

    @Test
    fun `claimAttachment - unknown attachment type returns DistributeFailed and no mailRecord`() = runBlocking {
        // 附件 type 不在已知 11 类（服务端别名字符串/新增类型）时：
        // distributeAttachmentsInline 的 when else 分支抛异常 → 同事务回滚
        // （mailRecords 不写）→ 返回 DistributeFailed，失败响亮且保留凭据可重试。
        val mail = createUnclaimedMail().copy(
            attachments = """[{"type":"mystery","name":"神秘物品","quantity":1,"rarity":1}]"""
        )
        `when`(mailRepo.getById(eq(testMailId))).thenReturn(mail)

        val result = service.claimAttachment(testMailId)

        assertTrue("未知附件类型必须返回 DistributeFailed", result is ClaimResult.DistributeFailed)
        assertTrue(
            "失败必须回滚，不得写入 mailRecords（否则会显示已领取但无物品）",
            stateStore.gameData.value.mailRecords.none { it.mailId == testMailId }
        )
    }

    // ============================================================
    // claimAttachment — 其他边界条件
    // ============================================================

    @Test
    fun `claimAttachment - mail not found returns MailNotFound`() = runBlocking {
        `when`(mailRepo.getById(eq(testMailId))).thenReturn(null)
        val result = service.claimAttachment(testMailId)
        assertTrue(result is ClaimResult.MailNotFound)
    }

    @Test
    fun `claimAttachment - expired mail returns Expired`() = runBlocking {
        val expiredMail = createUnclaimedMail().copy(
            expireTime = now - 1000 // 已过期
        )
        `when`(mailRepo.getById(eq(testMailId))).thenReturn(expiredMail)
        val result = service.claimAttachment(testMailId)
        assertTrue(result is ClaimResult.Expired)
    }

    @Test
    fun `claimAttachment - already claimed in Room returns AlreadyClaimed`() = runBlocking {
        val claimedMail = createUnclaimedMail().copy(attachmentClaimed = true)
        `when`(mailRepo.getById(eq(testMailId))).thenReturn(claimedMail)
        val result = service.claimAttachment(testMailId)
        assertTrue(result is ClaimResult.AlreadyClaimed)
    }

    // ============================================================
    // 验证 ClaimResult sealed class 穷举完整性（编译时保证）
    // ============================================================

    @Test
    fun `ClaimResult sealed class has all expected variants`() {
        // 若编译通过即证明穷举完备；此测试文档化所有变体
        val variants = listOf(
            ClaimResult.Success(emptyList()),
            ClaimResult.AlreadyClaimed,
            ClaimResult.Expired,
            ClaimResult.MailNotFound,
            ClaimResult.CapacityInsufficient("仓库满"),
            ClaimResult.DistributeFailed("发放失败")
        )
        assertEquals(6, variants.size)
    }

    private companion object {
        /** 永久邮件测试用 id（永不过期语义的样例邮件） */
        const val WHITELIST_BONUS_MAIL_ID = "whitelist_bonus_v1"
    }

    @Test
    fun `claimAttachment - permanent mail never expires`() = runBlocking {
        // Arrange: expireTime = Long.MAX_VALUE 的永久邮件，mailRecords 无记录
        val permanentMail = createUnclaimedMail().copy(
            id = WHITELIST_BONUS_MAIL_ID,
            expireTime = Long.MAX_VALUE,
            source = "admin"
        )
        `when`(mailRepo.getById(eq(WHITELIST_BONUS_MAIL_ID)))
            .thenReturn(permanentMail)

        // Act
        val result = service.claimAttachment(WHITELIST_BONUS_MAIL_ID)

        // Assert: 永久邮件不应被判为过期
        assertTrue(
            "永久邮件领取不应返回 Expired",
            result !is ClaimResult.Expired
        )
    }

    @Test
    fun `resetAndInitSlot - never deletes any mail (mails retained forever)`() = runBlocking {
        // Arrange: startMailFlowCollector 需要真实 scope
        `when`(serviceScopeProvider.scope).thenReturn(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        )

        // Act: 读档/切档/重开路径
        service.resetAndInit()

        // Assert: 邮件永久保留——reset 绝不删除任何邮件（全量清空或按源删除都不发生），
        // 否则未领取的溢出/直发邮件（草稿已被 drain 消费、无处重建）会被静默清掉
        verify(mailRepo, never()).deleteAllReadAndClaimed()
        verify(mailRepo, never()).deleteIfClaimed(any())
    }

    @Test
    fun `markAllAsRead - never deletes unclaimed mails`() = runBlocking {
        // Arrange: 一封已读未领取、一封已读已领取
        val now = System.currentTimeMillis()
        val unclaimed = createUnclaimedMail(id = "unclaimed_1", hasAttachments = true).copy(isRead = true)
        val claimed = createUnclaimedMail(id = "claimed_1", hasAttachments = true).copy(
            isRead = true, attachmentClaimed = true
        )
        `when`(mailRepo.getActiveMails()).thenReturn(flowOf(listOf(unclaimed, claimed)))

        // Act: 一键已读（会对未领取附件尝试领取；容量充足）
        service.markAllAsRead()

        // Assert: 已读未领取的邮件绝不被自动删除——删除入口只有玩家手动"删除已读"
        verify(mailRepo, never()).deleteAllReadAndClaimed()
    }

    @Test
    fun `claimAttachment - 上品灵石 attachment distributes HIGH grade matching name`() = runBlocking {
        // Arrange: 附件名"上品灵石"，发放品阶必须与名称一致（与邮件附件卡片
        // 精灵图品阶解析一致，杜绝显示品阶与到账品阶不一致的错图）
        `when`(spiritStoneWallet.add(any(), any(), any(), any(), any())).thenAnswer { inv ->
            val state = inv.getArgument<com.xianxia.sect.core.state.MutableGameState>(0)
            val amount = inv.getArgument<Long>(1)
            state.gameData = state.gameData.copy(spiritStones = state.gameData.spiritStones + amount)
            state.gameData.spiritStones
        }
        val mail = createUnclaimedMail().copy(
            attachments = """[{"type":"spiritStones","name":"上品灵石","quantity":5,"rarity":3}]"""
        )
        `when`(mailRepo.getById(eq(testMailId))).thenReturn(mail)

        // Act
        val result = service.claimAttachment(testMailId)

        // Assert: 成功领取且按上品灵石入账
        assertTrue("上品灵石邮件应领取成功", result is ClaimResult.Success)
        verify(spiritStoneWallet).add(
            any(),
            eq(5L),
            eq(SpiritStoneGrade.HIGH),
            any(),
            any()
        )
        Unit
    }

    @Test
    fun `claimAttachment - plain 灵石 attachment distributes LOW grade`() = runBlocking {
        // Arrange: 默认名称"灵石"（无品阶词）→ LOW，保持既有行为
        `when`(spiritStoneWallet.add(any(), any(), any(), any(), any())).thenAnswer { inv ->
            val state = inv.getArgument<com.xianxia.sect.core.state.MutableGameState>(0)
            val amount = inv.getArgument<Long>(1)
            state.gameData = state.gameData.copy(spiritStones = state.gameData.spiritStones + amount)
            state.gameData.spiritStones
        }
        `when`(mailRepo.getById(eq(testMailId)))
            .thenReturn(createUnclaimedMail())

        // Act
        val result = service.claimAttachment(testMailId)

        // Assert: 成功领取且按下品灵石入账（名称无品阶词默认 LOW）
        assertTrue("普通灵石邮件应领取成功", result is ClaimResult.Success)
        verify(spiritStoneWallet).add(
            any(),
            eq(100L),
            eq(SpiritStoneGrade.LOW),
            any(),
            any()
        )
        Unit
    }
}
