package com.xianxia.sect.core.model

import androidx.annotation.Keep
import com.xianxia.sect.core.config.FavorConfig
import kotlinx.serialization.Serializable

/**
 * 宗门关系 — 记录两个宗门之间的好感度数据。
 *
 * sectId1 和 sectId2 按字典序排序（minOf / maxOf），确保同一条关系有唯一表示。
 */
@Keep
@Serializable
data class SectRelation(
    val sectId1: String,
    val sectId2: String,
    var favor: Int = FavorConfig.INITIAL_FAVOR,
    var lastInteractionYear: Int = 0,
    var noGiftYears: Int = 0,
    var acquainted: Boolean = false
)
