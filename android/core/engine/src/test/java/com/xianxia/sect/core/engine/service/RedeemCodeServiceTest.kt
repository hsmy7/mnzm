package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.REWARD_TYPE_FRAGMENT
import com.xianxia.sect.core.engine.RedeemCodeManager
import com.xianxia.sect.core.engine.clearAllCaches
import com.xianxia.sect.core.engine.domain.gacha.GachaFacade
import com.xianxia.sect.core.engine.domain.gacha.GachaFragmentLedger
import com.xianxia.sect.core.engine.domain.gacha.GachaGrantResult
import com.xianxia.sect.core.engine.domain.gacha.GachaPullResult
import com.xianxia.sect.core.engine.mockSmart
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.core.model.GachaHistoryEntry
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.RedeemCode
import com.xianxia.sect.core.model.RedeemRewardType
import com.xianxia.sect.core.platform.ApkSigningCertificateSource
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.HttpClientProvider
import com.xianxia.sect.core.util.asKotlinRandom
import com.xianxia.sect.core.wallet.SpiritStoneLedger
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * RedeemCodeService 兑换码改道（G08 D-9/D-16）后的落地守卫。
 *
 * 守护三条红线：
 * 1. **兑换码不直造弟子**——本地臂与 API 臂都不再插入弟子实例、不再写
 *    `annualNewDisciples`（计数已改挂角色模板实例化，见 `DiscipleService.instantiateTemplate`）；
 * 2. **角色奖励一律发碎片**，且入账只有 [GachaFacade.grantFragments] 一个口
 *    （服务侧不直写 `gachaFragmentCounts` / `gachaStarMap`）；
 * 3. **入账次序**：兑换码先核销、碎片后入账——重试因此不会双增碎片。
 *
 * 装配说明：[GachaFacade] 用手写 Fake 并复用**真实账本** [GachaFragmentLedger]
 * 落账（AGENTS.md §9.4「优先 Fake 而非 Mock」），使碎片/星级断言落在生产口径上；
 * 服务端臂用 [StubHttpClient] 注入响应体，未注入即抛错 ⇒ 服务按生产逻辑降级本地臂。
 */
@org.junit.experimental.categories.Category(com.xianxia.sect.core.RobolectricTests::class)
@RunWith(RobolectricTestRunner::class)
class RedeemCodeServiceTest {

    private lateinit var store: FakeAtomicStateStore
    private lateinit var httpClient: StubHttpClient
    private lateinit var gachaFacade: RecordingGachaFacade
    private lateinit var service: RedeemCodeService
    private val mailRng = DeterministicRng.fromSeed(FIXED_SEED).asKotlinRandom()

    @Before
    fun setUp() {
        RedeemCodeManager.clearAllCaches()
        store = FakeAtomicStateStore()
        store.setGameData(GameData(spiritStones = INITIAL_SPIRIT_STONES))

        httpClient = StubHttpClient()
        gachaFacade = RecordingGachaFacade(store)

        val inventorySystem = mockSmart(InventorySystem::class.java)
        // withOverflowMailSuppressed / withTrackingSource 透传 block（SecretRealmServiceTest 同款模式）
        // doAnswer 风格：泛型返回类型走 `when(...)` 会先触发真实调用（TestMockSupport 已知限制）
        Mockito.doAnswer { inv ->
            inv.getArgument<() -> Any>(0).invoke()
        }.`when`(inventorySystem).withOverflowMailSuppressed<Any>(any())
        Mockito.doAnswer { inv ->
            inv.getArgument<() -> Any>(1).invoke()
        }.`when`(inventorySystem).withTrackingSource<Any>(any(), any())

        val wallet = SpiritStoneWallet(store, SpiritStoneLedger(), mockSmart(EventBus::class.java))

        service = RedeemCodeService(
            stateStore = store,
            httpClient = httpClient,
            spiritStoneWallet = wallet,
            gameRngManager = GameRngManager().apply { initSystemSeed(FIXED_SEED) },
            signingCertificates = mockSmart(ApkSigningCertificateSource::class.java),
            inventorySystem = inventorySystem,
            gachaFacade = gachaFacade
        )
    }

    // ── ① 未配置码：本地臂必须整单拒绝 ────────────────────────────────────────

