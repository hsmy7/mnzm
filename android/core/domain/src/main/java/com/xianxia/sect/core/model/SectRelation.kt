package com.xianxia.sect.core.model

import androidx.annotation.Keep
import com.xianxia.sect.core.config.FavorConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * 宗门关系 — 记录两个宗门之间的好感度数据。
 *
 * sectId1 和 sectId2 按字典序排序（minOf / maxOf），确保同一条关系有唯一表示。
 */
@Keep
@Serializable
data class SectRelation(
    @ProtoNumber(1) val sectId1: String,
    @ProtoNumber(2) val sectId2: String,
    @ProtoNumber(3) var favor: Int = FavorConfig.INITIAL_FAVOR,
    @ProtoNumber(4) var lastInteractionYear: Int = 0,
    @ProtoNumber(5) var noGiftYears: Int = 0,
    @ProtoNumber(6) var acquainted: Boolean = false
)
