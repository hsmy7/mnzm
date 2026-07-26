package com.xianxia.sect.ui.game

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.DiscipleStatus
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

internal fun List<DiscipleAggregate>.applyFilters(
    realmFilter: Set<Int>,
    spiritRootFilter: Set<Int>,
    attributeSort: String?,
    defaultSortAttribute: String? = null
): List<DiscipleAggregate> {
    val hasAnyFilter = attributeSort != null || realmFilter.isNotEmpty() || spiritRootFilter.isNotEmpty()

    val sorted = if (attributeSort != null) {
        sortedWith(
            compareByDescending<DiscipleAggregate> { it.getAttributeValue(attributeSort) }
                .thenBy { it.realm }
                .thenByDescending { it.realmLayer }
                .thenBy { it.getSpiritRootCount() }
        )
    } else if (hasAnyFilter) {
        sortedWith(
            compareBy<DiscipleAggregate> { it.realm }
                .thenByDescending { it.realmLayer }
                .thenBy { it.getSpiritRootCount() }
        )
    } else {
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

/**
 * 根据"显示所有可用弟子"开关过滤弟子列表：
 * - 勾选时：排除 [ON_MISSION]（任务中）及 [battleAndExplorationIds]
 *   中的弟子（探索/战斗中），其余状态均显示（含血炼中、思过中等）
 * - 不勾选时：仅显示 [IDLE]（空闲中），同时排除 [battleAndExplorationIds] 中的弟子
 * [additionalCheck] 用于叠加其他过滤条件（如 realmLayer、年龄、弟子类型等）
 *
 * 当 showAllEnabled=true 时血炼中（REFINING）和思过中（REFLECTING）弟子可见，
 * 选择后触发对应的特殊行为（血炼失败/释放思过），见 DisciplesTab 的 onClick 逻辑。
 */
internal fun List<DiscipleAggregate>.filterByDiscipleStatus(
    showAllEnabled: Boolean,
    battleAndExplorationIds: Set<String> = emptySet(),
    additionalCheck: (DiscipleAggregate) -> Boolean = { true }
): List<DiscipleAggregate> {
    return filter { d ->
        val statusOk = if (showAllEnabled) {
            d.status != DiscipleStatus.ON_MISSION
            && d.id !in battleAndExplorationIds
        } else {
            d.status == DiscipleStatus.IDLE
            && d.id !in battleAndExplorationIds
        }
        statusOk && d.isAlive && additionalCheck(d)
    }
}