    @Test
    fun redeemCode_codeNotConfigured_failsWithZeroLedgerChange() = runTest {
        // Given 改道后编译期码表为空（旧「8982 = 10 名弟子」码已随 D-10 删除）
        assertNull("8982 必须已从码表移除", RedeemCodeManager.getRedeemCode("8982"))

        // When 服务端不可用（未注入响应）走本地臂兑换该码
        // 说明：本地臂前置还有 APK 签名门槛（单测里签名基准不可通过），
        //      故不断言失败文案——本用例判据是「任何发放副作用都不许发生」。
        val result = service.redeemCode("8982", usedCodes = emptyList(), currentYear = 1, currentMonth = 1)

        // Then 失败收场，且不留下任何发放痕迹
        assertFalse("未配置的码必须兑换失败: ${result.message}", result.success)
        assertTrue("不得直造弟子实例", store.discipleTables.assembleAll().isEmpty())
        assertEquals(
            "兑换码不得写年报新增弟子（计数已改挂模板实例化，D-16）。" +
                "落点：RedeemCodeService 的 annualNewDisciples 写入点应不存在",
            0,
            store.gameDataSnapshot.annualNewDisciples
        )
        assertTrue("不得记入已用码", store.gameDataSnapshot.usedRedeemCodes.isEmpty())
        assertTrue("不得入账碎片", gachaFacade.grants.isEmpty())
        assertEquals("灵石不得变动", INITIAL_SPIRIT_STONES, store.gameDataSnapshot.spiritStones)
    }

    @Test
    fun applyLocalRedeemState_fragmentEntryOnly_marksCodeUsedWithoutWritingFragmentLedger() {
        // Given 本地臂产出的碎片奖励条目
        val generated = RedeemCodeManager.generateReward(
            redeemCode = RedeemCode(
                code = "FRAGLOCAL",
                rewardType = RedeemRewardType.FRAGMENT,
                quantity = 60,
                templateId = "suqing"
            ),
            random = mailRng,
            nowMs = FIXED_NOW_MS
        )
        assertTrue("碎片码奖励生成应成功: ${generated.message}", generated.success)

        // When 落地本地兑换状态（= localRedeem 的事务部分）
        val applied = store.updateAndReturn {
            service.applyLocalRedeemState(
                state = this,
                result = generated,
                code = "FRAGLOCAL",
                defaultRarity = 1,
                mailRng = mailRng
            )
        }

        // Then 码已核销，但两张碎片账本必须仍为空：入账在事务外经门面完成
        assertTrue("落地应成功", applied)
        assertTrue("兑换码应已记入已用", store.gameDataSnapshot.usedRedeemCodes.contains("FRAGLOCAL"))
        assertTrue(
            "碎片不得在兑换事务内直写 gachaFragmentCounts（唯一入账口是 GachaFacade）。" +
                "落点：RedeemCodeService.applyLocalRedeemState",
            store.gameDataSnapshot.gachaFragmentCounts.isEmpty()
        )
        assertTrue("碎片不得在兑换事务内直写 gachaStarMap", store.gameDataSnapshot.gachaStarMap.isEmpty())
        assertTrue("不得直造弟子实例", store.discipleTables.assembleAll().isEmpty())
        assertEquals("不得写年报新增弟子", 0, store.gameDataSnapshot.annualNewDisciples)
    }

    // ── ② 碎片奖励入账：账本口径 + 入账次序 ───────────────────────────────────

    @Test
    fun redeemCode_serverFragmentReward_grantsViaFacadeAfterCodeConsumed() = runTest {
        // Given 服务端下发一条碎片奖励，数量刚好跨一档星级门槛
        val quantity = GameConfig.Gacha.FRAGMENTS_PER_STAR + FRAGMENT_OVERSHOT
        httpClient.response = apiSuccessJson(fragmentReward("zhouming", "周明", quantity))

        // When 走 API 臂兑换
        val result = service.redeemCode("FRAGM001", usedCodes = emptyList(), currentYear = 1, currentMonth = 1)

        // Then 成功，且碎片只经门面入账一次
        assertTrue("服务端碎片码应兑换成功: ${result.message}", result.success)
        assertEquals("碎片入账口只能有门面一次", listOf("zhouming" to quantity), gachaFacade.grants)
        // 真实账本口径（门槛/升星由 GachaFragmentLedger 单源给出，不写死黄金值）
        assertEquals(
            "跨门槛后星内进度应为余数",
            mapOf("zhouming" to FRAGMENT_OVERSHOT),
            store.gameDataSnapshot.gachaFragmentCounts
        )
        assertEquals("满门槛应升 1 星", mapOf("zhouming" to 1), store.gameDataSnapshot.gachaStarMap)
        // 次序：入账发生时码已核销——这正是「失败重试不双增」的前提（A-4）
        assertTrue(
            "碎片必须在兑换码核销之后才入账，否则仓库失败重试会双增碎片。" +
                "落点：RedeemCodeService.tryServerRedeem 的 grantApiFragmentRewards 调用位置",
            gachaFacade.usedCodesAtFirstGrant?.contains("FRAGM001") == true
        )
        assertTrue("碎片码不得直造弟子", store.discipleTables.assembleAll().isEmpty())
        assertEquals(
            "兑换码链路不得触发寻访出货（出货会扣灵石并按卡池概率掷点，与碎片入账是两条通道）",
            emptyList<String>(), gachaFacade.pullCalls
        )
    }

