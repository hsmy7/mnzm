package com.xianxia.sect.core.util

import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.model.SectRelation
import com.xianxia.sect.core.model.Alliance
import java.util.Locale
import kotlin.math.ceil
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
