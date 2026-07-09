package com.xianxia.sect.ui.game.building

import androidx.compose.ui.graphics.Color
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry

@Deprecated("Use BuildingFeatureRegistry instead", ReplaceWith("BuildingFeatureRegistry"))
enum class BuildingDef(
    val key: String,
    val displayName: String,
    val drawableRes: Int,
    val color: Color,
    val noLimit: Boolean = false
) {
    SPIRIT_MINE("mining", "灵矿场", R.drawable.building_spirit_mine, Color(0xFFBCAAA4), noLimit = true),
    HERB_GARDEN("herb_garden", "灵植阁", R.drawable.building_herb_garden, Color(0xFFA5D6A7), noLimit = true),
    SPIRIT_FIELD("spirit_field", "灵田", R.drawable.building_spirit_field, Color(0xFFC8E6C9), noLimit = true),
    ALCHEMY("alchemy", "炼丹炉", R.drawable.building_alchemy, Color(0xFFEF9A9A), noLimit = true),
    FORGE("forge", "锻造坊", R.drawable.building_forge, Color(0xFFB0BEC5), noLimit = true),
    WAREHOUSE("warehouse", "仓库", R.drawable.building_warehouse, Color(0xFFFFCC80), noLimit = true),
    LIBRARY("library", "藏经阁", R.drawable.building_library, Color(0xFF80CBC4)),
    WEN_DAO_PEAK("wen_dao_peak", "问道塔", R.drawable.building_wen_dao_peak, Color(0xFFFFAB91)),
    QINGYUN_PEAK("qingyun_peak", "青云塔", R.drawable.building_qingyun_peak, Color(0xFF9FA8DA)),
    TIANSHU_HALL("tianshu_hall", "天枢殿", R.drawable.building_tianshu_hall, Color(0xFFFFF176)),
    LAW_ENFORCEMENT("law_enforcement_hall", "执法堂", R.drawable.building_law_enforcement, Color(0xFFCE93D8)),
    MISSION_HALL("mission_hall", "任务阁", R.drawable.building_mission_hall, Color(0xFF90CAF9)),
    PATROL_TOWER("patrol_tower", "巡视楼", R.drawable.building_patrol_tower, Color(0xFF795548), noLimit = true),
    REFLECTION_CLIFF("reflection_cliff", "监牢", R.drawable.building_reflection_cliff, Color(0xFFBDBDBD)),
    SINGLE_RESIDENCE("single_residence", "单人住所", R.drawable.building_single_residence, Color(0xFFEEEEEE), noLimit = true),
    SINGLE_RESIDENCE_UPGRADED("single_residence_upgraded", "中级单人住所", R.drawable.building_single_residence_upgraded, Color(0xFFEEEEEE), noLimit = true),
    MULTI_RESIDENCE("multi_residence", "多人住所", R.drawable.building_multi_residence, Color(0xFFEEEEEE), noLimit = true),
    BLOOD_REFINING_POOL("blood_refining_pool", "血炼池", R.drawable.blood_refining_pool, Color(0xFFB71C1C), noLimit = true);

    val isResidence: Boolean get() = BuildingFeatureRegistry.isResidence(displayName)
}

object BuildingRegistry {
    val ALL get() = BuildingFeatureRegistry.all
    val byName get() = BuildingFeatureRegistry.all.associateBy { it.displayName }
    val names get() = BuildingFeatureRegistry.all.map { it.displayName }
    val constructible get() = BuildingFeatureRegistry.constructible

    fun findByDisplayName(name: String): com.xianxia.sect.core.engine.domain.building.BuildingFeature? =
        BuildingFeatureRegistry.findByDisplayName(name)
    fun drawableRes(name: String): Int =
        BuildingFeatureRegistry.findByDisplayName(name)?.drawableRes ?: R.drawable.building_alchemy
    fun color(name: String): Color =
        BuildingFeatureRegistry.findByDisplayName(name)?.let { Color(it.color) } ?: Color(0xFFEEEEEE)
    fun hasNoLimit(name: String): Boolean = BuildingFeatureRegistry.hasNoLimit(name)
    fun isResidence(name: String): Boolean = BuildingFeatureRegistry.isResidence(name)
    val residenceNames get() = ALL.filter { it.isResidence }.map { it.displayName }
    val noLimitNames get() = ALL.filter { it.unlimitedBuild }.map { it.displayName }

    /** 返回所有建筑的 displayName → drawableRes 映射，供 SpriteResRegistry 注册 */
    fun allDrawableMap(): Map<String, Int> =
        ALL.associate { it.displayName to it.drawableRes }
}
