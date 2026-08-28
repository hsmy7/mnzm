package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.RedeemCodeManager
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.platform.ApkSigningCertificateSource
import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.HttpClientProvider
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.util.asKotlinRandom
import com.xianxia.sect.core.wallet.SpiritStoneLedger
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * RedeemCodeService 本地兑换落地守卫测试。
 *
 * 守护 2026-08 修复：本地兑换路径此前会**双发弟子**——`result.rewards` 中的
 * disciple 条目被 `applyRedeemReward` → `applyDiscipleRedeemReward` 用 null 配置
 * 重复生成（境界错乱成炼气期），随后 `result.disciples`（携带正确境界配置）又
 * 插入一次。修复后在本地路径跳过 disciple 条目，弟子仅经 `result.disciples`
 * 统一插入。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class RedeemCodeServiceTest {

    private lateinit var store: FakeAtomicStateStore
    private lateinit var service: RedeemCodeService
    private val fixedRng = DeterministicRng.fromSeed(20260819L)
    private val mailRng = fixedRng.asKotlinRandom()

    @Before
    fun setUp() {
        RedeemCodeManager.clearAllCaches()
        store = FakeAtomicStateStore()
        store.setGameData(com.xianxia.sect.core.model.GameData(spiritStones = 1000))

        val rngManager = mock(GameRngManager::class.java)
        whenever(rngManager.getRng(RngPartition.MAIL)).thenReturn(fixedRng)

        val inventorySystem = mock(InventorySystem::class.java)
        // withOverflowMailSuppressed / withTrackingSource 透传 block（SecretRealmServiceTest 同款模式）
        whenever(inventorySystem.withOverflowMailSuppressed<Any>(any())).thenAnswer { inv ->
            inv.getArgument<() -> Any>(0).invoke()
        }
        whenever(inventorySystem.withTrackingSource<Any>(any(), any())).thenAnswer { inv ->
            inv.getArgument<() -> Any>(1).invoke()
        }

        val wallet = SpiritStoneWallet(store, SpiritStoneLedger(), mock(EventBus::class.java))

        service = RedeemCodeService(
            stateStore = store,
            httpClient = mock(HttpClientProvider::class.java),
            spiritStoneWallet = wallet,
            gameRngManager = rngManager,
            signingCertificates = mock(ApkSigningCertificateSource::class.java),
            inventorySystem = inventorySystem
        )
    }

    private fun applyLocalRedeem(
        code: String,
        existingNames: Set<String> = emptySet(),
        random: kotlin.random.Random = mailRng
    ): Boolean {
        val redeemCodeData = RedeemCodeManager.getRedeemCode(code)
            ?: error("兑换码未注册: $code")
        val result = RedeemCodeManager.generateReward(
            redeemCodeData,
            existingNames = existingNames,
            random = random
        )
        check(result.success) { "generateReward 失败: ${result.message}" }
        return store.updateAndReturn {
            service.applyLocalRedeemState(
                state = this,
                result = result,
                code = code,
                defaultRarity = redeemCodeData.rarity,
                mailRng = random
            )
        }
    }

    // ── 本地兑换弟子双发修复守卫 ──

    @Test
    fun `applyLocalRedeemState - 8982 still grants exactly 10 disciples (no regression)`() {
        val succeeded = applyLocalRedeem("8982")
        assertTrue("兑换应成功", succeeded)

        val disciples = store.discipleTables.assembleAll()
        assertEquals("8982 应恰好发放 10 名弟子（修复前双发为 20 名）", 10, disciples.size)
        assertTrue("8982 弟子应为单灵根配置（realm=9）", disciples.all { it.realm == 9 })
        assertEquals("8982 无灵石奖励", 1000L, store.gameDataSnapshot.spiritStones)
        assertTrue("兑换码 8982 应记入已用", store.gameDataSnapshot.usedRedeemCodes.contains("8982"))
        assertEquals("年报新增弟子应计 10", 10, store.gameDataSnapshot.annualNewDisciples)
    }

    @Test
    fun `applyLocalRedeemState - repeated apply does not double insert disciples`() {
        // 双发根因：rewards 中 disciple 条目若被 applyRedeemReward 处理会用 null 配置
        // 重新生成弟子（境界错乱）。守卫：连续两次落地同一批奖励，弟子数量严格等于
        // 两批之和（每批 10），不得出现 40 名（双发 ×2）。
        val succeeded = applyLocalRedeem("8982")
        assertTrue("首次兑换应成功", succeeded)
        val firstCount = store.discipleTables.assembleAll().size
        assertEquals(10, firstCount)

        val result2 = run {
            val code = RedeemCodeManager.getRedeemCode("8982") ?: error("8982 未注册")
            RedeemCodeManager.generateReward(code, existingNames = emptySet(), random = mailRng)
        }
        val succeeded2 = store.updateAndReturn {
            service.applyLocalRedeemState(
                state = this,
                result = result2,
                code = "8982",
                defaultRarity = 1,
                mailRng = mailRng
            )
        }
        assertTrue("第二次落地应成功", succeeded2)
        assertEquals("两批共应 20 名弟子（每批 10，无双发）", 20, store.discipleTables.assembleAll().size)
    }
}
