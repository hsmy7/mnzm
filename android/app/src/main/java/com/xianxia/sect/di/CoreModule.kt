package com.xianxia.sect.di

import com.xianxia.sect.core.engine.system.*
import com.xianxia.sect.core.engine.service.*
import com.xianxia.sect.core.engine.domain.production.ProductionSubsystem
import com.xianxia.sect.core.engine.domain.production.EconomySubsystem
import com.xianxia.sect.core.event.EventBus
import com.xianxia.sect.core.event.EventBusPort
import com.xianxia.sect.di.MailRepositoryImpl
import com.xianxia.sect.di.SaveStorageImpl
import com.xianxia.sect.core.util.AnalyticsTracker
import com.xianxia.sect.core.util.BackgroundTaskScheduler
import com.xianxia.sect.core.util.CoroutineScopeProvider
import com.xianxia.sect.core.util.GCOptimizer
import com.xianxia.sect.core.util.GCOptimizerProvider
import com.xianxia.sect.core.util.HttpClientProvider
import com.xianxia.sect.core.util.MemoryMonitor
import com.xianxia.sect.core.util.MemoryMonitorProvider
import com.xianxia.sect.di.ApplicationScopeProvider
import com.xianxia.sect.network.SecureHttpClient
import com.xianxia.sect.taptap.TapDBManager
import kotlinx.coroutines.CoroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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
    fun provideCoroutineScopeProvider(impl: ApplicationScopeProvider): CoroutineScopeProvider = impl

    @Provides
    @Singleton
    fun provideCoroutineScope(scopeProvider: ApplicationScopeProvider): CoroutineScope = scopeProvider.scope

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
    fun provideGameStateStore(impl: com.xianxia.sect.core.state.GameStateStoreImpl): com.xianxia.sect.core.state.GameStateStore = impl

    @Provides
    @Singleton
    fun provideSaveFacade(impl: com.xianxia.sect.core.engine.domain.save.SaveFacadeImpl): com.xianxia.sect.core.engine.domain.save.SaveFacade = impl

    @Provides
    @Singleton
    fun provideHttpClientProvider(secureClient: SecureHttpClient): HttpClientProvider {
        return object : HttpClientProvider {
            override suspend fun get(url: String): String {
                val request = secureClient.newRequestBuilder(url).build()
                val response = secureClient.execute(request)
                return response.body?.string() ?: ""
            }

            override suspend fun post(url: String, body: String): String {
                val requestBody = body.toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = secureClient.newRequestBuilder(url)
                    .post(requestBody)
                    .build()
                val response = secureClient.execute(request)
                return response.body?.string() ?: ""
            }
        }
    }

    @Provides
    @Singleton
    fun provideMemoryMonitorProvider(memoryMonitor: MemoryMonitor): MemoryMonitorProvider {
        return object : MemoryMonitorProvider {
            override fun getCurrentMemoryInfo(): MemoryMonitorProvider.MemoryInfo? {
                return memoryMonitor.getCurrentMemoryInfo()?.let {
                    MemoryMonitorProvider.MemoryInfo(
                        totalMemory = it.totalMemory,
                        availableMemory = it.availableMemory,
                        usedMemory = it.usedMemory,
                        usedPercent = it.usedPercent,
                        isLowMemory = it.isLowMemory,
                        isWarning = it.isWarning,
                        isCritical = it.isCritical
                    )
                }
            }
        }
    }

    @Provides
    @Singleton
    fun provideGCOptimizerProvider(gcOptimizer: GCOptimizer): GCOptimizerProvider {
        return object : GCOptimizerProvider {
            override fun getGCStats(): GCOptimizerProvider.GCStats {
                val stats = gcOptimizer.getGCStats()
                return GCOptimizerProvider.GCStats(
                    totalGCCount = stats.totalGCCount,
                    totalGCTimeMs = stats.totalGCTimeMs,
                    averageGCTimeMs = stats.averageGCTimeMs,
                    lastGCTimeMs = stats.lastGCTimeMs,
                    timeSinceLastGC = stats.timeSinceLastGC
                )
            }
        }
    }

    @Provides
    @Singleton
    fun provideMailRepository(impl: MailRepositoryImpl): com.xianxia.sect.core.repository.MailRepository = impl

    @Provides
    @Singleton
    fun provideSaveStorage(impl: SaveStorageImpl): com.xianxia.sect.core.repository.SaveStorage = impl

    @Provides
    @Singleton
    fun provideAnalyticsTracker(): AnalyticsTracker {
        return object : AnalyticsTracker {
            override fun trackEvent(eventName: String, properties: Map<String, Any>) {
                TapDBManager.trackEvent(eventName, properties)
            }
        }
    }
}
