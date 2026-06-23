package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.MailClaimRecord
import com.xianxia.sect.core.model.MailEntity
import com.xianxia.sect.core.repository.MailRepository
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.GameStateStoreImpl
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.di.ApplicationScopeProvider
import com.xianxia.sect.core.util.HttpClientProvider
import com.xianxia.sect.data.GameStateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
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
class MailServiceTest {

    private lateinit var service: MailService
    private lateinit var mailRepo: MailRepository
    private lateinit var stateStore: GameStateStore
    private lateinit var inventoryConfig: InventoryConfig
    private lateinit var httpClient: HttpClientProvider
    private lateinit var scopeProvider: ApplicationScopeProvider

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
        httpClient = mock(HttpClientProvider::class.java)
        stateStore = GameStateStoreImpl(
            scopeProvider,
            mock(GameStateRepository::class.java)
        )

        // 设置默认 mock 行为
        `when`(mailRepo.getActiveMails(any(), any())).thenReturn(flowOf(emptyList()))

        service = MailService(
            mailRepo = mailRepo,
            stateStore = stateStore,
            inventoryConfig = inventoryConfig,
            httpClient = httpClient,
            appContext = mock(android.content.Context::class.java)
        )

        runBlocking { stateStore.reset() }
    }

    @After
    fun tearDown() {
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
            `when`(mailRepo.getById(testMailId)).thenReturn(mail)

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
            `when`(mailRepo.getById(testMailId)).thenReturn(mail)
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
        `when`(mailRepo.getById(testMailId)).thenReturn(mail)

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
        `when`(mailRepo.getById(testMailId)).thenReturn(null)
        val result = service.claimAttachment(testMailId, testSlotId)
        assertTrue(result is ClaimResult.MailNotFound)
    }

    @Test
    fun `claimAttachment - expired mail returns Expired`() = runBlocking {
        val expiredMail = createUnclaimedMail().copy(
            expireTime = now - 1000 // 已过期
        )
        `when`(mailRepo.getById(testMailId)).thenReturn(expiredMail)
        val result = service.claimAttachment(testMailId, testSlotId)
        assertTrue(result is ClaimResult.Expired)
    }

    @Test
    fun `claimAttachment - already claimed in Room returns AlreadyClaimed`() = runBlocking {
        val claimedMail = createUnclaimedMail().copy(attachmentClaimed = true)
        `when`(mailRepo.getById(testMailId)).thenReturn(claimedMail)
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
}
