package com.xianxia.sect.core.util

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleAggregate

/**
 * 防守弟子排序：先按大境界（realm 升序 = 数值越小境界越高，仙人 0 在前、炼气 9 在后），
 * 同境界内按小层降序（层数高 = 修为更深优先）。
 *
 * 修复 2026-08-15 宗门防守战/妖兽防守战选人缺陷：原实现按 realmLayer（小层）降序，
 * 高境界弟子突破大境界后 layer 重置为 1，会被同池中低境界高 layer 弟子挤出防守队，
 * 导致"玩家高境界弟子不上场、被 AI 低境界弟子击败"。
 */
fun List<Disciple>.sortedByRealmForDefense(): List<Disciple> =
    this.sortedWith(compareBy<Disciple> { it.realm }.thenByDescending { it.realmLayer })

fun List<DiscipleAggregate>.sortedByFollowAndRealm(): List<DiscipleAggregate> {
    return this.sortedWith(compareByDescending<DiscipleAggregate> { it.isFollowed }
        .thenBy { it.realm }
        .thenByDescending { it.realmLayer })
}

fun List<DiscipleAggregate>.sortedByFollowAttributeAndRealm(attribute: String? = null): List<DiscipleAggregate> {
    return if (attribute != null) {
        this.sortedWith(compareByDescending<DiscipleAggregate> { it.isFollowed }
            .thenBy { it.realm }
            .thenByDescending { it.realmLayer }
            .thenByDescending { disciple ->
                when (attribute) {
                    "comprehension" -> disciple.comprehension
                    "intelligence" -> disciple.intelligence
                    "charm" -> disciple.charm
                    "loyalty" -> disciple.loyalty
                    "artifactRefining" -> disciple.artifactRefining
                    "pillRefining" -> disciple.pillRefining
                    "spiritPlanting" -> disciple.spiritPlanting
                    "mining" -> disciple.mining
                    "teaching" -> disciple.teaching
                    "morality" -> disciple.morality
                    else -> 0
                }
            })
    } else {
        this.sortedWith(compareByDescending<DiscipleAggregate> { it.isFollowed }
            .thenBy { it.realm }
            .thenByDescending { it.realmLayer }
            .thenByDescending { it.comprehension })
    }
}

val DiscipleAggregate.isFollowed: Boolean
    get() = statusData["followed"] == "true"
