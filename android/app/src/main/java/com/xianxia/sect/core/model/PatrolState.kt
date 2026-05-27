package com.xianxia.sect.core.model

import kotlinx.serialization.Serializable

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

@Serializable
data class PatrolConfig(
    val targetRealms: Set<Int> = setOf(9),
    val maxBeastCount: Int = 1,
    val requireFullStatus: Boolean = true
)
