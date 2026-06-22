package com.xianxia.sect.core.model

import androidx.annotation.Keep
import kotlinx.serialization.Serializable

@Keep
@Serializable
data class PatrolSlot(
    val index: Int = 0,
    val discipleId: String = "",
    val discipleName: String = "",
    val discipleRealm: String = "",
    val portraitRes: String = "",
    /**
     * 所属巡视楼建筑实例 ID。
     *
     * 用于建筑移除时按实例精确匹配槽位，替代旧的 `dropLast(8)` 按位置截断模式。
     * 旧存档加载时为空字符串，由 `migratePatrolSlotsIfNeeded` 按建筑顺序回填。
     */
    val buildingInstanceId: String = ""
) {
    val isActive: Boolean get() = discipleId.isNotEmpty()
}

@Keep
@Serializable
data class PatrolConfig(
    val targetRealms: Set<Int> = setOf(9),
    val maxBeastCount: Int = 1,
    val requireFullStatus: Boolean = true
)
