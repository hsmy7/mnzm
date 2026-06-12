package com.xianxia.sect.ui.game

import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.util.isFollowed
import com.xianxia.sect.core.util.sortedByFollowAttributeAndRealm

internal data class AttributeFilterOption(
    val key: String,
    val name: String
)

internal val SPIRIT_ROOT_FILTER_OPTIONS = listOf(
    1 to "单灵根",
    2 to "双灵根",
    3 to "三灵根",
    4 to "四灵根",
    5 to "五灵根"
)

internal val ATTRIBUTE_FILTER_OPTIONS = listOf(
    AttributeFilterOption("comprehension", "悟性"),
    AttributeFilterOption("intelligence", "智力"),
    AttributeFilterOption("charm", "魅力"),
    AttributeFilterOption("loyalty", "忠诚"),
    AttributeFilterOption("artifactRefining", "炼器"),
    AttributeFilterOption("pillRefining", "炼丹"),
    AttributeFilterOption("spiritPlanting", "灵植"),
    AttributeFilterOption("mining", "采矿"),
    AttributeFilterOption("teaching", "传道"),
    AttributeFilterOption("morality", "道德")
)

internal fun DiscipleAggregate.getAttributeValue(key: String): Int = when (key) {
    "comprehension" -> comprehension
    "intelligence" -> intelligence
    "charm" -> charm
    "loyalty" -> loyalty
    "artifactRefining" -> artifactRefining
    "pillRefining" -> pillRefining
    "spiritPlanting" -> spiritPlanting
    "mining" -> mining
    "teaching" -> teaching
    "morality" -> morality
    else -> 0
}

internal fun DiscipleAggregate.getSpiritRootCount(): Int = spiritRoot.types.size

internal fun List<DiscipleAggregate>.applyFilters(
    realmFilter: Set<Int>,
    spiritRootFilter: Set<Int>,
    attributeSort: String?,
    defaultSortAttribute: String? = null
): List<DiscipleAggregate> {
    val sorted = if (attributeSort != null) {
        sortedWith(
            compareByDescending<DiscipleAggregate> { it.isFollowed }
                .thenByDescending { it.getAttributeValue(attributeSort) }
                .thenBy { it.realm }
                .thenByDescending { it.realmLayer }
        )
    } else {
        sortedByFollowAttributeAndRealm(defaultSortAttribute)
    }
    val realmFiltered = if (realmFilter.isNotEmpty()) sorted.filter { it.realm in realmFilter } else sorted
    return if (spiritRootFilter.isNotEmpty()) {
        realmFiltered.filter { it.getSpiritRootCount() in spiritRootFilter }
            .sortedBy { it.getSpiritRootCount() }
    } else {
        realmFiltered
    }
}
