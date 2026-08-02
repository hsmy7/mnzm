package com.xianxia.sect.core.model

import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import com.xianxia.sect.core.GameConfig
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoPacked
import com.xianxia.sect.core.model.production.ProductionSlot
import com.xianxia.sect.core.util.TimeProgressUtil

// GameDataFieldModels.kt — 灵田种植（P-2 从 GameData.kt 拆分，同包模型，序列化字段不变）

// 种植槽位数据
@Keep
@Serializable
data class PlantSlotData(
    @ProtoNumber(1) val index: Int = 0,
    @ProtoNumber(2) val status: String = "idle",
    @ProtoNumber(3) val seedId: String = "",
    @ProtoNumber(4) val seedName: String = "",
    @ProtoNumber(5) val startYear: Int = 0,
    @ProtoNumber(6) val startMonth: Int = 0,
    @ProtoNumber(7) val growTime: Int = 0,
    @ProtoNumber(8) val expectedYield: Int = 0
) {
    val isGrowing: Boolean get() = status == "growing"
    val isIdle: Boolean get() = status == "idle"

    fun isFinished(currentYear: Int, currentMonth: Int): Boolean {
        if (status != "growing") return status == "mature"
        return TimeProgressUtil.isTimeElapsed(startYear, startMonth, growTime, currentYear, currentMonth)
    }

    fun remainingTime(currentYear: Int, currentMonth: Int): Int {
        if (status != "growing") return 0
        return TimeProgressUtil.calculateRemainingMonths(startYear, startMonth, growTime, currentYear, currentMonth)
    }

    companion object {
        const val MAX_AI_DISCIPLES_PER_SECT = 1000
    }
}

// 灵田种植数据
@Keep
@Serializable
data class SpiritFieldPlant(
    @ProtoNumber(1) val buildingInstanceId: String,
    @ProtoNumber(2) val seedId: String = "",
    @ProtoNumber(3) val seedName: String = "",
    @ProtoNumber(4) val growTime: Int = 0,
    @ProtoNumber(5) val expectedYield: Int = 0,
    @ProtoNumber(6) val plantYear: Int = 0,
    @ProtoNumber(7) val plantMonth: Int = 0,
    @ProtoNumber(8) val sectId: String = "",
    @ProtoNumber(9) val completionMonth: Int = 0,
    @ProtoNumber(10) val completionPhase: Int = 1
)
