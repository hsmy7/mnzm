package com.xianxia.sect.di

import com.xianxia.sect.core.engine.service.AdService
import com.xianxia.sect.core.repository.*
import com.xianxia.sect.data.local.*
import com.xianxia.sect.taptap.AdServiceImpl
import com.xianxia.sect.taptap.LeaderboardCloudApi
import com.xianxia.sect.taptap.TapTapLeaderboardApi
import com.xianxia.sect.taptap.TapTapLoginBridge
import com.xianxia.sect.taptap.TapTapLoginBridgeImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BridgeBindingsModule {

    // Repository interface → implementation bindings
    @Provides @Singleton
    fun provideDiscipleRepository(impl: DiscipleRepositoryImpl): DiscipleRepository = impl

    @Provides @Singleton
    fun provideWorldRepository(impl: WorldRepositoryImpl): WorldRepository = impl

    @Provides @Singleton
    fun provideInventoryRepository(impl: InventoryRepositoryImpl): InventoryRepository = impl

    @Provides @Singleton
    fun provideEquipmentRepository(impl: EquipmentRepositoryImpl): EquipmentRepository = impl

    @Provides @Singleton
    fun provideGameDataRepository(impl: GameDataRepositoryImpl): GameDataRepository = impl

    // Data port bindings
    @Provides @Singleton
    fun provideProductionSlotDataPort(impl: ProductionSlotDataPortImpl): ProductionSlotDataPort = impl

    @Provides @Singleton
    fun provideGameHeavyDataPort(impl: GameHeavyDataPortImpl): GameHeavyDataPort = impl

    @Provides @Singleton
    fun provideHeavyDataDecoder(impl: HeavyDataDecoderImpl): HeavyDataDecoder = impl

    // Service bindings
    @Provides @Singleton
    fun provideAdService(impl: AdServiceImpl): AdService = impl

    // TapTap 登录桥（排行榜云端功能使用）
    @Provides @Singleton
    fun provideTapTapLoginBridge(impl: TapTapLoginBridgeImpl): TapTapLoginBridge = impl

    // 排行榜云端 API（tap-leaderboard SDK 实现）
    @Provides @Singleton
    fun provideLeaderboardCloudApi(impl: TapTapLeaderboardApi): LeaderboardCloudApi = impl
}
