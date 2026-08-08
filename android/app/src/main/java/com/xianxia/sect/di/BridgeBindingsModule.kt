package com.xianxia.sect.di

import com.xianxia.sect.core.engine.service.AdService
import com.xianxia.sect.core.repository.DiscipleRepository
import com.xianxia.sect.core.repository.DiscipleRepositoryImpl
import com.xianxia.sect.core.repository.EquipmentRepository
import com.xianxia.sect.core.repository.EquipmentRepositoryImpl
import com.xianxia.sect.core.repository.GameDataRepository
import com.xianxia.sect.core.repository.GameDataRepositoryImpl
import com.xianxia.sect.core.repository.GameHeavyDataPort
import com.xianxia.sect.core.repository.HeavyDataDecoder
import com.xianxia.sect.core.repository.InventoryRepository
import com.xianxia.sect.core.repository.InventoryRepositoryImpl
import com.xianxia.sect.core.repository.ProductionSlotDataPort
import com.xianxia.sect.core.repository.WorldRepository
import com.xianxia.sect.core.repository.WorldRepositoryImpl
import com.xianxia.sect.data.local.GameHeavyDataPortImpl
import com.xianxia.sect.data.local.HeavyDataDecoderImpl
import com.xianxia.sect.data.local.ProductionSlotDataPortImpl
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
