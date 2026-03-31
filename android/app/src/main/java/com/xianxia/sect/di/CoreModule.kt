package com.xianxia.sect.di

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.GameEngineAdapter
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.subsystem.*
import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.core.performance.GamePerformanceMonitor
import com.xianxia.sect.core.state.UnifiedGameStateManager
import com.xianxia.sect.core.state.StateChangeRequestBus
import com.xianxia.sect.data.facade.RefactoredStorageFacade
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoreModule {
    
    @Provides
    @Singleton
    fun provideEventBus(): EventBus = EventBus()
    
    @Provides
    @Singleton
    fun provideUnifiedGameStateManager(eventBus: EventBus): UnifiedGameStateManager =
        UnifiedGameStateManager(eventBus)
    
    @Provides
    @Singleton
    fun provideStateChangeRequestBus(
        unifiedStateManager: UnifiedGameStateManager,
        eventBus: EventBus
    ): StateChangeRequestBus = StateChangeRequestBus(unifiedStateManager, eventBus)
    
    @Provides
    @Singleton
    fun provideCultivationSubsystem(
        eventBus: EventBus,
        stateManager: UnifiedGameStateManager
    ): CultivationSubsystem = CultivationSubsystem(eventBus, stateManager)
    
    @Provides
    @Singleton
    fun provideTimeSubsystem(
        eventBus: EventBus,
        stateManager: UnifiedGameStateManager
    ): TimeSubsystem = TimeSubsystem(eventBus, stateManager)
    
    @Provides
    @Singleton
    fun provideDiscipleLifecycleSubsystem(
        eventBus: EventBus,
        stateManager: UnifiedGameStateManager
    ): DiscipleLifecycleSubsystem = DiscipleLifecycleSubsystem(eventBus, stateManager)
    
    @Provides
    @Singleton
    fun provideAlchemySubsystem(
        eventBus: EventBus,
        stateManager: UnifiedGameStateManager
    ): AlchemySubsystem = AlchemySubsystem(eventBus, stateManager)
    
    @Provides
    @Singleton
    fun provideForgingSubsystem(
        eventBus: EventBus,
        stateManager: UnifiedGameStateManager
    ): ForgingSubsystem = ForgingSubsystem(eventBus, stateManager)
    
    @Provides
    @IntoSet
    @Singleton
    fun provideCultivationSubsystemIntoSet(
        subsystem: CultivationSubsystem
    ): GameSubsystem = subsystem
    
    @Provides
    @IntoSet
    @Singleton
    fun provideTimeSubsystemIntoSet(
        subsystem: TimeSubsystem
    ): GameSubsystem = subsystem
    
    @Provides
    @IntoSet
    @Singleton
    fun provideDiscipleLifecycleSubsystemIntoSet(
        subsystem: DiscipleLifecycleSubsystem
    ): GameSubsystem = subsystem
    
    @Provides
    @IntoSet
    @Singleton
    fun provideAlchemySubsystemIntoSet(
        subsystem: AlchemySubsystem
    ): GameSubsystem = subsystem
    
    @Provides
    @IntoSet
    @Singleton
    fun provideForgingSubsystemIntoSet(
        subsystem: ForgingSubsystem
    ): GameSubsystem = subsystem
    
    @Provides
    @Singleton
    fun provideGameEngineCore(
        stateManager: UnifiedGameStateManager,
        eventBus: EventBus,
        performanceMonitor: GamePerformanceMonitor,
        gameEngine: GameEngine,
        storageFacade: RefactoredStorageFacade,
        subsystems: Set<@JvmSuppressWildcards GameSubsystem>
    ): GameEngineCore = GameEngineCore(
        stateManager,
        eventBus,
        performanceMonitor,
        gameEngine,
        storageFacade,
        subsystems
    )
    
    @Provides
    @Singleton
    fun provideGameEngineAdapter(
        gameEngine: GameEngine,
        stateManager: UnifiedGameStateManager,
        eventBus: EventBus
    ): GameEngineAdapter = GameEngineAdapter(gameEngine, stateManager, eventBus)
    
    @Provides
    @Singleton
    fun provideGamePerformanceMonitor(): GamePerformanceMonitor {
        val monitor = GamePerformanceMonitor()
        monitor.start()
        return monitor
    }
}
