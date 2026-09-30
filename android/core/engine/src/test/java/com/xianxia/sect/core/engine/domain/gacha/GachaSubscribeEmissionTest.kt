package com.xianxia.sect.core.engine.domain.gacha

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.FakeAtomicStateStore
import com.xianxia.sect.core.engine.mockSmart
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.core.model.GachaHistoryEntry
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.wallet.SpiritStoneLedger
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** 寻访服务夹具的无界 scope（生产由 Dagger 提供；Unconfined 让 map + stateIn 在写入线程同步完成）。 */
private object EmissionScopeProvider : CoroutineScopeProvider {
    override val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    override val ioScope = scope
}

/**
 * GachaSubscribeEmissionTest — 寻访四本账的**订阅面**守卫（G11 D-7，无 JNI）。
 *
 * ## 这条测试钉的是什么
 * [GachaService] 的四个 `StateFlow` 全部是 `stateStore.gameData.map { 字段 }.stateIn(...)`。
 * 状态存储的提交判据是**引用比较**（`GameStateStoreImpl.emitStateFlows` 的
 * `reusableMutableState.gameData !== baseline.gameData`；即使去掉该守卫，
 * `MutableStateFlow` 也按 `equals` 去重，而 `GameData` 是无自定义 `equals` 的
 * `data class`）。所以「在事务里对同一个 GameData 实例写 `gameData.某字段 = 新值`」
 * **不会**发射——UI 侧订阅的流就此陈旧，要等下一笔换了引用的事务才连带刷出来。
 * 本仓事务内改 `GameData` 的既有范式一律重新赋值实例
 * （`SpiritStoneWallet.updateGrade` 的 `gd.copy(...)`、`MutableGameState.recordEvent`、
 * 镜像侧 `GameDataFieldPatch.apply` 的 `current.copy()`）。
 *
 * ## 判别力自证（退回旧实现 ⇒ 哪条红）
 * | 构造反例（只改一处） | 变红的用例 |
 * |---|---|
 * | 把 [GachaService.grantFragmentsLocally] 的两行写回改回 `gameData.gachaXxx = ...` 原地赋值 | `碎片入账后…订阅流必须发射`（发射计数停在 1、值停在初值） |
 * | 把 [GachaService.pullLocally] 的写回改回原地赋值 | `寻访出货后…订阅流必须发射`（pityCounters / history 停在初值） |
 * | 事务里连 `copy()` 都不做（空提交） | 两条用例同时红 |
 * 正向对照：入账/出货前的初值断言保证「发射计数 = 2」不是空转基线。
 *
 * ## 为什么用 [FakeAtomicStateStore] 而不是 `nativebridge.FakeGameStateStore`
 * 后者的 `gameData` 是「每次属性访问新建一个一次性 `MutableStateFlow`」的最小桩，
 * 与内部状态断开——任何写法都不会发射，用它测订阅面等于测空气。
 * [FakeAtomicStateStore] 的 `_gameData` 是真实 `MutableStateFlow`，与生产同源于
 * 「同实例赋值被 equals 去重」这条判据（生产另有的 `!==` 守卫与 `_updateVersion`
 * 在 Fake 里不存在，不影响本用例的可观察结果）。
 */
class GachaSubscribeEmissionTest {

    @Test
    fun `碎片入账后 星级与碎片两本账的订阅流必须发射`() {
        val store = FakeAtomicStateStore().apply { setGameData(seedGameData()) }
        val service = service(store)
        val stars = mutableListOf<Map<String, Int>>()
        val fragments = mutableListOf<Map<String, Int>>()
        val subscriptions = listOf(
            subscribe(service.starMap, stars),
            subscribe(service.fragmentCounts, fragments),
        )

        assertEquals(
            "星级流初值必须是空账本（否则下面的发射计数判据失去基线）",
            listOf<Map<String, Int>>(emptyMap()),
            stars,
        )

        val outcome = service.grantFragmentsLocally(TEMPLATE_ID, GameConfig.Gacha.FRAGMENTS_PER_STAR)
        subscriptions.forEach(Job::cancel)

        assertNotNull("满 100 片必须入账升星（夹具本身先证伪「没落账」）", outcome)
        assertEquals(
            "starMap 必须向订阅者发射第 2 个值。停在 1 说明事务里对同一 GameData 实例原地改字段——" +
                "引用没变 ⇒ 状态存储不提交 ⇒ 结果页/图鉴刷不出来。" +
                "修法：事务内重新赋值实例（gameData = gameData.copy(...)，见 SpiritStoneWallet.updateGrade）",
            EMISSIONS_AFTER_ONE_WRITE,
            stars.size,
        )
        assertEquals("starMap 终值必须是该模板 1 星", mapOf(TEMPLATE_ID to 1), stars.last())
        assertEquals("fragmentCounts 同样必须发射", EMISSIONS_AFTER_ONE_WRITE, fragments.size)
        assertEquals(
            "满星门槛后星内进度归零但仍建键（稀疏只针对星级账本，见 GachaFragmentLedger.grant）",
            mapOf(TEMPLATE_ID to 0),
            fragments.last(),
        )
    }

