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
    AttributeFilterOption("aptitude", "资质"),
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
    "aptitude" -> aptitude
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
 * 长老/执事等岗位候选弟子的硬性条件过滤（不含状态过滤）：
 * 存活 + 达最小年龄 + 已入修炼（realmLayer > 0）。
 * 状态过滤（空闲中/显示所有弟子）由调用方对话框统一委托 [filterByDiscipleStatus]，
 * 此处不做 status 过滤——预过滤 status == IDLE 会导致对话框"显示所有弟子"勾选失效
 * （回归：问道塔/青云峰传道长老选择界面）。
 */
internal fun List<DiscipleAggregate>.eligibleElderCandidates(): List<DiscipleAggregate> =
    filter { it.isAlive && it.age >= GameConfig.Disciple.MIN_AGE && it.realmLayer > 0 }

/**
 * 根据"显示所有可用弟子"开关过滤弟子列表：
 * - 勾选时：排除 [ON_MISSION]（任务中）、[IN_TEAM]（队伍中）、[SECRET_REALM]（远古秘境中）
 *   及 [battleAndExplorationIds] 中的弟子（探索/战斗中），其余状态均显示（含血炼中、思过中等）
 * - 不勾选时：仅显示 [IDLE]（空闲中），同时排除 [battleAndExplorationIds] 中的弟子
 * [additionalCheck] 用于叠加其他过滤条件（如 realmLayer、年龄、弟子类型等）
 *
 * 当 showAllEnabled=true 时血炼中（REFINING）和思过中（REFLECTING）弟子可见，
 * 选择后触发对应的特殊行为（血炼失败/释放思过），见 DisciplesTab 的 onClick 逻辑。
 *
 * SECRET_REALM 单独排除：秘境成员由推导系统标记为 SECRET_REALM（不再并入 IN_TEAM），
 * 若漏排除则会在"显示所有"弹窗中变成可选中（可被其他系统误分配，回归）。
 * WAREHOUSE_GARRISON 不排除（与 GARRISONING 现状一致：showAll 可见可选中，选中后走正常卸任）。
 */
internal fun List<DiscipleAggregate>.filterByDiscipleStatus(
    showAllEnabled: Boolean,
    battleAndExplorationIds: Set<String> = emptySet(),
    additionalCheck: (DiscipleAggregate) -> Boolean = { true }
): List<DiscipleAggregate> {
    return filter { d ->
        val statusOk = if (showAllEnabled) {
            d.status != DiscipleStatus.ON_MISSION
            && d.status != DiscipleStatus.IN_TEAM
            && d.status != DiscipleStatus.SECRET_REALM
            && d.id !in battleAndExplorationIds
        } else {
            d.status == DiscipleStatus.IDLE
            && d.id !in battleAndExplorationIds
        }
        statusOk && d.isAlive && additionalCheck(d)
    }
}
