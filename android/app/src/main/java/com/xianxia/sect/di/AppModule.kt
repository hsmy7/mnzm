package com.xianxia.sect.di

import android.app.ActivityManager
import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.data.local.*
import com.xianxia.sect.data.GameRepository
import com.xianxia.sect.data.SessionManager
import com.xianxia.sect.data.cache.CacheConfig
import com.xianxia.sect.data.cache.GameDataCacheManager
import com.xianxia.sect.data.incremental.ChangeTracker
import com.xianxia.sect.data.incremental.DeltaCompressor
import com.xianxia.sect.data.incremental.IncrementalStorageManager
import com.xianxia.sect.data.incremental.IncrementalStorageCoordinator
import com.xianxia.sect.data.serialization.unified.SaveDataConverter
import com.xianxia.sect.data.serialization.unified.UnifiedSerializationEngine
import com.xianxia.sect.data.unified.UnifiedSaveRepository
import com.xianxia.sect.domain.usecase.*
import com.xianxia.sect.ui.state.DialogStateManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    @Provides
    @Singleton
    fun provideSessionManager(@ApplicationContext context: Context): SessionManager {
        return SessionManager(context)
    }
    
    @Provides
    @Singleton
    fun provideGameDatabase(@ApplicationContext context: Context): GameDatabase {
        return Room.databaseBuilder(
            context,
            GameDatabase::class.java,
            "xianxia_sect_db"
        )
            .addMigrations(*DatabaseMigrations.ALL_MIGRATIONS)
            .fallbackToDestructiveMigrationOnDowngrade()
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
    }
    
    @Provides
    fun provideBatchUpdateDao(database: GameDatabase): BatchUpdateDao = database.batchUpdateDao()
    
    @Provides
    fun provideGameDataDao(database: GameDatabase): GameDataDao = database.gameDataDao()
    
    @Provides
    fun provideDiscipleDao(database: GameDatabase): DiscipleDao = database.discipleDao()
    
    @Provides
    fun provideEquipmentDao(database: GameDatabase): EquipmentDao = database.equipmentDao()
    
    @Provides
    fun provideManualDao(database: GameDatabase): ManualDao = database.manualDao()
    
    @Provides
    fun providePillDao(database: GameDatabase): PillDao = database.pillDao()
    
    @Provides
    fun provideMaterialDao(database: GameDatabase): MaterialDao = database.materialDao()
    
    @Provides
    fun provideSeedDao(database: GameDatabase): SeedDao = database.seedDao()
    
    @Provides
    fun provideHerbDao(database: GameDatabase): HerbDao = database.herbDao()
    
    @Provides
    fun provideExplorationTeamDao(database: GameDatabase): ExplorationTeamDao = 
        database.explorationTeamDao()
    
    @Provides
    fun provideBuildingSlotDao(database: GameDatabase): BuildingSlotDao = 
        database.buildingSlotDao()
    
    @Provides
    fun provideGameEventDao(database: GameDatabase): GameEventDao = database.gameEventDao()
    
    @Provides
    fun provideDungeonDao(database: GameDatabase): DungeonDao = database.dungeonDao()
    
    @Provides
    fun provideRecipeDao(database: GameDatabase): RecipeDao = database.recipeDao()
    
    @Provides
    fun provideBattleLogDao(database: GameDatabase): BattleLogDao = database.battleLogDao()

    @Provides
    fun provideForgeSlotDao(database: GameDatabase): ForgeSlotDao = database.forgeSlotDao()
    
    @Provides
    fun provideAlchemySlotDao(database: GameDatabase): AlchemySlotDao = database.alchemySlotDao()
    
    @Provides
    @Singleton
    fun provideGameRepository(
        database: GameDatabase,
        gameDataDao: GameDataDao,
        discipleDao: DiscipleDao,
        equipmentDao: EquipmentDao,
        manualDao: ManualDao,
        pillDao: PillDao,
        materialDao: MaterialDao,
        seedDao: SeedDao,
        herbDao: HerbDao,
        explorationTeamDao: ExplorationTeamDao,
        buildingSlotDao: BuildingSlotDao,
        gameEventDao: GameEventDao,
        dungeonDao: DungeonDao,
        recipeDao: RecipeDao,
        battleLogDao: BattleLogDao,
        forgeSlotDao: ForgeSlotDao
    ): GameRepository {
        return GameRepository(
            database,
            gameDataDao,
            discipleDao,
            equipmentDao,
            manualDao,
            pillDao,
            materialDao,
            seedDao,
            herbDao,
            explorationTeamDao,
            buildingSlotDao,
            gameEventDao,
            dungeonDao,
            recipeDao,
            battleLogDao,
            forgeSlotDao
        )
    }

    @Provides
    @Singleton
    fun provideCacheConfig(@ApplicationContext context: Context): CacheConfig {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val isLowRamDevice = activityManager.isLowRamDevice
        val memoryClass = activityManager.memoryClass

        val memoryCacheSize = when {
            isLowRamDevice || memoryClass <= 128 -> 20L * 1024 * 1024
            memoryClass <= 256 -> 50L * 1024 * 1024
            else -> 100L * 1024 * 1024
        }

        val diskCacheSize = memoryCacheSize * 2L

        return CacheConfig(
            memoryCacheSize = memoryCacheSize,
            diskCacheSize = diskCacheSize,
            writeBatchSize = if (isLowRamDevice) 50 else 200,
            writeDelayMs = if (isLowRamDevice) 2000L else 500L,
            enableDiskCache = true,
            enableCompression = true
        )
    }

    @Provides
    @Singleton
    fun provideGameDataCacheManager(
        @ApplicationContext context: Context,
        database: GameDatabase,
        cacheConfig: CacheConfig
    ): GameDataCacheManager {
        return GameDataCacheManager(context, database, cacheConfig)
    }
    

    @Provides
    @Singleton
    fun provideChangeTracker(): ChangeTracker {
        return ChangeTracker()
    }
    
    @Provides
    @Singleton
    fun provideDeltaCompressor(): DeltaCompressor {
        return DeltaCompressor()
    }
    
    @Provides
    @Singleton
    fun provideIncrementalStorageManager(
        @ApplicationContext context: Context,
        changeTracker: ChangeTracker,
        deltaCompressor: DeltaCompressor,
        saveDataConverter: SaveDataConverter,
        serializationEngine: UnifiedSerializationEngine
    ): IncrementalStorageManager {
        return IncrementalStorageManager(
            context,
            changeTracker,
            deltaCompressor,
            saveDataConverter,
            serializationEngine
        )
    }
    
    @Provides
    @Singleton
    fun provideIncrementalStorageCoordinator(
        @ApplicationContext context: Context,
        database: GameDatabase,
        cacheManager: GameDataCacheManager,
        saveRepository: UnifiedSaveRepository,
        changeTracker: ChangeTracker,
        deltaCompressor: DeltaCompressor,
        incrementalStorageManager: IncrementalStorageManager
    ): IncrementalStorageCoordinator {
        return IncrementalStorageCoordinator(
            context,
            database,
            cacheManager,
            saveRepository,
            changeTracker,
            deltaCompressor,
            incrementalStorageManager
        )
    }
}