    @Test
    fun `寻访出货后 保底计数与历史环的订阅流必须发射`() {
        val store = FakeAtomicStateStore().apply { setGameData(seedGameData()) }
        val manager = GameRngManager().apply { initSystemSeed(SEED) }
        val service = service(store, manager)
        val pities = mutableListOf<Map<String, Int>>()
        val histories = mutableListOf<List<GachaHistoryEntry>>()
        val subscriptions = listOf(
            subscribe(service.pityCounters, pities),
            subscribe(service.history, histories),
        )

        assertEquals(
            "保底流初值必须是空账本（正向对照）",
            listOf<Map<String, Int>>(emptyMap()),
            pities,
        )

        val local = service.pullLocally(characterOnlyPool(), POOL_ID, PULL_COUNT)
        subscriptions.forEach(Job::cancel)
        val pityAfter = local?.pityAfter ?: UNSET_PITY

        assertNotNull("余额充足时寻访必须出货（夹具先证伪「没出货」）", local)
        assertEquals(
            "pityCounters 必须发射新值：保底计数是主界面进度条 x/threshold 的唯一数据源，" +
                "停在 1 意味着玩家抽完看不到进度变化（修法同上——事务内换实例）",
            EMISSIONS_AFTER_ONE_WRITE,
            pities.size,
        )
        // -1 不是合法保底计数（值域 0..threshold-1），出货缺失时上面的取值必然红
        assertEquals("保底流终值必须是本池抽后计数", mapOf(POOL_ID to pityAfter), pities.last())
        assertEquals("history 必须发射", EMISSIONS_AFTER_ONE_WRITE, histories.size)
        assertEquals("历史环终值必须有出货条目", PULL_COUNT, histories.last().size)
    }

    // ── 夹具 ────────────────────────────────────────────────────────

    /** 订阅并在 Unconfined 线程上记录每次发射（含初值），用于把「值变了」与「流发了」分开判。 */
    private fun <T> subscribe(flow: StateFlow<T>, sink: MutableList<T>): Job =
        EmissionScopeProvider.scope.launch { flow.collect { sink.add(it) } }

    /** 出货前的账本初值：余额够一次寻访，四本账全空（与真实开局同形状）。 */
    private fun seedGameData(): GameData = GameData().apply {
        spiritStones = PRICE_PER_PULL.toLong() * PULL_COUNT
        gameYear = PRESET_YEAR
        gameMonth = PRESET_MONTH
    }

    private fun service(store: FakeAtomicStateStore, manager: GameRngManager): GachaService = GachaService(
        stateStore = store,
        scopeProvider = EmissionScopeProvider,
        spiritStoneWallet = SpiritStoneWallet(
            stateStore = store,
            ledger = SpiritStoneLedger(),
            eventBus = mockSmart(EventBus::class.java),
        ),
        gameRngManager = manager,
        inventorySystem = mockSmart(InventorySystem::class.java),
    )

    private fun service(store: FakeAtomicStateStore): GachaService =
        service(store, GameRngManager().apply { initSystemSeed(SEED) })

    /** 只有角色类别的合法池（不触达入库口，RNG 消费序由 GachaPullGuardTest 专门锁）。 */
    private fun characterOnlyPool(): GachaPoolSpec = GachaPoolSpec(
        poolId = POOL_ID,
        enabled = true,
        pricePerPull = PRICE_PER_PULL,
        categories = listOf(GachaCategorySpec(CATEGORY_KIND, WEIGHT_TOTAL, listOf(TEMPLATE_ID), "", 0)),
        itemRarityWeights = listOf(GachaRarityWeightSpec(RARITY, WEIGHT_TOTAL)),
        fragmentCountWeights = listOf(WEIGHT_TOTAL),
        itemCountWeights = listOf(WEIGHT_TOTAL),
        pity = GachaPitySpec(PITY_THRESHOLD, PITY_FRAGMENT_COUNT, PICK_MODE),
    )

    private companion object {
        const val TEMPLATE_ID = "zhouming"
        const val POOL_ID = "standard"
        const val CATEGORY_KIND = "character_single"
        const val PRICE_PER_PULL = 5000
        const val PULL_COUNT = 1
        const val WEIGHT_TOTAL = 100
        const val RARITY = 1
        const val PITY_THRESHOLD = 10
        const val PITY_FRAGMENT_COUNT = 5
        const val PICK_MODE = "random"
        const val PRESET_YEAR = 1
        const val PRESET_MONTH = 1
        const val SEED = 0x5EEDL

        /** 初值 + 一次写入 = 两次发射（少于 2 即订阅面没收到新值） */
        const val EMISSIONS_AFTER_ONE_WRITE = 2

        /** 出货缺失时的占位保底值：不在合法值域 0..pullThreshold-1 内，比对必然红 */
        const val UNSET_PITY = -1
    }
}
