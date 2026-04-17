package com.xianxia.sect.core.model.production

import kotlinx.serialization.Serializable

@Serializable
data class GameTime(
    val year: Int,
    val month: Int
)
