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
    val portraitRes: String = ""
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
