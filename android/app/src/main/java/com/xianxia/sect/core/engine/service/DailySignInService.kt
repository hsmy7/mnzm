package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.config.InventoryConfig
import com.xianxia.sect.core.model.DailySignInReward
import com.xianxia.sect.core.model.SignInDayState
import com.xianxia.sect.core.model.SignInState
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.ItemDatabase
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.gameDataSnapshot
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
            DailySignInReward(weekday = 6, itemName = "悟法丹", quantity = 5, type = "pill", rarity = 1),
            DailySignInReward(weekday = 7, itemName = "灵品储物袋", quantity = 1, type = "storageBag", rarity = 2)
        )
    }

    fun getRewardForWeekday(weekday: Int): DailySignInReward {
        return WEEKLY_REWARDS.find { it.weekday == weekday } ?: WEEKLY_REWARDS.first()
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
        val calendar = Calendar.getInstance()
        val currentYear = calendar.get(Calendar.YEAR)
        val currentMonth = calendar.get(Calendar.MONTH)
        val today = calendar.get(Calendar.DAY_OF_MONTH)

        calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
        val targetDayOfYear = calendar.get(Calendar.DAY_OF_YEAR)
        val todayDayOfYear = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)

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

        // 发放奖励；返回 null 表示成功，返回错误消息表示容量不足
        val capacityError = distributeReward(reward)
        if (capacityError != null) {
            return ClaimDailyResult.CapacityInsufficient(capacityError)
        }

        // 更新签到状态
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

        return ClaimDailyResult.Success(reward)
    }

    /**
     * @return null 表示发放成功，非 null 字符串表示容量不足的错误消息
     */
    private suspend fun distributeReward(reward: DailySignInReward): String? {
        var capacityError: String? = null
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
                    }
                }
                "beastMaterial" -> {
                    val beastMat = BeastMaterialDatabase.getMaterialByName(reward.itemName)
                    if (beastMat == null) {
                        android.util.Log.w("DailySignInService", "Beast material '${reward.itemName}' not found in database")
                        // 回退：作为通用 material 创建
                        val qty = reward.quantity.coerceAtLeast(1)
                        val mat = com.xianxia.sect.core.model.Material(
                            id = java.util.UUID.randomUUID().toString(),
                            name = reward.itemName,
                            rarity = reward.rarity,
                            category = com.xianxia.sect.core.model.MaterialCategory.BEAST_HIDE,
                            quantity = qty
                        )
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
                }
                "pill" -> {
                    val qty = reward.quantity.coerceAtLeast(1)
                    val template = ItemDatabase.getPillByName(reward.itemName)
                    val pill = if (template != null) {
                        ItemDatabase.createPillFromTemplate(template, qty)
                    } else {
                        android.util.Log.w("DailySignInService", "Pill '${reward.itemName}' not found in ItemDatabase, generating random")
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
                }
                "randomMaterial" -> {
                    val qty = reward.quantity.coerceAtLeast(1)
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
                    }
                }
                "randomSeed" -> {
                    val qty = reward.quantity.coerceAtLeast(1)
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
                    }
                }
                "randomPill" -> {
                    val qty = reward.quantity.coerceAtLeast(1)
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
                    }
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
                }
            }
        }
        return capacityError
    }
}

sealed class ClaimDailyResult {
    data class Success(val reward: DailySignInReward) : ClaimDailyResult()
    data object AlreadyClaimed : ClaimDailyResult()
    data class CapacityInsufficient(val message: String) : ClaimDailyResult()
}
