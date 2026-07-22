package com.xianxia.sect.core.engine.service

import kotlin.math.roundToInt
import kotlin.math.roundToLong
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.registry.*
import com.xianxia.sect.core.util.PortraitPool
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.core.util.SpiritRootGenerator
import com.xianxia.sect.core.util.NameService
import com.xianxia.sect.core.engine.domain.disciple.DiscipleFactory
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.engine.annotation.GameService
import javax.inject.Inject
import javax.inject.Singleton

data class PoolEntry(
    val name: String,
    val type: String
)

data class MerchantItemPools(
    val poolByRarity: MutableMap<Int, MutableList<PoolEntry>> = mutableMapOf(),
    val rarityMap: MutableMap<String, Int> = mutableMapOf(),
    val priceMap: MutableMap<String, Long> = mutableMapOf()
)

@Singleton
@GameService("MerchantAndRecruitService")
class MerchantAndRecruitService @Inject constructor(
    private val stateStore: GameStateStore,
    private val scopeProvider: CoroutineScopeProvider,
    private val discipleFactory: com.xianxia.sect.core.engine.domain.disciple.DiscipleFactory,
    private val rngManager: GameRngManager
) {
    private val rng get() = rngManager.getRng(RngPartition.SYSTEM)
    private val scope get() = scopeProvider.scope

    companion object {
        private const val TAG = "MerchantAndRecruit"
        private const val TRAVELING_MERCHANT_ITEM_COUNT = 40
        private const val MERCHANT_PITY_THRESHOLD = 10
        private const val ACQUISITION_ITEM_COUNT_MIN = 1
        private const val ACQUISITION_ITEM_COUNT_MAX = 9
        private const val MAX_REASONABLE_AGE = 10000
        private const val MERCHANT_REFRESH_CHANCE_INTERVAL_YEARS = 30
        /** 手动刷新次数上限 */
        private const val MAX_MERCHANT_REFRESH_CHANCES = 999
        private val VALID_REALM_RANGE = GameConfig.Realm.CONFIGS.keys.let { it.min()..it.max() }

        internal val RARITY_PROBABILITIES = mapOf(
            6 to 0.003,
            5 to 0.027,
            4 to 0.05,
            3 to 0.12,
            2 to 0.40,
            1 to 0.40
        )

        /** 计算纳徒长老魅力带来的招募上限加成。
         *  魅力以80为基准，每高4点+1上限，不足0返回0 */
        fun calcRecruitBonusCap(charm: Int): Int = maxOf(0, (charm - 80) / 4)

        /**
         * 扫描 [state] 的 recruitList，将符合 autoRecruitSpiritRootFilter 的弟子自动加入宗门。
         * 必须在 [GameStateStore.update] 事务内调用（接收 [MutableGameState]）。
         * 任何新增待招募弟子的操作完成后均应调用此方法，确保自动招募及时生效。
         *
         * @param state 事务内的可变游戏状态
         * @return 实际自动招募的弟子数量
         */
        fun processAutoRecruit(state: MutableGameState): Int {
            val rawFilter = state.gameData.autoRecruitSpiritRootFilter
            if (rawFilter.isNullOrEmpty()) return 0
            // 守卫：只接受 1-5（有效灵根数量），剔除入库不合理值
            val filter = rawFilter.filter { it in 1..5 }.toSet()
            if (filter.isEmpty()) return 0

            val (autoRecruits, keepManual) = state.gameData.recruitList
                .distinctBy { it.id }
                .partition { disciple ->
                disciple.spiritRootType.split(",").count { it.isNotBlank() } in filter
            }
            if (autoRecruits.isEmpty()) return 0

            val currentMonthIndex = state.gameData.gameYear * 12 + state.gameData.gameMonth
            var recruited = 0
            for (disciple in autoRecruits) {
                if (disciple.name.isBlank() || disciple.age <= 0 || disciple.age > MAX_REASONABLE_AGE
                    || disciple.realm !in VALID_REALM_RANGE) {
                    DomainLog.w(TAG, "processAutoRecruit: skipping corrupted disciple ${disciple.id}")
                    continue
                }
                val newId = state.discipleTables.allocateAndInsert(
                    disciple.copy(usage = disciple.usage.copy(recruitedMonth = currentMonthIndex))
                        .also { it.lifeEvents = listOf("${disciple.age}岁：加入宗门") }
                )
                if (newId.isNotEmpty()) {
                    recruited++
                }
            }

            state.gameData = state.gameData.copy(recruitList = keepManual)
            DomainLog.i(TAG, "processAutoRecruit: auto-recruited $recruited disciples, " +
                "${keepManual.size} left for manual review")
            return recruited
        }
    }

    // ── 商人 ──────────────────────────────────────────────────────────

    fun refreshTravelingMerchant(year: Int, month: Int) {
        val pools = buildMerchantItemPools()

        if (pools.poolByRarity.values.all { it.isEmpty() }) return

        val newItems = mutableListOf<MerchantItem>()

        stateStore.update {
            // 防止 Int 溢出：超过 20 亿时折叠刷新计数，保留保底相位
            val newRefreshCount = if (gameData.merchantRefreshCount >= 2_000_000_000) {
                (gameData.merchantRefreshCount % MERCHANT_PITY_THRESHOLD) + 1
            } else {
                gameData.merchantRefreshCount + 1
            }
            val isPityRefresh = newRefreshCount % MERCHANT_PITY_THRESHOLD == 0

            if (isPityRefresh) {
                addGuaranteedMythicItem(newItems, pools, year, month, newRefreshCount)
            }

            val remainingCount = TRAVELING_MERCHANT_ITEM_COUNT - newItems.size
            repeat(remainingCount) {
                val selectedRarity = selectRarity()
                val selectedItem = selectItemByRarity(pools.poolByRarity, selectedRarity)
                    ?: selectFirstAvailableItem(pools.poolByRarity)

                if (selectedItem != null) {
                    newItems.add(createMerchantItem(selectedItem, pools, year, month))
                }
            }

            val mergedItems = mergeMerchantItems(newItems)

            gameData = gameData.copy(
                travelingMerchantItems = mergedItems,
                merchantLastRefreshYear = year,
                merchantRefreshCount = newRefreshCount
            )
        }
    }

    fun buildMerchantItemPools(): MerchantItemPools {
        val pools = MerchantItemPools()

        for (rarity in 1..6) {
            pools.poolByRarity[rarity] = mutableListOf()
        }

        EquipmentDatabase.allTemplates.values.forEach { t ->
            pools.poolByRarity.getOrPut(t.rarity) { mutableListOf() }.add(PoolEntry(t.name, "equipment"))
            pools.rarityMap[t.name] = t.rarity
            pools.priceMap[t.name] = t.price.toLong()
        }

        if (ManualDatabase.isInitialized) {
            ManualDatabase.allManuals.values.forEach { t ->
                pools.poolByRarity.getOrPut(t.rarity) { mutableListOf() }.add(PoolEntry(t.name, "manual"))
                pools.rarityMap[t.name] = t.rarity
                pools.priceMap[t.name] = t.price.toLong()
            }
        }

        val addedPillNames = mutableSetOf<String>()
        ItemDatabase.allPills.values.forEach { t ->
            if (t.grade == PillGrade.MEDIUM && t.name !in addedPillNames) {
                addedPillNames.add(t.name)
                pools.poolByRarity.getOrPut(t.rarity) { mutableListOf() }.add(PoolEntry(t.name, "pill"))
                pools.rarityMap[t.name] = t.rarity
                pools.priceMap[t.name] = t.price.toLong()
            }
        }

        ItemDatabase.allMaterials.values.forEach { t ->
            pools.poolByRarity.getOrPut(t.rarity) { mutableListOf() }.add(PoolEntry(t.name, "material"))
            pools.rarityMap[t.name] = t.rarity
            pools.priceMap[t.name] = t.price.toLong()
        }

        HerbDatabase.getAllHerbs().forEach { h ->
            pools.poolByRarity.getOrPut(h.rarity) { mutableListOf() }.add(PoolEntry(h.name, "herb"))
            pools.rarityMap[h.name] = h.rarity
            pools.priceMap[h.name] = h.price.toLong()
        }

        HerbDatabase.getAllSeeds().forEach { s ->
            pools.poolByRarity.getOrPut(s.rarity) { mutableListOf() }.add(PoolEntry(s.name, "seed"))
            pools.rarityMap[s.name] = s.rarity
            pools.priceMap[s.name] = s.price.toLong()
        }

        // 中品/上品灵石加入旅行商人与收购池（价格按下品结算）
        pools.poolByRarity.getOrPut(3) { mutableListOf() }.add(PoolEntry("中品灵石", "spiritStone"))
        pools.rarityMap["中品灵石"] = 3
        pools.priceMap["中品灵石"] = SpiritStoneExchange.RATIO

        pools.poolByRarity.getOrPut(4) { mutableListOf() }.add(PoolEntry("上品灵石", "spiritStone"))
        pools.rarityMap["上品灵石"] = 4
        pools.priceMap["上品灵石"] = SpiritStoneExchange.RATIO * SpiritStoneExchange.RATIO

        return pools
    }

    fun selectRarity(): Int {
        val rand = rng.nextDouble()
        var cumulative = 0.0
        for ((rarity, prob) in RARITY_PROBABILITIES.entries.sortedByDescending { it.key }) {
            cumulative += prob
            if (rand < cumulative) return rarity
        }
        return 1
    }

    fun selectItemByRarity(itemPoolByRarity: Map<Int, List<PoolEntry>>, rarity: Int): PoolEntry? {
        val pool = itemPoolByRarity[rarity] ?: return null
        if (pool.isEmpty()) return null
        return pool[rng.nextInt(pool.size)]
    }

    fun selectFirstAvailableItem(itemPoolByRarity: Map<Int, List<PoolEntry>>): PoolEntry? {
        val pool = (1..6).firstNotNullOfOrNull { r -> itemPoolByRarity[r]?.takeIf { it.isNotEmpty() } } ?: return null
        return pool[rng.nextInt(pool.size)]
    }

    fun calculateMerchantStock(type: String, rarity: Int): Int {
        val isConsumable = type in listOf("herb", "seed", "material")
        return if (isConsumable) {
            when (rarity) {
                6 -> 3 + rng.nextInt(5)
                5 -> 3 + rng.nextInt(5)
                4 -> 5 + rng.nextInt(6)
                3 -> 5 + rng.nextInt(8)
                2 -> 5 + rng.nextInt(11)
                else -> 7 + rng.nextInt(9)
            }
        } else {
            when (rarity) {
                6 -> 1 + rng.nextInt(3)
                5 -> 1 + rng.nextInt(3)
                4 -> 1 + rng.nextInt(5)
                3 -> 1 + rng.nextInt(5)
                2 -> 1 + rng.nextInt(5)
                else -> 1 + rng.nextInt(5)
            }
        }
    }

    fun selectMerchantPillGrade(): PillGrade {
        val roll = rng.nextDouble()
        return when {
            roll < 0.03 -> PillGrade.HIGH
            roll < 0.40 -> PillGrade.MEDIUM
            else -> PillGrade.LOW
        }
    }

    fun createMerchantItem(
        entry: PoolEntry,
        pools: MerchantItemPools,
        year: Int,
        month: Int,
        forcedRarity: Int? = null
    ): MerchantItem {
        val rarity = forcedRarity ?: pools.rarityMap[entry.name] ?: 1
        val basePrice = pools.priceMap[entry.name]
            ?: GameConfig.Rarity.get(rarity).materialBasePrice.toLong()
        val quantity = calculateMerchantStock(entry.type, rarity)

        val grade: PillGrade? = if (entry.type == "pill") selectMerchantPillGrade() else null
        val adjustedPrice = if (grade != null) (basePrice * grade.priceMultiplier / PillGrade.MEDIUM.priceMultiplier).roundToLong() else basePrice

        return MerchantItem(
            id = java.util.UUID.randomUUID().toString(),
            name = entry.name,
            type = entry.type,
            itemId = java.util.UUID.randomUUID().toString(),
            rarity = rarity,
            price = GameUtils.applyPriceFluctuation(adjustedPrice),
            quantity = quantity,
            obtainedYear = year,
            obtainedMonth = month,
            grade = grade?.displayName
        )
    }

    fun mergeMerchantItems(items: List<MerchantItem>): List<MerchantItem> {
        val merged = mutableMapOf<String, MerchantItem>()
        for (item in items) {
            val key = if (item.grade != null) "${item.name}:${item.type}:${item.grade}" else "${item.name}:${item.type}"
            val existing = merged[key]
            if (existing != null) {
                val totalQuantity = existing.quantity + item.quantity
                val weightedPrice = (existing.price * existing.quantity + item.price * item.quantity) / totalQuantity
                merged[key] = existing.copy(
                    quantity = totalQuantity,
                    price = weightedPrice
                )
            } else {
                merged[key] = item
            }
        }
        return merged.values.toList()
    }

    fun addGuaranteedMythicItem(
        newItems: MutableList<MerchantItem>,
        pools: MerchantItemPools,
        year: Int,
        month: Int,
        refreshCount: Int
    ) {
        val mythicPool = pools.poolByRarity[6]
        if (mythicPool == null || mythicPool.isEmpty()) {
            DomainLog.w(TAG, "商人保底触发但天品物品池为空，跳过保底")
            return
        }

        val mythicItem = mythicPool[rng.nextInt(mythicPool.size)]
        val guaranteedMythicItem = createMerchantItem(mythicItem, pools, year, month, forcedRarity = 6)

        newItems.add(guaranteedMythicItem)

        DomainLog.i(TAG, "商人第${refreshCount}次刷新触发保底，优先添加天品物品：${mythicItem.name}")
    }

    // ── 手动刷新 ──────────────────────────────────────────────────────

    /**
     * 手动刷新旅行商人物品。
     * 消耗1次刷新机会，重新生成商品。
     * @return true=刷新成功, false=无可用刷新次数
     */
    fun refreshTravelingMerchantManual(): Boolean {
        // 原子化检查并扣减：在锁内读取最新 chances，消除 TOCTOU 窗口
        val decremented = stateStore.updateAndReturn {
            if (gameData.merchantRefreshChances <= 0) return@updateAndReturn false
            gameData = gameData.copy(
                merchantRefreshChances = gameData.merchantRefreshChances - 1
            )
            true
        }
        if (!decremented) return false
        val current = stateStore.gameDataSnapshot
        refreshTravelingMerchant(current.gameYear, current.gameMonth)
        DomainLog.i(TAG, "手动刷新商人成功，剩余次数=${stateStore.gameDataSnapshot.merchantRefreshChances}")
        return true
    }

    /**
     * 年度检查：每30年给1次手动刷新次数。
     * 由 [CultivationEventProcessor] 在 yearly events 中调用。
     */
    fun giveMerchantRefreshChanceIfDue(year: Int) {
        if (year <= 0) return  // 防御：无效年份跳过
        stateStore.update {
            val lastGrant = gameData.merchantLastRefreshChanceGrantYear
            if (gameData.merchantRefreshChances >= MAX_MERCHANT_REFRESH_CHANCES) return@update
            // lastGrant==0：首次（旧存档兼容代码已设 lastGrant=currentYear，不会误判）
            if (lastGrant == 0 || year - lastGrant >= MERCHANT_REFRESH_CHANCE_INTERVAL_YEARS) {
                gameData = gameData.copy(
                    merchantRefreshChances = (gameData.merchantRefreshChances + 1)
                        .coerceAtMost(MAX_MERCHANT_REFRESH_CHANCES),
                    merchantLastRefreshChanceGrantYear = year
                )
            }
        }
    }

    // ── 招募 ──────────────────────────────────────────────────────────

    fun refreshMerchantAcquisition(year: Int, month: Int) {
        val pools = buildMerchantItemPools()
        if (pools.poolByRarity.values.all { it.isEmpty() }) return

        val acquisitionCount = ACQUISITION_ITEM_COUNT_MIN + rng.nextInt(ACQUISITION_ITEM_COUNT_MAX - ACQUISITION_ITEM_COUNT_MIN + 1)
        val newItems = mutableListOf<MerchantItem>()

        repeat(acquisitionCount) {
            val selectedRarity = selectRarity()
            val selectedItem = selectItemByRarity(pools.poolByRarity, selectedRarity)
                ?: selectFirstAvailableItem(pools.poolByRarity)

            if (selectedItem != null) {
                newItems.add(createMerchantItem(selectedItem, pools, year, month))
            }
        }

        val mergedItems = mergeMerchantItems(newItems)

        stateStore.update { gameData = gameData.copy(
            merchantAcquisitionItems = mergedItems,
            merchantAcquisitionLastRefreshYear = year
        ) }
    }

    // ── 招募 ──────────────────────────────────────────────────────────

    /** 计算纳徒长老魅力带来的当前招募上限加成 */
    private fun calcRecruitBonusCap(): Int {
        val recruitingElderId = stateStore.gameData.value.elderSlots.recruitingElder
        if (recruitingElderId.isEmpty()) return 0
        val elderCharm = stateStore.disciples.value
            .find { it.id == recruitingElderId }?.charm ?: return 0
        return Companion.calcRecruitBonusCap(elderCharm)
    }

    fun refreshRecruitList(year: Int) {
        val playerSect = stateStore.gameData.value.worldMapSects
            .find { it.isPlayerSect }
        val recruitCount = if (playerSect != null) {
            val range = SectLevel.recruitRange(playerSect.level)
            val bonusCap = calcRecruitBonusCap()
            val until = range.last + 1 + bonusCap
            // F2: 防 Range 为空导致 rng.nextInt(bound) 抛 IllegalArgumentException
            if (until <= range.first) range.first
            else range.first + rng.nextInt(until - range.first)
        } else {
            rng.nextInt(7)  // 兜底：找不到玩家宗门时保持旧逻辑
        }
        val newRecruitDisciples = mutableListOf<Disciple>()
        val usedNames = (stateStore.disciples.value + stateStore.gameData.value.recruitList).map { it.name }.toMutableSet()
        repeat(recruitCount) {
            val gender = if (rng.nextInt(2) == 0) "male" else "female"
            val nameResult = NameService.generateName(
                gender, NameService.NameStyle.FULL, usedNames
            )
            val disciple = discipleFactory.create(
                DiscipleFactory.DiscipleSeed(
                    id = java.util.UUID.randomUUID().toString(),
                    gender = gender,
                    nameResult = nameResult,
                    spiritRootType = SpiritRootGenerator.generate(object : kotlin.random.Random() {
                        override fun nextBits(bitCount: Int) = (rng.nextInt() ushr (32 - bitCount))
                        override fun nextInt(bound: Int) = rng.nextInt(bound)
                    }),
                    age = 16 + rng.nextInt(14),
                    realmLayer = 1,
                    social = SocialData(),
                    nextInt = { from, until -> from + rng.nextInt(until - from) }
                )
            )
            newRecruitDisciples.add(disciple)
            usedNames.add(disciple.name)
        }

        // 单事务：追加到 recruitList + 自动招募，保证原子性
        stateStore.update {
            gameData = gameData.copy(
                recruitList = gameData.recruitList + newRecruitDisciples,
                lastRecruitYear = year
            )
            processAutoRecruit(this)
        }
        DomainLog.d(TAG, "refreshRecruitList: year=$year, generated ${newRecruitDisciples.size} new recruits")
    }
}
