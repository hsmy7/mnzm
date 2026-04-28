package com.xianxia.sect.core.util

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.model.SectRelation
import com.xianxia.sect.core.model.Alliance
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.random.Random

enum class SectRelationLevel(val displayName: String, val minFavor: Int, val maxFavor: Int, val colorHex: Long) {
    HOSTILE("敌对", 0, 9, 0xFFF44336),
    ANTAGONISTIC("交恶", 10, 39, 0xFFFF5722),
    NORMAL("普通", 40, 59, 0xFFFF9800),
    FRIENDLY("友善", 60, 79, 0xFF8BC34A),
    INTIMATE("至交", 80, 100, 0xFF4CAF50);

    val maxAllowedRarity: Int
        get() = when (this) {
            NORMAL -> 2
            FRIENDLY -> 4
            INTIMATE -> 6
            else -> 0
        }

    companion object {
        fun fromFavor(favor: Int): SectRelationLevel {
            return entries.find { favor in it.minFavor..it.maxFavor } ?: HOSTILE
        }
    }
}

object GameUtils {
    
    fun generateId(): String = UUID.randomUUID().toString()
    
    fun generateShortId(): String = UUID.randomUUID().toString().take(8)
    
    fun randomInt(min: Int, max: Int): Int {
        require(min <= max) { "min must be <= max" }
        if (min == max) return min
        return Random.nextLong(min.toLong(), max.toLong() + 1L).toInt()
    }
    
    fun randomLong(min: Long, max: Long): Long {
        require(min <= max) { "min must be <= max" }
        if (min == max) return min
        if (min == Long.MIN_VALUE && max == Long.MAX_VALUE) return Random.nextLong()

        val bound = max - min + 1
        if (bound > 0) {
            return min + Random.nextLong(bound)
        }

        var value: Long
        do {
            value = Random.nextLong()
        } while (value < min || value > max)
        return value
    }
    
    fun randomDouble(min: Double, max: Double): Double {
        require(min <= max) { "min must be <= max" }
        if (min == max) return min
        return Random.nextDouble(min, max)
    }
    
    fun randomChance(chance: Double): Boolean {
        val normalized = chance.coerceIn(0.0, 1.0)
        return Random.nextDouble() < normalized
    }
    
    /**
     * 计算队伍平均境界（包含小境界层数）
     * 返回精确的平均境界值，包含小数部分表示层数进度
     * 数值越小表示境界越高（0=仙人, 9=炼气）
     * 
     * 例如：
     * - 炼气期1层 (realm=9, layer=1) -> 8.9
     * - 炼气期9层 (realm=9, layer=9) -> 8.1（接近筑基期）
     * - 筑基期1层 (realm=8, layer=1) -> 7.9
     *
     * @param realms 境界值列表，每个值为 Pair(realm, realmLayer)
     * @return 平均境界值，空列表返回 9.0（炼气期）
     */
    fun calculateAverageRealm(realms: List<Pair<Int, Int?>>): Double {
        if (realms.isEmpty()) return 9.0
        
        val total = realms.sumOf { (realm, layer) ->
            val layerProgress = (layer ?: 1) / 10.0
            realm - layerProgress
        }
        return total / realms.size
    }
    
    /**
     * 计算弟子列表的平均境界
     * 
     * @param disciples 弟子列表，需要实现 realm 和 realmLayer 属性
     * @return 平均境界值
     */
    fun <T> calculateTeamAverageRealm(
        disciples: List<T>,
        realmExtractor: (T) -> Int,
        layerExtractor: (T) -> Int?
    ): Double {
        if (disciples.isEmpty()) return 9.0
        
        val total = disciples.sumOf { disciple ->
            val realm = realmExtractor(disciple)
            val layer = layerExtractor(disciple)
            val layerProgress = (layer ?: 1) / 10.0
            realm - layerProgress
        }
        return total / disciples.size
    }

    fun <T> calculateBeastRealm(
        disciples: List<T>,
        realmExtractor: (T) -> Int,
        layerExtractor: (T) -> Int?
    ): Int {
        if (disciples.isEmpty()) return 9

        val avgRealm = calculateTeamAverageRealm(disciples, realmExtractor, layerExtractor)

        val realmIndex = ceil(avgRealm).toInt().coerceIn(0, 9)

        val teamMaxRealm = disciples.maxOf { realmExtractor(it) }
        return realmIndex.coerceAtMost(teamMaxRealm)
    }

    fun calculateBeastRealmFromAvg(avgRealm: Double, teamMaxRealm: Int = 9): Int {
        val realmIndex = ceil(avgRealm).toInt().coerceIn(0, 9)
        return realmIndex.coerceAtMost(teamMaxRealm)
    }
    
    fun <T> randomFrom(list: List<T>): T? = list.randomOrNull()
    
    fun <T> randomFromWeighted(items: List<Pair<T, Double>>): T? {
        val validItems = items.filter { (_, weight) -> weight.isFinite() && weight > 0.0 }
        if (validItems.isEmpty()) return null

        val totalWeight = validItems.sumOf { it.second }
        var roll = Random.nextDouble(totalWeight)
        
        validItems.forEach { (item, weight) ->
            roll -= weight
            if (roll <= 0) return item
        }
        
        return validItems.lastOrNull()?.first
    }
    
    fun <T> shuffle(list: List<T>): List<T> = list.shuffled()
    
