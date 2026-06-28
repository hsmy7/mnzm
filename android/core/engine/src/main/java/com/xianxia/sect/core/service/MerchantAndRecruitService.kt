package com.xianxia.sect.core.engine.service

import kotlin.random.Random
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.GameStateStore
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
    private val discipleFactory: com.xianxia.sect.core.engine.domain.disciple.DiscipleFactory
) {
    private val scope get() = scopeProvider.scope

    companion object {
        private const val TAG = "MerchantAndRecruit"
        private const val TRAVELING_MERCHANT_ITEM_COUNT = 40
        private const val MERCHANT_PITY_THRESHOLD = 10
        private const val ACQUISITION_ITEM_COUNT_MIN = 1
        private const val ACQUISITION_ITEM_COUNT_MAX = 6

        private val RARITY_PROBABILITIES = mapOf(
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
    }

    // ── 商人 ──────────────────────────────────────────────────────────

    suspend fun refreshTravelingMerchant(year: Int, month: Int) {
        val pools = buildMerchantItemPools()

        if (pools.poolByRarity.values.all { it.isEmpty() }) return

        val data = stateStore.gameData.value
        val newRefreshCount = data.merchantRefreshCount + 1
        val isPityRefresh = newRefreshCount % MERCHANT_PITY_THRESHOLD == 0

        val newItems = mutableListOf<MerchantItem>()

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

        stateStore.update { gameData = data.copy(
            travelingMerchantItems = mergedItems,
            merchantLastRefreshYear = year,
            merchantRefreshCount = newRefreshCount
        ) }
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

        pools.poolByRarity.getOrPut(5) { mutableListOf() }.add(PoolEntry("上品灵石", "spiritStone"))
        pools.rarityMap["上品灵石"] = 5
        pools.priceMap["上品灵石"] = SpiritStoneExchange.RATIO * SpiritStoneExchange.RATIO

        return pools
    }

    fun selectRarity(): Int {
        val rand = Random.nextDouble()
        var cumulative = 0.0
        for ((rarity, prob) in RARITY_PROBABILITIES.entries.sortedByDescending { it.key }) {
            cumulative += prob
            if (rand < cumulative) return rarity
        }
        return 1
    }

    fun selectItemByRarity(itemPoolByRarity: Map<Int, List<PoolEntry>>, rarity: Int): PoolEntry? {
        return itemPoolByRarity[rarity]?.takeIf { it.isNotEmpty() }?.random()
    }

    fun selectFirstAvailableItem(itemPoolByRarity: Map<Int, List<PoolEntry>>): PoolEntry? {
        return (1..6).firstNotNullOfOrNull { r -> itemPoolByRarity[r]?.takeIf { it.isNotEmpty() }?.random() }
    }

    fun calculateMerchantStock(type: String, rarity: Int): Int {
        val isConsumable = type in listOf("herb", "seed", "material")
        return if (isConsumable) {
            when (rarity) {
                6 -> Random.nextInt(3, 8)
                5 -> Random.nextInt(3, 8)
                4 -> Random.nextInt(5, 11)
                3 -> Random.nextInt(5, 13)
                2 -> Random.nextInt(5, 16)
                else -> Random.nextInt(7, 16)
            }
        } else {
            when (rarity) {
                6 -> Random.nextInt(1, 4)
                5 -> Random.nextInt(1, 4)
                4 -> Random.nextInt(1, 6)
                3 -> Random.nextInt(1, 6)
                2 -> Random.nextInt(1, 6)
                else -> Random.nextInt(1, 6)
            }
        }
    }

    fun selectMerchantPillGrade(): PillGrade {
        val roll = Random.nextDouble()
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

        val mythicItem = mythicPool.random()
        val guaranteedMythicItem = createMerchantItem(mythicItem, pools, year, month, forcedRarity = 6)

        newItems.add(guaranteedMythicItem)

        DomainLog.i(TAG, "商人第${refreshCount}次刷新触发保底，优先添加天品物品：${mythicItem.name}")
    }

    // ── 招募 ──────────────────────────────────────────────────────────

    suspend fun refreshMerchantAcquisition(year: Int, month: Int) {
        val pools = buildMerchantItemPools()
        if (pools.poolByRarity.values.all { it.isEmpty() }) return

        val acquisitionCount = Random.nextInt(ACQUISITION_ITEM_COUNT_MIN, ACQUISITION_ITEM_COUNT_MAX + 1)
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

    suspend fun refreshRecruitList(year: Int) {
        val playerSect = stateStore.gameData.value.worldMapSects
            .find { it.isPlayerSect }
        val recruitCount = if (playerSect != null) {
            val range = SectLevel.recruitRange(playerSect.level)
            val bonusCap = calcRecruitBonusCap()
            Random.nextInt(range.first, range.last + 1 + bonusCap)
        } else {
            Random.nextInt(0, 7)  // 兜底：找不到玩家宗门时保持旧逻辑
        }
        val newRecruitDisciples = mutableListOf<Disciple>()
        val usedNames = (stateStore.disciples.value + stateStore.gameData.value.recruitList).map { it.name }.toMutableSet()
        repeat(recruitCount) {
            val gender = if (Random.nextBoolean()) "male" else "female"
            val nameResult = NameService.generateName(
                gender, NameService.NameStyle.FULL, usedNames
            )
            val disciple = discipleFactory.create(
                DiscipleFactory.DiscipleSeed(
                    id = java.util.UUID.randomUUID().toString(),
                    gender = gender,
                    nameResult = nameResult,
                    spiritRootType = SpiritRootGenerator.generate(Random),
                    age = Random.nextInt(16, 30),
                    realmLayer = 1,
                    social = SocialData(),
                    nextInt = { from, until -> Random.nextInt(from, until) }
                )
            )
            newRecruitDisciples.add(disciple)
            usedNames.add(disciple.name)
        }

        val filter = stateStore.gameData.value.autoRecruitSpiritRootFilter
        val (autoRecruits, manualRecruits) = newRecruitDisciples.partition { disciple ->
            val rootCount = disciple.spiritRootType.split(",").size
            rootCount in filter
        }
        if (autoRecruits.isNotEmpty()) {
            val currentMonthIndex = year * 12 + 1
            var nextId = (stateStore.discipleTables.ids.maxOrNull() ?: 0) + 1
            autoRecruits.forEach { disciple ->
                disciple.id = nextId.toString()
                disciple.recruitedMonth = currentMonthIndex
                nextId++
            }
            stateStore.update { autoRecruits.forEach { discipleTables.insert(it) } }
            newRecruitDisciples.clear()
            newRecruitDisciples.addAll(manualRecruits)
            DomainLog.i(TAG, "autoRecruit: auto-recruited ${autoRecruits.size} disciples, ${manualRecruits.size} left for manual review")
        }

        val previousRecruitCount = stateStore.gameData.value.recruitList.size
        stateStore.update { gameData = gameData.copy(recruitList = newRecruitDisciples, lastRecruitYear = year) }
        DomainLog.d(TAG, "refreshRecruitList: year=$year, generated ${newRecruitDisciples.size} new recruits (previous recruitList had $previousRecruitCount)")
    }
}
