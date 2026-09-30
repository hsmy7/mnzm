package com.xianxia.sect.core.engine.domain.gacha

import com.xianxia.sect.core.model.CharacterTemplateDb
import com.xianxia.sect.core.model.GachaHistoryEntry
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.util.DeterministicRng

// ============================================================
// GachaPullLedger — 寻访抽卡的 Kotlin 回退臂（与 C++ `gacha_tx.h` 逐字同式）
//
// ## 分工
// C++ 是 AUTHORITATIVE 权威臂；本对象是它的等价回退臂（门控关闭 / native 桥不可用
// 时出货不能停）。两条臂**同一组掷点序、同一套账本换算**，由 `DiffGachaPullTest`
// 双臂对拍锁死——与 `GachaFragmentLedger` 之于 `gacha_fragment.h` 同型。
//
// ## 掷点契约（对拍命门；分区 = `RngPartition.GACHA`）
//   保底抽：`nextInt(保底候选数)` —— 1 次（发 pool.pity.fragmentCount 片，数量无掷点）
//   角色抽：`nextInt(100)` 类别 → `nextInt(该类别候选数)` → `nextInt(100)` 碎片数量 —— 3 次
//   物品抽：`nextInt(100)` 类别 → `nextInt(100)` 品阶 → `nextInt(候选数)` →
//           `nextInt(100)` 数量 —— 4 次
// 数量掷点均为池级权重表（`fragmentCountWeights` 下标 i = i+1 片 / `itemCountWeights`
// 下标 i = i+1 件，各自由 poolError 校验和恰为 100）。加权口径固定为「一次 `nextInt(100)`
// + 按**声明顺序**累加 weightPct，取第一个 `roll < 累计和` 的项」。物品候选一律
// `filter(品阶) → 按模板 id 升序`——表迭代序两侧不保证相同，不排序就会两头出货不同
// （C++ 同式，见 `gacha_tx.h::itemCandidates`）。
//
// ## 本对象不做什么
// 不扣灵石（钱包语义归 `SpiritStoneWallet`）、不写仓库（归 `InventorySystem.addXxx`
// 统一入口）、不建历史环（归 `GachaService` 的事务）、不入册弟子（归
// `DiscipleService.instantiateTemplate` 唯一口）。它只做「掷点 + 两张碎片账本」，
// 因而是纯函数——同一输入恒得同一输出，可被 GTest 之外的 JVM 侧直接断言。
// ============================================================

/**
 * 一次抽卡的产出。
 *
 * @property row 该抽的历史条目（新在前的语义由调用方在写入时处理）
 * @property fragmentCounts 入账后的碎片进度账本（入参 map 不被修改）
 * @property starMap 入账后的星级账本（稀疏：0 星无键）
 * @property unlockedTemplateIds 本次由 0 星跨到 1 星的模板（入册描述符）
 * @property pityAfter 该抽之后的保底计数（触发保底即归零）
 * @property itemGrant 物品类掉落的入库描述符；角色/保底抽为 null
 */
internal data class PullStep(
    val row: GachaHistoryEntry,
    val fragmentCounts: Map<String, Int>,
    val starMap: Map<String, Int>,
    val unlockedTemplateIds: List<String>,
    val pityAfter: Int,
    val itemGrant: GachaItemGrant?,
)

/**
 * 物品入库描述符（由 `GachaService` 经 `InventorySystem` 统一入口落成实例）。
 *
 * @property itemSource 模板表标识：`herbs` / `seeds` / `beastMaterials`
 * @property itemId 模板 id（进历史条目与仓库反查用）
 * @property count 本次入账件数（数量掷点产出；[itemCandidates] 的候选描述符阶段恒为
 *   占位值 0，由 `pullOnce` 掷出数量后 `copy` 填入——服务层拿到的描述符必带真实数量）
 */
internal data class GachaItemGrant(
    val itemSource: String,
    val itemId: String,
    val name: String,
    val rarity: Int,
    val category: String,
    val growTime: Int,
    val yield: Int,
    val count: Int = 0,
)

/** 寻访回退臂的纯掷点账本。 */
internal object GachaPullLedger {