    fun formatNumber(value: Long): String {
        return when {
            value >= 1_000_000_000 -> String.format(Locale.getDefault(), "%.1f亿", value / 1_000_000_000.0)
            value >= 10_000 -> String.format(Locale.getDefault(), "%.1f万", value / 10_000.0)
            else -> value.toString()
        }
    }
    
    fun formatPercent(value: Double): String {
        return String.format(Locale.getDefault(), "%.1f%%", value * 100)
    }
    
    fun formatTime(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        
        return when {
            hours > 0 -> String.format(Locale.getDefault(), "%d时%d分%d秒", hours, minutes, secs)
            minutes > 0 -> String.format(Locale.getDefault(), "%d分%d秒", minutes, secs)
            else -> String.format(Locale.getDefault(), "%d秒", secs)
        }
    }
    
    fun calculateBreakthroughChance(
        baseChance: Double,
        pillBonus: Double = 0.0,
        talentBonus: Double = 0.0
    ): Double {
        return (baseChance + pillBonus + talentBonus).coerceIn(0.0, 1.0)
    }
    
    fun calculateDamage(
        attack: Int,
        defense: Int,
        multiplier: Double = 1.0,
        isCrit: Boolean = false,
        critMultiplier: Double = GameConfig.Battle.CRIT_MULTIPLIER
    ): Int {
        val baseDamage = (attack * multiplier - defense * 0.5).toInt()
        val finalDamage = maxOf(1, baseDamage)
        val damage = if (isCrit) (finalDamage * critMultiplier).toInt() else finalDamage
        return damage
    }
    
    fun calculateExperienceForLevel(level: Int, baseExp: Int = 100): Int {
        if (level <= 0 || baseExp <= 0) return 0
        val exp = baseExp.toDouble() * 1.5.pow(level - 1.0)
        return exp.coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
    }
    
    fun applyPriceFluctuation(basePrice: Int, random: Random = Random): Int {
        val fluctuationPercent = random.nextDouble(-20.0, 20.0)
        val roundedPercent = (fluctuationPercent * 10).toInt() / 10.0
        val result = basePrice * (1 + roundedPercent / 100.0)
        return result.toInt().coerceAtLeast(1)
    }

    fun applyPriceFluctuation(basePrice: Long, random: Random = Random): Long {
        val fluctuationPercent = random.nextDouble(-20.0, 20.0)
        val roundedPercent = (fluctuationPercent * 10).toInt() / 10.0
        val result = basePrice * (1 + roundedPercent / 100.0)
        return result.toLong().coerceAtLeast(1L)
    }

    fun generateRandomName(style: NameStyle = NameStyle.COMMON): String {
        val nameStyle = when (style) {
            NameStyle.COMMON -> NameService.NameStyle.COMMON
            NameStyle.XIANXIA -> NameService.NameStyle.XIANXIA
            NameStyle.FULL -> NameService.NameStyle.FULL
        }
        val gender = if (kotlin.random.Random.nextBoolean()) "male" else "female"
        return NameService.generateName(gender, nameStyle).fullName
    }

    enum class NameStyle {
        COMMON,
        XIANXIA,
        FULL
    }

    fun generateRandomSpiritRoot(): SpiritRootData {
        val types = listOf("metal", "wood", "water", "fire", "earth")
        val type = randomFrom(types) ?: "metal"
        val quality = randomInt(1, 10)
        return SpiritRootData(type, quality)
    }
    
    data class SpiritRootData(
        val type: String,
        val quality: Int
    )

    fun getSectRelation(worldMapSects: List<WorldSect>, sectRelations: List<SectRelation>, sectId: String): Int {
        val playerSect = worldMapSects.find { it.isPlayerSect } ?: return 0
        return sectRelations.find {
            (it.sectId1 == playerSect.id && it.sectId2 == sectId) ||
            (it.sectId1 == sectId && it.sectId2 == playerSect.id)
        }?.favor ?: 0
    }

    fun getSectRelationLevel(favor: Int): SectRelationLevel {
        return SectRelationLevel.fromFavor(favor)
    }

    fun calculateSectTradePriceMultiplier(
        worldMapSects: List<WorldSect>,
        sectRelations: List<SectRelation>,
        alliances: List<Alliance>,
        sectId: String
    ): Double {
        val relation = getSectRelation(worldMapSects, sectRelations, sectId)
        val isAlly = alliances.any { it.sectIds.contains("player") && it.sectIds.contains(sectId) }
        return when {
            isAlly -> (0.9 * (1.0 - maxOf(0, relation - 70) * 0.01)).coerceAtLeast(0.85)
            relation >= 70 -> (1.0 - (relation - 70) * 0.01).coerceAtLeast(0.85)
            else -> 1.0
        }
    }
}

object CollectionUtils {
    fun <T> List<T>.getOrDefault(index: Int, defaultValue: T): T {
        return if (index in indices) this[index] else defaultValue
    }
    
    fun <K, V> MutableMap<K, MutableList<V>>.addTo(key: K, value: V) {
        getOrPut(key) { mutableListOf() }.add(value)
    }
    
    fun <T> MutableList<T>.removeFirst(predicate: (T) -> Boolean): Boolean {
        val index = indexOfFirst(predicate)
        return if (index >= 0) {
            removeAt(index)
            true
        } else false
    }
}

object StringUtils {

    fun truncate(str: String, maxLength: Int, suffix: String = "..."): String {
        if (maxLength <= 0) return ""
        if (str.length <= maxLength) return str
        if (maxLength <= suffix.length) return suffix.take(maxLength)
        return str.take(maxLength - suffix.length) + suffix
    }
}
