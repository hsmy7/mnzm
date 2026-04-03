package com.xianxia.sect.di

import android.content.Context
import com.xianxia.sect.data.cache.GameDataCacheManager
import com.xianxia.sect.data.concurrent.SlotLockManager
import com.xianxia.sect.data.config.StorageConfig
import com.xianxia.sect.data.coordinator.SaveCoordinator
import com.xianxia.sect.data.crypto.KeyRotationManager
import com.xianxia.sect.data.facade.RefactoredStorageFacade
import com.xianxia.sect.data.facade.StorageHealthChecker
import com.xianxia.sect.data.facade.StorageExporter
import com.xianxia.sect.data.facade.StorageStatsCollector
import com.xianxia.sect.data.incremental.IncrementalStorageManager
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.data.recovery.DeltaChainRecovery
import com.xianxia.sect.data.recovery.MultiLevelRecoveryManager
import com.xianxia.sect.data.transaction.EnhancedTransactionalManager
import com.xianxia.sect.data.transaction.RefactoredTransactionalSaveManager
import com.xianxia.sect.data.unified.BackupManager
import com.xianxia.sect.data.unified.MetadataManager
import com.xianxia.sect.data.unified.SaveFileHandler
import com.xianxia.sect.data.unified.SerializationHelper
import com.xianxia.sect.data.unified.UnifiedSaveRepository
import com.xianxia.sect.data.wal.EnhancedTransactionalWAL
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    
    @Provides
    @Singleton
    fun provideSlotLockManager(): SlotLockManager {
        return SlotLockManager(maxSlots = 5)
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
    fun provideCoroutineScope(): CoroutineScope {
        return CoroutineScope(Dispatchers.IO + SupervisorJob())
    }
    
    @Provides
    @Singleton
    fun provideEnhancedTransactionalWAL(
        @ApplicationContext context: Context
    ): EnhancedTransactionalWAL {
        return EnhancedTransactionalWAL.getInstance(context)
    }
    
    @Provides
    @Singleton
    fun provideRefactoredTransactionalSaveManager(
        @ApplicationContext context: Context,
        database: GameDatabase,
        wal: EnhancedTransactionalWAL,
        lockManager: SlotLockManager,
        scope: CoroutineScope
    ): RefactoredTransactionalSaveManager {
        return RefactoredTransactionalSaveManager(
            context = context,
            database = database,
            wal = wal,
            lockManager = lockManager,
            scope = scope
        )
    }
    
    @Provides
    @Singleton
    fun provideEnhancedTransactionalManager(
        @ApplicationContext context: Context,
        database: GameDatabase,
        wal: EnhancedTransactionalWAL,
        lockManager: SlotLockManager,
        scope: CoroutineScope
    ): EnhancedTransactionalManager {
        return EnhancedTransactionalManager(
            context = context,
            database = database,
            wal = wal,
            lockManager = lockManager,
            scope = scope
        )
    }
    
    @Provides
    @Singleton
    fun provideSaveFileHandler(
        @ApplicationContext context: Context
    ): SaveFileHandler {
        return SaveFileHandler(context)
    }
    
    @Provides
    @Singleton
    fun provideSerializationHelper(
        serializationEngine: com.xianxia.sect.data.serialization.unified.UnifiedSerializationEngine,
        saveDataConverter: com.xianxia.sect.data.serialization.unified.SaveDataConverter
    ): SerializationHelper {
        return SerializationHelper(serializationEngine, saveDataConverter)
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
    fun provideUnifiedSaveRepository(
        @ApplicationContext context: Context,
        database: GameDatabase,
        cacheManager: GameDataCacheManager,
        transactionalSaveManager: RefactoredTransactionalSaveManager,
        wal: EnhancedTransactionalWAL,
        lockManager: SlotLockManager,
        fileHandler: SaveFileHandler,
        backupManager: BackupManager,
        serializationHelper: SerializationHelper,
        metadataManager: MetadataManager
    ): UnifiedSaveRepository {
        return UnifiedSaveRepository(
            context = context,
            database = database,
            cacheManager = cacheManager,
            transactionalSaveManager = transactionalSaveManager,
            wal = wal,
            lockManager = lockManager,
            fileHandler = fileHandler,
            backupManager = backupManager,
            serializationHelper = serializationHelper,
            metadataManager = metadataManager
        )
    }
    
    @Provides
    @Singleton
    fun provideStorageHealthChecker(
        @ApplicationContext context: Context,
        saveRepository: UnifiedSaveRepository,
        lockManager: SlotLockManager
    ): StorageHealthChecker {
        return StorageHealthChecker(context, saveRepository, lockManager)
    }
    
    @Provides
    @Singleton
    fun provideStorageExporter(
        @ApplicationContext context: Context,
        saveRepository: UnifiedSaveRepository,
        lockManager: SlotLockManager
    ): StorageExporter {
        return StorageExporter(context, saveRepository, lockManager)
    }
    
    @Provides
    @Singleton
    fun provideStorageStatsCollector(
        @ApplicationContext context: Context,
        saveRepository: UnifiedSaveRepository,
        wal: EnhancedTransactionalWAL
    ): StorageStatsCollector {
        return StorageStatsCollector(context, saveRepository, wal)
    }
    
    @Provides
    @Singleton
    fun provideSaveCoordinator(
        @ApplicationContext context: Context,
        saveRepository: UnifiedSaveRepository,
        incrementalManager: IncrementalStorageManager,
        cacheManager: GameDataCacheManager,
        wal: EnhancedTransactionalWAL,
        lockManager: SlotLockManager,
        config: StorageConfig,
        scope: CoroutineScope
    ): SaveCoordinator {
        return SaveCoordinator(
            context = context,
            saveRepository = saveRepository,
            incrementalManager = incrementalManager,
            cacheManager = cacheManager,
            wal = wal,
            lockManager = lockManager,
            config = config,
            scope = scope
        )
    }
    
    @Provides
    @Singleton
    fun provideDeltaChainRecovery(
        @ApplicationContext context: Context,
        incrementalManager: IncrementalStorageManager,
        config: StorageConfig
    ): DeltaChainRecovery {
        return DeltaChainRecovery(
            context = context,
            incrementalManager = incrementalManager,
            config = config
        )
    }
    
    @Provides
    @Singleton
    fun provideRefactoredStorageFacade(
        @ApplicationContext context: Context,
        saveRepository: UnifiedSaveRepository,
        transactionalSaveManager: RefactoredTransactionalSaveManager,
        wal: EnhancedTransactionalWAL,
        lockManager: SlotLockManager,
        healthChecker: StorageHealthChecker,
        exporter: StorageExporter,
        statsCollector: StorageStatsCollector,
        incrementalStorageManager: IncrementalStorageManager
    ): RefactoredStorageFacade {
        return RefactoredStorageFacade(
            context = context,
            saveRepository = saveRepository,
            transactionalSaveManager = transactionalSaveManager,
            wal = wal,
            lockManager = lockManager,
            healthChecker = healthChecker,
            exporter = exporter,
            statsCollector = statsCollector,
            incrementalStorageManager = incrementalStorageManager
        )
    }
    
    @Provides
    @Singleton
    fun provideMultiLevelRecoveryManager(
        @ApplicationContext context: Context,
        saveRepository: UnifiedSaveRepository,
        wal: EnhancedTransactionalWAL,
        lockManager: SlotLockManager
    ): MultiLevelRecoveryManager {
        return MultiLevelRecoveryManager(
            context = context,
            saveRepository = saveRepository,
            wal = wal,
            lockManager = lockManager
        )
    }
    
    @Provides
    @Singleton
    fun provideKeyRotationManager(
        @ApplicationContext context: Context,
        saveRepository: UnifiedSaveRepository
    ): KeyRotationManager {
        return KeyRotationManager(context, saveRepository)
    }
}
