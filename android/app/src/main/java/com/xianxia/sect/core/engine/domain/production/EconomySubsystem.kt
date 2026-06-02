package com.xianxia.sect.core.engine.domain.production

import android.util.Log
import com.xianxia.sect.core.engine.service.CultivationService
import com.xianxia.sect.core.engine.system.GameSystem
import com.xianxia.sect.core.engine.system.SystemPriority
import com.xianxia.sect.core.event.BuildingCompletedEvent
import com.xianxia.sect.core.event.DomainEvent
import com.xianxia.sect.core.event.DomainEventSubscriber
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.di.ApplicationScopeProvider
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@SystemPriority(order = 206)
@Singleton
class EconomySubsystem @Inject constructor(
    private val cultivationService: CultivationService,
    private val applicationScopeProvider: ApplicationScopeProvider,
    private val eventBus: EventBusPort
) : GameSystem, DomainEventSubscriber {

    companion object {
        private const val TAG = "EconomySubsystem"
        const val SYSTEM_NAME = "EconomySubsystem"
    }

    private val scope get() = applicationScopeProvider.scope

    override val systemName: String = SYSTEM_NAME

    override val subscribedTypes: Set<String> = setOf("building_completed")

    override fun onEvent(event: DomainEvent) {
        if (event !is BuildingCompletedEvent) return
        scope.launch {
            cultivationService.processSpiritMineProduction()
        }
    }

    override fun initialize() {
        eventBus.subscribe(this)
        Log.d(TAG, "EconomySubsystem initialized")
    }

    override fun release() {
        eventBus.unsubscribe(this)
        Log.d(TAG, "EconomySubsystem released")
    }

    override suspend fun clearForSlot(slotId: Int) {}

    override suspend fun onMonthTick(state: MutableGameState) {
        cultivationService.processPolicyCosts()
        cultivationService.processSalaryPayment(state.gameData.gameYear, state.gameData.gameMonth)
        cultivationService.processResidenceLoyalty()
    }
}