    /** 权重总量（与 C++ `gacha_tx.h::kWeightTotal` 同值；配置侧守卫断言四张权重表和均为它） */
    const val WEIGHT_TOTAL = 100

    /** 保底类别标签（历史条目 `category` 取值域：character / item / pity） */
    const val CATEGORY_CHARACTER = "character"
    const val CATEGORY_ITEM = "item"
    const val CATEGORY_PITY = "pity"

    /** 池内全部角色候选（各角色类别 templateIds 按声明序拼接）。 */
    fun characterPool(pool: GachaPoolSpec): List<String> =
        pool.categories.filter { it.isCharacter }.flatMap { it.templateIds }

    /**
     * 保底归属候选集（按 `pity.pickMode` 取集；pickMode 已由 [poolError] 校验合法）。
     *
     * `singleSpiritRoot` = 全部角色候选按**模板灵根数 = 1** 过滤（与 C++
     * `singleRootCandidates`/`pityCandidates` 同式——业务语义直表，不依赖
     * 「character_single 类别恰好等价」的配置巧合，双臂各查己方模板表）。
     */
    private val GachaPoolSpec.pityCandidates: List<String>
        get() {
            val candidates = characterPool(this)
            return if (pity.pickMode == PICK_MODE_SINGLE_SPIRIT_ROOT) {
                candidates.filter { CharacterTemplateDb.byId(it)?.spiritRoots?.size == 1 }
            } else {
                candidates
            }
        }

    /**
     * 某来源表指定品阶的候选（**按模板 id 升序**钉死双臂一致）。
     *
     * 描述符的 `count` 恒为占位值 0——数量属「该抽的掷点结果」而非模板属性，
     * 由 [pullOnce] 掷出后 `copy` 填入。
     */
    fun itemCandidates(itemSource: String, rarity: Int): List<GachaItemGrant> = when (itemSource) {
        "herbs" -> HerbDatabase.getAllHerbs().asSequence()
            .filter { it.rarity == rarity }
            .map { GachaItemGrant(itemSource, it.id, it.name, it.rarity, it.category, 0, 0) }
            .sortedBy { it.itemId }
            .toList()
        "seeds" -> HerbDatabase.getAllSeeds().asSequence()
            .filter { it.rarity == rarity }
            .map {
                GachaItemGrant(itemSource, it.id, it.name, it.rarity, "", it.growTime, it.yield)
            }
            .sortedBy { it.itemId }
            .toList()
        "beastMaterials" -> BeastMaterialDatabase.getAllMaterials().asSequence()
            .filter { it.rarity == rarity }
            .map {
                GachaItemGrant(itemSource, it.id, it.name, it.rarity, it.materialCategory.name, 0, 0)
            }
            .sortedBy { it.itemId }
            .toList()
        else -> emptyList()
    }

    /** 品阶截断：池级掷点结果不得超出该类别投放上限（池内最高品阶口径）。 */
    fun clampedRarity(rarity: Int, maxRarity: Int): Int = minOf(rarity, maxRarity)

    /** 加权抽取下标（声明序累加；见文件头掷点契约）。 */
    fun weightedPickIndex(rng: DeterministicRng, weights: List<Int>): Int {
        val roll = rng.nextInt(WEIGHT_TOTAL)
        var accumulated = 0
        for (index in weights.indices) {
            accumulated += weights[index]
            if (roll < accumulated) return index
        }
        // 权重和自洽（poolError 已校验）时不可达；退化为末位而非越界
        return weights.lastIndex
    }

