package com.xianxia.sect.di

import android.content.Context
import com.xianxia.sect.data.archive.DataArchiver
import com.xianxia.sect.data.backup.SaveSerializer
import com.xianxia.sect.data.compression.DataCompressor
import com.xianxia.sect.data.concurrent.SlotLockManager
import com.xianxia.sect.data.config.SaveLimitsConfig
import com.xianxia.sect.data.config.StorageConfig
import com.xianxia.sect.data.engine.StorageCoreFacade
import com.xianxia.sect.data.engine.StorageEngine
import com.xianxia.sect.data.engine.StorageInfraFacade
import com.xianxia.sect.data.engine.StorageMaintenanceFacade

import com.xianxia.sect.data.memory.DynamicMemoryManager

import com.xianxia.sect.data.serialization.unified.SerializationModule
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
        return SlotLockManager()
    }

    @Provides
    @Singleton
    fun provideStorageConfig(
        keyValueStore: com.xianxia.sect.data.prefs.KeyValueStore
    ): StorageConfig {
        return StorageConfig(keyValueStore)
    }

    @Provides
    @Singleton
    fun providePersistenceTelemetryPort(
        storageMetrics: com.xianxia.sect.data.engine.StorageMetrics
    ): com.xianxia.sect.core.util.PersistenceTelemetryPort {
        return storageMetrics
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
        serializationEngine: com.xianxia.sect.data.serialization.unified.UnifiedSerializationEngine
    ): SerializationModule {
        return SerializationModule(serializationEngine)
    }

    @Provides
    @Singleton
    fun provideDataCompressor(): DataCompressor {
        return DataCompressor()
    }

    @Provides
    @Singleton
    fun provideDataArchiver(
        accountSpace: com.xianxia.sect.data.account.AccountSpaceManager,
        dataCompressor: DataCompressor
    ): DataArchiver {
        return DataArchiver(accountSpace, dataCompressor)
    }

    @Provides
    @Singleton
    fun provideSaveLimitsConfig(
        keyValueStore: com.xianxia.sect.data.prefs.KeyValueStore
    ): SaveLimitsConfig {
        return SaveLimitsConfig(keyValueStore)
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
        saveFileManager: com.xianxia.sect.data.backup.SaveFileManager,
        saveBackendModeProvider: com.xianxia.sect.data.cloud.SaveBackendModeProvider,
        serializationModule: com.xianxia.sect.data.serialization.unified.SerializationModule,
        storageConfig: com.xianxia.sect.data.config.StorageConfig
    ): StorageEngine {
        return StorageEngine(
            core = core,
            saveLimitsConfig = saveLimitsConfig,
            dataArchiver = dataArchiver,
            infra = infra,
            maintenanceFacade = maintenanceFacade,
            saveFileManager = saveFileManager,
            saveBackendModeProvider = saveBackendModeProvider,
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

}

/** SS5：保存脏集双端口绑定（馈送口 = store 提交段消费；出口 = 快照构建点消费）。 */
@Module
@InstallIn(SingletonComponent::class)
object SaveDirtyPortModule {

    @Provides
    @Singleton
    fun provideSaveDirtyRecorder(
        dirtySetTracker: com.xianxia.sect.data.engine.DirtySetTracker
    ): com.xianxia.sect.core.state.SaveDirtyRecorder = dirtySetTracker

    @Provides
    @Singleton
    fun provideSaveDirtyDeltaSource(
        dirtySetTracker: com.xianxia.sect.data.engine.DirtySetTracker
    ): com.xianxia.sect.core.state.SaveDirtyDeltaSource = dirtySetTracker
}
