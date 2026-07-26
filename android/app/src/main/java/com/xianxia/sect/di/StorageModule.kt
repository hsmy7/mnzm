package com.xianxia.sect.di

import android.content.Context
import com.xianxia.sect.data.archive.DataArchiver
import com.xianxia.sect.data.backup.SaveSerializer
import com.xianxia.sect.data.compression.DataCompressor
import com.xianxia.sect.data.concurrent.SlotLockManager
import com.xianxia.sect.data.config.SaveLimitsConfig
import com.xianxia.sect.data.config.StorageConfig
import com.xianxia.sect.data.crypto.KeyRotationManager
import com.xianxia.sect.data.engine.StorageCoreFacade
import com.xianxia.sect.data.engine.StorageEngine
import com.xianxia.sect.data.engine.StorageInfraFacade
import com.xianxia.sect.data.engine.StorageMaintenanceFacade

import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.data.memory.DynamicMemoryManager

import com.xianxia.sect.data.serialization.unified.SerializationModule
import com.xianxia.sect.data.wal.FunctionalWAL
import com.xianxia.sect.data.wal.WALProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides
    @Singleton
    fun provideSlotLockManager(): SlotLockManager {
        return SlotLockManager(maxSlots = 6)
    }

    @Provides
    @Singleton
    fun provideStorageConfig(
        @ApplicationContext context: Context
    ): StorageConfig {
        return StorageConfig(context)
    }

    @Provides
    @Singleton
    fun provideWAL(
        @ApplicationContext context: Context,
        applicationScopeProvider: ApplicationScopeProvider,
        thermalMonitor: com.xianxia.sect.core.perf.ThermalMonitor
    ): WALProvider {
        return FunctionalWAL(context, applicationScopeProvider, thermalMonitor)
    }

    @Provides
    @Singleton
    fun provideDynamicMemoryManager(
        @ApplicationContext context: Context
    ): DynamicMemoryManager {
        return DynamicMemoryManager(context)
    }

    @Provides
    @Singleton
    fun provideSerializationModule(
        serializationEngine: com.xianxia.sect.data.serialization.unified.UnifiedSerializationEngine,
        oldSaveFormatDeserializer: com.xianxia.sect.data.serialization.backwardcompat.OldSaveFormatDeserializer
    ): SerializationModule {
        return SerializationModule(serializationEngine, oldSaveFormatDeserializer)
    }

    @Provides
    @Singleton
    fun provideDataCompressor(): DataCompressor {
        return DataCompressor()
    }

    @Provides
    @Singleton
    fun provideDataArchiver(
        @ApplicationContext context: Context,
        dataCompressor: DataCompressor
    ): DataArchiver {
        return DataArchiver(context, dataCompressor)
    }

    @Provides
    @Singleton
    fun provideSaveLimitsConfig(
        @ApplicationContext context: Context
    ): SaveLimitsConfig {
        return SaveLimitsConfig(context)
    }

    @Suppress("LongParameterList")
    @Provides
    @Singleton
    internal fun provideStorageEngine(
        core: StorageCoreFacade,
        saveLimitsConfig: SaveLimitsConfig,
        dataArchiver: DataArchiver,
        infra: StorageInfraFacade,
        maintenanceFacade: StorageMaintenanceFacade,
        stateStore: com.xianxia.sect.core.state.GameStateStore,
        repository: com.xianxia.sect.data.GameStateRepository,
        saveFileManager: com.xianxia.sect.data.backup.SaveFileManager,
        serializationModule: com.xianxia.sect.data.serialization.unified.SerializationModule,
        storageConfig: com.xianxia.sect.data.config.StorageConfig
    ): StorageEngine {
        return StorageEngine(
            core = core,
            saveLimitsConfig = saveLimitsConfig,
            dataArchiver = dataArchiver,
            infra = infra,
            maintenanceFacade = maintenanceFacade,
            stateStore = stateStore,
            repository = repository,
            saveFileManager = saveFileManager,
            serializationModule = serializationModule,
            storageConfig = storageConfig
        )
    }

    @Provides
    @Singleton
    fun provideSaveSerializer(
        serializationModule: SerializationModule
    ): SaveSerializer {
        return SaveSerializer { saveData ->
            serializationModule.serializeAndCompressSaveData(saveData)
        }
    }

    @Provides
    @Singleton
    fun provideKeyRotationManager(
        @ApplicationContext context: Context,
        storageFacade: StorageFacade
    ): KeyRotationManager {
        return KeyRotationManager(context, storageFacade)
    }

}
