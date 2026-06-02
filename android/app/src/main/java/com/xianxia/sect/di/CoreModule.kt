package com.xianxia.sect.di

import com.xianxia.sect.core.engine.system.*
import com.xianxia.sect.core.engine.service.*
import com.xianxia.sect.core.engine.domain.production.ProductionSubsystem
import com.xianxia.sect.core.engine.domain.production.EconomySubsystem
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
        cultivationTickSystem: CultivationTickSystem,
        inventorySystem: InventorySystem,
        explorationTickSystem: ExplorationTickSystem,
        mailSystem: MailSystem,
        partnerSystem: PartnerSystem,
        childBirthSystem: ChildBirthSystem,
        productionSubsystem: ProductionSubsystem,
        economySubsystem: EconomySubsystem
    ): SystemManager = SystemManager(
        setOf(
            timeSystem,
            cultivationTickSystem,
            inventorySystem,
            explorationTickSystem,
            mailSystem,
            partnerSystem,
            childBirthSystem,
            productionSubsystem,
            economySubsystem
        )
    )

    @Provides
    @Singleton
    fun provideDiscipleFacade(impl: com.xianxia.sect.core.engine.domain.disciple.DiscipleFacadeImpl): com.xianxia.sect.core.engine.domain.disciple.DiscipleFacade = impl

    @Provides
    @Singleton
    fun provideBattleFacade(impl: com.xianxia.sect.core.engine.domain.battle.BattleFacadeImpl): com.xianxia.sect.core.engine.domain.battle.BattleFacade = impl

    @Provides
    @Singleton
    fun provideBuildingFacade(impl: com.xianxia.sect.core.engine.domain.building.BuildingFacadeImpl): com.xianxia.sect.core.engine.domain.building.BuildingFacade = impl

    @Provides
    @Singleton
    fun provideInventoryFacade(impl: com.xianxia.sect.core.engine.domain.inventory.InventoryFacadeImpl): com.xianxia.sect.core.engine.domain.inventory.InventoryFacade = impl

    @Provides
    @Singleton
    fun provideDiplomacyFacade(impl: com.xianxia.sect.core.engine.domain.diplomacy.DiplomacyFacadeImpl): com.xianxia.sect.core.engine.domain.diplomacy.DiplomacyFacade = impl

    @Provides
    @Singleton
    fun provideProductionFacade(impl: com.xianxia.sect.core.engine.domain.production.ProductionFacadeImpl): com.xianxia.sect.core.engine.domain.production.ProductionFacade = impl

    @Provides
    @Singleton
    fun provideSaveFacade(impl: com.xianxia.sect.core.engine.domain.save.SaveFacadeImpl): com.xianxia.sect.core.engine.domain.save.SaveFacade = impl
}
