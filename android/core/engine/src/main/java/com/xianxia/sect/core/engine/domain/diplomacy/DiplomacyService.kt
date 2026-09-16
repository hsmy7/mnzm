@file:Suppress("TooManyFunctions") // 提取的私有辅助函数集中于本文件，文件级函数数为拆分的固有代价
package com.xianxia.sect.core.engine.domain.diplomacy

import com.xianxia.sect.core.domain.FavorDomain
import com.xianxia.sect.core.domain.favor.FavorService
import com.xianxia.sect.core.engine.SectCombatPowerCalculator
import com.xianxia.sect.core.model.AISectPersonality
import com.xianxia.sect.core.model.Alliance
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GameEventCategory
import com.xianxia.sect.core.model.GameEventType
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.MaterialCategory
import com.xianxia.sect.core.model.MerchantItem
import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.model.PillGrade
import com.xianxia.sect.core.model.SectBattleType
import com.xianxia.sect.core.model.SectDetail
import com.xianxia.sect.core.model.SectRelationLevel
import com.xianxia.sect.core.model.SpiritStoneExchange
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.model.spiritStones
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.recordGameEvent
import com.xianxia.sect.core.engine.rebaselineNativeMirror
import com.xianxia.sect.core.engine.system.InventorySystem
import com.xianxia.sect.core.engine.system.MerchantItemConverter
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import com.xianxia.sect.core.registry.PillRecipeDatabase
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.wallet.SpiritStoneReason
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.wallet.DeductResult
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RarityTimeProgression
import com.xianxia.sect.core.util.asKotlinRandom
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.nativebridge.ActionIds
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.params
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.bool
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import java.util.UUID
import com.xianxia.sect.core.engine.system.canAddItems
import com.xianxia.sect.core.engine.system.canAddManual
import com.xianxia.sect.core.engine.system.canAddPill
import com.xianxia.sect.core.engine.system.canAddMaterial
import com.xianxia.sect.core.engine.system.canAddHerb
import com.xianxia.sect.core.engine.system.canAddSeed
import com.xianxia.sect.core.engine.system.canAddItemInTransaction



/**
 * 外交服务 — 管理宗门之间的联盟、交易。
 *
 * 送礼相关逻辑已移至 [com.xianxia.sect.core.domain.favor.GiftService]，
 * 好感度相关方法和查询委托 [FavorDomain] 和 [FavorService]。
 */
