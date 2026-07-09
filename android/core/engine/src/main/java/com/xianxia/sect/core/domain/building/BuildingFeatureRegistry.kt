package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.production.BuildingType
import java.util.concurrent.ConcurrentHashMap

/**
 * 建筑特征注册表：BuildingFeature 的统一查询入口。
 *
 * 三索引（key/displayName/buildingType）均注册到 ConcurrentHashMap，
 * 线程安全且 O(1) 查询。
 *
 * 注册时机：在 XianxiaApplication.onCreate() 中通过 [registerDefaults] 扩展函数完成。
 * 参考实现见 `feature/game/.../BuildingFeatureBoot.kt`。
 */
object BuildingFeatureRegistry {
    private val byKey = ConcurrentHashMap<String, BuildingFeature>()
    private val byDisplayName = ConcurrentHashMap<String, BuildingFeature>()
    private val byBuildingType = ConcurrentHashMap<BuildingType, BuildingFeature>()

    fun register(feature: BuildingFeature) {
        byKey[feature.key] = feature
        byDisplayName[feature.displayName] = feature
        byBuildingType[feature.buildingType] = feature
    }

    fun findByKey(key: String): BuildingFeature? = byKey[key]
    fun findByDisplayName(name: String): BuildingFeature? = byDisplayName[name]
    fun findByBuildingType(type: BuildingType): BuildingFeature? = byBuildingType[type]

    val all: Collection<BuildingFeature> get() = byKey.values
    val constructible: List<BuildingFeature> get() = byKey.values.filter { it.isConstructible }

    fun countByType(data: GameData, type: BuildingType): Int =
        data.placedBuildings.count { findByDisplayName(it.displayName)?.buildingType == type }

    fun residenceBonus(name: String): String =
        findByDisplayName(name)?.residenceSpeedBonus ?: ""

    /** 住所修炼速度倍率（从 [residenceSpeedBonus] 字符串解析，如 "修炼速度+20%" → 1.20） */
    fun residenceSpeedMultiplier(name: String): Double {
        val bonus = findByDisplayName(name)?.residenceSpeedBonus ?: return 1.0
        val pct = bonus.substringAfter("+").substringBefore("%").toDoubleOrNull() ?: return 1.0
        return 1.0 + pct / 100.0
    }

    fun isResidence(name: String): Boolean =
        findByDisplayName(name)?.isResidence ?: false

    fun hasNoLimit(name: String): Boolean =
        findByDisplayName(name)?.unlimitedBuild ?: false
}
