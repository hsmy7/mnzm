package com.xianxia.sect.di

import android.content.Context
import com.xianxia.sect.data.archive.DataArchiver
import com.xianxia.sect.data.cache.CacheLayer
import com.xianxia.sect.data.compression.DataCompressor
import com.xianxia.sect.data.concurrent.SlotLockManager
import com.xianxia.sect.data.config.SaveLimitsConfig
import com.xianxia.sect.data.config.StorageConfig
import com.xianxia.sect.data.crypto.KeyRotationManager
import com.xianxia.sect.data.engine.DataArchiveScheduler
import com.xianxia.sect.data.engine.DataPruningScheduler
import com.xianxia.sect.data.engine.ProactiveMemoryGuard
import com.xianxia.sect.data.engine.StorageBackup
import com.xianxia.sect.data.engine.StorageCircuitBreaker
import com.xianxia.sect.data.engine.StorageEngine
import com.xianxia.sect.data.engine.StorageIntegrity
import com.xianxia.sect.data.engine.StorageMetrics
import com.xianxia.sect.data.engine.StorageWal
import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.data.incremental.ChangeLogPersistence
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.data.memory.DynamicMemoryManager
import com.xianxia.sect.data.recovery.RecoveryManager
import com.xianxia.sect.data.unified.BackupManager
import com.xianxia.sect.data.unified.MetadataManager
import com.xianxia.sect.data.unified.SaveFileHandler
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
        applicationScopeProvider: ApplicationScopeProvider
    ): WALProvider {
        return FunctionalWAL(context, applicationScopeProvider)
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
    fun provideSaveFileHandler(
        @ApplicationContext context: Context,
        memoryManager: DynamicMemoryManager
    ): SaveFileHandler {
        return SaveFileHandler(context, memoryManager)
    }

    @Provides
    @Singleton
    fun provideSerializationModule(
        serializationEngine: com.xianxia.sect.data.serialization.unified.UnifiedSerializationEngine,
        saveDataConverter: com.xianxia.sect.data.serialization.unified.SaveDataConverter
    ): SerializationModule {
        return SerializationModule(serializationEngine, saveDataConverter)
    }

    @Provides
    @Singleton
    fun provideMetadataManager(
        @ApplicationContext context: Context,
        fileHandler: SaveFileHandler
    ): MetadataManager {
        return MetadataManager(context, fileHandler)
    }

    @Provides
    @Singleton
    fun provideBackupManager(
        fileHandler: SaveFileHandler,
        lockManager: SlotLockManager
    ): BackupManager {
        return BackupManager(fileHandler, lockManager)
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

    @Provides
    @Singleton
    internal fun provideStorageEngine(
        database: GameDatabase,
        cache: CacheLayer,
        lockManager: SlotLockManager,
        wal: WALProvider,
        saveLimitsConfig: SaveLimitsConfig,
        changeLogPersistence: ChangeLogPersistence,
        dataArchiver: DataArchiver,
        applicationScopeProvider: ApplicationScopeProvider,
        circuitBreaker: StorageCircuitBreaker,
        pruningScheduler: DataPruningScheduler,
        archiveScheduler: DataArchiveScheduler,
        memoryGuard: ProactiveMemoryGuard,
        storageIntegrity: StorageIntegrity,
        storageBackup: StorageBackup,
        storageWal: StorageWal,
        storageMetrics: StorageMetrics
    ): StorageEngine {
        return StorageEngine(
            database = database,
            cache = cache,
            lockManager = lockManager,
            wal = wal,
            saveLimitsConfig = saveLimitsConfig,
            changeLogPersistence = changeLogPersistence,
            dataArchiver = dataArchiver,
            applicationScopeProvider = applicationScopeProvider,
            circuitBreaker = circuitBreaker,
            pruningScheduler = pruningScheduler,
            archiveScheduler = archiveScheduler,
            memoryGuard = memoryGuard,
            storageIntegrity = storageIntegrity,
            storageBackup = storageBackup,
            storageWal = storageWal,
            storageMetrics = storageMetrics
        )
    }

    @Provides
    @Singleton
    fun provideRecoveryManager(
        @ApplicationContext context: Context,
        wal: WALProvider,
        lockManager: SlotLockManager,
        database: GameDatabase
    ): RecoveryManager {
        return RecoveryManager(
            context = context,
            wal = wal,
            lockManager = lockManager,
            database = database
        )
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
