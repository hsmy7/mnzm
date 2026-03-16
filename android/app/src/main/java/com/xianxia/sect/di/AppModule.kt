package com.xianxia.sect.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.data.local.*
import com.xianxia.sect.data.GameRepository
import com.xianxia.sect.data.SessionManager
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
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()
    }
    
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
    @Singleton
    fun provideGameEngine(): GameEngine = GameEngine()
    
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
}
