package com.xianxia.sect.core.model

import androidx.annotation.Keep
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoPacked

@Keep
@Serializable
data class PatrolSlot(
    @ProtoNumber(1) val index: Int = 0,
    @ProtoNumber(2) val discipleId: String = "",
    @ProtoNumber(3) val discipleName: String = "",
    @ProtoNumber(4) val discipleRealm: String = "",
    @ProtoNumber(5) val portraitRes: String = "",
    /**
     * 所属巡视楼建筑实例 ID。
     *
     * 用于建筑移除时按实例精确匹配槽位，替代旧的 `dropLast(8)` 按位置截断模式。
     * 旧存档加载时为空字符串，由 `migratePatrolSlotsIfNeeded` 按建筑顺序回填。
     */
    @ProtoNumber(6) val buildingInstanceId: String = ""
) {
    val isActive: Boolean get() = discipleId.isNotEmpty()
}

@Keep
@Serializable
data class PatrolConfig(
    @ProtoPacked @ProtoNumber(1) val targetRealms: Set<Int> = setOf(9),
    @ProtoNumber(2) val maxBeastCount: Int = 1,
    @ProtoNumber(3) val requireFullStatus: Boolean = true
)