    /**
     * 抽一次卡：推进保底计数 → 掷点 → 入账 → 产出历史条目。
     *
     * 前置条件由调用方保证（[poolError] 通过 + 余额已扣 + 池内候选非空），
     * 因此本函数没有失败出口——与 C++ 扣费后无失败分支的原子性口径一致。
     *
     * @param rng 抽卡分区随机源（`RngPartition.GACHA`，由调用方取得后传入）
     * @param pityBefore 该抽之前的保底计数（0..pullThreshold-1）
     */
    fun pullOnce(
        rng: DeterministicRng,
        pool: GachaPoolSpec,
        poolId: String,
        pityBefore: Int,
        fragmentCounts: Map<String, Int>,
        starMap: Map<String, Int>,
        monthIndex: Int,
    ): PullStep {
        val pity = pityBefore + 1
        if (pity >= pool.pity.pullThreshold) {
            // 第 N 抽本身即保底：不 roll 类别，按 pickMode 取候选随机归属后发
            // fragmentCount 片（数量固定，无数量掷点），计数归零
            val candidates = pool.pityCandidates
            val templateId = candidates[rng.nextInt(candidates.size)]
            val granted = grantFragment(fragmentCounts, starMap, templateId, pool.pity.fragmentCount)
            val row = historyEntry(
                poolId, CATEGORY_PITY, pool.pity.fragmentCount,
                isPity = true, monthIndex = monthIndex, templateId = templateId,
            )
            return PullStep(row, granted.fragmentCounts, granted.starMap, granted.unlocked, 0, null)
        }

        val categoryIndex = weightedPickIndex(rng, pool.categories.map { it.weightPct })
        val category = pool.categories[categoryIndex]
        if (category.isCharacter) {
            val templateId = category.templateIds[rng.nextInt(category.templateIds.size)]
            // 碎片数量加权（池级 fragmentCountWeights，下标 i = i+1 片）
            val fragments = weightedPickIndex(rng, pool.fragmentCountWeights) + 1
            val granted = grantFragment(fragmentCounts, starMap, templateId, fragments)
            val row = historyEntry(
                poolId, CATEGORY_CHARACTER, fragments,
                isPity = false, monthIndex = monthIndex, templateId = templateId,
            )
            return PullStep(
                row, granted.fragmentCounts, granted.starMap, granted.unlocked, pity, null
            )
        }

        val rarityRolled = pool.itemRarityWeights[
            weightedPickIndex(rng, pool.itemRarityWeights.map { it.weightPct })
        ].rarity
        val rarity = clampedRarity(rarityRolled, category.maxRarity)
        val candidates = itemCandidates(category.itemSource, rarity)
        val rolled = candidates[rng.nextInt(candidates.size)]
        // 物品数量加权（池级 itemCountWeights，下标 i = i+1 件；钟形近似正态分布）
        val count = weightedPickIndex(rng, pool.itemCountWeights) + 1
        val grant = rolled.copy(count = count)
        val row = historyEntry(
            poolId, CATEGORY_ITEM, count,
            isPity = false, monthIndex = monthIndex, itemId = grant.itemId, rarity = rarity,
        )
        return PullStep(row, fragmentCounts, starMap, emptyList(), pity, grant)
    }

    /**
     * 池自洽校验（零 RNG、零写入；与 C++ `gacha_tx.h::checkPool` 同判据）。
     *
     * 三段判据彼此独立（池头 / 权重面 / 逐类别），命中任一段即同一结果码 ⇒ 段间顺序
     * 不影响结论，故不写成一条 20+ 分支的长函数（detekt `CyclomaticComplexMethod` 15）。
     *
     * @return null = 通过；否则为结果码（PoolNotFound / PoolDisabled / PoolMalformed）
     */
    fun poolError(pool: GachaPoolSpec?): String? = when {
        pool == null -> GachaPullOutcome.CODE_POOL_NOT_FOUND
        !pool.enabled -> GachaPullOutcome.CODE_POOL_DISABLED
        pool.headerError() || pool.weightsError() ||
            pool.categories.any { pool.categoryError(it) } -> GachaPullOutcome.CODE_POOL_MALFORMED
        else -> null
    }

    /** 池头与开局口径：id/价格非法、候选表为空、保底参数非法、保底挑选方式不在白名单 */
    private fun GachaPoolSpec.headerError(): Boolean =
        poolId.isEmpty() || pricePerPull <= 0 ||
            categories.isEmpty() || itemRarityWeights.isEmpty() ||
            pity.pullThreshold < 1 || pity.fragmentCount < 1 ||
            (pity.pickMode != PICK_MODE_RANDOM && pity.pickMode != PICK_MODE_SINGLE_SPIRIT_ROOT)

