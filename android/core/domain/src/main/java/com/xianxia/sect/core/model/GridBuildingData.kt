package com.xianxia.sect.core.model

import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.Serializable

@Immutable
@Keep
@Serializable
data class GridBuildingData(
    @ProtoNumber(1) val buildingId: String = "",
    @ProtoNumber(2) val displayName: String = "",
    @ProtoNumber(3) val gridX: Int = 0,
    @ProtoNumber(4) val gridY: Int = 0,
    @ProtoNumber(5) val width: Int = 2,
    @ProtoNumber(6) val height: Int = 2,
    @ProtoNumber(7) val instanceId: String = "",
    @ProtoNumber(8) val sectId: String = ""
) {
    fun withInstanceId(): GridBuildingData =
        if (instanceId.isNotBlank()) this else copy(instanceId = java.util.UUID.randomUUID().toString())

    companion object {
        fun ensureAllHaveInstanceId(buildings: List<GridBuildingData>): List<GridBuildingData> =
            buildings.map { it.withInstanceId() }
    }
}
