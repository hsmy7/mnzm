package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.AdFreeWhitelist
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.model.MailClaimRecord
import com.xianxia.sect.core.model.MailEntity
import com.xianxia.sect.core.repository.MailRepository
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.GameStateStoreImpl
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.di.ApplicationScopeProvider
import com.xianxia.sect.core.util.HttpClientProvider
import com.xianxia.sect.core.state.testGameStateRepository
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
    private lateinit var httpClient: HttpClientProvider
    private lateinit var scopeProvider: ApplicationScopeProvider
    private lateinit var serviceScopeProvider: com.xianxia.sect.core.util.CoroutineScopeProvider
    private val spiritStoneWallet = mock(SpiritStoneWallet::class.java)

    // 测试常量
    private val testSlotId = 1
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
            slotId = testSlotId,
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
        httpClient = mock(HttpClientProvider::class.java)
        stateStore = GameStateStoreImpl(
            scopeProvider,
            testGameStateRepository()
        )
        (stateStore as GameStateStoreImpl).unsafeAllowMainThreadUpdateForTest = true

        // 设置默认 mock 行为
        `when`(mailRepo.getActiveMails(any())).thenReturn(flowOf(emptyList()))
        val gameRngManager = mock(com.xianxia.sect.core.util.GameRngManager::class.java)
        `when`(gameRngManager.getRng(any())).thenReturn(DeterministicRng(42))

        val inventorySystem = com.xianxia.sect.core.engine.system.InventorySystem(
            stateStore,
            inventoryConfig,
            spiritStoneWallet,
            mock(com.xianxia.sect.core.engine.config.GameConfigProvider::class.java)
        )
        serviceScopeProvider = mock(com.xianxia.sect.core.util.CoroutineScopeProvider::class.java)
        service = MailService(
            mailRepo = mailRepo,
            stateStore = stateStore,
            httpClient = httpClient,
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
            `when`(mailRepo.getById(eq(testSlotId), eq(testMailId))).thenReturn(mail)

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
            val result = service.claimAttachment(testMailId, testSlotId)

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
            `when`(mailRepo.getById(eq(testSlotId), eq(testMailId))).thenReturn(mail)
            `when`(mailRepo.update(any())).thenThrow(RuntimeException("Disk full"))

            stateStore.update {
                gameData = gameData.copy(
                    mailRecords = listOf(
                        MailClaimRecord(testMailId, now, "online")
                    )
                )
            }

            // Act: 不应因自愈失败而崩溃
            val result = service.claimAttachment(testMailId, testSlotId)

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
        `when`(mailRepo.getById(eq(testSlotId), eq(testMailId))).thenReturn(mail)

        // Act
        val result = service.claimAttachment(testMailId, testSlotId)

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

    // ============================================================
    // claimAttachment — 其他边界条件
    // ============================================================

    @Test
    fun `claimAttachment - mail not found returns MailNotFound`() = runBlocking {
        `when`(mailRepo.getById(eq(testSlotId), eq(testMailId))).thenReturn(null)
        val result = service.claimAttachment(testMailId, testSlotId)
        assertTrue(result is ClaimResult.MailNotFound)
    }

    @Test
    fun `claimAttachment - expired mail returns Expired`() = runBlocking {
        val expiredMail = createUnclaimedMail().copy(
            expireTime = now - 1000 // 已过期
        )
        `when`(mailRepo.getById(eq(testSlotId), eq(testMailId))).thenReturn(expiredMail)
        val result = service.claimAttachment(testMailId, testSlotId)
        assertTrue(result is ClaimResult.Expired)
    }

    @Test
    fun `claimAttachment - already claimed in Room returns AlreadyClaimed`() = runBlocking {
        val claimedMail = createUnclaimedMail().copy(attachmentClaimed = true)
        `when`(mailRepo.getById(eq(testSlotId), eq(testMailId))).thenReturn(claimedMail)
        val result = service.claimAttachment(testMailId, testSlotId)
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

    // ============================================================
    // injectWhitelistBonus — 白名单用户专属福利（1000 万灵石永久邮件）
    // ============================================================

    private companion object {
        const val WHITELIST_UNION_ID = "4FTGX7tp7MO1nr+j/Vwm5A=="
        const val WHITELIST_BONUS_MAIL_ID = "whitelist_bonus_v1"
        const val WHITELIST_BONUS_AMOUNT = 10_000_000

        // 专属福利（单用户定向活动，2026-08-04 发放）测试常量
        const val EXCLUSIVE_UNION_ID = "4FTGX7tp7MO1nr+j/Vwm5A=="
        const val EXCLUSIVE_BONUS_MAIL_ID = "exclusive_bonus_20260904"
        const val EXCLUSIVE_BONUS_AMOUNT = 10_000_000
        const val EXCLUSIVE_BONUS_DISCIPLE_COUNT = 10
        const val EXCLUSIVE_BONUS_EXPIRE_MS = 1_788_537_599_000L

        // 单用户定向补偿邮件（地品储物袋 ×10）测试常量
        const val COMPENSATION_UNION_ID = "I13lvAJjLgqSvh/LHjiJCg=="
        const val COMPENSATION_MAIL_ID = "compensation_storage_bag_v1"
        const val COMPENSATION_BAG_COUNT = 10
        const val COMPENSATION_BAG_RARITY = 5
    }

    @Test
    fun `injectWhitelistBonus - privileged user injects 10M permanent mail`() = runBlocking {
        // Arrange: 白名单用户，DB 中无该邮件
        AdFreeWhitelist.initialize(WHITELIST_UNION_ID)
        `when`(mailRepo.getById(eq(testSlotId), eq(WHITELIST_BONUS_MAIL_ID))).thenReturn(null)

        // Act
        val injected = service.injectWhitelistBonus(testSlotId)

        // Assert
        assertTrue("白名单用户应成功注入", injected)
        verify(mailRepo).insertWithEnforceLimit(argThat { mail ->
            mail.id == WHITELIST_BONUS_MAIL_ID &&
                mail.expireTime == Long.MAX_VALUE &&
                mail.source == "admin" &&
                mail.hasAttachment &&
                mail.attachments.contains("\"quantity\":$WHITELIST_BONUS_AMOUNT")
        }, any())
    }

    @Test
    fun `injectWhitelistBonus - non whitelist user skips`() = runBlocking {
        // Arrange: 非白名单用户
        AdFreeWhitelist.initialize("some_other_union_id")

        // Act
        val injected = service.injectWhitelistBonus(testSlotId)

        // Assert
        assertFalse("非白名单用户应跳过", injected)
        verify(mailRepo, never()).insertWithEnforceLimit(any(), any())
    }

    @Test
    fun `injectWhitelistBonus - uninitialized unionId skips`() = runBlocking {
        // Arrange: 未初始化（时序兜底：即使初始化丢失也不注入）
        AdFreeWhitelist.initialize(null)

        // Act
        val injected = service.injectWhitelistBonus(testSlotId)

        // Assert
        assertFalse("unionId 未初始化时应跳过", injected)
        verify(mailRepo, never()).insertWithEnforceLimit(any(), any())
    }

    @Test
    fun `injectWhitelistBonus - already claimed in mailRecords skips`() = runBlocking {
        // Arrange: 白名单用户，但 mailRecords 已有领取记录
        AdFreeWhitelist.initialize(WHITELIST_UNION_ID)
        stateStore.update {
            gameData = gameData.copy(
                mailRecords = listOf(
                    MailClaimRecord(WHITELIST_BONUS_MAIL_ID, now, "admin")
                )
            )
        }

        // Act
        val injected = service.injectWhitelistBonus(testSlotId)

        // Assert
        assertFalse("已领取时应跳过", injected)
        verify(mailRepo, never()).insertWithEnforceLimit(any(), any())
    }

    @Test
    fun `injectWhitelistBonus - mail already in DB skips`() = runBlocking {
        // Arrange: 白名单用户，但 DB 中已存在该邮件
        AdFreeWhitelist.initialize(WHITELIST_UNION_ID)
        val existing = MailEntity(
            id = WHITELIST_BONUS_MAIL_ID,
            slotId = testSlotId,
            source = "admin",
            mailType = "reward",
            title = "白名单专属福利",
            content = "",
            senderName = "天道意志",
            sendTime = now,
            expireTime = Long.MAX_VALUE,
            hasAttachment = true,
            attachments = "[]"
        )
        `when`(mailRepo.getById(eq(testSlotId), eq(WHITELIST_BONUS_MAIL_ID)))
            .thenReturn(existing)

        // Act
        val injected = service.injectWhitelistBonus(testSlotId)

        // Assert
        assertFalse("邮件已存在时应跳过", injected)
        verify(mailRepo, never()).insertWithEnforceLimit(any(), any())
    }

    @Test
    fun `injectWhitelistBonus - repeated calls inject only once`() = runBlocking {
        // Arrange: 用内存 map 模拟 Room DB 的写入可见性
        AdFreeWhitelist.initialize(WHITELIST_UNION_ID)
        val db = mutableMapOf<String, MailEntity>()
        `when`(mailRepo.getById(eq(testSlotId), any())).thenAnswer { inv ->
            val mailId: String = inv.getArgument(1)
            db[mailId]
        }
        `when`(mailRepo.insertWithEnforceLimit(any(), any())).thenAnswer { inv ->
            val mail: MailEntity = inv.getArgument(0)
            db[mail.id] = mail
            null
        }

        // Act
        val first = service.injectWhitelistBonus(testSlotId)
        val second = service.injectWhitelistBonus(testSlotId)

        // Assert: 首次注入成功，第二次被 DB 存在性检查拦截
        assertTrue("首次调用应注入成功", first)
        assertFalse("第二次调用应跳过（DB 已存在）", second)
        assertEquals("DB 中应只有一封福利邮件", 1, db.size)
    }

    @Test
    fun `injectWhitelistBonus - getById throws still injects`() = runBlocking {
        // Arrange: DB 检查异常时不应阻塞注入（与 getById 容错模式一致）
        AdFreeWhitelist.initialize(WHITELIST_UNION_ID)
        `when`(mailRepo.getById(eq(testSlotId), eq(WHITELIST_BONUS_MAIL_ID)))
            .thenThrow(RuntimeException("DB error"))

        // Act
        val injected = service.injectWhitelistBonus(testSlotId)

        // Assert
        assertTrue("getById 抛异常时仍应注入", injected)
        verify(mailRepo).insertWithEnforceLimit(any(), any())
    }

    @Test
    fun `claimAttachment - permanent mail never expires`() = runBlocking {
        // Arrange: expireTime = Long.MAX_VALUE 的永久邮件，mailRecords 无记录
        val permanentMail = createUnclaimedMail().copy(
            id = WHITELIST_BONUS_MAIL_ID,
            expireTime = Long.MAX_VALUE,
            source = "admin"
        )
        `when`(mailRepo.getById(eq(testSlotId), eq(WHITELIST_BONUS_MAIL_ID)))
            .thenReturn(permanentMail)

        // Act
        val result = service.claimAttachment(WHITELIST_BONUS_MAIL_ID, testSlotId)

        // Assert: 永久邮件不应被判为过期
        assertTrue(
            "永久邮件领取不应返回 Expired",
            result !is ClaimResult.Expired
        )
    }

    // ============================================================
    // injectExclusiveBonus — 单用户专属运营福利
    //（1000 万灵石 + 10 单灵根弟子，2026-09-04 截止，每档一次）
    // ============================================================

    /** 用内存 map 模拟 Room DB 的写入可见性（含 getById 与 insert） */
    private suspend fun installInMemoryMailDb(): MutableMap<String, MailEntity> {
        val db = mutableMapOf<String, MailEntity>()
        `when`(mailRepo.getById(eq(testSlotId), any())).thenAnswer { inv ->
            val mailId: String = inv.getArgument(1)
            db[mailId]
        }
        `when`(mailRepo.insertWithEnforceLimit(any(), any())).thenAnswer { inv ->
            val mail: MailEntity = inv.getArgument(0)
            db[mail.id] = mail
            null
        }
        return db
    }

    @Test
    fun `injectExclusiveBonus - target user injects 10M stones and 10 disciples with deadline expiry`() = runBlocking {
        // Arrange: 目标用户，DB 中无该邮件
        AdFreeWhitelist.initialize(EXCLUSIVE_UNION_ID)
        `when`(mailRepo.getById(eq(testSlotId), eq(EXCLUSIVE_BONUS_MAIL_ID))).thenReturn(null)

        // Act
        val injected = service.injectExclusiveBonus(testSlotId)

        // Assert: 注入成功且附件配置正确（1000 万灵石 + 10 单灵根弟子，截止 2026-09-04）
        assertTrue("目标用户应成功注入", injected)
        verify(mailRepo).insertWithEnforceLimit(argThat { mail ->
            mail.id == EXCLUSIVE_BONUS_MAIL_ID &&
                mail.expireTime == EXCLUSIVE_BONUS_EXPIRE_MS &&
                mail.source == "admin" &&
                mail.hasAttachment &&
                mail.attachments.contains("\"quantity\":$EXCLUSIVE_BONUS_AMOUNT") &&
                mail.attachments.contains("\"quantity\":$EXCLUSIVE_BONUS_DISCIPLE_COUNT") &&
                mail.attachments.contains("\"spiritRootCount\":\"1\"")
        }, any())
    }

    @Test
    fun `injectExclusiveBonus - non target user skips`() = runBlocking {
        // Arrange: 其他 unionId 用户（非专属目标）
        AdFreeWhitelist.initialize("some_other_union_id")

        // Act
        val injected = service.injectExclusiveBonus(testSlotId)

        // Assert
        assertFalse("非目标用户应跳过", injected)
        verify(mailRepo, never()).insertWithEnforceLimit(any(), any())
    }

    @Test
    fun `injectExclusiveBonus - uninitialized unionId skips`() = runBlocking {
        // Arrange: 未初始化（时序兜底：即使初始化丢失也不注入）
        AdFreeWhitelist.initialize(null)

        // Act
        val injected = service.injectExclusiveBonus(testSlotId)

        // Assert
        assertFalse("unionId 未初始化时应跳过", injected)
        verify(mailRepo, never()).insertWithEnforceLimit(any(), any())
    }

    @Test
    fun `injectExclusiveBonus - already claimed in mailRecords skips`() = runBlocking {
        // Arrange: 目标用户，但 mailRecords 已有领取记录
        AdFreeWhitelist.initialize(EXCLUSIVE_UNION_ID)
        stateStore.update {
            gameData = gameData.copy(
                mailRecords = listOf(
                    MailClaimRecord(EXCLUSIVE_BONUS_MAIL_ID, now, "admin")
                )
            )
        }

        // Act
        val injected = service.injectExclusiveBonus(testSlotId)

        // Assert: 每个存档仅可领取一次
        assertFalse("已领取时应跳过", injected)
        verify(mailRepo, never()).insertWithEnforceLimit(any(), any())
    }

    @Test
    fun `injectExclusiveBonus - mail already in DB skips`() = runBlocking {
        // Arrange: 目标用户，但 DB 中已存在该邮件
        AdFreeWhitelist.initialize(EXCLUSIVE_UNION_ID)
        val existing = MailEntity(
            id = EXCLUSIVE_BONUS_MAIL_ID,
            slotId = testSlotId,
            source = "admin",
            mailType = "reward",
            title = "专属修士礼包",
            content = "",
            senderName = "天道意志",
            sendTime = now,
            expireTime = EXCLUSIVE_BONUS_EXPIRE_MS,
            hasAttachment = true,
            attachments = "[]"
        )
        `when`(mailRepo.getById(eq(testSlotId), eq(EXCLUSIVE_BONUS_MAIL_ID)))
            .thenReturn(existing)

        // Act
        val injected = service.injectExclusiveBonus(testSlotId)

        // Assert
        assertFalse("邮件已存在时应跳过", injected)
        verify(mailRepo, never()).insertWithEnforceLimit(any(), any())
    }

    @Test
    fun `injectExclusiveBonus - repeated calls inject only once`() = runBlocking {
        // Arrange: 用内存 map 模拟 Room DB 的写入可见性
        AdFreeWhitelist.initialize(EXCLUSIVE_UNION_ID)
        val db = installInMemoryMailDb()

        // Act
        val first = service.injectExclusiveBonus(testSlotId)
        val second = service.injectExclusiveBonus(testSlotId)

        // Assert: 首次注入成功，第二次被 DB 存在性检查拦截
        assertTrue("首次调用应注入成功", first)
        assertFalse("第二次调用应跳过（DB 已存在）", second)
        assertEquals("DB 中应只有一封专属邮件", 1, db.size)
    }

    @Test
    fun `injectExclusiveBonus - getById throws still injects`() = runBlocking {
        // Arrange: DB 检查异常时不应阻塞注入（与 getById 容错模式一致）
        AdFreeWhitelist.initialize(EXCLUSIVE_UNION_ID)
        `when`(mailRepo.getById(eq(testSlotId), eq(EXCLUSIVE_BONUS_MAIL_ID)))
            .thenThrow(RuntimeException("DB error"))

        // Act
        val injected = service.injectExclusiveBonus(testSlotId)

        // Assert
        assertTrue("getById 抛异常时仍应注入", injected)
        verify(mailRepo).insertWithEnforceLimit(any(), any())
    }

    @Test
    fun `claimAttachment - exclusive bonus distributes 10M stones and 10 single-root disciples`() = runBlocking {
        // Arrange: 目标用户注入专属邮件，wallet 以真实入账方式响应（验证灵石到账）
        AdFreeWhitelist.initialize(EXCLUSIVE_UNION_ID)
        installInMemoryMailDb()
        assertTrue("预置专属邮件应注入成功", service.injectExclusiveBonus(testSlotId))
        val initialStones = stateStore.gameData.value.spiritStones
        `when`(spiritStoneWallet.add(any(), any(), any(), any(), any())).thenAnswer { inv ->
            val state = inv.getArgument<com.xianxia.sect.core.state.MutableGameState>(0)
            val amount = inv.getArgument<Long>(1)
            state.gameData = state.gameData.copy(spiritStones = state.gameData.spiritStones + amount)
            state.gameData.spiritStones
        }

        // Act: 领取专属邮件
        val result = service.claimAttachment(EXCLUSIVE_BONUS_MAIL_ID, testSlotId)

        // Assert: 领取成功，灵石 +1000 万
        assertTrue("专属邮件应领取成功", result is ClaimResult.Success)
        assertEquals(
            "灵石应增加 1000 万",
            initialStones + EXCLUSIVE_BONUS_AMOUNT.toLong(),
            stateStore.gameData.value.spiritStones
        )

        // Assert: 10 位弟子全部入账且均为单灵根
        val disciples = stateStore.discipleTables.assembleAll()
        assertEquals("应获得 10 位弟子", EXCLUSIVE_BONUS_DISCIPLE_COUNT, disciples.size)
        disciples.forEach { disciple ->
            assertEquals(
                "弟子 ${disciple.name} 应为单灵根",
                1,
                disciple.spiritRootType.split(",").size
            )
        }

        // Assert: 领取记录已写入（每档一次）
        assertTrue(
            "领取后 mailRecords 应包含专属邮件",
            stateStore.gameData.value.mailRecords.any { it.mailId == EXCLUSIVE_BONUS_MAIL_ID }
        )
    }

    @Test
    fun `claimAttachment - exclusive bonus expired after deadline returns Expired`() = runBlocking {
        // Arrange: 目标用户注入专属邮件后，模拟已过截止时间（2026-09-04 之后）
        AdFreeWhitelist.initialize(EXCLUSIVE_UNION_ID)
        val db = installInMemoryMailDb()
        assertTrue("预置专属邮件应注入成功", service.injectExclusiveBonus(testSlotId))
        db[EXCLUSIVE_BONUS_MAIL_ID] = db[EXCLUSIVE_BONUS_MAIL_ID]!!.copy(expireTime = now - 1000)
        val initialStones = stateStore.gameData.value.spiritStones

        // Act: 截止后领取
        val result = service.claimAttachment(EXCLUSIVE_BONUS_MAIL_ID, testSlotId)

        // Assert: 为期一个月的活动截止后不可再领取
        assertTrue("过期邮件应返回 Expired", result is ClaimResult.Expired)
        assertEquals("过期邮件不应发放灵石", initialStones, stateStore.gameData.value.spiritStones)
    }

    // ============================================================
    // injectStorageBagCompensation — 单用户定向补偿邮件
    //（地品储物袋 ×10，3 天有效，每档一次）
    // ============================================================

    @Test
    fun `injectStorageBagCompensation - target user injects 10 earth-tier bags with 3d expiry`() = runBlocking {
        // Arrange: 目标用户，DB 中无该邮件
        AdFreeWhitelist.initialize(COMPENSATION_UNION_ID)
        `when`(mailRepo.getById(eq(testSlotId), eq(COMPENSATION_MAIL_ID))).thenReturn(null)

        // Act
        val injected = service.injectStorageBagCompensation(testSlotId)

        // Assert: 注入成功且附件配置正确（地品储物袋 ×10，rarity=5，3 天有效）
        assertTrue("目标用户应成功注入", injected)
        verify(mailRepo).insertWithEnforceLimit(argThat { mail ->
            mail.id == COMPENSATION_MAIL_ID &&
                mail.source == "admin" &&
                mail.mailType == "compensation" &&
                mail.hasAttachment &&
                mail.expireTime > now + 2L * 24 * 60 * 60 * 1000 &&
                mail.expireTime <= now + 4L * 24 * 60 * 60 * 1000 &&
                mail.attachments.contains("\"type\":\"storageBag\"") &&
                mail.attachments.contains("\"quantity\":$COMPENSATION_BAG_COUNT") &&
                mail.attachments.contains("\"rarity\":$COMPENSATION_BAG_RARITY") &&
                mail.attachments.contains("地品储物袋")
        }, any())
    }

    @Test
    fun `injectStorageBagCompensation - non target user skips`() = runBlocking {
        // Arrange: 其他 unionId 用户（非补偿目标）
        AdFreeWhitelist.initialize("some_other_union_id")

        // Act
        val injected = service.injectStorageBagCompensation(testSlotId)

        // Assert
        assertFalse("非目标用户应跳过", injected)
        verify(mailRepo, never()).insertWithEnforceLimit(any(), any())
    }

    @Test
    fun `injectStorageBagCompensation - uninitialized unionId skips`() = runBlocking {
        // Arrange: 未初始化（时序兜底：即使初始化丢失也不注入）
        AdFreeWhitelist.initialize(null)

        // Act
        val injected = service.injectStorageBagCompensation(testSlotId)

        // Assert
        assertFalse("unionId 未初始化时应跳过", injected)
        verify(mailRepo, never()).insertWithEnforceLimit(any(), any())
    }

    @Test
    fun `injectStorageBagCompensation - already claimed in mailRecords skips`() = runBlocking {
        // Arrange: 目标用户，但 mailRecords 已有领取记录
        AdFreeWhitelist.initialize(COMPENSATION_UNION_ID)
        stateStore.update {
            gameData = gameData.copy(
                mailRecords = listOf(
                    MailClaimRecord(COMPENSATION_MAIL_ID, now, "admin")
                )
            )
        }

        // Act
        val injected = service.injectStorageBagCompensation(testSlotId)

        // Assert: 每个存档仅可领取一次
        assertFalse("已领取时应跳过", injected)
        verify(mailRepo, never()).insertWithEnforceLimit(any(), any())
    }

    @Test
    fun `injectStorageBagCompensation - mail already in DB skips`() = runBlocking {
        // Arrange: 目标用户，但 DB 中已存在该邮件
        AdFreeWhitelist.initialize(COMPENSATION_UNION_ID)
        val existing = MailEntity(
            id = COMPENSATION_MAIL_ID,
            slotId = testSlotId,
            source = "admin",
            mailType = "compensation",
            title = "补偿礼包",
            content = "",
            senderName = "天道意志",
            sendTime = now,
            expireTime = now + 3L * 24 * 60 * 60 * 1000,
            hasAttachment = true,
            attachments = "[]"
        )
        `when`(mailRepo.getById(eq(testSlotId), eq(COMPENSATION_MAIL_ID)))
            .thenReturn(existing)

        // Act
        val injected = service.injectStorageBagCompensation(testSlotId)

        // Assert
        assertFalse("邮件已存在时应跳过", injected)
        verify(mailRepo, never()).insertWithEnforceLimit(any(), any())
    }

    @Test
    fun `injectStorageBagCompensation - repeated calls inject only once`() = runBlocking {
        // Arrange: 用内存 map 模拟 Room DB 的写入可见性
        AdFreeWhitelist.initialize(COMPENSATION_UNION_ID)
        val db = installInMemoryMailDb()

        // Act
        val first = service.injectStorageBagCompensation(testSlotId)
        val second = service.injectStorageBagCompensation(testSlotId)

        // Assert: 首次注入成功，第二次被 DB 存在性检查拦截
        assertTrue("首次调用应注入成功", first)
        assertFalse("第二次调用应跳过（DB 已存在）", second)
        assertEquals("DB 中应只有一封补偿邮件", 1, db.size)
    }

    @Test
    fun `injectStorageBagCompensation - getById throws still injects`() = runBlocking {
        // Arrange: DB 检查异常时不应阻塞注入（与 getById 容错模式一致）
        AdFreeWhitelist.initialize(COMPENSATION_UNION_ID)
        `when`(mailRepo.getById(eq(testSlotId), eq(COMPENSATION_MAIL_ID)))
            .thenThrow(RuntimeException("DB error"))

        // Act
        val injected = service.injectStorageBagCompensation(testSlotId)

        // Assert
        assertTrue("getById 抛异常时仍应注入", injected)
        verify(mailRepo).insertWithEnforceLimit(any(), any())
    }

    @Test
    fun `claimAttachment - compensation distributes 10 earth-tier storage bags as one stack`() = runBlocking {
        // Arrange: 目标用户注入补偿邮件
        AdFreeWhitelist.initialize(COMPENSATION_UNION_ID)
        installInMemoryMailDb()
        assertTrue("预置补偿邮件应注入成功", service.injectStorageBagCompensation(testSlotId))

        // Act: 领取补偿邮件
        val result = service.claimAttachment(COMPENSATION_MAIL_ID, testSlotId)

        // Assert: 领取成功，10 个地品储物袋合并为单个堆叠入袋
        assertTrue("补偿邮件应领取成功", result is ClaimResult.Success)
        val bags = stateStore.storageBags.value
        assertEquals("储物袋应合并为一个堆叠", 1, bags.size)
        assertEquals("应获得地品储物袋", "地品储物袋", bags[0].name)
        assertEquals("地品储物袋数量应为 10", COMPENSATION_BAG_COUNT, bags[0].quantity)
        assertEquals("地品储物袋品阶应为 5", COMPENSATION_BAG_RARITY, bags[0].rarity)

        // Assert: 领取记录已写入（每档一次）
        assertTrue(
            "领取后 mailRecords 应包含补偿邮件",
            stateStore.gameData.value.mailRecords.any { it.mailId == COMPENSATION_MAIL_ID }
        )
    }

    @Test
    fun `resetAndInitSlot - never deletes any mail (mails retained forever)`() = runBlocking {
        // Arrange: startMailFlowCollector 需要真实 scope；在线接口返回空列表
        `when`(serviceScopeProvider.scope).thenReturn(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        )
        `when`(httpClient.get(any())).thenReturn("{}")

        // Act: 读档/切档/重开路径
        service.resetAndInitSlot(testSlotId)

        // Assert: 邮件永久保留——reset 绝不删除任何邮件（全量清空或按源删除都不发生），
        // 否则未领取的溢出/直发邮件（草稿已被 drain 消费、无处重建）会被静默清掉
        verify(mailRepo, never()).deleteAllForSlot(testSlotId)
        verify(mailRepo, never()).deleteAllReadAndClaimed(testSlotId)
        verify(mailRepo, never()).deleteIfClaimed(any(), any())
    }

    @Test
    fun `markAllAsRead - never deletes unclaimed mails`() = runBlocking {
        // Arrange: 一封已读未领取、一封已读已领取
        val now = System.currentTimeMillis()
        val unclaimed = createUnclaimedMail(id = "unclaimed_1", hasAttachments = true).copy(isRead = true)
        val claimed = createUnclaimedMail(id = "claimed_1", hasAttachments = true).copy(
            isRead = true, attachmentClaimed = true
        )
        `when`(mailRepo.getActiveMails(any())).thenReturn(flowOf(listOf(unclaimed, claimed)))

        // Act: 一键已读（会对未领取附件尝试领取；容量充足）
        service.markAllAsRead(testSlotId)

        // Assert: 已读未领取的邮件绝不被自动删除——删除入口只有玩家手动"删除已读"
        verify(mailRepo, never()).deleteAllReadAndClaimed(testSlotId)
    }
}
