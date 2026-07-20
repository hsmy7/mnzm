package com.xianxia.sect.ui.game.delegate

import androidx.compose.runtime.mutableStateListOf
import com.xianxia.sect.core.engine.*
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.ui.game.DiscipleDetailRequest
import com.xianxia.sect.ui.game.TopOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class OverlayDelegate(
    private val gameEngine: GameEngine,
    private val scope: CoroutineScope
) {

    private val _overlayOrder = mutableStateListOf<TopOverlay>()
    val overlayOrder: List<TopOverlay> get() = _overlayOrder

    fun pushOverlay(overlay: TopOverlay) {
        _overlayOrder.remove(overlay)
        _overlayOrder.add(overlay)
    }

    fun popOverlay(overlay: TopOverlay) {
        _overlayOrder.remove(overlay)
    }

    private val _detailDisciple = MutableStateFlow<DiscipleDetailRequest?>(null)
    val detailDisciple: StateFlow<DiscipleDetailRequest?> = _detailDisciple.asStateFlow()

    fun showDiscipleDetail(request: DiscipleDetailRequest) {
        _detailDisciple.value = request
        gameEngine.setFocusedDiscipleId(request.disciple.id)
        pushOverlay(TopOverlay.DISCIPLE_DETAIL)
    }

    fun dismissDiscipleDetail() {
        _detailDisciple.value = null
        gameEngine.setFocusedDiscipleId(null)
        popOverlay(TopOverlay.DISCIPLE_DETAIL)
    }

    fun navigateDiscipleDetail(disciple: DiscipleAggregate) {
        val current = _detailDisciple.value ?: return
        val target = current.allDisciples.find { it.id == disciple.id } ?: disciple
        _detailDisciple.update { it?.copy(disciple = target) }
    }
}
