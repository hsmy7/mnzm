package com.xianxia.sect.core.model

import kotlinx.serialization.Serializable

/**
 * 地图标记点类型
 */
@Serializable
enum class MapMarkerType {
    CITY,           // 城市
    SECT,           // 宗门
    RESOURCE,       // 资源点
    DUNGEON,        // 秘境
    WILDERNESS      // 荒野
}

/**
 * 宗门显示状态
 */
@Serializable
data class SectDisplayState(
    val isPlayerSect: Boolean = false,
    val isPlayerOccupied: Boolean = false,
    val occupierSectId: String? = null,
    val isRighteous: Boolean = true
)

/**
 * 地图标记点
 */
@Serializable
data class MapMarker(
    val id: String,
    val name: String,
    val type: MapMarkerType,
    val x: Float,       // 归一化坐标 (0-1)
    val y: Float,       // 归一化坐标 (0-1)
    val level: Int = 1, // 等级
    val ownerId: String? = null, // 所有者 ID
    val isCapital: Boolean = false, // 是否都城
    val description: String = "",
    val displayState: SectDisplayState? = null
)

/**
 * 地图路径
 */
@Serializable
data class MapPath(
    val fromId: String,
    val toId: String,
    val distance: Int = 1
)

/**
 * 世界地图
 */
@Serializable
data class WorldMap(
    val id: String = "main_map",
    val name: String = "大世界地图",
    val markers: List<MapMarker> = emptyList(),
    val paths: List<MapPath> = emptyList(),
    val width: Int = 4000,
    val height: Int = 3500
)