    /** 权重面：四张权重表和必须恰为 100（[weightedPickIndex] 的口径前提），品阶合法且保底候选非空 */
    private fun GachaPoolSpec.weightsError(): Boolean =
        pityCandidates.isEmpty() ||
            categories.sumOf { it.weightPct } != WEIGHT_TOTAL ||
            itemRarityWeights.sumOf { it.weightPct } != WEIGHT_TOTAL ||
            itemRarityWeights.any { it.rarity < 1 } ||
            fragmentCountWeights.sumOf { it } != WEIGHT_TOTAL ||
            itemCountWeights.sumOf { it } != WEIGHT_TOTAL

    /** 单类别自洽：角色须全在模板表内，物品须有合法来源且每个可达品阶桶都有候选 */
    private fun GachaPoolSpec.categoryError(category: GachaCategorySpec): Boolean {
        if (category.kind.isEmpty() || category.weightPct <= 0) return true
        if (category.isCharacter) {
            // 池引用域封闭：只允许模板表内角色（与 C++ `characterTemplateById` 命中判定
            // 同式——配置漂移在此显形，而不是运行时出货成不存在的角色）
            return category.templateIds.isEmpty() ||
                category.templateIds.any { CharacterTemplateDb.byId(it) == null }
        }
        if (category.itemSource !in ITEM_SOURCES || category.maxRarity < 1) return true
        // 每个可达品阶桶都必须有候选：多抽中途不得出现失败分支（原子性前提）
        return itemRarityWeights
            .filter { it.weightPct > 0 }
            .map { clampedRarity(it.rarity, category.maxRarity) }
            .distinct()
            .any { itemCandidates(category.itemSource, it).isEmpty() }
    }

    /** 碎片入账（唯一写者仍是 [GachaFragmentLedger]，本函数只是它的转发 + 解锁判定） */
    private fun grantFragment(
        fragmentCounts: Map<String, Int>,
        starMap: Map<String, Int>,
        templateId: String,
        count: Int,
    ): GrantWithUnlock {
        val outcome = GachaFragmentLedger.grant(fragmentCounts, starMap, templateId, count)
            ?: return GrantWithUnlock(fragmentCounts, starMap, emptyList())
        // 解锁判据 = 首次跨过第 1 个门槛（0 星 → 1 星）；满星/存量角色不重复解锁
        val unlocked =
            if (outcome.starBefore < 1 && outcome.starAfter >= 1) listOf(templateId) else emptyList()
        return GrantWithUnlock(outcome.fragmentCounts, outcome.starMap, unlocked)
    }

    /** 历史条目：角色抽只带 `templateId`，物品抽只带 `itemId` + 品阶（与 C++ 编码同形） */
    private fun historyEntry(
        poolId: String,
        category: String,
        count: Int,
        isPity: Boolean,
        monthIndex: Int,
        templateId: String = "",
        itemId: String = "",
        rarity: Int = 0,
    ): GachaHistoryEntry = GachaHistoryEntry(
        poolId = poolId,
        category = category,
        templateId = templateId,
        itemId = itemId,
        rarity = rarity,
        count = count,
        isPity = isPity,
        gameMonthIndex = monthIndex,
    )

    /** 入账结果 + 解锁描述符（内部传递用） */
    private data class GrantWithUnlock(
        val fragmentCounts: Map<String, Int>,
        val starMap: Map<String, Int>,
        val unlocked: List<String>,
    )

    private val ITEM_SOURCES = setOf("herbs", "seeds", "beastMaterials")

    /** 受支持的保底挑选方式（与 C++ `kPickModeRandom` / `kPickModeSingleSpiritRoot` 同字面量；
     *  自选保底属 G13 备选，其余取值一律按不自洽拒绝） */
    private const val PICK_MODE_RANDOM = "random"
    private const val PICK_MODE_SINGLE_SPIRIT_ROOT = "singleSpiritRoot"
}

/** 抽卡结果码（与 C++ 失败信封的 `code` 字面量逐一相同）。 */
internal object GachaPullOutcome {
    const val CODE_POOL_NOT_FOUND = "PoolNotFound"
    const val CODE_POOL_DISABLED = "PoolDisabled"
    const val CODE_POOL_MALFORMED = "PoolMalformed"
    const val CODE_INSUFFICIENT = "InsufficientSpiritStones"
    const val CODE_INVALID_PULL_COUNT = "InvalidPullCount"
}