    // ── ③ 同一碎片码重复领取：不双增 ──────────────────────────────────────────

    @Test
    fun redeemCode_sameFragmentCodeTwice_secondAttemptAddsNoFragments() = runTest {
        // Given 首次领取成功
        httpClient.response = apiSuccessJson(fragmentReward("suqing", "苏晴", 30))
        val first = service.redeemCode("FRAGDUP01", usedCodes = emptyList(), currentYear = 1, currentMonth = 1)
        assertTrue("首次兑换应成功: ${first.message}", first.success)
        val fragmentsAfterFirst = store.gameDataSnapshot.gachaFragmentCounts
        val grantsAfterFirst = gachaFacade.grants.size
        assertEquals("首次入账 30 碎片", mapOf("suqing" to 30), fragmentsAfterFirst)

        // When 用兑换后的已用码清单（UI 口径：RedeemCodeDelegate 传 currentGameData.usedRedeemCodes）再兑一次
        val second = service.redeemCode(
            "FRAGDUP01",
            usedCodes = store.gameDataSnapshot.usedRedeemCodes,
            currentYear = 1,
            currentMonth = 1
        )

        // Then 第二次被拦下，碎片与入账次数零增量
        assertFalse("重复领取必须失败: ${second.message}", second.success)
        assertEquals("第二次不得再调用碎片入账口", grantsAfterFirst, gachaFacade.grants.size)
        assertEquals(
            "碎片账本不得双增。落点：RedeemCodeService.validateAndTrimRedeemCode 的已用码拦截",
            fragmentsAfterFirst,
            store.gameDataSnapshot.gachaFragmentCounts
        )
    }

    // ── ④ 服务端遗留弟子奖励：拒发 ────────────────────────────────────────────

    @Test
    fun redeemCode_serverDiscipleReward_refusedWithoutCreatingDisciples() = runTest {
        // Given 旧版服务端仍可能下发 type=disciple 的奖励，与一条正常碎片奖励混排
        httpClient.response = apiSuccessJson(
            discipleReward("随机弟子", 5),
            fragmentReward("xieche", "谢澈", 20)
        )

        // When 走 API 臂兑换
        val result = service.redeemCode("OLDDISC01", usedCodes = emptyList(), currentYear = 1, currentMonth = 1)

        // Then 弟子项被拒发不得把整单判成容量失败，也不得产生弟子
        assertTrue("拒发单项奖励不应导致整单失败: ${result.message}", result.success)
        assertTrue("不得直造任何弟子实例", store.discipleTables.assembleAll().isEmpty())
        assertEquals(
            "兑换码不得写年报新增弟子。落点：RedeemCodeService 不应再持有 annualNewDisciples 写入",
            0,
            store.gameDataSnapshot.annualNewDisciples
        )
        assertEquals("只有碎片项入账", listOf("xieche" to 20), gachaFacade.grants)
        assertTrue("码仍须核销", store.gameDataSnapshot.usedRedeemCodes.contains("OLDDISC01"))
    }

    @Test
    fun redeemCode_serverFragmentUnknownTemplate_failsWithoutConsumingCode() = runTest {
        // Given 服务端下发一个模板表里查不到的角色碎片（运营错配）
        httpClient.response = apiSuccessJson(fragmentReward("ghost", "不存在的角色", 30))

        // When 走 API 臂兑换
        val result = service.redeemCode("FRAGBAD01", usedCodes = emptyList(), currentYear = 1, currentMonth = 1)

        // Then 失败且不核销、不入账——玩家可在改配后重试，不会静默损失
        assertFalse("未知模板必须整单拒发: ${result.message}", result.success)
        assertTrue("不得入账碎片", gachaFacade.grants.isEmpty())
        assertTrue(
            "模板未知时不得消耗兑换码。落点：RedeemCodeService.tryServerRedeem 的 invalidFragment 预检",
            store.gameDataSnapshot.usedRedeemCodes.isEmpty()
        )
        assertTrue("不得直造弟子", store.discipleTables.assembleAll().isEmpty())
    }

    // ── 夹具 ──────────────────────────────────────────────────────────────────

    /** 服务端成功响应体（只填 rewards 数组，其余字段走服务侧默认解码）。 */
    private fun apiSuccessJson(vararg rewards: String): String =
        """{"success":true,"message":"兑换成功","rewards":[${rewards.joinToString(",")}]}"""

    /** 角色碎片奖励条目（type 字面量与服务侧 REWARD_TYPE_FRAGMENT 同源）。 */
    private fun fragmentReward(templateId: String, name: String, quantity: Int): String =
        """{"type":"$REWARD_TYPE_FRAGMENT","name":"$name","quantity":$quantity,""" +
            """"rarity":1,"templateId":"$templateId"}"""

