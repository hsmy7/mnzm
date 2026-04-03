package com.xianxia.sect.di

import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.engine.GameEngineCore
import com.xianxia.sect.core.engine.system.*
import com.xianxia.sect.core.engine.service.*
import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.core.performance.GamePerformanceMonitor
import com.xianxia.sect.core.state.UnifiedGameStateManager
import com.xianxia.sect.core.state.StateChangeRequestBus
import com.xianxia.sect.data.facade.RefactoredStorageFacade
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

    // ==================== System Manager ====================

    @Provides
    @Singleton
    fun provideSystemManager(
        sectSystem: SectSystem,
        discipleSystem: DiscipleSystem,
        inventorySystem: InventorySystem,
        explorationSystem: ExplorationSystem,
        buildingSubsystem: BuildingSubsystem,
        diplomacySubsystem: DiplomacySubsystem,
        merchantSubsystem: MerchantSubsystem,
        eventSubsystem: EventSubsystem,
        timeSystem: TimeSystem,
        cultivationSystem: CultivationSystem,
        alchemySubsystem: AlchemySubsystem,
        forgingSubsystem: ForgingSubsystem,
        herbGardenSubsystem: HerbGardenSubsystem,
        productionSubsystem: ProductionSubsystem
    ): SystemManager = SystemManager(
        sectSystem,
        discipleSystem,
        inventorySystem,
        explorationSystem,
        buildingSubsystem,
        diplomacySubsystem,
        merchantSubsystem,
        eventSubsystem,
        timeSystem,
        cultivationSystem,
        alchemySubsystem,
        forgingSubsystem,
        herbGardenSubsystem,
        productionSubsystem
    )

    // ==================== Service Layer (New - Post-Refactoring) ====================
    //
    // 注意：以下 8 个 Service 不再在此注册为 @Singleton
    // 原因：Service 构造函数需要 MutableStateFlow 引用，无法通过 DI 自动注入
    // 实际创建位置：GameEngine (@ViewModelScoped) 的 constructor 中直接创建
    // 生命周期：跟随 GameEngine 的 @ViewModelScoped 作用域
    //
    // 涉及的 Service:
    // - CultivationService, InventoryService, DiscipleService, CombatService
    // - ExplorationService, EventService, BuildingService, SaveService
    //
    // 如果未来需要将这些 Service 提供给其他组件，可以考虑：
    // 1. 通过 GameEngine 暴露 getter 方法
    // 2. 或使用 lazy 委托 + Provider 模式（需重构 Service 构造函数）

    // ==================== GameEngineCore ====================
    //
    // 注意：GameEngineCore 不再通过 @Provides 提供
    // 原因：它依赖 @ViewModelScoped 的 GameEngine，无法从 SingletonComponent 提供
    // 实际创建位置：GameViewModel 的 init 块中直接构建
    //
    @Provides
    @Singleton
    fun provideGamePerformanceMonitor(): GamePerformanceMonitor {
        val monitor = GamePerformanceMonitor()
        monitor.start()
        return monitor
    }
}
