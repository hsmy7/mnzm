@file:Suppress("DEPRECATION")

package com.xianxia.sect.di

import android.content.Context
import com.xianxia.sect.data.archive.DataArchiver
import com.xianxia.sect.data.cache.GameDataCacheManager
import com.xianxia.sect.data.compression.DataCompressor
import com.xianxia.sect.data.concurrent.SlotLockManager
import com.xianxia.sect.data.concurrent.StorageCircuitBreaker
import com.xianxia.sect.data.concurrent.StorageScopeManager
import com.xianxia.sect.data.config.SaveLimitsConfig
import com.xianxia.sect.data.config.StorageConfig
import com.xianxia.sect.data.coordinator.SaveCoordinator
import com.xianxia.sect.data.crypto.KeyRotationManager
import com.xianxia.sect.data.engine.UnifiedStorageEngine
import com.xianxia.sect.data.facade.RefactoredStorageFacade
import com.xianxia.sect.data.facade.StorageHealthChecker
import com.xianxia.sect.data.facade.StorageExporter
import com.xianxia.sect.data.facade.StorageStatsCollector
import com.xianxia.sect.data.incremental.ChangeLogPersistence
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.data.memory.DynamicMemoryManager
import com.xianxia.sect.data.memory.ProactiveMemoryGuard
import com.xianxia.sect.data.orchestrator.StorageOrchestrator
import com.xianxia.sect.data.pruning.DataPruningScheduler
import com.xianxia.sect.data.archive.DataArchiveScheduler
import com.xianxia.sect.data.recovery.MultiLevelRecoveryManager
import com.xianxia.sect.data.transaction.RefactoredTransactionalSaveManager
import com.xianxia.sect.data.unified.BackupManager
import com.xianxia.sect.data.unified.MetadataManager
import com.xianxia.sect.data.unified.SaveFileHandler
import com.xianxia.sect.data.unified.SerializationHelper
import com.xianxia.sect.data.unified.UnifiedSaveRepository
import com.xianxia.sect.data.wal.FunctionalWAL
import com.xianxia.sect.data.wal.WALProvider
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
    fun provideCoroutineScope(): CoroutineScope {
        return CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @Provides
    @Singleton
    fun provideWAL(
        @ApplicationContext context: Context
    ): WALProvider {
        return FunctionalWAL(context)
    }

    @Provides
    @Singleton
    fun provideRefactoredTransactionalSaveManager(
        @ApplicationContext context: Context,
        database: GameDatabase,
        wal: WALProvider,
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

    @Suppress("DEPRECATION")
    @Provides
    @Singleton
    fun provideUnifiedSaveRepository(
        @ApplicationContext context: Context,
        database: GameDatabase,
        cacheManager: GameDataCacheManager,
        transactionalSaveManager: RefactoredTransactionalSaveManager,
        lockManager: SlotLockManager,
        backupManager: BackupManager,
        saveLimitsConfig: SaveLimitsConfig,
        dataArchiver: DataArchiver
    ): UnifiedSaveRepository {
        return UnifiedSaveRepository(
            context = context,
            database = database,
            cacheManager = cacheManager,
            transactionalSaveManager = transactionalSaveManager,
            lockManager = lockManager,
            backupManager = backupManager,
            saveLimitsConfig = saveLimitsConfig,
            dataArchiver = dataArchiver
        )
    }

    @Suppress("DEPRECATION")
    @Provides
    @Singleton
    fun provideStorageHealthChecker(
        @ApplicationContext context: Context,
        storageGateway: com.xianxia.sect.data.StorageGateway,
        unifiedSaveRepository: com.xianxia.sect.data.unified.UnifiedSaveRepository,
        lockManager: SlotLockManager
    ): StorageHealthChecker {
        return StorageHealthChecker(context, storageGateway, unifiedSaveRepository, lockManager)
    }

    @Provides
    @Singleton
    fun provideStorageExporter(
        @ApplicationContext context: Context,
        storageGateway: com.xianxia.sect.data.StorageGateway,
        lockManager: SlotLockManager
    ): StorageExporter {
        return StorageExporter(context, storageGateway, lockManager)
    }

    @Suppress("DEPRECATION")
    @Provides
    @Singleton
    fun provideStorageStatsCollector(
        @ApplicationContext context: Context,
        storageGateway: com.xianxia.sect.data.StorageGateway,
        unifiedSaveRepository: com.xianxia.sect.data.unified.UnifiedSaveRepository,
        wal: WALProvider
    ): StorageStatsCollector {
        return StorageStatsCollector(context, storageGateway, unifiedSaveRepository, wal)
    }

    @Provides
    @Singleton
    fun provideSaveCoordinator(
        @ApplicationContext context: Context,
        engine: UnifiedStorageEngine,
        cacheManager: GameDataCacheManager,
        lockManager: SlotLockManager,
        config: StorageConfig,
        scope: CoroutineScope
    ): SaveCoordinator {
        return SaveCoordinator(
            context = context,
            engine = engine,
            cacheManager = cacheManager,
            lockManager = lockManager,
            config = config,
            scope = scope
        )
    }

    @Provides
    @Singleton
    fun provideUnifiedStorageEngine(
        @ApplicationContext context: Context,
        database: GameDatabase,
        cacheManager: GameDataCacheManager,
        lockManager: SlotLockManager,
        wal: WALProvider,
        saveLimitsConfig: SaveLimitsConfig,
        serializationHelper: SerializationHelper,
        changeLogPersistence: ChangeLogPersistence,
        scope: CoroutineScope
    ): UnifiedStorageEngine {
        return UnifiedStorageEngine(
            context = context,
            database = database,
            cacheManager = cacheManager,
            lockManager = lockManager,
            wal = wal,
            saveLimitsConfig = saveLimitsConfig,
            serializationHelper = serializationHelper,
            changeLogPersistence = changeLogPersistence,
            scope = scope
        )
    }

    @Provides
    @Singleton
    fun provideRefactoredStorageFacade(
        @ApplicationContext context: Context,
        engine: UnifiedStorageEngine,
        storageGateway: com.xianxia.sect.data.StorageGateway,
        unifiedSaveRepository: com.xianxia.sect.data.unified.UnifiedSaveRepository,
        lockManager: SlotLockManager,
        healthChecker: StorageHealthChecker,
        exporter: StorageExporter,
        statsCollector: StorageStatsCollector,
        saveLimitsConfig: com.xianxia.sect.data.config.SaveLimitsConfig,
        deleteCoordinator: com.xianxia.sect.data.coordinator.DeleteCoordinator,
        orchestrator: com.xianxia.sect.data.orchestrator.StorageOrchestrator,
        memoryGuard: com.xianxia.sect.data.memory.ProactiveMemoryGuard,
        pruningScheduler: com.xianxia.sect.data.pruning.DataPruningScheduler,
        archiveScheduler: DataArchiveScheduler,
        scopeManager: StorageScopeManager
    ): RefactoredStorageFacade {
        return RefactoredStorageFacade(
            context = context,
            engine = engine,
            storageGateway = storageGateway,
            unifiedSaveRepository = unifiedSaveRepository,
            lockManager = lockManager,
            healthChecker = healthChecker,
            exporter = exporter,
            statsCollector = statsCollector,
            saveLimitsConfig = saveLimitsConfig,
            deleteCoordinator = deleteCoordinator,
            orchestrator = orchestrator,
            memoryGuard = memoryGuard,
            pruningScheduler = pruningScheduler,
            archiveScheduler = archiveScheduler,
            scopeManager = scopeManager
        )
    }

    @Suppress("DEPRECATION")
    @Provides
    @Singleton
    fun provideMultiLevelRecoveryManager(
        @ApplicationContext context: Context,
        storageGateway: com.xianxia.sect.data.StorageGateway,
        unifiedSaveRepository: com.xianxia.sect.data.unified.UnifiedSaveRepository,
        wal: WALProvider,
        lockManager: SlotLockManager
    ): MultiLevelRecoveryManager {
        return MultiLevelRecoveryManager(
            context = context,
            storageGateway = storageGateway,
            unifiedSaveRepository = unifiedSaveRepository,
            wal = wal,
            lockManager = lockManager
        )
    }

    @Suppress("DEPRECATION")
    @Provides
    @Singleton
    fun provideKeyRotationManager(
        @ApplicationContext context: Context,
        saveRepository: UnifiedSaveRepository
    ): KeyRotationManager {
        return KeyRotationManager(context, saveRepository)
    }

    @Suppress("DEPRECATION")
    @Provides
    @Singleton
    fun provideSaveRepository(
        unifiedSaveRepository: UnifiedSaveRepository
    ): com.xianxia.sect.data.unified.SaveRepository {
        return unifiedSaveRepository
    }

    @Provides
    @Singleton
    fun provideStorageGateway(
        saveRepository: com.xianxia.sect.data.unified.SaveRepository,
        gameRepository: com.xianxia.sect.data.GameRepository,
        orchestrator: com.xianxia.sect.data.orchestrator.StorageOrchestrator
    ): com.xianxia.sect.data.StorageGateway {
        return com.xianxia.sect.data.StorageGateway(
            saveRepository = saveRepository,
            gameRepository = gameRepository,
            orchestrator = orchestrator
        )
    }

    @Provides
    @Singleton
    fun provideStorageScopeManager(): StorageScopeManager {
        return StorageScopeManager()
    }

    @Provides
    @Singleton
    fun provideStorageCircuitBreaker(): StorageCircuitBreaker {
        return StorageCircuitBreaker()
    }

    @Provides
    @Singleton
    fun provideStorageOrchestrator(
        @ApplicationContext context: Context,
        saveRepository: com.xianxia.sect.data.unified.SaveRepository,
        gameRepository: com.xianxia.sect.data.GameRepository,
        engine: UnifiedStorageEngine,
        cacheManager: GameDataCacheManager,
        database: GameDatabase,
        wal: WALProvider,
        memoryManager: DynamicMemoryManager,
        scopeManager: StorageScopeManager,
        circuitBreaker: StorageCircuitBreaker
    ): StorageOrchestrator {
        return StorageOrchestrator(
            context = context,
            saveRepository = saveRepository,
            gameRepository = gameRepository,
            engine = engine,
            cacheManager = cacheManager,
            database = database,
            wal = wal,
            memoryManager = memoryManager,
            scopeManager = scopeManager,
            circuitBreaker = circuitBreaker
        )
    }

    @Provides
    @Singleton
    fun provideProactiveMemoryGuard(
        memoryManager: DynamicMemoryManager,
        cacheManager: GameDataCacheManager,
        scopeManager: StorageScopeManager
    ): ProactiveMemoryGuard {
        return ProactiveMemoryGuard(memoryManager, cacheManager, scopeManager)
    }

    @Provides
    @Singleton
    fun provideDataPruningScheduler(
        database: GameDatabase,
        scopeManager: StorageScopeManager,
        circuitBreaker: StorageCircuitBreaker
    ): DataPruningScheduler {
        return DataPruningScheduler(database, scopeManager, circuitBreaker)
    }
}
