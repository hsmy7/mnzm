package com.xianxia.sect.core.engine.service

import kotlin.math.roundToLong
import kotlin.random.Random
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.PillGrade
import com.xianxia.sect.core.model.SpiritStoneExchange
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RarityTimeProgression
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.util.asKotlinRandom
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.nativebridge.ActionIds
import com.xianxia.sect.core.nativebridge.GameCoreBridge
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.params
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.nativebridge.StateSyncService
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Provider
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
    private val rngManager: GameRngManager,
    // W4-B/B3（w3-05）：行商刷新族落账段下沉 C++（1770–1773），native 臂经
    // [Provider] 惰性取镜像服务（GameEngineCore 构造链持有本服务——Dagger 破环
    // 与 JadeSymbolService/DiplomacyService 同构）；null（既有测试直构）⇒ 恒走回退臂。
    private val gameEngineCoreProvider: Provider<GameEngineCore>? = null
) {
    private val rng get() = rngManager.getRng(RngPartition.SYSTEM)

    // ── W4-B/B3 native 臂（池生成留 Kotlin、落账归 C++）────────────────

    private val itemJson = Json { encodeDefaults = true }

    /**
     * 行商族事务统一转发（1770–1773）。
     *
     * 降级契约（findings 13 同款）：flag 非 AUTHORITATIVE / 无 Provider /
     * 镜像服务缺失 / native 失败信封 → null → 调用点回退 Kotlin 原路径
     * （回退臂重执行校验链——手动刷新失败信封在 Kotlin 侧重验后如实返回 false）。
     */
    private fun tryNativeMerchant(
        actionId: Int,
        paramsBuilder: JsonObjectBuilder.() -> Unit
    ): JsonObject? {
        if (!NativeEngineFlag.authoritative) return null
        val core = gameEngineCoreProvider?.get() ?: return null
        val sync: StateSyncService? = core.stateSyncServiceRef
        if (sync == null) return null
        return GameEngineNativeOps.tryExecuteNative(
            stateSyncService = sync,
            actionId = actionId,
            paramsJson = params(paramsBuilder)
        ) as? JsonObject
    }

    /** items 整表编码（键名与 C++ json_codec MerchantItem GC_TO/GC_FROM 一致）。 */
    private fun JsonObjectBuilder.putItems(items: List<MerchantItem>) {
        val encoded = itemJson.encodeToString(ListSerializer(MerchantItem.serializer()), items)
        put("items", Json.parseToJsonElement(encoded).jsonArray)
    }

    /**
     * native 通道**预检**（生成池**之前**调用）：flag / Provider / 镜像 / bridge
     * 四级可用性。🔴 必须 先于 `generateMerchantItemsForNative`——否则降级路径
     * 会先消耗一轮 SYSTEM 分区抽取（池生成）再由回退臂重生成（第二轮抽取），
     * 使抽取序相对 flag OFF 臂漂移（RNG 红线：抽取序零变更）。
     */
    private fun nativeMerchantAvailable(): Boolean {
        if (!NativeEngineFlag.authoritative) return false
        val core = gameEngineCoreProvider?.get() ?: return false
        val sync: StateSyncService? = core.stateSyncServiceRef
        if (sync == null) return false
        return GameCoreBridge.isLoaded
    }

    companion object {
        private const val TAG = "MerchantAndRecruit"
        private const val TRAVELING_MERCHANT_ITEM_COUNT = 40
        private const val MERCHANT_PITY_THRESHOLD = 10
        private const val ACQUISITION_ITEM_COUNT_MIN = 1
        private const val ACQUISITION_ITEM_COUNT_MAX = 9
        private const val MERCHANT_REFRESH_CHANCE_INTERVAL_YEARS = 30
        /** 灵石单次库存上限（与宗门交易 calcStock 的 coerceAtMost(3) 对齐） */
        private const val MAX_SPIRIT_STONE_STOCK = 3
    }

    // ── 商人 ──────────────────────────────────────────────────────────

    fun refreshTravelingMerchant(year: Int, month: Int) {
        val pools = buildMerchantItemPools()

        if (pools.poolByRarity.values.all { it.isEmpty() }) return

        // native 臂（W4-B/B3，MERCHANT_TRAVELING_REFRESH_TX）：以镜像 count 预计算
        // 新计数/保底相位 → 池整表生成（SYSTEM 分区，存档可重放）→ 落账归 C++。
        // 🔴 预检先于池生成（nativeMerchantAvailable）——降级路径不得消耗抽取序；
        // 失败信封/降级 → 走下方 Kotlin 原路径（回退臂逐字保留）。
        if (nativeMerchantAvailable()) {
            val snapshotCount = stateStore.gameDataSnapshot.merchantRefreshCount
            val newRefreshCount = foldRefreshCount(snapshotCount)
            val items = generateMerchantItemsForNative(pools, year, month, newRefreshCount)
            val reply = tryNativeMerchant(ActionIds.MERCHANT_TRAVELING_REFRESH_TX) {
                put("year", year)
                put("newRefreshCount", newRefreshCount)
                putItems(items)
            }
            if (reply != null) return
        }

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
                addGuaranteedTopRarityItem(newItems, pools, year, month, newRefreshCount)
            }

            val remainingCount = TRAVELING_MERCHANT_ITEM_COUNT - newItems.size
            repeat(remainingCount) {
                val selectedRarity = selectRarity(year)
                val selectedItem = selectItemByRarity(pools.poolByRarity, selectedRarity)
                    ?: selectFirstAvailableItem(pools.poolByRarity)

                if (selectedItem != null) {
                    // 旅行商人价格波动收敛 SYSTEM
                    // 分区（原默认 Random.Default JVM 全局随机——非托管不入
                    // rngStates，价格每次进程不同；分区化后存档可重放确定性）
                    newItems.add(createMerchantItem(
                        selectedItem, pools, year, month, random = rng.asKotlinRandom()))
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

    /** 刷新计数折叠（防 Int 溢出，保留保底相位）——native 臂镜像 count 预计算用。 */
    private fun foldRefreshCount(current: Int): Int =
        if (current >= 2_000_000_000) (current % MERCHANT_PITY_THRESHOLD) + 1 else current + 1

    /** 池整表生成（native 臂专用，SYSTEM 分区；保底相位由 newRefreshCount 决定）。 */
    private fun generateMerchantItemsForNative(
        pools: MerchantItemPools,
        year: Int,
        month: Int,
        newRefreshCount: Int
    ): List<MerchantItem> {
        val newItems = mutableListOf<MerchantItem>()
        if (newRefreshCount % MERCHANT_PITY_THRESHOLD == 0) {
            addGuaranteedTopRarityItem(newItems, pools, year, month, newRefreshCount)
        }
        val remainingCount = TRAVELING_MERCHANT_ITEM_COUNT - newItems.size
        repeat(remainingCount) {
            val selectedRarity = selectRarity(year)
            val selectedItem = selectItemByRarity(pools.poolByRarity, selectedRarity)
                ?: selectFirstAvailableItem(pools.poolByRarity)
            if (selectedItem != null) {
                newItems.add(createMerchantItem(
                    selectedItem, pools, year, month, random = rng.asKotlinRandom()))
            }
        }
        return mergeMerchantItems(newItems)
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

    /**
     * 按年份品阶权重曲线抽样商品品阶（[RarityTimeProgression.rollRarity]）。
     * 恰好消费 1 次分区 PRNG draw，保证随机流结构稳定。
     */
    fun selectRarity(year: Int): Int = RarityTimeProgression.rollRarity(rng, year)

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
        val stock = if (isConsumable) {
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
        return capSpiritStoneStock(type, stock)
    }

    /** 灵石库存上限与宗门交易一致（中品/上品灵石每次最多 [MAX_SPIRIT_STONE_STOCK] 个） */
    private fun capSpiritStoneStock(type: String, stock: Int): Int =
        if (type == "spiritStone") stock.coerceAtMost(MAX_SPIRIT_STONE_STOCK) else stock

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
        forcedRarity: Int? = null,
        random: kotlin.random.Random = Random.Default
    ): MerchantItem {
        val rarity = forcedRarity ?: pools.rarityMap[entry.name] ?: 1
        val basePrice = pools.priceMap[entry.name]
            ?: GameConfig.Rarity.get(rarity).materialBasePrice.toLong()
        val quantity = calculateMerchantStock(entry.type, rarity)

        val grade: PillGrade? = if (entry.type == "pill") selectMerchantPillGrade() else null
        val adjustedPrice = if (grade != null) (basePrice * grade.priceMultiplier / PillGrade.MEDIUM.priceMultiplier)
            .roundToLong() else basePrice

        return MerchantItem(
            id = java.util.UUID.randomUUID().toString(),
            name = entry.name,
            type = entry.type,
            itemId = java.util.UUID.randomUUID().toString(),
            rarity = rarity,
            // 价格波动 RNG 收敛——收购路径（已下沉 C++）
            // 传 SYSTEM 分区（rng.asKotlinRandom()，确定性可对拍）；
            // 旅行商人路径保持默认 Random.Default（JVM 全局随机——生成期随机、
            // 结果落库后固定，对存档确定性无影响）
            price = GameUtils.applyPriceFluctuation(adjustedPrice, random),
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

    /**
     * 保底：每 [MERCHANT_PITY_THRESHOLD] 次刷新必出 1 件**下一阶段最高品阶**物品
     * （[RarityTimeProgression.pityRarityForYear]，如 60 年属凡~灵段 → 必出宝品）。
     * 保底品阶池为空时回退当前段最高品阶池，再空则跳过（不崩溃）。
     */
    fun addGuaranteedTopRarityItem(
        newItems: MutableList<MerchantItem>,
        pools: MerchantItemPools,
        year: Int,
        month: Int,
        refreshCount: Int
    ) {
        val pityRarity = RarityTimeProgression.pityRarityForYear(year)
        val fallbackRarity = RarityTimeProgression.maxRarityForYear(year)
        val rarity = if (pools.poolByRarity[pityRarity].isNullOrEmpty() &&
            pools.poolByRarity[fallbackRarity].isNullOrEmpty()
        ) {
            DomainLog.w(TAG, "商人保底触发但品阶 $pityRarity/$fallbackRarity 物品池均为空，跳过保底")
            return
        } else if (pools.poolByRarity[pityRarity].isNullOrEmpty()) {
            fallbackRarity
        } else {
            pityRarity
        }

        val pityPool = pools.poolByRarity[rarity] ?: return
        val pityItem = pityPool[rng.nextInt(pityPool.size)]
        // 保底物品价格同步收敛 SYSTEM 分区
        val guaranteedItem = createMerchantItem(
            pityItem, pools, year, month, forcedRarity = rarity, random = rng.asKotlinRandom()
        )

        newItems.add(guaranteedItem)

        val rarityName = GameConfig.Rarity.getName(rarity)
        DomainLog.i(TAG, "商人第${refreshCount}次刷新触发保底，优先添加${rarityName}物品：${pityItem.name}")
    }

    // ── 手动刷新 ──────────────────────────────────────────────────────

    /**
     * 手动刷新旅行商人物品。
     * 消耗1次刷新机会，重新生成商品。
     * @return true=刷新成功, false=无可用刷新次数
     */
    fun refreshTravelingMerchantManual(): Boolean {
        // native 臂（W4-B/B3，MERCHANT_MANUAL_REFRESH_TX）：校验链 + 扣凭据 +
        // 池覆写**单事务原子**（C++ 侧以权威 chances 重验——失败信封 → 走回退臂
        // 重执行校验链并如实返回 false）。
        val preCheck = stateStore.gameDataSnapshot
        if (nativeMerchantAvailable() && preCheck.merchantRefreshChances > 0) {
            val pools = buildMerchantItemPools()
            if (!pools.poolByRarity.values.all { it.isEmpty() }) {
                val newRefreshCount = foldRefreshCount(preCheck.merchantRefreshCount)
                val items = generateMerchantItemsForNative(
                    pools, preCheck.gameYear, preCheck.gameMonth, newRefreshCount)
                val reply = tryNativeMerchant(ActionIds.MERCHANT_MANUAL_REFRESH_TX) {
                    put("year", preCheck.gameYear)
                    put("newRefreshCount", newRefreshCount)
                    putItems(items)
                }
                if (reply != null) {
                    DomainLog.i(TAG, "手动刷新商人成功，剩余次数=${stateStore.gameDataSnapshot.merchantRefreshChances}")
                    return true
                }
            }
        }
        // 原子化检查并扣减：在锁内读取最新 chances，消除 TOCTOU 窗口
        // （Kotlin 原路径，回退臂逐字保留）
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
     *
     * native 臂（W4-B/B3，MERCHANT_CHANCE_GRANT_TX）：判定链（达上限/未到间隔
     * 零写入）+ 发放归 C++；失败/降级 → Kotlin 原路径。
     */
    fun giveMerchantRefreshChanceIfDue(year: Int) {
        if (year <= 0) return  // 防御：无效年份跳过
        val reply = tryNativeMerchant(ActionIds.MERCHANT_CHANCE_GRANT_TX) {
            put("year", year)
            put("maxChances", GameConfig.JadePurchase.MERCHANT_REFRESH_MAX)
            put("intervalYears", MERCHANT_REFRESH_CHANCE_INTERVAL_YEARS)
        }
        if (reply != null) return
        stateStore.update {
            val lastGrant = gameData.merchantLastRefreshChanceGrantYear
            if (gameData.merchantRefreshChances >= GameConfig.JadePurchase.MERCHANT_REFRESH_MAX) return@update
            // lastGrant==0：首次（旧存档兼容代码已设 lastGrant=currentYear，不会误判）
            if (lastGrant == 0 || year - lastGrant >= MERCHANT_REFRESH_CHANCE_INTERVAL_YEARS) {
                gameData = gameData.copy(
                    merchantRefreshChances = (gameData.merchantRefreshChances + 1)
                        .coerceAtMost(GameConfig.JadePurchase.MERCHANT_REFRESH_MAX),
                    merchantLastRefreshChanceGrantYear = year
                )
            }
        }
    }

    // ── 招募 ──────────────────────────────────────────────────────────

    fun refreshMerchantAcquisition(year: Int, month: Int) {
        val pools = buildMerchantItemPools()
        if (pools.poolByRarity.values.all { it.isEmpty() }) return

        val acquisitionCount = ACQUISITION_ITEM_COUNT_MIN + rng
            .nextInt(ACQUISITION_ITEM_COUNT_MAX - ACQUISITION_ITEM_COUNT_MIN + 1)
        val newItems = mutableListOf<MerchantItem>()

        repeat(acquisitionCount) {
            val selectedRarity = selectRarity(year)
            val selectedItem = selectItemByRarity(pools.poolByRarity, selectedRarity)
                ?: selectFirstAvailableItem(pools.poolByRarity)

            if (selectedItem != null) {
                // 收购价格波动收敛 SYSTEM 分区——
                // 年变收购已下沉 C++（消费序逐位一致，跨语言可对拍）
                newItems.add(createMerchantItem(selectedItem, pools, year, month, random = rng.asKotlinRandom()))
            }
        }

        val mergedItems = mergeMerchantItems(newItems)

        // native 臂（W4-B/B3，MERCHANT_ACQUISITION_REFRESH_TX）：落账归 C++。
        // 该写面（merchantAcquisitionItems / merchantAcquisitionLastRefreshYear）
        // 为在册**已关闭**字段——本臂消除 AUTHORITATIVE 下的关闭域 Kotlin 写者残留。
        val reply = tryNativeMerchant(ActionIds.MERCHANT_ACQUISITION_REFRESH_TX) {
            put("year", year)
            putItems(mergedItems)
        }
        if (reply != null) return

        stateStore.update { gameData = gameData.copy(
            merchantAcquisitionItems = mergedItems,
            merchantAcquisitionLastRefreshYear = year
        ) }
    }

}
