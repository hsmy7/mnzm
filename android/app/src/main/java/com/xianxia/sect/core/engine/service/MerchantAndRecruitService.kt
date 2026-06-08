package com.xianxia.sect.core.engine.service

import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.*
import com.xianxia.sect.core.util.PortraitPool
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.core.util.SpiritRootGenerator
import com.xianxia.sect.core.util.NameService
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.di.ApplicationScopeProvider
import android.util.Log
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
class MerchantAndRecruitService @Inject constructor(
    private val stateStore: GameStateStore,
    private val applicationScopeProvider: ApplicationScopeProvider
) {
    private val scope get() = applicationScopeProvider.scope

    companion object {
        private const val TAG = "MerchantAndRecruit"
        private const val TRAVELING_MERCHANT_ITEM_COUNT = 40
        private const val MERCHANT_PITY_THRESHOLD = 10

        private val RARITY_PROBABILITIES = mapOf(
            6 to 0.003,
            5 to 0.027,
            4 to 0.05,
            3 to 0.12,
            2 to 0.40,
            1 to 0.40
        )
    }

    // ── 状态访问器 ──────────────────────────────────────────────────────

    private var currentGameData: GameData
        get() = stateStore.currentTransactionMutableState()?.gameData ?: stateStore.gameData.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.gameData = value; return }
            scope.launch { stateStore.update { gameData = value } }
        }

    private var currentDisciples: List<Disciple>
        get() = stateStore.currentTransactionMutableState()?.disciples ?: stateStore.disciples.value
        set(value) {
            val ts = stateStore.currentTransactionMutableState()
            if (ts != null) { ts.disciples = value; return }
            scope.launch { stateStore.update { disciples = value } }
        }

    // ── 商人 ──────────────────────────────────────────────────────────

    suspend fun refreshTravelingMerchant(year: Int, month: Int) {
        val pools = buildMerchantItemPools()

        if (pools.poolByRarity.values.all { it.isEmpty() }) return

        val data = currentGameData
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

        currentGameData = data.copy(
            travelingMerchantItems = mergedItems,
            merchantLastRefreshYear = year,
            merchantRefreshCount = newRefreshCount
        )
    }

    fun buildMerchantItemPools(): MerchantItemPools {
        val pools = MerchantItemPools()

        for (rarity in 1..6) {
            pools.poolByRarity[rarity] = mutableListOf()
        }

        EquipmentDatabase.allTemplates.values.forEach { t ->
            pools.poolByRarity.getOrPut(t.rarity) { mutableListOf() }.add(PoolEntry(t.name, "equipment"))
            pools.rarityMap[t.name] = t.rarity
            pools.priceMap[t.name] = (t.price * GameConfig.Rarity.PRICE_MULTIPLIER).roundToInt().toLong()
        }

        if (ManualDatabase.isInitialized) {
            ManualDatabase.allManuals.values.forEach { t ->
                pools.poolByRarity.getOrPut(t.rarity) { mutableListOf() }.add(PoolEntry(t.name, "manual"))
                pools.rarityMap[t.name] = t.rarity
                pools.priceMap[t.name] = (t.price * GameConfig.Rarity.PRICE_MULTIPLIER).roundToInt().toLong()
            }
        }

        val addedPillNames = mutableSetOf<String>()
        ItemDatabase.allPills.values.forEach { t ->
            if (t.grade == PillGrade.MEDIUM && t.name !in addedPillNames) {
                addedPillNames.add(t.name)
                pools.poolByRarity.getOrPut(t.rarity) { mutableListOf() }.add(PoolEntry(t.name, "pill"))
                pools.rarityMap[t.name] = t.rarity
                pools.priceMap[t.name] = (t.price * GameConfig.Rarity.PRICE_MULTIPLIER).roundToInt().toLong()
            }
        }

        ItemDatabase.allMaterials.values.forEach { t ->
            pools.poolByRarity.getOrPut(t.rarity) { mutableListOf() }.add(PoolEntry(t.name, "material"))
            pools.rarityMap[t.name] = t.rarity
            pools.priceMap[t.name] = (t.price * GameConfig.Rarity.PRICE_MULTIPLIER).roundToInt().toLong()
        }

        HerbDatabase.getAllHerbs().forEach { h ->
            pools.poolByRarity.getOrPut(h.rarity) { mutableListOf() }.add(PoolEntry(h.name, "herb"))
            pools.rarityMap[h.name] = h.rarity
            pools.priceMap[h.name] = (h.price * GameConfig.Rarity.PRICE_MULTIPLIER).roundToInt().toLong()
        }

        HerbDatabase.getAllSeeds().forEach { s ->
            pools.poolByRarity.getOrPut(s.rarity) { mutableListOf() }.add(PoolEntry(s.name, "seed"))
            pools.rarityMap[s.name] = s.rarity
            pools.priceMap[s.name] = (s.price * GameConfig.Rarity.PRICE_MULTIPLIER).roundToInt().toLong()
        }

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
            ?: (GameConfig.Rarity.get(rarity).materialBasePrice * GameConfig.Rarity.PRICE_MULTIPLIER).roundToInt().toLong()
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
            Log.w(TAG, "商人保底触发但天品物品池为空，跳过保底")
            return
        }

        val mythicItem = mythicPool.random()
        val guaranteedMythicItem = createMerchantItem(mythicItem, pools, year, month, forcedRarity = 6)

        newItems.add(guaranteedMythicItem)

        Log.i(TAG, "商人第${refreshCount}次刷新触发保底，优先添加天品物品：${mythicItem.name}")
    }

    // ── 招募 ──────────────────────────────────────────────────────────

    suspend fun refreshRecruitList(year: Int) {
        val recruitCount = Random.nextInt(0, 7)
        val newRecruitDisciples = mutableListOf<Disciple>()
        val usedNames = (currentDisciples + currentGameData.recruitList).map { it.name }.toMutableSet()
        repeat(recruitCount) {
            val gender = if (Random.nextBoolean()) "male" else "female"
            val nameResult = NameService.generateName(gender, NameService.NameStyle.FULL, usedNames)
            val spiritRootType = SpiritRootGenerator.generate(Random)
            val hpVariance = Random.nextInt(-50, 51)
            val mpVariance = Random.nextInt(-50, 51)
            val physicalAttackVariance = Random.nextInt(-50, 51)
            val magicAttackVariance = Random.nextInt(-50, 51)
            val physicalDefenseVariance = Random.nextInt(-50, 51)
            val magicDefenseVariance = Random.nextInt(-50, 51)
            val speedVariance = Random.nextInt(-50, 51)
            val spiritRootCount = spiritRootType.split(",").size
            val comprehension = when (spiritRootCount) {
                1 -> Random.nextInt(80, 101)
                2 -> Random.nextInt(60, 101)
                3 -> Random.nextInt(40, 101)
                4 -> Random.nextInt(20, 101)
                else -> Random.nextInt(1, 101)
            }
            val disciple = Disciple(
                id = java.util.UUID.randomUUID().toString(),
                name = nameResult.fullName,
                surname = nameResult.surname,
                gender = gender,
                portraitRes = PortraitPool.getRandomPortrait(gender),
                age = Random.nextInt(16, 30),
                realm = 9,
                realmLayer = 1,
                spiritRootType = spiritRootType,
                status = DiscipleStatus.IDLE,
                discipleType = "outer",
                talentIds = TalentDatabase.generateTalentsForDisciple().map { it.id },
                combat = com.xianxia.sect.core.model.CombatAttributes(
                    hpVariance = hpVariance,
                    mpVariance = mpVariance,
                    physicalAttackVariance = physicalAttackVariance,
                    magicAttackVariance = magicAttackVariance,
                    physicalDefenseVariance = physicalDefenseVariance,
                    magicDefenseVariance = magicDefenseVariance,
                    speedVariance = speedVariance
                ),
                social = com.xianxia.sect.core.model.SocialData(),
                skills = com.xianxia.sect.core.model.SkillStats(
                    intelligence = Random.nextInt(1, 101),
                    charm = Random.nextInt(1, 101),
                    loyalty = Random.nextInt(1, 101),
                    comprehension = comprehension,
                    morality = Random.nextInt(1, 101),
                    artifactRefining = Random.nextInt(1, 101),
                    pillRefining = Random.nextInt(1, 101),
                    spiritPlanting = Random.nextInt(1, 101),
                    mining = Random.nextInt(1, 101),
                    teaching = Random.nextInt(1, 101)
                )
            ).apply {
                val baseStats = Disciple.calculateBaseStatsWithVariance(
                    hpVariance, mpVariance, physicalAttackVariance, magicAttackVariance,
                    physicalDefenseVariance, magicDefenseVariance, speedVariance
                )
                combat.baseHp = baseStats.baseHp
                combat.baseMp = baseStats.baseMp
                combat.basePhysicalAttack = baseStats.basePhysicalAttack
                combat.baseMagicAttack = baseStats.baseMagicAttack
                combat.basePhysicalDefense = baseStats.basePhysicalDefense
                combat.baseMagicDefense = baseStats.baseMagicDefense
                combat.baseSpeed = baseStats.baseSpeed

                val talentEffects = TalentDatabase.calculateTalentEffects(talentIds)
                val lifespanBonus = talentEffects["lifespan"] ?: 0.0
                val baseLifespan = GameConfig.Realm.get(realm).maxAge
                lifespan = (baseLifespan * (1.0 + lifespanBonus)).toInt().coerceAtLeast(1)
            }
            newRecruitDisciples.add(disciple)
            usedNames.add(disciple.name)
        }

        val filter = currentGameData.autoRecruitSpiritRootFilter
        val (autoRecruits, manualRecruits) = newRecruitDisciples.partition { disciple ->
            val rootCount = disciple.spiritRootType.split(",").size
            rootCount in filter
        }
        if (autoRecruits.isNotEmpty()) {
            val currentMonthIndex = year * 12 + 1
            autoRecruits.forEach { it.recruitedMonth = currentMonthIndex }
            currentDisciples = currentDisciples + autoRecruits
            newRecruitDisciples.clear()
            newRecruitDisciples.addAll(manualRecruits)
            Log.i(TAG, "autoRecruit: auto-recruited ${autoRecruits.size} disciples, ${manualRecruits.size} left for manual review")
        }

        val previousRecruitCount = currentGameData.recruitList.size
        currentGameData = currentGameData.copy(recruitList = newRecruitDisciples, lastRecruitYear = year)
        Log.d(TAG, "refreshRecruitList: year=$year, generated ${newRecruitDisciples.size} new recruits (previous recruitList had $previousRecruitCount)")
    }
}
