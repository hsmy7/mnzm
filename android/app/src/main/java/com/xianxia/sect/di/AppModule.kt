package com.xianxia.sect.di

import android.app.ActivityManager
import android.content.Context
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.data.local.*
import com.xianxia.sect.data.SessionManager
import com.xianxia.sect.data.cache.CacheConfig
import com.xianxia.sect.data.cache.GameDataCacheManager
import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.data.incremental.ChangeTracker
import com.xianxia.sect.data.incremental.ChangeLogPersistence
import com.xianxia.sect.data.serialization.unified.SaveDataConverter
import com.xianxia.sect.data.serialization.unified.UnifiedSerializationEngine
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
    
    /**
     * GameDatabase 单例提供者 — 使用统一实例创建方法
     * 
     * 路由改造说明：
     * - 原路径可能使用 per-slot 数据库（已废弃）
     * - 新路径：统一单实例 DB (xianxia_sect.db)，所有 slot 共享同一数据库文件
     * - transactionalSaveManager 通过 slot 字段区分不同存档的数据行
     *
     * @see GameDatabase.create 统一实例工厂方法
     */
    @Provides
    @Singleton
    fun provideGameDatabase(@ApplicationContext context: Context): GameDatabase {
        return GameDatabase.create(context.applicationContext)
    }
    
    @Provides
    fun provideGameDataDao(database: GameDatabase): GameDataDao = database.gameDataDao()
    
    @Provides
    fun provideDiscipleDao(database: GameDatabase): DiscipleDao = database.discipleDao()
    
    @Provides
    fun provideEquipmentStackDao(database: GameDatabase): EquipmentStackDao = database.equipmentStackDao()

    @Provides
    fun provideEquipmentInstanceDao(database: GameDatabase): EquipmentInstanceDao = database.equipmentInstanceDao()

    @Provides
    fun provideManualStackDao(database: GameDatabase): ManualStackDao = database.manualStackDao()

    @Provides
    fun provideManualInstanceDao(database: GameDatabase): ManualInstanceDao = database.manualInstanceDao()

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
    fun provideDiscipleCoreDao(database: GameDatabase): DiscipleCoreDao = database.discipleCoreDao()

    @Provides
    fun provideDiscipleCombatStatsDao(database: GameDatabase): DiscipleCombatStatsDao = database.discipleCombatStatsDao()

    @Provides
    fun provideDiscipleEquipmentDao(database: GameDatabase): DiscipleEquipmentDao = database.discipleEquipmentDao()

    @Provides
    fun provideDiscipleExtendedDao(database: GameDatabase): DiscipleExtendedDao = database.discipleExtendedDao()

    @Provides
    fun provideDiscipleAttributesDao(database: GameDatabase): DiscipleAttributesDao = database.discipleAttributesDao()
    
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
        cacheConfig: CacheConfig,
        applicationScopeProvider: ApplicationScopeProvider
    ): GameDataCacheManager {
        return GameDataCacheManager(context, database, cacheConfig, null, applicationScopeProvider)
    }
    

    @Provides
    @Singleton
    fun provideChangeTracker(): ChangeTracker {
        return ChangeTracker()
    }

    @Provides
    @Singleton
    fun provideChangeLogPersistence(database: GameDatabase): ChangeLogPersistence {
        return ChangeLogPersistence(database)
    }
}