@Singleton
class DiplomacyService @Inject constructor(
    private val stateStore: GameStateStore,
    private val inventorySystem: InventorySystem,
    private val favorService: FavorService,
    private val spiritStoneWallet: SpiritStoneWallet,
    private val rngManager: GameRngManager,
    /**
     * C++ 引擎核心（DIPLOMACY_TX native 转发通道；batch-09）。默认 null
     * 仅供测试直构——null 或 flag 关闭时恒走 Kotlin 原路径。
     *
     * 经 [Provider] 注入：GameEngineCore 构造链上经 CultivationService →
     * CultivationEventProcessor 反向依赖本服务，直接注入会成 Dagger 环；
     * Provider 为惰性边（Dagger 官方破环手段），取值延迟到 native 转发调用点。
     */
    private val gameEngineCoreProvider: Provider<GameEngineCore>? = null
) {
    /** native 转发用引擎核心；无 Provider（测试直构）时为 null → 调用点走 Kotlin 原路径 */
    private val gameEngineCore: GameEngineCore? get() = gameEngineCoreProvider?.get()
    private val rng get() = rngManager.getRng(RngPartition.SYSTEM)
    private val discipleTables: DiscipleTables
        get() = stateStore.discipleTables

    companion object {
        private const val TAG = "DiplomacyService"

        /** 宗门交易列表强制刷新间隔（年）：年度事件按此差值判据刷新所有宗门 */
        private const val SECT_TRADE_REFRESH_INTERVAL_YEARS = 3
    }

    // ==================== 联盟系统 ====================

    /**
     * 简化版结盟请求（聊天流使用）
     * 无灵石费用、无需envoyDiscipleId、无需游说弟子
     *
     * 使用 [IntelligentSectDecisionEngine] 的四因素加权模型判定：
     * - 战力差 (20%) — 势均力敌更易结盟
     * - 占领丢失 (15%)
     * - 胜负 (25%) — 胜率反映宗门实力
     * - 好感度 (40%) — 信任是结盟的基础
     * - AI 个性 — 作为概率修正因子
     */
    suspend fun requestAllianceSimple(sectId: String): Boolean =
        // AUTHORITATIVE 转发（DIPLOMACY_TX，batch-09 下沉）：C++ 资格门控 +
        // 四因素概率 + SYSTEM 1×nextDouble + 盟约事务单一真相；失败信封
        // （资格/aiPower——Kotlin 同位置早退零抽取）回退 Kotlin 原路径。
        requestAllianceNative(sectId) ?: requestAllianceKotlin(sectId)

    /** Kotlin 原路径（native 降级/校验失败信封回退臂——原 requestAllianceSimple 本体） */
    private suspend fun requestAllianceKotlin(sectId: String): Boolean {
        val data = stateStore.gameData.value
        val sect = data.worldMapSects.find { it.id == sectId }
            ?: return false

        if (failsAllianceSimpleEligibility(data, sect)) return false

        val playerSect = data.worldMapSects.find { it.isPlayerSect } ?: return false

        // 使用共享引擎计算结盟概率（战力差/占领丢失/胜负/好感度/AI 个性）
        val successChance = computeAllianceSuccessChance(
            data = data,
            sect = sect,
            playerSectId = playerSect.id
        ) ?: return false

        val success = rng.nextDouble() < successChance

        if (success) {
            stateStore.update {
                applyAllianceCreation(sectId = sectId, sect = sect)
            }
        }

        return success
    }

    /** 结盟资格门控：非玩家宗门 + 无既有盟约 + 玩家宗门存在 */
    private fun failsAllianceSimpleEligibility(data: GameData, sect: WorldSect): Boolean {
        if (sect.isPlayerSect) return true
        if (sect.allianceId.isNotEmpty()) return true

        // 检查玩家是否已有盟约
        val existingAlliance = data.alliances.any { it.sectIds.contains("player") }
        if (existingAlliance) return true

        return data.worldMapSects.none { it.isPlayerSect }
    }

    /**
     * 计算结盟成功概率：
     * 四因素加权模型（战力差 20% / 占领丢失 15% / 胜负 25% / 好感度 40%）+ AI 个性修正。
     *
     * @return null 表示 AI 宗门战力非法（调用方按失败处理）
     */
    private fun computeAllianceSuccessChance(
        data: GameData,
        sect: WorldSect,
        playerSectId: String
    ): Double? {
        // 计算双方战力（统一永久基础属性公式，无装备/功法估算项）
        val playerPower = calculatePlayerTotalPower()
        val aiSectDisciples = data.aiSectDisciples[sect.id] ?: emptyList()
        val aiPower = SectCombatPowerCalculator.calculateSectPower(aiSectDisciples)
        if (aiPower <= 0) return null
        val powerRatio = playerPower.toDouble() / aiPower.toDouble()

        // 好感度（转为等级）
        val favor = FavorDomain.findFavor(data.sectRelations, playerSectId, sect.id)
        val favorLevel = SectRelationLevel.fromFavor(favor)

        // AI 个性
        val personality = data.aiSectPersonalities[sect.id] ?: AISectPersonality.BALANCED

        // 战斗记录（近3年）
        val recentRecords = data.sectBattleRecords.filter {
            it.year >= data.gameYear - 3
        }
        val conquestCount = recentRecords.count { it.type == SectBattleType.CONQUEST }
        val lostSectCount = recentRecords.count { it.type == SectBattleType.LOST_SECT }
        val battleWinCount = recentRecords.count { it.type == SectBattleType.BATTLE_WIN }
        val battleLossCount = recentRecords.count { it.type == SectBattleType.BATTLE_LOSS }

        return IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.ALLIANCE_PROFILE,
            powerRatio = powerRatio,
            conquestCount = conquestCount,
            lostSectCount = lostSectCount,
            battleWinCount = battleWinCount,
            battleLossCount = battleLossCount,
            favorLevel = favorLevel,
            personality = personality
        )
    }

    /** 结盟成功事务内写入：相识标记 + 盟约创建 + 双方宗门关联 */
    private fun MutableGameState.applyAllianceCreation(sectId: String, sect: WorldSect) {
        // 确保双方已相识
        gameData = gameData.copy(
            sectRelations = FavorDomain.setAcquainted(
                relations = gameData.sectRelations,
                sectId1 = "player",
                sectId2 = sectId,
                year = gameData.gameYear
            )
        )

        val alliance = Alliance(
            id = UUID.randomUUID().toString(),
            sectIds = listOf("player", sectId),
            startYear = gameData.gameYear,
            initiatorId = "player"
        )
        gameData = gameData.copy(
            alliances = gameData.alliances + alliance,
            worldMapSects = gameData.worldMapSects.map { s ->
                when {
                    s.id == sectId -> s.copy(allianceId = alliance.id, allianceStartYear = gameData.gameYear)
                    s.isPlayerSect -> s.copy(allianceId = alliance.id, allianceStartYear = gameData.gameYear)
                    else -> s
                }
            }
        )
        recordGameEvent(
            GameEventCategory.WORLD, GameEventType.ALLIANCE,
            "与${sect.name}结为同盟"
        )
    }

    /** 计算玩家宗门总战力（与 AI 同一公式：永久基础属性，无装备/功法） */
    private fun calculatePlayerTotalPower(): Long {
        val disciples = discipleTables.assembleAll()
        return SectCombatPowerCalculator.calculateSectPower(disciples)
    }

    /**
     * 简化版解除结盟（聊天流使用）
     * 无灵石惩罚
     */
    suspend fun dissolveAllianceSimple(sectId: String): Boolean {
        // AUTHORITATIVE 转发（DIPLOMACY_TX，batch-09 下沉；零 RNG 事务，
        // 失败信封回退 Kotlin 原路径同语义复现）
        dissolveAllianceNative(sectId)?.let { return it }

        var success = false
        stateStore.update {
            val sect = gameData.worldMapSects.find { it.id == sectId } ?: return@update
            if (sect.allianceId.isEmpty()) return@update
            val alliance = gameData.alliances.find { it.id == sect.allianceId } ?: return@update

            gameData = gameData.copy(
                worldMapSects = gameData.worldMapSects.map { s ->
                    if (alliance.sectIds.contains(s.id)) s.copy(allianceId = "", allianceStartYear = 0)
                    else s
                },
                alliances = gameData.alliances.filter { it.id != alliance.id }
            )
            recordGameEvent(
                GameEventCategory.WORLD, GameEventType.ALLIANCE_BREAK,
                "与${sect.name}解除同盟"
            )
            success = true
        }
        return success
    }

    // ==================== 宗门交易系统 ====================

    private data class SectTradeValidation(
        val sect: WorldSect,
        val item: MerchantItem,
        val actualQuantity: Int,
        val totalPrice: Long,
        val updatedSectDetails: Map<String, SectDetail>
    )

    /**
     * 生成宗门交易商品列表。传 [sectId] 时使用确定性种子
     * `sectId.hashCode() + year`——同 (sectId, year) 生成结果完全可复现
     * （仅 `id`/`itemId` 为 UUID 实例标识，不可复现）。
     * 注意：不传 sectId 时回退 SYSTEM 分区 RNG（消耗分区 draw 状态）——
     * 该回退路径无外部调用方，保留签名供内部确定性调用
     * （getOrRefreshSectTradeItems 的批量刷新 L446 / 单宗刷新 L404）。
     */
    fun generateSectTradeItems(year: Int, sectId: String? = null): List<MerchantItem> {
        val items = mutableListOf<MerchantItem>()
        val rngLocal = if (sectId != null) {
            DeterministicRng.fromSeed(sectId.hashCode().toLong() + year)
        } else {
            rng
        }

        val itemCount = 20
        val generatedNames = mutableSetOf<String>()
        var attempts = 0
        val maxAttempts = itemCount * 3

        while (items.size < itemCount && attempts < maxAttempts) {
            attempts++
            val item = generateSingleTradeItem(rngLocal = rngLocal, year = year) ?: continue

            if (!generatedNames.contains(item.name)) {
                generatedNames.add(item.name)
                items.add(item)
            }
        }

        items.sortByDescending { it.rarity }
        return items
    }

    /**
     * 单次商品抽取：先抽类型再抽品阶（RNG 消耗的
     * 条件/次数/顺序与原实现完全一致），按类型分发到对应生成器；
     * 返回 null 表示该类型当年无可生成物品（不计入列表，仅消耗一次尝试次数）。
     */
    private fun generateSingleTradeItem(rngLocal: DeterministicRng, year: Int): MerchantItem? {
        val types = listOf("equipment", "manual", "pill", "material", "herb", "seed", "spiritStone")
        val type = types[rngLocal.nextInt(types.size)]
        val rarity = selectRarityByMerchantProbabilities(rngLocal, year)

        return when (type) {
            "equipment" -> generateEquipmentItem(rngLocal = rngLocal, rarity = rarity, year = year)
            "manual" -> generateManualItem(rngLocal = rngLocal, rarity = rarity, year = year)
            "pill" -> generatePillItem(rngLocal = rngLocal, rarity = rarity, year = year)
            "material" -> generateMaterialItem(rngLocal = rngLocal, rarity = rarity, year = year)
            "herb" -> generateHerbItem(rngLocal = rngLocal, rarity = rarity, year = year)
            "seed" -> generateSeedItem(rngLocal = rngLocal, rarity = rarity, year = year)
            "spiritStone" -> generateSpiritStoneItem(rngLocal = rngLocal, rarity = rarity, year = year)
            else -> null
        }
    }

    /** 装备类商品生成 */
    private fun generateEquipmentItem(
        rngLocal: DeterministicRng,
        rarity: Int,
        year: Int
    ): MerchantItem {
        // 传入 rngLocal：装备名选择与类型/品阶同源，保证同 (sectId, year) 完全可复现
        val equipment = EquipmentDatabase.generateRandom(rarity, rarity, rngLocal.asKotlinRandom())
        val template = EquipmentDatabase.getTemplateByName(equipment.name)
        val basePrice = (template?.price ?: GameConfig.Rarity.get(rarity).basePrice).toLong()
        return MerchantItem(
            id = UUID.randomUUID().toString(),
            name = equipment.name,
            type = "equipment",
            itemId = equipment.id,
            rarity = equipment.rarity,
            price = GameUtils.applyPriceFluctuation(basePrice, rngLocal.asKotlinRandom()),
            quantity = calcMerchantStock(rngLocal = rngLocal, t = "equipment", r = rarity),
            obtainedYear = year,
            obtainedMonth = 1
        )
    }

    /** 功法类商品生成 */
    private fun generateManualItem(
        rngLocal: DeterministicRng,
        rarity: Int,
        year: Int
    ): MerchantItem? {
        // 同 equipment：传入 rngLocal 保证确定性复现。
        // 初始化守卫：数据库未初始化时跳过（避免年度单事务内抛异常被吞导致全量刷新丢失）
        if (!ManualDatabase.isInitialized) return null
        val manual = ManualDatabase.generateRandom(rarity, rarity, null, rngLocal.asKotlinRandom())
        val template = ManualDatabase.getByName(manual.name)
        val basePrice = (template?.price ?: GameConfig.Rarity.get(rarity).basePrice).toLong()
        return MerchantItem(
            id = UUID.randomUUID().toString(),
            name = manual.name,
            type = "manual",
            itemId = manual.id,
            rarity = manual.rarity,
            price = GameUtils.applyPriceFluctuation(basePrice, rngLocal.asKotlinRandom()),
            quantity = calcMerchantStock(rngLocal = rngLocal, t = "manual", r = rarity),
            obtainedYear = year,
            obtainedMonth = 1
        )
    }

    /** 丹药类商品生成 */
    private fun generatePillItem(
        rngLocal: DeterministicRng,
        rarity: Int,
        year: Int
    ): MerchantItem? {
        val pillTemplates = ItemDatabase.getPillsByRarity(rarity)
        if (pillTemplates.isEmpty()) return null
        val template = pillTemplates[rngLocal.nextInt(pillTemplates.size)]
        val pill = ItemDatabase.createPillFromTemplate(template)
        val basePrice = template.price.toLong()
        return MerchantItem(
            id = UUID.randomUUID().toString(),
            name = pill.name,
            type = "pill",
            itemId = pill.id,
            rarity = pill.rarity,
            price = GameUtils.applyPriceFluctuation(basePrice, rngLocal.asKotlinRandom()),
            quantity = calcMerchantStock(rngLocal = rngLocal, t = "pill", r = rarity),
            obtainedYear = year,
            obtainedMonth = 1,
            grade = pill.grade.displayName
        )
    }

    /** 材料类商品生成 */
    private fun generateMaterialItem(
        rngLocal: DeterministicRng,
        rarity: Int,
        year: Int
    ): MerchantItem? {
        val materials = BeastMaterialDatabase.getMaterialsByRarity(rarity)
        if (materials.isEmpty()) return null
        val material = materials[rngLocal.nextInt(materials.size)]
        val basePrice = material.price.toLong()
        return MerchantItem(
            id = UUID.randomUUID().toString(),
            name = material.name,
            type = "material",
            itemId = material.id,
            rarity = material.rarity,
            price = GameUtils.applyPriceFluctuation(basePrice, rngLocal.asKotlinRandom()),
            quantity = calcMerchantStock(rngLocal = rngLocal, t = "material", r = rarity),
            obtainedYear = year,
            obtainedMonth = 1
        )
    }

    /** 草药类商品生成 */
    private fun generateHerbItem(
        rngLocal: DeterministicRng,
        rarity: Int,
        year: Int
    ): MerchantItem? {
        val herbs = HerbDatabase.getByRarity(rarity)
        if (herbs.isEmpty()) return null
        val herb = herbs[rngLocal.nextInt(herbs.size)]
        val basePrice = herb.price.toLong()
        return MerchantItem(
            id = UUID.randomUUID().toString(),
            name = herb.name,
            type = "herb",
            itemId = herb.id,
            rarity = herb.rarity,
            price = GameUtils.applyPriceFluctuation(basePrice, rngLocal.asKotlinRandom()),
            quantity = calcMerchantStock(rngLocal = rngLocal, t = "herb", r = rarity),
            obtainedYear = year,
            obtainedMonth = 1
        )
    }

    /** 种子类商品生成 */
    private fun generateSeedItem(
        rngLocal: DeterministicRng,
        rarity: Int,
        year: Int
    ): MerchantItem? {
        val seeds = HerbDatabase.getSeedsByRarity(rarity)
        if (seeds.isEmpty()) return null
        val seed = seeds[rngLocal.nextInt(seeds.size)]
        val basePrice = seed.price.toLong()
        return MerchantItem(
            id = UUID.randomUUID().toString(),
            name = seed.name,
            type = "seed",
            itemId = seed.id,
            rarity = seed.rarity,
            price = GameUtils.applyPriceFluctuation(basePrice, rngLocal.asKotlinRandom()),
            quantity = calcMerchantStock(rngLocal = rngLocal, t = "seed", r = rarity),
            obtainedYear = year,
            obtainedMonth = 1
        )
    }

    /** 灵石类商品生成 */
    private fun generateSpiritStoneItem(
        rngLocal: DeterministicRng,
        rarity: Int,
        year: Int
    ): MerchantItem? {
        // 灵石受年份品阶上限约束：映射品阶（上品=4/中品=3）超过当年可出上限时跳过
        val spiritStoneRarity = if (rarity >= 4) 4 else 3
        if (spiritStoneRarity > RarityTimeProgression.maxRarityForYear(year)) return null
        val isHigh = rarity >= 4
        val name = if (isHigh) "上品灵石" else "中品灵石"
        val itemRarity = if (isHigh) 4 else 3
        val basePrice = if (isHigh) {
            SpiritStoneExchange.RATIO * SpiritStoneExchange.RATIO
        } else {
            SpiritStoneExchange.RATIO
        }
        return MerchantItem(
            id = UUID.randomUUID().toString(),
            name = name,
            type = "spiritStone",
            itemId = UUID.randomUUID().toString(),
            rarity = itemRarity,
            price = GameUtils.applyPriceFluctuation(basePrice, rngLocal.asKotlinRandom()),
            quantity = calcMerchantStock(rngLocal = rngLocal, t = "spiritStone", r = rarity).coerceAtMost(3),
            obtainedYear = year,
            obtainedMonth = 1
        )
    }

    /** 商品库存量抽样：消耗品（草药/种子/材料）与耐用品两档库存曲线 */
    private fun calcMerchantStock(rngLocal: DeterministicRng, t: String, r: Int): Int {
        val isConsumable = t in listOf("herb", "seed", "material")
        return if (isConsumable) {
            when (r) {
                6 -> 3 + rngLocal.nextInt(5)
                5 -> 3 + rngLocal.nextInt(5)
                4 -> 5 + rngLocal.nextInt(6)
                3 -> 5 + rngLocal.nextInt(8)
                2 -> 5 + rngLocal.nextInt(11)
                else -> 7 + rngLocal.nextInt(9)
            }
        } else {
            when (r) {
                6 -> 1 + rngLocal.nextInt(3)
                5 -> 1 + rngLocal.nextInt(3)
                4 -> 1 + rngLocal.nextInt(5)
                3 -> 1 + rngLocal.nextInt(5)
                2 -> 1 + rngLocal.nextInt(5)
                else -> 1 + rngLocal.nextInt(5)
            }
        }
    }

    suspend fun getOrRefreshSectTradeItems(sectId: String): List<MerchantItem> {
        val data = stateStore.gameData.value
        val sect = data.worldMapSects.find { it.id == sectId } ?: return emptyList()
        val sectDetail = data.sectDetails[sectId] ?: SectDetail(sectId = sectId)

        val currentYear = data.gameYear
        if (shouldRefreshSectTrade(currentYear, sectDetail)) {
            val newItems = generateSectTradeItems(currentYear, sectId)
            // 捕获豁免（updateMirror，§2.80）：懒刷新写 sectDetails（已关闭回导）——
            // 刷新发生即基线重建回导 C++（贸易界面打开触发，刷新周期 2 游戏年/宗门）
            stateStore.updateMirror {
                val updatedSectDetails = gameData.sectDetails.toMutableMap()
                updatedSectDetails[sectId] = (gameData.sectDetails[sectId] ?: SectDetail(sectId = sectId)).copy(
                    tradeItems = newItems,
                    tradeLastRefreshYear = currentYear
                )
                gameData = gameData.copy(sectDetails = updatedSectDetails)
            }
            gameEngineCore?.rebaselineNativeMirror("宗门贸易懒刷新")
            return newItems
        }

        return sectDetail.tradeItems
    }

    /**
     * 宗门交易刷新判据（年度 [refreshAllSectTrades] 与懒刷新 [getOrRefreshSectTradeItems] 共用单一来源）：
     * 距上次刷新满 [SECT_TRADE_REFRESH_INTERVAL_YEARS] 年，或列表为空兜底刷新。
     * 自愈：`tradeLastRefreshYear` 为未来值（时钟回拨/存档篡改）时按 0 处理——
     * 差值恒满足 → 立即刷新并写回当前年份，避免商品列表永久停滞。
     */
    private fun shouldRefreshSectTrade(year: Int, detail: SectDetail): Boolean {
        val lastRefresh = if (detail.tradeLastRefreshYear > year) 0 else detail.tradeLastRefreshYear
        return year - lastRefresh >= SECT_TRADE_REFRESH_INTERVAL_YEARS || detail.tradeItems.isEmpty()
    }

    /**
     * 年度强制刷新所有 AI 宗门交易列表（每 [SECT_TRADE_REFRESH_INTERVAL_YEARS] 年一次）。
     * 由年度事件处理器调用；与 [getOrRefreshSectTradeItems] 同差值判据、同确定性种子
     * （`sectId.hashCode() + year`）——同一年份下年度刷新与懒刷新结果幂等。
     * 读写在同一次 [GameStateStore.modifyState] 内完成：事务内 `gameData` 为重入缓冲
     * 副本，判据与写入基于同一快照（对齐 runGarrisonAndReport 的事务 buffer 模式）。
     */
    fun refreshAllSectTrades(year: Int) {
        stateStore.modifyState {
            if (gameData.sectDetails.isEmpty()) return@modifyState

            val refreshed = mutableMapOf<String, List<MerchantItem>>()
            for (sect in gameData.worldMapSects) {
                // 玩家宗门跳过 + 无详情跳过：合并为 null 传播，避免循环内多个 continue
                val detail = if (sect.isPlayerSect) null else gameData.sectDetails[sect.id]
                if (detail != null && shouldRefreshSectTrade(year, detail)) {
                    refreshed[sect.id] = generateSectTradeItems(year, sect.id)
                }
            }
            if (refreshed.isEmpty()) return@modifyState

            val updatedSectDetails = gameData.sectDetails.toMutableMap()
            for ((sectId, items) in refreshed) {
                updatedSectDetails[sectId] = (updatedSectDetails[sectId] ?: SectDetail(sectId = sectId)).copy(
                    tradeItems = items,
                    tradeLastRefreshYear = year
                )
            }
            gameData = gameData.copy(sectDetails = updatedSectDetails)
        }
    }

    private fun validateSectTrade(data: GameData, sectId: String, itemId: String, quantity: Int): SectTradeValidation? {
        val sect = data.worldMapSects.find { it.id == sectId } ?: return null
        val sectDetail = data.sectDetails[sectId]
        val tradeItems = sectDetail?.tradeItems ?: emptyList()
        val item = tradeItems.find { it.id == itemId } ?: return null

        if (!passesSectTradeEligibility(data, sectId, item, quantity)) return null

        val actualQuantity = minOf(quantity, item.quantity)
        val priceMultiplier = favorService.getTradePriceMultiplier(sectId)
        val totalPrice = (item.price * priceMultiplier).toLong() * actualQuantity

        val updatedTradeItems = if (item.quantity > actualQuantity) {
            tradeItems.map {
                if (it.id == itemId) it.copy(quantity = it.quantity - actualQuantity)
                else it
            }
        } else {
            tradeItems.filter { it.id != itemId }
        }

        val updatedSectDetails = data.sectDetails.toMutableMap()
        if (sectDetail != null) {
            updatedSectDetails[sectId] = sectDetail.copy(tradeItems = updatedTradeItems)
        }

        return SectTradeValidation(sect, item, actualQuantity, totalPrice, updatedSectDetails)
    }

    /**
     * 宗门交易资格校验：好感等级 → 品阶上限 → 灵石充足 →
     * 仓库容量。
     *
     * @return true = 交易资格成立
     */
    private fun passesSectTradeEligibility(
        data: GameData,
        sectId: String,
        item: MerchantItem,
        quantity: Int
    ): Boolean {
        val relation = favorService.getFavor(sectId)
        val relationLevel = FavorDomain.getLevel(relation)
        if (relationLevel !in listOf(SectRelationLevel.NORMAL, SectRelationLevel.FRIENDLY,
            SectRelationLevel.INTIMATE)) {
            return false
        }

        val maxAllowedRarity = relationLevel.maxAllowedRarity
        if (item.rarity > maxAllowedRarity) {
            return false
        }

        val actualQuantity = minOf(quantity, item.quantity)
        val priceMultiplier = favorService.getTradePriceMultiplier(sectId)
        val totalPrice = (item.price * priceMultiplier).toLong() * actualQuantity

        if (data.spiritStones < totalPrice) {
            return false
        }

        return checkTradeCapacity(item = item, actualQuantity = actualQuantity)
    }

    /** 交易物品仓库容量预检：按物品类型委托 [InventorySystem.canAddXxx] */
    @Suppress("CyclomaticComplexMethod")
    private fun checkTradeCapacity(item: MerchantItem, actualQuantity: Int): Boolean = when (item.type.lowercase()) {
        "equipment" -> inventorySystem.canAddItems(actualQuantity)
        "manual" -> {
            val t = ManualDatabase.getByName(item.name)
            inventorySystem.canAddManual(item.name, item.rarity, t?.type ?: ManualType.SUPPORT)
        }
        "pill" -> {
            val t = PillRecipeDatabase.getRecipeByName(item.name)
            val grade = item.grade?.let { gn -> PillGrade.entries.find { it.displayName == gn } } ?: PillGrade.MEDIUM
            inventorySystem.canAddPill(item.name, item.rarity, t?.category ?: PillCategory.FUNCTIONAL, grade)
        }
        "material" -> {
            val t = BeastMaterialDatabase.getMaterialByName(item.name)
            val cat = t?.category?.let { catName ->
                try {
                    MaterialCategory.valueOf(catName)
                } catch (ignored: IllegalArgumentException) {
                    MaterialCategory.BEAST_HIDE
                }
            } ?: MaterialCategory.BEAST_HIDE
            inventorySystem.canAddMaterial(item.name, item.rarity, cat)
        }
        "herb" -> {
            val t = HerbDatabase.getHerbByName(item.name)
            inventorySystem.canAddHerb(item.name, item.rarity, t?.category ?: "spirit")
        }
        "seed" -> {
            val t = HerbDatabase.getSeedByName(item.name)
            inventorySystem.canAddSeed(item.name, item.rarity, t?.growTime ?: 12)
        }
        "spiritstone" -> true
        else -> false
    }

