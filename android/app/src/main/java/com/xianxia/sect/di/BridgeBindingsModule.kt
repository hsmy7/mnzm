package com.xianxia.sect.di

import com.xianxia.sect.core.repository.*
import com.xianxia.sect.data.local.*
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
    fun provideForgeRepository(impl: ForgeRepositoryImpl): ForgeRepository = impl

    @Provides @Singleton
    fun provideGameDataRepository(impl: GameDataRepositoryImpl): GameDataRepository = impl

    // Data port bindings
    @Provides @Singleton
    fun provideProductionSlotDataPort(impl: ProductionSlotDataPortImpl): ProductionSlotDataPort = impl

    @Provides @Singleton
    fun provideGameHeavyDataPort(impl: GameHeavyDataPortImpl): GameHeavyDataPort = impl

    @Provides @Singleton
    fun provideHeavyDataDecoder(impl: HeavyDataDecoderImpl): HeavyDataDecoder = impl
}
