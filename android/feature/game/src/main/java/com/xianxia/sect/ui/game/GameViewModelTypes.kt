package com.xianxia.sect.ui.game

import androidx.compose.runtime.Immutable
import com.xianxia.sect.core.model.DiscipleAggregate

@Immutable
data class DiscipleDetailRequest(
    val disciple: DiscipleAggregate,
    val allDisciples: List<DiscipleAggregate>,
    val onNavigateToDisciple: ((DiscipleAggregate) -> Unit)? = null
)

enum class TopOverlay {
    DISCIPLE_DETAIL,
    BATTLE_RESULT,
    BATTLE_LOG_DETAIL
}
