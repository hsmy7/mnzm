package com.xianxia.sect.core.engine.domain.gacha

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.model.GachaHistoryEntry
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MaterialCategory
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.wallet.DeductResult
import com.xianxia.sect.core.wallet.SpiritStoneReason
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 寻访域服务（G01 立骨架）。
 *
 * 状态面：聚合 C++ 镜像过来的卡池字段供 UI 订阅（权威逻辑在
 * `gacha_fragment.h` / `gacha_tx`，本类只读派生）。
 *
 * 写入面：**Kotlin 侧碎片账本的唯一服务级入口** [grantFragmentsLocally]——
 * 它是 native 臂降级后的等价回退臂，落账一律经 [GachaFragmentLedger]，
 * 本类之外不得再出现对 `gachaFragmentCounts` / `gachaStarMap` 的写入。
 */
@Singleton
@GameService(name = "GachaService")
class GachaService @Inject constructor(
    private val stateStore: GameStateStore,
    scopeProvider: CoroutineScopeProvider,
    private val spiritStoneWallet: SpiritStoneWallet,
    private val gameRngManager: GameRngManager,
    private val inventorySystem: InventorySystem,
) {
    private val scope = scopeProvider.scope

    val pityCounters: StateFlow<Map<String, Int>> = stateStore.gameData
        .map { it.gachaPityCounters }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val fragmentCounts: StateFlow<Map<String, Int>> = stateStore.gameData
        .map { it.gachaFragmentCounts }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val starMap: StateFlow<Map<String, Int>> = stateStore.gameData
        .map { it.gachaStarMap }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val history: StateFlow<List<GachaHistoryEntry>> = stateStore.gameData
        .map { it.gachaHistory }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * 碎片入账的 Kotlin 回退臂（与 C++ `gacha_fragment.h::addFragment` 逐字同式）。
     *
     * 单次 `updateAndReturn` 事务内原子完成「读两张账本 → 账本累加升星 → 写回」，
     * 禁止拆成多次孤立 update。入参无效（空白模板 id / 非正数量）时账本零改动。
     *
     * 写回必须**换 GameData 实例**（`copy`）：状态存储的提交判据是引用比较
     * （`GameStateStoreImpl.emitStateFlows` 的 `gameData !== baseline.gameData`，
     * `MutableStateFlow` 亦按 `equals` 去重），原地改字段不产生发射 ⇒
     * [starMap] / [fragmentCounts] 的订阅方（结果页、图鉴）看不到入账。
     * 本仓事务内改 `GameData` 的既有范式同此（`SpiritStoneWallet.updateGrade`）。
     *
     * @param templateId 角色模板 id
     * @param count 本次入账的碎片数
     * @return 入账结果（含入账前后星级与入账后星内进度）；入参无效时为 null
     */
    internal fun grantFragmentsLocally(
        templateId: String,
        count: Int,
    ): GachaFragmentLedger.GrantOutcome? = stateStore.updateAndReturn {
        val outcome = GachaFragmentLedger.grant(
            fragmentCounts = gameData.gachaFragmentCounts,
            starMap = gameData.gachaStarMap,
            templateId = templateId,
            count = count,
        )
        if (outcome != null) {
            gameData = gameData.copy(
                gachaFragmentCounts = outcome.fragmentCounts,
                gachaStarMap = outcome.starMap,
            )
        }
        outcome
    }

    /**
     * 寻访出货的 Kotlin 回退臂（与 C++ `gacha_tx.h::pullTx` 逐字同式）。
     *
     * 单笔 `updateAndReturn` 事务内原子完成：「扣灵石 → 逐抽 roll（碎片入账 /
     * 物品入库）→ 写寻访历史环 → 回写保底计数」。掷点全部消费
     * [RngPartition.GACHA] 独立分区——玩家点击驱动的流不与任何结算分区共用，
     * 抽多少卡都不会挪动既有分区的抽取序。
     *
     * 前置校验（池自洽 + 余额）由调用方完成（[GachaPullLedger.poolError]），
     * 事务内**只有扣费一处可能失败**，且它发生在任何写入之前——与 C++ 的
     * 「前置校验全覆盖 ⇒ 中途无失败分支」原子性口径一致。
     *
     * @param pool 池规格（`GachaPoolConfig` 解析自与 C++ 注入同一份数据文件）
     * @param count 抽数（1 或 10）
     * @return 出货结果（含历史条目、入库描述符与解锁模板）；余额不足返回 null
     */
    internal fun pullLocally(
        pool: GachaPoolSpec,
        poolId: String,
        count: Int,
    ): LocalPull? {
        val totalCost = pool.pricePerPull.toLong() * count
        return stateStore.updateAndReturn {
            val data = gameData
            if (data.spiritStones < totalCost) {
                return@updateAndReturn null
            }
            val deduct = spiritStoneWallet.deduct(
                state = this,
                amount = totalCost,
                grade = SpiritStoneGrade.LOW,
                reason = SpiritStoneReason.Gacha,
                source = SpiritStoneSource.Gacha,
                autoConvert = false,
            )
            if (deduct !is DeductResult.Success) {
                return@updateAndReturn null
            }
            val rolls = rollRepeated(pool = pool, poolId = poolId, count = count, data = data)
            rolls.grants.forEach { grant -> grantItemToWarehouse(grant) }
            // 四本账一次换实例写回（判据同 grantFragmentsLocally：原地改字段不发射；
            // 这一臂原本只被上面的扣费 copy 顺带提交，不依赖那个偶发引用）
            gameData = gameData.copy(
                gachaFragmentCounts = rolls.fragmentCounts,
                gachaStarMap = rolls.starMap,
                gachaPityCounters = data.gachaPityCounters + (poolId to rolls.pity),
                gachaHistory = (rolls.rows.asReversed() + data.gachaHistory)
                    .take(GameConfig.Gacha.HISTORY_RING_SIZE),
            )
            LocalPull(
                rows = rolls.rows,
                unlockedTemplateIds = rolls.unlocked,
                pityAfter = rolls.pity,
                pricePaid = totalCost,
                spiritStonesAfter = deduct.balanceAfter,
            )
        }
    }

    /**
     * 逐抽 roll（[count] 次单抽语义，消费 [RngPartition.GACHA] 的同一条流）。
     *
     * 只做掷点与账本推演，不写状态存储——写回由 [pullLocally] 在一次事务里完成，
     * 拆清楚「算」与「写」两条责任，也让抽取序在这里一次性定型。
     */
    private fun rollRepeated(
        pool: GachaPoolSpec,
        poolId: String,
        count: Int,
        data: GameData,
    ): RollOutcome {
        val rng = gameRngManager.getRng(RngPartition.GACHA)
        val monthIndex = data.gameYear * MONTHS_PER_YEAR + data.gameMonth
        var pity = data.gachaPityCounters[poolId] ?: 0
        var fragmentCounts = data.gachaFragmentCounts
        var starMap = data.gachaStarMap
        val rows = ArrayList<GachaHistoryEntry>(count)
        val unlocked = ArrayList<String>()
        val grants = ArrayList<GachaItemGrant>()
        repeat(count) {
            val step = GachaPullLedger.pullOnce(
                rng = rng,
                pool = pool,
                poolId = poolId,
                pityBefore = pity,
                fragmentCounts = fragmentCounts,
                starMap = starMap,
                monthIndex = monthIndex,
            )
            pity = step.pityAfter
            fragmentCounts = step.fragmentCounts
            starMap = step.starMap
            unlocked += step.unlockedTemplateIds
            step.itemGrant?.let { grants += it }
            // 抽取序（结果页格序 = 数组下标，D-10/D-13）；历史环另按「新在前」写入
            rows += step.row
        }
        return RollOutcome(
            fragmentCounts = fragmentCounts,
            starMap = starMap,
            pity = pity,
            rows = rows,
            unlocked = unlocked,
            grants = grants,
        )
    }

    /** 逐抽推演的产物：三本账终值 + 抽取序 + 解锁模板 + 待入库物品 */
    private class RollOutcome(
        val fragmentCounts: Map<String, Int>,
        val starMap: Map<String, Int>,
        val pity: Int,
        val rows: List<GachaHistoryEntry>,
        val unlocked: List<String>,
        val grants: List<GachaItemGrant>,
    )

    /**
     * 回退臂的一次寻访产出。
     *
     * @property rows 抽取序（与 [GachaPullResult.Success.rows] 同序，十连第 10 格在末位）；
     *   `gachaHistory` 环是另一个口径——新在前，两者不得混用
     */
    internal data class LocalPull(
        val rows: List<GachaHistoryEntry>,
        val unlockedTemplateIds: List<String>,
        val pityAfter: Int,
        val pricePaid: Long,
        val spiritStonesAfter: Long,
    )

    /**
     * 物品类掉落入仓库（统一入口 + 发放类溢出语义）。
     *
     * `withTrackingSource` 的来源键与 C++ `gacha_tx.h::kTrackingSource` 同字面量，
     * 玩家可见显示名在 `OverflowMailSender.SOURCE_DISPLAY_NAMES`；**不得**包
     * `withOverflowMailSuppressed`——满仓要转邮件而不是丢件或失败。
     */
    private fun grantItemToWarehouse(grant: GachaItemGrant) {
        inventorySystem.withTrackingSource(GACHA_TRACKING_SOURCE) {
            when (grant.itemSource) {
                ITEM_SOURCE_HERBS -> inventorySystem.addHerb(
                    Herb(
                        id = gachaInstanceId(grant),
                        name = grant.name,
                        rarity = grant.rarity,
                        category = grant.category,
                        quantity = GachaPullLedger.ITEM_QUANTITY,
                    )
                )
                ITEM_SOURCE_SEEDS -> inventorySystem.addSeed(
                    Seed(
                        id = gachaInstanceId(grant),
                        name = grant.name,
                        rarity = grant.rarity,
                        growTime = grant.growTime,
                        yield = grant.yield,
                        quantity = GachaPullLedger.ITEM_QUANTITY,
                    )
                )
                else -> inventorySystem.addMaterial(
                    Material(
                        id = gachaInstanceId(grant),
                        name = grant.name,
                        rarity = grant.rarity,
                        // 候选来自 BeastMaterialDatabase 模板本身，枚举名往返恒等
                        // （C++ 侧同一模板把 materialCategory 字符串直接写入实例）
                        category = MaterialCategory.valueOf(grant.category),
                        quantity = GachaPullLedger.ITEM_QUANTITY,
                    )
                )
            }
        }
    }

    /** 堆叠实例 id（合并按 名称+品阶+分类 键，实例 id 只在新建堆叠时留痕） */
    private fun gachaInstanceId(grant: GachaItemGrant): String = "gacha-${grant.itemId}"

    private companion object {
        /** 绝对月序换算（与 C++ `gacha_tx.h::absoluteMonth` 同式） */
        const val MONTHS_PER_YEAR = 12

        const val GACHA_TRACKING_SOURCE = "gacha_pull"
        const val ITEM_SOURCE_HERBS = "herbs"
        const val ITEM_SOURCE_SEEDS = "seeds"
    }
}