    /** 已下线的弟子奖励条目（只可能来自旧版服务端下发）。 */
    private fun discipleReward(name: String, quantity: Int): String =
        """{"type":"disciple","name":"$name","quantity":$quantity,"rarity":1}"""

    /**
     * 手写 Fake [HttpClientProvider]：注入响应体即走 API 臂，未注入即抛 IO 异常
     * ⇒ 服务按生产逻辑降级本地臂（不 mock 挂起函数，避免 stub 注册触发真实执行）。
     */
    private class StubHttpClient : HttpClientProvider {
        var response: String? = null

        override suspend fun get(url: String): String =
            throw IOException("兑换码链路不使用 GET")

        override suspend fun post(url: String, body: String): String =
            response ?: throw IOException("服务端不可用（测试夹具未注入响应）")
    }

    /**
     * 手写 Fake [GachaFacade]：记录每次入账调用，并用**真实账本** [GachaFragmentLedger]
     * 把结果写进 [FakeAtomicStateStore]，使碎片/星级断言落在生产换算口径上
     * （门槛与升星由账本单源给出，本测试不写死黄金值）。
     *
     * 出货面（[pullOnce] / [pullTen]）在本链路是**禁止调用面**：调用即记入
     * [pullCalls] 并当场判红。判据是契约而非占位——G09 之后 `pullOnce` 已会真扣灵石、
     * 真掷概率，兑换码若误走它，玩家会以 5000 灵石/次的代价「兑」到一次寻访。
     */
    private class RecordingGachaFacade(private val store: FakeAtomicStateStore) : GachaFacade {
        private val _grants = mutableListOf<Pair<String, Int>>()
        val grants: List<Pair<String, Int>> get() = _grants

        private val _pullCalls = mutableListOf<String>()

        /** 寻访出货调用记录（正常路径恒为空——发放与寻访是两条互不相犯的通道） */
        val pullCalls: List<String> get() = _pullCalls

        /** 首次入账时账本里已有的已用码清单——用于钉「码已核销才入账」的次序 */
        var usedCodesAtFirstGrant: List<String>? = null
            private set

        override val pityCounters = MutableStateFlow<Map<String, Int>>(emptyMap())
        override val fragmentCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
        override val starMap = MutableStateFlow<Map<String, Int>>(emptyMap())
        override val history = MutableStateFlow<List<GachaHistoryEntry>>(emptyList())

        override suspend fun pullOnce(poolId: String): GachaPullResult = rejectPull("pullOnce", poolId)

        override suspend fun pullTen(poolId: String): GachaPullResult = rejectPull("pullTen", poolId)

        /** 出货被调用 = 兑换码越界走到寻访域，直接判红并留下调用痕迹供断言 */
        private fun rejectPull(op: String, poolId: String): GachaPullResult {
            _pullCalls += "$op($poolId)"
            throw AssertionError(
                "兑换码链路触发了寻访出货 $op(poolId=\"$poolId\")——角色类奖励的唯一通道是 " +
                    "grantFragments 入账（G08 D-9/D-16），寻访出货会扣灵石并按卡池概率掷点。" +
                    "落点：RedeemCodeService 的奖励发放分支（改回 grantFragments）"
            )
        }

        override suspend fun grantFragments(templateId: String, count: Int): GachaGrantResult {
            if (_grants.isEmpty()) usedCodesAtFirstGrant = store.gameDataSnapshot.usedRedeemCodes
            val outcome = GachaFragmentLedger.grant(
                fragmentCounts = store.gameDataSnapshot.gachaFragmentCounts,
                starMap = store.gameDataSnapshot.gachaStarMap,
                templateId = templateId,
                count = count
            ) ?: return GachaGrantResult.Failure("账本拒绝入账（无效入参，测试夹具不应触达）")
            store.update {
                gameData = gameData.copy(
                    gachaFragmentCounts = outcome.fragmentCounts,
                    gachaStarMap = outcome.starMap
                )
            }
            _grants += templateId to count
            return GachaGrantResult.Granted(
                starBefore = outcome.starBefore,
                starAfter = outcome.starAfter,
                fragmentsAfter = outcome.fragmentsAfter
            )
        }
    }

    private companion object {
        /** 固定种子：MAIL 分区 RNG 在测试内可复现 */
        const val FIXED_SEED = 20260819L

        /** 固定起算时刻：限流与使用记录不依赖挂钟 */
        const val FIXED_NOW_MS = 1_700_000_000_000L

        /** 起始灵石（哨兵默认值，与 §2.13 的 13+ 处断言口径一致，禁止改 GameData 默认值） */
        const val INITIAL_SPIRIT_STONES = 1000L

        /** 跨一档星级门槛后的星内余数 */
        const val FRAGMENT_OVERSHOT = 20
    }
}
