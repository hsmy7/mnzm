package com.xianxia.sect.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.xianxia.sect.core.model.*
import com.xianxia.sect.core.model.ModelConverters

@Database(
    entities = [
        GameData::class,
        Disciple::class,
        Equipment::class,
        Manual::class,
        Pill::class,
        Material::class,
        Seed::class,
        Herb::class,
        ExplorationTeam::class,
        BuildingSlot::class,
        GameEvent::class,
        Dungeon::class,
        Recipe::class,
        BattleLog::class,
        WarTeam::class,
        ForgeSlot::class
    ],
    version = 41,
    exportSchema = false
)
@TypeConverters(ModelConverters::class)
abstract class GameDatabase : RoomDatabase() {
    abstract fun gameDataDao(): GameDataDao
    abstract fun discipleDao(): DiscipleDao
    abstract fun equipmentDao(): EquipmentDao
    abstract fun manualDao(): ManualDao
    abstract fun pillDao(): PillDao
    abstract fun materialDao(): MaterialDao
    abstract fun seedDao(): SeedDao
    abstract fun herbDao(): HerbDao
    abstract fun explorationTeamDao(): ExplorationTeamDao
    abstract fun buildingSlotDao(): BuildingSlotDao
    abstract fun gameEventDao(): GameEventDao
    abstract fun dungeonDao(): DungeonDao
    abstract fun recipeDao(): RecipeDao
    abstract fun battleLogDao(): BattleLogDao
    abstract fun warTeamDao(): WarTeamDao
    abstract fun forgeSlotDao(): ForgeSlotDao
}
