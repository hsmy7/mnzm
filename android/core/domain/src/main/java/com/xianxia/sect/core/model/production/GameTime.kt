package com.xianxia.sect.core.model.production

import androidx.annotation.Keep
import kotlinx.serialization.Serializable

@Keep
@Serializable
data class GameTime(
    val year: Int,
    val month: Int
)
