package com.xianxia.sect.ui.game

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.util.isFollowed

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

/**
 * 权威境界筛选选项，基于 GameConfig.Realm.CONFIGS（0-9 共 10 项，含仙人/炼虚）。
 * 数值越小境界越高，与 Disciple.realm 字段一致。
 */
internal val REALM_FILTER_OPTIONS: List<Pair<Int, String>> =
    (0..9).map { it to GameConfig.Realm.getName(it) }

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

/**
 * 统一筛选 + 排序算法。
 *
 * 排序规则：
 * - 有属性排序键（attributeSort != null）：属性降序 → 境界升序（高境界在前）→ realmLayer 降序 → 灵根数升序
 * - 无属性排序键但有其他筛选（境界/灵根）：境界升序 → realmLayer 降序 → 灵根数升序
 * - 无任何筛选：已关注优先 → 若有 defaultSortAttribute 则按推荐属性降序 → 境界升序 → realmLayer 降序；否则已关注优先 → 境界升序 → realmLayer 降序
 *
 * 注：已关注优先仅在"无任何筛选"时生效；一旦玩家触发任何筛选（属性/境界/灵根），已关注不再参与排序。
 */
internal fun List<DiscipleAggregate>.applyFilters(
    realmFilter: Set<Int>,
    spiritRootFilter: Set<Int>,
    attributeSort: String?,
    defaultSortAttribute: String? = null
): List<DiscipleAggregate> {
    val hasAnyFilter = attributeSort != null || realmFilter.isNotEmpty() || spiritRootFilter.isNotEmpty()

    val sorted = if (attributeSort != null) {
        // 有属性排序键：属性降序 → 境界升序 → realmLayer 降序 → 灵根数升序
        sortedWith(
            compareByDescending<DiscipleAggregate> { it.getAttributeValue(attributeSort) }
                .thenBy { it.realm }
                .thenByDescending { it.realmLayer }
                .thenBy { it.getSpiritRootCount() }
        )
    } else if (hasAnyFilter) {
        // 无属性键但有境界/灵根筛选：境界升序 → realmLayer 降序 → 灵根数升序
        sortedWith(
            compareBy<DiscipleAggregate> { it.realm }
                .thenByDescending { it.realmLayer }
                .thenBy { it.getSpiritRootCount() }
        )
    } else {
        // 无任何筛选：已关注优先 → 推荐属性（若有）→ 境界升序 → realmLayer 降序
        if (defaultSortAttribute != null) {
            sortedWith(
                compareByDescending<DiscipleAggregate> { it.isFollowed }
                    .thenByDescending { it.getAttributeValue(defaultSortAttribute) }
                    .thenBy { it.realm }
                    .thenByDescending { it.realmLayer }
            )
        } else {
            sortedWith(
                compareByDescending<DiscipleAggregate> { it.isFollowed }
                    .thenBy { it.realm }
                    .thenByDescending { it.realmLayer }
            )
        }
    }

    var result = sorted
    if (realmFilter.isNotEmpty()) {
        result = result.filter { it.realm in realmFilter }
    }
    if (spiritRootFilter.isNotEmpty()) {
        result = result.filter { it.getSpiritRootCount() in spiritRootFilter }
    }
    return result
}
