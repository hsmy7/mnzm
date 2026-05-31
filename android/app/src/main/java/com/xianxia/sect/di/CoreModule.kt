package com.xianxia.sect.di

import com.xianxia.sect.core.engine.system.*
import com.xianxia.sect.core.engine.service.*
import com.xianxia.sect.core.engine.subsystem.*
import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.core.util.BackgroundTaskScheduler
import com.xianxia.sect.di.ApplicationScopeProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoreModule {

    @Provides
    @Singleton
    fun provideBackgroundTaskScheduler(scopeProvider: ApplicationScopeProvider): BackgroundTaskScheduler =
        BackgroundTaskScheduler(scopeProvider.scope)

    @Provides
    @Singleton
    fun provideEventBusPort(eventBus: EventBus): EventBusPort = eventBus

    @Provides
    @Singleton
    fun provideSystemManager(
        timeSystem: TimeSystem,
        cultivationService: CultivationService,
        discipleService: DiscipleService,
        inventorySystem: InventorySystem,
        combatService: CombatService,
        explorationService: ExplorationService,
        buildingService: BuildingService,
        diplomacyService: DiplomacyService,
        saveService: SaveService,
        formulaService: FormulaService,
        redeemCodeService: RedeemCodeService,
        partnerSystem: PartnerSystem,
        childBirthSystem: ChildBirthSystem,
        buildingSubsystem: BuildingSubsystem,
        productionSubsystem: ProductionSubsystem,
        economySubsystem: EconomySubsystem
    ): SystemManager = SystemManager(
        setOf(
            timeSystem,
            cultivationService,
            discipleService,
            inventorySystem,
            combatService,
            explorationService,
            buildingService,
            diplomacyService,
            saveService,
            formulaService,
            redeemCodeService,
            partnerSystem,
            childBirthSystem,
            buildingSubsystem,
            productionSubsystem,
            economySubsystem
        )
    )
}
