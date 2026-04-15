package com.xianxia.sect.di

import com.xianxia.sect.core.engine.system.*
import com.xianxia.sect.core.engine.service.*
import com.xianxia.sect.core.engine.subsystem.*
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
    fun provideSystemManager(
        timeSystem: TimeSystem,
        cultivationService: CultivationService,
        discipleService: DiscipleService,
        inventorySystem: InventorySystem,
        combatService: CombatService,
        explorationService: ExplorationService,
        buildingService: BuildingService,
        diplomacyService: DiplomacyService,
        eventService: EventService,
        saveService: SaveService,
        formulaService: FormulaService,
        redeemCodeService: RedeemCodeService,
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
            eventService,
            saveService,
            formulaService,
            redeemCodeService,
            buildingSubsystem,
            productionSubsystem,
            economySubsystem
        )
    )
}
