package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.model.DailySignInReward
import com.xianxia.sect.core.model.MilestoneReward
import com.xianxia.sect.core.model.RewardCardItem
import com.xianxia.sect.core.model.SignInDayState
import com.xianxia.sect.core.model.SignInState
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.util.DomainLog
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@GameService("DailySignInService")
@Singleton
class DailySignInService @Inject constructor(
    private val stateStore: GameStateStore,
    private val inventoryConfig: InventoryConfig
) {
    companion object {
        private const val TAG = "DailySignInService"

        val WEEKLY_REWARDS = listOf(
            DailySignInReward(weekday = 1, itemName = "灵石", quantity = 10000, type = "spiritStones", rarity = 1),
            DailySignInReward(weekday = 2, itemName = "凡品材料", quantity = 20, type = "randomMaterial", rarity = 1),
            DailySignInReward(weekday = 3, itemName = "凡品储物袋", quantity = 1, type = "storageBag", rarity = 1),
            DailySignInReward(weekday = 4, itemName = "凡品种子", quantity = 20, type = "randomSeed", rarity = 1),
            DailySignInReward(weekday = 5, itemName = "凡品丹药", quantity = 2, type = "randomPill", rarity = 1),
            DailySignInReward(weekday = 6, itemName = "随机凡品草药", quantity = 20, type = "randomHerb", rarity = 1),
            DailySignInReward(weekday = 7, itemName = "灵品储物袋", quantity = 1, type = "storageBag", rarity = 2)
        )

        /** 累计签到里程碑奖励 */
        val MILESTONE_REWARDS = listOf(
            MilestoneReward(day = 7, itemName = "灵石", quantity = 50000, type = "spiritStones", rarity = 1),
            MilestoneReward(day = 14, itemName = "灵品储物袋", quantity = 1, type = "storageBag", rarity = 2),
            MilestoneReward(day = 21, itemName = "灵石", quantity = 100000, type = "spiritStones", rarity = 1),
            MilestoneReward(day = 28, itemName = "宝品储物袋", quantity = 1, type = "storageBag", rarity = 3)
        )
    }

    fun getRewardForWeekday(weekday: Int): DailySignInReward {
        return WEEKLY_REWARDS.find { it.weekday == weekday } ?: WEEKLY_REWARDS.first()
    }

    fun getMilestoneRewards(): List<MilestoneReward> = MILESTONE_REWARDS

    fun getClaimedMilestones(): List<Int> {
        return stateStore.gameDataSnapshot.signInState.claimedMilestones
    }

    fun getCurrentSignInState(): SignInState {
        val data = stateStore.gameDataSnapshot
        val calendar = Calendar.getInstance()
        val currentYear = calendar.get(Calendar.YEAR)
        val currentMonth = calendar.get(Calendar.MONTH) + 1

        var state = data.signInState

        // 月份变更时重置签到状态
        if (state.currentYear != currentYear || state.currentMonth != currentMonth) {
            state = SignInState(
                claimedDays = emptyList(),
                currentMonth = currentMonth,
                currentYear = currentYear
            )
        }

        return state
    }

    fun getDayState(dayOfMonth: Int, signInState: SignInState): SignInDayState {
        // 单次 Calendar.getInstance() 避免午夜跨越时多次调用不一致
        val calendar = Calendar.getInstance()
        val currentYear = calendar.get(Calendar.YEAR)
        val currentMonth = calendar.get(Calendar.MONTH)
        val today = calendar.get(Calendar.DAY_OF_MONTH)

        calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
        val targetDayOfYear = calendar.get(Calendar.DAY_OF_YEAR)
        // 复用同一 calendar 实例计算今天的天序，避免二次 getInstance 可能跨越午夜
        calendar.set(Calendar.DAY_OF_MONTH, today)
        val todayDayOfYear = calendar.get(Calendar.DAY_OF_YEAR)

        return when {
            dayOfMonth == today -> {
                if (dayOfMonth in signInState.claimedDays) SignInDayState.TODAY_CLAIMED
                else SignInDayState.TODAY_UNCLAIMED
            }
            targetDayOfYear < todayDayOfYear -> {
                if (dayOfMonth in signInState.claimedDays) SignInDayState.PAST_CLAIMED
                else SignInDayState.MISSED
            }
            else -> SignInDayState.FUTURE
        }
    }

    fun getDaysInMonth(): Int {
        val calendar = Calendar.getInstance()
        return calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    fun getFirstDayOfWeek(): Int {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        // Calendar.DAY_OF_WEEK: 1=Sunday, 2=Monday, ..., 7=Saturday
        return calendar.get(Calendar.DAY_OF_WEEK)
    }

    fun getWeekdayForDay(dayOfMonth: Int): Int {
        val firstDayOfWeek = getFirstDayOfWeek()
        // firstDayOfWeek: Calendar.DAY_OF_WEEK (1=Sunday, 2=Monday, ..., 7=Saturday)
        // 计算当前日期对应的星期几 (Calendar.DAY_OF_WEEK 值)
        val calendarDayOfWeek = ((dayOfMonth - 1 + firstDayOfWeek - 1) % 7) + 1
        // calendarDayOfWeek: 1=Sunday, 2=Monday, ..., 7=Saturday
        // 转换为 WEEKLY_REWARDS 的索引: 1=Monday, 2=Tuesday, ..., 7=Sunday
        return if (calendarDayOfWeek == 1) 7 else calendarDayOfWeek - 1
    }

    suspend fun claimDailySignIn(): ClaimDailyResult {
        val state = getCurrentSignInState()
        val calendar = Calendar.getInstance()
        val today = calendar.get(Calendar.DAY_OF_MONTH)

        if (today in state.claimedDays) {
            return ClaimDailyResult.AlreadyClaimed
        }

        val weekday = getWeekdayForDay(today)
        val reward = getRewardForWeekday(weekday)

        // 发放每日奖励；返回 null 表示成功，返回错误消息表示容量不足
        val (capacityError, dailyCards) = distributeReward(reward)
        if (capacityError != null) {
            return ClaimDailyResult.CapacityInsufficient(capacityError)
        }
        val allCards = dailyCards.toMutableList()

        // 更新签到状态（先更新 claimedDays，再据此判断里程碑）
        stateStore.update {
            val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            val currentMonth = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1
            gameData = gameData.copy(
                signInState = gameData.signInState.copy(
                    claimedDays = gameData.signInState.claimedDays + today,
                    currentMonth = currentMonth,
                    currentYear = currentYear
                )
            )
        }

        // 检查并发放里程碑奖励
        val newClaimedDays = stateStore.gameDataSnapshot.signInState.claimedDays
        val newClaimedMilestones = stateStore.gameDataSnapshot.signInState.claimedMilestones.toMutableList()
        val earnedMilestones = mutableListOf<MilestoneReward>()

        for (milestone in MILESTONE_REWARDS) {
            if (newClaimedDays.size >= milestone.day && milestone.day !in newClaimedMilestones) {
                val (milestoneError, milestoneCards) = distributeReward(
                    DailySignInReward(
                        weekday = 0,
                        itemName = milestone.itemName,
                        quantity = milestone.quantity,
                        type = milestone.type,
                        rarity = milestone.rarity
                    )
                )
                if (milestoneError != null) {
                    return ClaimDailyResult.CapacityInsufficient(milestoneError)
                }
                newClaimedMilestones.add(milestone.day)
                earnedMilestones.add(milestone)
                allCards.addAll(milestoneCards)
            }
        }

        // 更新已领取的里程碑
        if (earnedMilestones.isNotEmpty()) {
            stateStore.update {
                gameData = gameData.copy(
                    signInState = gameData.signInState.copy(
                        claimedMilestones = newClaimedMilestones
                    )
                )
            }
        }

        return if (earnedMilestones.isNotEmpty()) {
            ClaimDailyResult.SuccessWithMilestones(reward, earnedMilestones, allCards)
        } else {
            ClaimDailyResult.Success(reward, allCards)
        }
    }

    /**
     * @return Pair(capacityError, generatedCards)
     *   capacityError: null 表示成功，非 null 为错误消息
     *   generatedCards: 实际生成的物品卡片（用于奖励动效）
     */
    private suspend fun distributeReward(
        reward: DailySignInReward
    ): Pair<String?, List<RewardCardItem>> {
        var capacityError: String? = null
        val generatedCards = mutableListOf<RewardCardItem>()
        stateStore.update {
            when (reward.type) {
                "spiritStones" -> {
                    val maxSpiritStones = Int.MAX_VALUE
                    if (gameData.spiritStones + reward.quantity > maxSpiritStones) {
                        capacityError = "灵石已达上限，无法签到领取"
                    } else {
                        gameData = gameData.copy(
                            spiritStones = gameData.spiritStones + reward.quantity
                        )
                        generatedCards.add(RewardCardItem(
                            itemName = "灵石", itemType = "spiritStones",
                            rarity = 1, quantity = reward.quantity
                        ))
                    }
                }
                "beastMaterial" -> {
                    val beastMat = BeastMaterialDatabase.getMaterialByName(reward.itemName)
                    val actualName: String
                    val actualRarity: Int
                    if (beastMat == null) {
                        DomainLog.w("DailySignInService", "Beast material '${reward.itemName}' not found in database")
                        val qty = reward.quantity.coerceAtLeast(1)
                        val mat = com.xianxia.sect.core.model.Material(
                            id = java.util.UUID.randomUUID().toString(),
                            name = reward.itemName,
                            rarity = reward.rarity,
                            category = com.xianxia.sect.core.model.MaterialCategory.BEAST_HIDE,
                            quantity = qty
                        )
                        actualName = mat.name; actualRarity = mat.rarity
                        val existing = materials.find {
                            it.name == mat.name && it.rarity == mat.rarity && it.category == mat.category
                        }
                        if (existing != null) {
                            val maxStack = inventoryConfig.getMaxStackSize("material")
                            if (existing.quantity >= maxStack) {
                                capacityError = "材料「${reward.itemName}」已达堆叠上限，请清理背包后重试"
                            } else {
                                val newQty = (existing.quantity + mat.quantity).coerceAtMost(maxStack)
                                materials = materials.map {
                                    if (it.id == existing.id) it.copy(quantity = newQty) else it
                                }
                            }
                        } else {
                            materials = materials + mat
                        }
                    } else {
                        val qty = reward.quantity.coerceAtLeast(1)
                        val mat = com.xianxia.sect.core.model.Material(
                            id = java.util.UUID.randomUUID().toString(),
                            name = beastMat.name,
                            rarity = beastMat.rarity,
                            category = beastMat.materialCategory,
                            quantity = qty
                        )
                        actualName = mat.name; actualRarity = mat.rarity
                        val existing = materials.find {
                            it.name == mat.name && it.rarity == mat.rarity && it.category == mat.category
                        }
                        if (existing != null) {
                            val maxStack = inventoryConfig.getMaxStackSize("material")
                            if (existing.quantity >= maxStack) {
                                capacityError = "材料「${reward.itemName}」已达堆叠上限，请清理背包后重试"
                            } else {
                                val newQty = (existing.quantity + mat.quantity).coerceAtMost(maxStack)
                                materials = materials.map {
                                    if (it.id == existing.id) it.copy(quantity = newQty) else it
                                }
                            }
                        } else {
                            materials = materials + mat
                        }
                    }
                    generatedCards.add(RewardCardItem(
                        itemName = actualName, itemType = "material",
                        rarity = actualRarity, quantity = reward.quantity
                    ))
                }
                "pill" -> {
                    val qty = reward.quantity.coerceAtLeast(1)
                    val template = ItemDatabase.getPillByName(reward.itemName)
                    val pill = if (template != null) {
                        ItemDatabase.createPillFromTemplate(template, qty)
                    } else {
                        DomainLog.w("DailySignInService", "Pill '${reward.itemName}' not found in ItemDatabase, generating random")
                        ItemDatabase.generateRandomPill(
                            minRarity = reward.rarity,
                            maxRarity = reward.rarity
                        ).copy(quantity = qty)
                    }
                    val existing = pills.find {
                        it.name == pill.name && it.rarity == pill.rarity && it.category == pill.category
                    }
                    if (existing != null) {
                        val maxStack = inventoryConfig.getMaxStackSize("pill")
                        if (existing.quantity >= maxStack) {
                            capacityError = "丹药「${reward.itemName}」已达堆叠上限，请清理背包后重试"
                        } else {
                            val newQty = (existing.quantity + pill.quantity).coerceAtMost(maxStack)
                            pills = pills.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                        }
                    } else {
                        pills = pills + pill
                    }
                    generatedCards.add(RewardCardItem(
                        itemName = pill.name, itemType = "pill",
                        rarity = pill.rarity, quantity = qty
                    ))
                }
                "randomMaterial" -> {
                    val qty = reward.quantity.coerceAtLeast(1)
                    val generated = mutableListOf<RewardCardItem>()
                    repeat(qty) {
                        val mat = ItemDatabase.generateRandomMaterial(minRarity = 1, maxRarity = 1).copy(
                            id = java.util.UUID.randomUUID().toString(), quantity = 1
                        )
                        val existing = materials.find {
                            it.name == mat.name && it.rarity == mat.rarity && it.category == mat.category
                        }
                        if (existing != null) {
                            val maxStack = inventoryConfig.getMaxStackSize("material")
                            if (existing.quantity < maxStack) {
                                val newQty = existing.quantity + 1
                                materials = materials.map {
                                    if (it.id == existing.id) it.copy(quantity = newQty) else it
                                }
                            }
                        } else {
                            materials = materials + mat
                        }
                        generated.add(RewardCardItem(
                            itemName = mat.name, itemType = "material",
                            rarity = mat.rarity, quantity = 1
                        ))
                    }
                    // 合并同名卡片
                    generatedCards.addAll(mergeCardsByName(generated))
                }
                "randomSeed" -> {
                    val qty = reward.quantity.coerceAtLeast(1)
                    val generated = mutableListOf<RewardCardItem>()
                    repeat(qty) {
                        val template = com.xianxia.sect.core.registry.HerbDatabase.generateRandomSeed(
                            minRarity = 1, maxRarity = 1
                        )
                        val seed = com.xianxia.sect.core.model.Seed(
                            id = java.util.UUID.randomUUID().toString(),
                            name = template.name,
                            rarity = template.rarity,
                            description = template.description,
                            growTime = template.growTime,
                            yield = template.yield,
                            quantity = 1
                        )
                        val existing = seeds.find {
                            it.name == seed.name && it.rarity == seed.rarity
                        }
                        if (existing != null) {
                            val maxStack = inventoryConfig.getMaxStackSize("seed")
                            if (existing.quantity < maxStack) {
                                val newQty = existing.quantity + 1
                                seeds = seeds.map {
                                    if (it.id == existing.id) it.copy(quantity = newQty) else it
                                }
                            }
                        } else {
                            seeds = seeds + seed
                        }
                        generated.add(RewardCardItem(
                            itemName = seed.name, itemType = "seed",
                            rarity = seed.rarity, quantity = 1
                        ))
                    }
                    generatedCards.addAll(mergeCardsByName(generated))
                }
                "randomPill" -> {
                    val qty = reward.quantity.coerceAtLeast(1)
                    val generated = mutableListOf<RewardCardItem>()
                    repeat(qty) {
                        val pill = ItemDatabase.generateRandomPill(minRarity = 1, maxRarity = 1).copy(
                            id = java.util.UUID.randomUUID().toString(), quantity = 1
                        )
                        val existing = pills.find {
                            it.name == pill.name && it.rarity == pill.rarity && it.category == pill.category
                        }
                        if (existing != null) {
                            val maxStack = inventoryConfig.getMaxStackSize("pill")
                            if (existing.quantity < maxStack) {
                                val newQty = existing.quantity + 1
                                pills = pills.map {
                                    if (it.id == existing.id) it.copy(quantity = newQty) else it
                                }
                            }
                        } else {
                            pills = pills + pill
                        }
                        generated.add(RewardCardItem(
                            itemName = pill.name, itemType = "pill",
                            rarity = pill.rarity, quantity = 1
                        ))
                    }
                    generatedCards.addAll(mergeCardsByName(generated))
                }
                "randomHerb" -> {
                    val qty = reward.quantity.coerceAtLeast(1)
                    val generated = mutableListOf<RewardCardItem>()
                    repeat(qty) {
                        val template = com.xianxia.sect.core.registry.HerbDatabase
                            .generateRandomHerb(minRarity = 1, maxRarity = 1)
                        val herb = com.xianxia.sect.core.model.Herb(
                            id = java.util.UUID.randomUUID().toString(),
                            name = template.name,
                            rarity = template.rarity,
                            description = template.description,
                            category = template.category,
                            quantity = 1
                        )
                        val existing = herbs.find {
                            it.name == herb.name && it.rarity == herb.rarity &&
                                it.category == herb.category
                        }
                        if (existing != null) {
                            val maxStack = inventoryConfig.getMaxStackSize("herb")
                            if (existing.quantity < maxStack) {
                                val newQty = existing.quantity + 1
                                herbs = herbs.map {
                                    if (it.id == existing.id)
                                        it.copy(quantity = newQty) else it
                                }
                            }
                        } else {
                            herbs = herbs + herb
                        }
                        generated.add(RewardCardItem(
                            itemName = herb.name, itemType = "herb",
                            rarity = herb.rarity, quantity = 1
                        ))
                    }
                    generatedCards.addAll(mergeCardsByName(generated))
                }
                "storageBag" -> {
                    val qty = reward.quantity.coerceAtLeast(1)
                    val rarity = reward.rarity.coerceIn(1, 6)
                    val bagName = com.xianxia.sect.core.model.StorageBag.TIER_NAMES.getOrElse(rarity - 1) { "凡品储物袋" }
                    val existing = storageBags.find { it.rarity == rarity }
                    if (existing != null) {
                        val maxStack = inventoryConfig.getMaxStackSize("storageBag")
                        if (existing.quantity >= maxStack) {
                            capacityError = "储物袋已达堆叠上限，请清理背包后重试"
                        } else {
                            val newQty = (existing.quantity + qty).coerceAtMost(maxStack)
                            storageBags = storageBags.map { if (it.id == existing.id) it.copy(quantity = newQty) else it }
                        }
                    } else {
                        storageBags = storageBags + com.xianxia.sect.core.model.StorageBag(
                            id = java.util.UUID.randomUUID().toString(),
                            name = bagName,
                            rarity = rarity,
                            quantity = qty
                        )
                    }
                    generatedCards.add(RewardCardItem(
                        itemName = bagName, itemType = "storageBag",
                        rarity = rarity, quantity = qty
                    ))
                }
            }
        }
        return Pair(capacityError, generatedCards)
    }

    /** 合并同名同稀有度的卡片 */
    private fun mergeCardsByName(cards: List<RewardCardItem>): List<RewardCardItem> {
        return cards.groupBy { "${it.itemName}_${it.rarity}" }.map { (_, group) ->
            group.first().copy(quantity = group.sumOf { it.quantity })
        }
    }

    /** 在小屏界面关闭后，将奖励卡片入队开始动效 */
    fun enqueueSignInCards(cards: List<RewardCardItem>) {
        if (cards.isNotEmpty()) {
            stateStore.enqueueRewardCards(cards)
        }
    }

}

sealed class ClaimDailyResult {
    data class Success(
        val reward: DailySignInReward,
        val cards: List<RewardCardItem> = emptyList()
    ) : ClaimDailyResult()
    data class SuccessWithMilestones(
        val reward: DailySignInReward,
        val milestones: List<MilestoneReward>,
        val cards: List<RewardCardItem> = emptyList()
    ) : ClaimDailyResult()
    data object AlreadyClaimed : ClaimDailyResult()
    data class CapacityInsufficient(val message: String) : ClaimDailyResult()
}
