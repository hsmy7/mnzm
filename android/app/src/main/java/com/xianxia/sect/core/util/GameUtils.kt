package com.xianxia.sect.core.util

import com.xianxia.sect.core.GameConfig
import java.util.UUID
import kotlin.math.pow
import kotlin.random.Random

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
    
    fun clamp(value: Int, min: Int, max: Int): Int = value.coerceIn(min, max)
    
    fun clamp(value: Long, min: Long, max: Long): Long = value.coerceIn(min, max)
    
    fun clamp(value: Double, min: Double, max: Double): Double = value.coerceIn(min, max)
    
    fun formatNumber(value: Long): String {
        return when {
            value >= 1_000_000_000 -> String.format("%.1f亿", value / 1_000_000_000.0)
            value >= 10_000 -> String.format("%.1f万", value / 10_000.0)
            else -> value.toString()
        }
    }
    
    fun formatPercent(value: Double): String {
        return String.format("%.1f%%", value * 100)
    }
    
    fun formatTime(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        
        return when {
            hours > 0 -> String.format("%d时%d分%d秒", hours, minutes, secs)
            minutes > 0 -> String.format("%d分%d秒", minutes, secs)
            else -> String.format("%d秒", secs)
        }
    }
    
    fun calculateBreakthroughChance(
        baseChance: Double,
        pillBonus: Double = 0.0,
        talentBonus: Double = 0.0
    ): Double {
        return clamp(baseChance + pillBonus + talentBonus, 0.0, 1.0)
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
        return damage.coerceAtMost(Int.MAX_VALUE / 2)
    }
    
    fun calculateExperienceForLevel(level: Int, baseExp: Int = 100): Int {
        if (level <= 0 || baseExp <= 0) return 0
        val exp = baseExp.toDouble() * 1.5.pow(level - 1.0)
        return exp.coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
    }
    
    fun generateRandomName(style: NameStyle = NameStyle.COMMON): String {
        val surnames = when (style) {
            NameStyle.COMMON -> commonSurnames
            NameStyle.XIANXIA -> xianxiaSurnames
            NameStyle.FULL -> commonSurnames + xianxiaSurnames
        }
        val names = when (style) {
            NameStyle.COMMON -> commonNames
            NameStyle.XIANXIA -> xianxiaNames
            NameStyle.FULL -> commonNames + xianxiaNames
        }
        
        val surname = randomFrom(surnames) ?: "李"
        val name = randomFrom(names) ?: "明"
        
        return surname + name
    }

    enum class NameStyle {
        COMMON,
        XIANXIA,
        FULL
    }

    private val commonSurnames = listOf(
        "李", "王", "张", "刘", "陈", "杨", "赵", "黄", "周", "吴"
    )

    private val xianxiaSurnames = listOf(
        "李", "王", "张", "刘", "陈", "杨", "赵", "黄", "周", "吴",
        "林", "叶", "萧", "楚", "苏", "慕容", "上官", "欧阳", "司徒", "南宫",
        "云", "风", "墨", "白", "青", "紫", "玄", "苍", "凌", "寒"
    )

    private val commonNames = listOf(
        "明", "华", "强", "伟", "芳", "娟", "敏", "静", "丽", "勇"
    )

    private val xianxiaNames = listOf(
        "逍遥", "无忌", "长生", "问道", "清风", "明月", "云飞", "天行",
        "子轩", "子涵", "子墨", "子瑜", "子琪",
        "凌霄", "御风", "踏云", "惊鸿", "逐月", "追星",
        "悟道", "通玄", "归真", "化神", "凝神",
        "青松", "翠竹", "寒梅", "幽兰", "紫霞", "晨曦",
        "剑心", "剑尘", "剑歌", "剑影", "剑魄",
        "丹辰", "丹华", "丹心", "丹青", "丹枫",
        "器宇", "器灵", "器心", "器魂",
        "阵玄", "阵灵", "阵心", "阵尘",
        "符玄", "符灵", "符心", "符尘",
        "风", "云", "雷", "电", "山", "河", "剑", "刀", "影", "光"
    )
    
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
    
    fun isEmpty(str: String?): Boolean = str.isNullOrEmpty()
    
    fun isNotEmpty(str: String?): Boolean = !str.isNullOrEmpty()
    
    fun truncate(str: String, maxLength: Int, suffix: String = "..."): String {
        if (maxLength <= 0) return ""
        if (str.length <= maxLength) return str
        if (maxLength <= suffix.length) return suffix.take(maxLength)
        return str.take(maxLength - suffix.length) + suffix
    }
    
    fun padLeft(str: String, length: Int, padChar: Char = ' '): String {
        return str.padStart(length, padChar)
    }
    
    fun padRight(str: String, length: Int, padChar: Char = ' '): String {
        return str.padEnd(length, padChar)
    }
}