suspend fun buyFromSectTradeSync(sectId: String, itemId: String, quantity: Int = 1) {
        // 捕获豁免（updateMirror，§2.81）：购买写面（钱包/sectRelations/sectDetails
        // 均已关闭回导；addSectTradeItemToMutableState 经统一入口 addXxx 重入本事务
        // 写 9 类实体集合——已关闭回导，引用比较检测不适用值等值收敛）
        stateStore.updateMirror {
            val v = validateSectTrade(gameData, sectId, itemId, quantity) ?: return@updateMirror

            // 确保双方已相识
            gameData = gameData.copy(
                sectRelations = FavorDomain.setAcquainted(
                    relations = gameData.sectRelations,
                    sectId1 = "player",
                    sectId2 = sectId,
                    year = gameData.gameYear
                )
            )

            // 扣款前容量预检（事务内最新状态）——
            // 仓库满时拒绝购买不扣灵石，避免"灵石已扣物品丢失"
            if (!inventorySystem.canAddItemInTransaction(this)) {
                stateStore.warehouseFullEvent.tryEmit("仓库容量不足，无法购买宗门贸易物品")
                return@updateMirror
            }
            val deductResult = spiritStoneWallet.deduct(this, v.totalPrice, SpiritStoneGrade.LOW,
                SpiritStoneReason.Purchase, SpiritStoneSource.MerchantTrade)
            if (deductResult !is DeductResult.Success) {
                return@updateMirror
            }
            gameData = gameData.copy(
                sectRelations = gameData.sectRelations,
                sectDetails = v.updatedSectDetails
            )
            // 预检后仍 Partial（合并空间不足）→ 溢出自动转邮件（手动-消耗类路径），物品不丢失
            addSectTradeItemToMutableState(v.item, v.actualQuantity)
        }
        // w3-13 通道关闭配套（§2.80/§2.81）：购买写面全部非捕获——发生后全量重建
        // native 基线回导 C++（低频用户动作）
        gameEngineCore?.rebaselineNativeMirror("宗门贸易购买")
    }

    /**
     * 宗门贸易物品入库——统一委托 [InventorySystem.addXxx]（走 StackableItemStore 合并），
     * 消除手写"找第一个堆叠 + 追加"导致同种物品分裂为多个堆叠的问题。
     */
    private fun MutableGameState.addSectTradeItemToMutableState(item: MerchantItem, actualQuantity: Int) {
        inventorySystem.withTrackingSource("sect_trade") {
            when (item.type.lowercase()) {
                "equipment" -> {
                    val eq = MerchantItemConverter.toEquipment(item).copy(quantity = actualQuantity)
                    handleSectTradeResult(inventorySystem.addEquipmentStack(eq), "装备 ${eq.name}")
                }
                "manual" -> {
                    val m = MerchantItemConverter.toManual(item).copy(quantity = actualQuantity)
                    handleSectTradeResult(inventorySystem.addManualStack(m), "功法 ${m.name}")
                }
                "pill" -> {
                    val p = MerchantItemConverter.toPill(item).copy(quantity = actualQuantity)
                    handleSectTradeResult(inventorySystem.addPill(p), "丹药 ${p.name}")
                }
                "material" -> {
                    val m = MerchantItemConverter.toMaterial(item).copy(quantity = actualQuantity)
                    handleSectTradeResult(inventorySystem.addMaterial(m), "材料 ${m.name}")
                }
                "herb" -> {
                    val h = MerchantItemConverter.toHerb(item).copy(quantity = actualQuantity)
                    handleSectTradeResult(inventorySystem.addHerb(h), "草药 ${h.name}")
                }
                "seed" -> {
                    val s = MerchantItemConverter.toSeed(item).copy(quantity = actualQuantity)
                    handleSectTradeResult(inventorySystem.addSeed(s), "种子 ${s.name}")
                }
                "spiritstone" -> {
                    val grade = SpiritStoneGrade.fromDisplayName(item.name) ?: return@withTrackingSource
                    spiritStoneWallet.add(this, actualQuantity.toLong(), grade, SpiritStoneSource.MerchantTrade)
                }
            }
        }
    }

    /** 记录 addXxx 三态结果 */
    private fun handleSectTradeResult(result: DomainResult<*>, label: String) {
        when (result) {
            is DomainResult.Success -> { /* 正常入库 */ }
            is DomainResult.Partial -> DomainLog.w(TAG, "$label 仓库已满，溢出 ${result.overflow} 个")
            is DomainResult.Failure -> DomainLog.w(TAG, "$label 入库失败: ${result.error}")
        }
    }

    /**
     * 按年份品阶权重曲线抽样商品品阶（[RarityTimeProgression.rollRarity]）。
     * 恰好消费 1 次 RNG draw；带 sectId 时使用确定性种子 RNG，同宗门同年份结果恒定。
     */
    private fun selectRarityByMerchantProbabilities(rngLocal: DeterministicRng, year: Int): Int =
        RarityTimeProgression.rollRarity(rngLocal, year)

    // ==================== native 转发（batch-09 外交事务下沉） ====================

    /**
     * 结盟请求 native 转发（C++ diplomacy_tx.h requestAllianceTransaction）。
     * 返回 null = 降级/校验失败信封（回退 Kotlin 原路径——失败臂零抽取
     * 零写入，双臂行为一致）；非 null = roll 已消费的终态。成功臂的消息栏
     * 事件（"与X结为同盟"）由本分支补写——C++ 事务已写事件，此处镜像回读
     * 后 Kotlin 侧不重复写（事件随 gameData 镜像同步）。
     *
     * 注：C++ 事务内已通过 settle_util::recordGameEvent 写入事件记录
     * （同守卫同序号分配），随 DirtyTracker 前向 diff 回读 Kotlin 镜像，
     * 本分支零事件写入。
     */
    @Suppress("ReturnCount")  // 降级契约：engineCore 缺失/flag 关/链路失败/字段缺失逐级返回 null
    private fun requestAllianceNative(sectId: String): Boolean? {
        val engineCore = gameEngineCore ?: return null
        if (!NativeEngineFlag.authoritative) return null
        val data = GameEngineNativeOps.tryExecuteNative(
            stateSyncService = engineCore.stateSyncServiceRef,
            actionId = ActionIds.DIPLOMACY_TX,
            paramsJson = params {
                put("op", "request_alliance")
                put("sectId", sectId)
            }
        ) ?: return null
        return data.bool("success")
    }

    /**
     * 解除结盟 native 转发（C++ diplomacy_tx.h dissolveAllianceTransaction）。
     * 事件记录由 C++ 事务写入（alliance_break），随镜像回读同步。
     */
    @Suppress("ReturnCount")
    private fun dissolveAllianceNative(sectId: String): Boolean? {
        val engineCore = gameEngineCore ?: return null
        if (!NativeEngineFlag.authoritative) return null
        val data = GameEngineNativeOps.tryExecuteNative(
            stateSyncService = engineCore.stateSyncServiceRef,
            actionId = ActionIds.DIPLOMACY_TX,
            paramsJson = params {
                put("op", "dissolve_alliance")
                put("sectId", sectId)
            }
        ) ?: return null
        return data.bool("success")
    }
}
