package com.xianxia.sect.core.model

@JvmInline
value class Rarity private constructor(val value: Int) : Comparable<Rarity> {
    
    companion object {
        val COMMON = Rarity(1)
        val UNCOMMON = Rarity(2)
        val RARE = Rarity(3)
        val EPIC = Rarity(4)
        val LEGENDARY = Rarity(5)
        val MYTHIC = Rarity(6)
        
        private val colorMap = mapOf(
            1 to "#9E9E9E",
            2 to "#4CAF50",
            3 to "#2196F3",
            4 to "#9C27B0",
            5 to "#FF9800",
            6 to "#E91E63"
        )
        
        private val nameMap = mapOf(
            1 to "普通",
            2 to "优秀",
            3 to "稀有",
            4 to "史诗",
            5 to "传说",
            6 to "神话"
        )
        
        private val validRange = 1..6
        
        fun fromInt(value: Int): Result<Rarity> {
            return if (value in validRange) {
                Result.success(Rarity(value))
            } else {
                Result.failure(IllegalArgumentException("Invalid rarity: $value, must be in $validRange"))
            }
        }
        
        fun unsafe(value: Int): Rarity = Rarity(value.coerceIn(validRange))
        
        fun isValid(value: Int): Boolean = value in validRange
        
        fun all(): List<Rarity> = validRange.map { Rarity(it) }
    }
    
    val color: String get() = colorMap[value] ?: "#9E9E9E"
    val name: String get() = nameMap[value] ?: "未知"
    val isCommon: Boolean get() = value == 1
    val isUncommon: Boolean get() = value == 2
    val isRare: Boolean get() = value == 3
    val isEpic: Boolean get() = value == 4
    val isLegendary: Boolean get() = value == 5
    val isMythic: Boolean get() = value == 6
    val isAtLeastRare: Boolean get() = value >= 3
    val isAtLeastEpic: Boolean get() = value >= 4
    val isAtLeastLegendary: Boolean get() = value >= 5
    
    override fun compareTo(other: Rarity): Int = value.compareTo(other.value)
    
    operator fun plus(delta: Int): Rarity = unsafe(value + delta)
    operator fun minus(delta: Int): Rarity = unsafe(value - delta)
    
    operator fun rangeTo(other: Rarity): ClosedRange<Rarity> = RarityRange(this, other)
    
    infix fun isBetterThan(other: Rarity): Boolean = value > other.value
    infix fun isWorseThan(other: Rarity): Boolean = value < other.value
    
    fun toInt(): Int = value
    
    override fun toString(): String = "Rarity($value, '$name')"
}

class RarityRange(
    override val start: Rarity,
    override val endInclusive: Rarity
) : ClosedRange<Rarity> {
    fun toList(): List<Rarity> = (start.value..endInclusive.value).map { Rarity.unsafe(it) }
    
    fun contains(value: Int): Boolean = value in start.value..endInclusive.value
}

fun Int.toRarity(): Result<Rarity> = Rarity.fromInt(this)

fun Int.toRarityOrCommon(): Rarity = Rarity.unsafe(this)
