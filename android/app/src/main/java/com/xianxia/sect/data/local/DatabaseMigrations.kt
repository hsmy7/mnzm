package com.xianxia.sect.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseMigrations {
    
    private const val TAG = "DatabaseMigrations"
    
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migrating 1->2: Adding indices")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_name` ON `disciples` (`name`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_realm_realmLayer` ON `disciples` (`realm`, `realmLayer`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_isAlive_realm` ON `disciples` (`isAlive`, `realm`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_isAlive_status` ON `disciples` (`isAlive`, `status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_discipleType` ON `disciples` (`discipleType`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_loyalty` ON `disciples` (`loyalty`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_disciples_age` ON `disciples` (`age`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_equipment_rarity` ON `equipment` (`rarity`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_equipment_ownerId` ON `equipment` (`ownerId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_equipment_isEquipped` ON `equipment` (`isEquipped`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_manuals_rarity` ON `manuals` (`rarity`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_manuals_ownerId` ON `manuals` (`ownerId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_pills_rarity` ON `pills` (`rarity`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_pills_quantity` ON `pills` (`quantity`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_materials_category` ON `materials` (`category`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_materials_quantity` ON `materials` (`quantity`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_herbs_quantity` ON `herbs` (`quantity`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_seeds_quantity` ON `seeds` (`quantity`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_exploration_teams_status` ON `exploration_teams` (`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_events_timestamp` ON `game_events` (`timestamp`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_game_events_year` ON `game_events` (`year`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_battle_logs_timestamp` ON `battle_logs` (`timestamp`)")
        }
    }
    
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migrating 2->3: Adding change_log table")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS change_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    table_name TEXT NOT NULL,
                    record_id TEXT NOT NULL,
                    operation TEXT NOT NULL,
                    old_value BLOB,
                    new_value BLOB,
                    timestamp INTEGER NOT NULL,
                    synced INTEGER NOT NULL DEFAULT 0,
                    sync_version INTEGER NOT NULL DEFAULT 0
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_change_log_table_record ON change_log (table_name, record_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_change_log_timestamp ON change_log (timestamp)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_change_log_synced ON change_log (synced)")
        }
    }
    
    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migrating 3->4: Adding alchemy_slots table")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS alchemy_slots (
                    id TEXT NOT NULL PRIMARY KEY,
                    slotIndex INTEGER NOT NULL,
                    recipeId TEXT,
                    recipeName TEXT NOT NULL,
                    pillName TEXT NOT NULL,
                    pillRarity INTEGER NOT NULL,
                    startYear INTEGER NOT NULL,
                    startMonth INTEGER NOT NULL,
                    duration INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    successRate REAL NOT NULL,
                    requiredMaterials TEXT NOT NULL
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_alchemy_slots_slotIndex ON alchemy_slots (slotIndex)")
        }
    }
    
    val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migrating 4->5: Adding performance indices")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_disciple_slot_alive ON disciple (slot, isAlive)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_disciple_slot_level ON disciple (slot, level)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_equipment_slot_rarity ON equipment (slot, rarity)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_battle_log_slot_time ON battle_log (slot, timestamp)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_event_slot_time ON game_event (slot, timestamp)")
        }
    }
    
    val MIGRATION_5_6: Migration = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migrating 5->6: Adding data_version table")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS data_version (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    table_name TEXT NOT NULL,
                    record_id TEXT NOT NULL,
                    version INTEGER NOT NULL DEFAULT 1,
                    updated_at INTEGER NOT NULL,
                    UNIQUE(table_name, record_id)
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_data_version_table ON data_version (table_name)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_data_version_record ON data_version (record_id)")
        }
    }
    
    val MIGRATION_6_7: Migration = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migrating 6->7: Adding storage_stats table")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS storage_stats (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    stat_type TEXT NOT NULL,
                    stat_key TEXT NOT NULL,
                    stat_value REAL NOT NULL,
                    recorded_at INTEGER NOT NULL
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_storage_stats_type ON storage_stats (stat_type)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_storage_stats_time ON storage_stats (recorded_at)")
        }
    }
    
    val MIGRATION_7_8: Migration = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migrating 7->8: Adding dungeons and recipes tables")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS dungeons (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL,
                    minRealm INTEGER NOT NULL,
                    maxRealm INTEGER NOT NULL,
                    difficulty INTEGER NOT NULL,
                    isUnlocked INTEGER NOT NULL DEFAULT 0,
                    rewards TEXT NOT NULL
                )
            """)
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS recipes (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    type TEXT NOT NULL,
                    description TEXT NOT NULL,
                    requiredMaterials TEXT NOT NULL,
                    outputItemId TEXT NOT NULL,
                    outputItemName TEXT NOT NULL,
                    outputQuantity INTEGER NOT NULL,
                    duration INTEGER NOT NULL,
                    difficulty INTEGER NOT NULL,
                    isUnlocked INTEGER NOT NULL DEFAULT 0
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_dungeons_isUnlocked ON dungeons (isUnlocked)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_recipes_isUnlocked ON recipes (isUnlocked)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_recipes_type ON recipes (type)")
        }
    }
    
    val MIGRATION_8_9: Migration = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migrating 8->9: Adding forge_slots table")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS forge_slots (
                    id TEXT NOT NULL PRIMARY KEY,
                    slotIndex INTEGER NOT NULL,
                    recipeId TEXT,
                    recipeName TEXT NOT NULL,
                    equipmentName TEXT NOT NULL,
                    equipmentRarity INTEGER NOT NULL,
                    startYear INTEGER NOT NULL,
                    startMonth INTEGER NOT NULL,
                    duration INTEGER NOT NULL,
                    status TEXT NOT NULL
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_forge_slots_slotIndex ON forge_slots (slotIndex)")
        }
    }
    
    val MIGRATION_9_10: Migration = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migrating 9->10: Adding production_slots table")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS production_slots (
                    id TEXT NOT NULL PRIMARY KEY,
                    slotIndex INTEGER NOT NULL,
                    buildingType TEXT NOT NULL,
                    status TEXT NOT NULL,
                    recipeId TEXT,
                    recipeName TEXT NOT NULL,
                    startYear INTEGER NOT NULL,
                    startMonth INTEGER NOT NULL,
                    duration INTEGER NOT NULL,
                    assignedDiscipleId TEXT,
                    assignedDiscipleName TEXT NOT NULL,
                    successRate REAL NOT NULL,
                    requiredMaterials TEXT NOT NULL,
                    outputItemId TEXT,
                    outputItemName TEXT NOT NULL,
                    outputItemRarity INTEGER NOT NULL
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_production_slots_building ON production_slots (buildingType)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_production_slots_building_index ON production_slots (buildingType, slotIndex)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_production_slots_status ON production_slots (status)")
        }
    }
    
    val MIGRATION_10_50: Migration = object : Migration(10, 50) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migrating 10->50: Schema consolidation (no-op, version jump)")
        }
    }
    
    val MIGRATION_50_51: Migration = object : Migration(50, 51) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migrating 50->51: Adding additional indices")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_disciples_realm_cultivation ON disciples (realm, cultivation DESC)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_equipment_rarity_level ON equipment (rarity, level DESC)")
        }
    }
    
    val MIGRATION_51_52: Migration = object : Migration(51, 52) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migrating 51->52: Adding manual proficiency indices")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_manuals_type_rarity ON manuals (type, rarity)")
        }
    }
    
    val MIGRATION_52_53: Migration = object : Migration(52, 53) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migrating 52->53: Adding dungeon and recipe indices")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_dungeons_isUnlocked ON dungeons (isUnlocked)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_recipes_isUnlocked ON recipes (isUnlocked)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_recipes_type ON recipes (type)")
        }
    }
    
    val MIGRATION_53_54: Migration = object : Migration(53, 54) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migrating 53->54: Adding change_log table")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS change_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    table_name TEXT NOT NULL,
                    record_id TEXT NOT NULL,
                    operation TEXT NOT NULL,
                    old_value BLOB,
                    new_value BLOB,
                    timestamp INTEGER NOT NULL,
                    synced INTEGER NOT NULL DEFAULT 0,
                    sync_version INTEGER NOT NULL DEFAULT 0
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_change_log_table_record ON change_log (table_name, record_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_change_log_timestamp ON change_log (timestamp)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_change_log_synced ON change_log (synced)")
        }
    }
    
    val MIGRATION_54_55: Migration = object : Migration(54, 55) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migrating 54->55: Adding alchemy_slots and indices")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS alchemy_slots (
                    id TEXT NOT NULL PRIMARY KEY,
                    slotIndex INTEGER NOT NULL,
                    recipeId TEXT,
                    recipeName TEXT NOT NULL,
                    pillName TEXT NOT NULL,
                    pillRarity INTEGER NOT NULL,
                    startYear INTEGER NOT NULL,
                    startMonth INTEGER NOT NULL,
                    duration INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    successRate REAL NOT NULL,
                    requiredMaterials TEXT NOT NULL
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_alchemy_slots_slotIndex ON alchemy_slots (slotIndex)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_disciple_slot_alive ON disciple (slot, isAlive)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_disciple_slot_level ON disciple (slot, level)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_equipment_slot_rarity ON equipment (slot, rarity)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_battle_log_slot_time ON battle_log (slot, timestamp)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_event_slot_time ON game_event (slot, timestamp)")
        }
    }
    
    val MIGRATION_55_56: Migration = object : Migration(55, 56) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migrating 55->56: Adding data_version table")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS data_version (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    table_name TEXT NOT NULL,
                    record_id TEXT NOT NULL,
                    version INTEGER NOT NULL DEFAULT 1,
                    updated_at INTEGER NOT NULL,
                    UNIQUE(table_name, record_id)
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_data_version_table ON data_version (table_name)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_data_version_record ON data_version (record_id)")
        }
    }
    
    val MIGRATION_56_57: Migration = object : Migration(56, 57) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migrating 56->57: Adding storage_stats table")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS storage_stats (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    stat_type TEXT NOT NULL,
                    stat_key TEXT NOT NULL,
                    stat_value REAL NOT NULL,
                    recorded_at INTEGER NOT NULL
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_storage_stats_type ON storage_stats (stat_type)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_storage_stats_time ON storage_stats (recorded_at)")
        }
    }
    
    val MIGRATION_57_58: Migration = object : Migration(57, 58) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migrating 57->58: Disciple entity split with foreign keys")
            
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS disciples_core (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    realm INTEGER NOT NULL,
                    realmLayer INTEGER NOT NULL,
                    cultivation REAL NOT NULL,
                    isAlive INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    discipleType TEXT NOT NULL,
                    age INTEGER NOT NULL,
                    lifespan INTEGER NOT NULL,
                    gender TEXT NOT NULL,
                    spiritRootType TEXT NOT NULL,
                    recruitedMonth INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_disciples_core_name ON disciples_core (name)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_disciples_core_realm ON disciples_core (realm, realmLayer)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_disciples_core_alive_realm ON disciples_core (isAlive, realm)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_disciples_core_alive_status ON disciples_core (isAlive, status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_disciples_core_type ON disciples_core (discipleType)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_disciples_core_age ON disciples_core (age)")
            
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS disciples_combat (
                    discipleId TEXT NOT NULL PRIMARY KEY,
                    baseHp INTEGER NOT NULL,
                    baseMp INTEGER NOT NULL,
                    basePhysicalAttack INTEGER NOT NULL,
                    baseMagicAttack INTEGER NOT NULL,
                    basePhysicalDefense INTEGER NOT NULL,
                    baseMagicDefense INTEGER NOT NULL,
                    baseSpeed INTEGER NOT NULL,
                    hpVariance INTEGER NOT NULL,
                    mpVariance INTEGER NOT NULL,
                    physicalAttackVariance INTEGER NOT NULL,
                    magicAttackVariance INTEGER NOT NULL,
                    physicalDefenseVariance INTEGER NOT NULL,
                    magicDefenseVariance INTEGER NOT NULL,
                    speedVariance INTEGER NOT NULL,
                    pillPhysicalAttackBonus REAL NOT NULL,
                    pillMagicAttackBonus REAL NOT NULL,
                    pillPhysicalDefenseBonus REAL NOT NULL,
                    pillMagicDefenseBonus REAL NOT NULL,
                    pillHpBonus REAL NOT NULL,
                    pillMpBonus REAL NOT NULL,
                    pillSpeedBonus REAL NOT NULL,
                    pillEffectDuration INTEGER NOT NULL,
                    battlesWon INTEGER NOT NULL,
                    totalCultivation INTEGER NOT NULL,
                    breakthroughCount INTEGER NOT NULL,
                    breakthroughFailCount INTEGER NOT NULL,
                    FOREIGN KEY (discipleId) REFERENCES disciples_core(id) ON DELETE CASCADE ON UPDATE CASCADE
                )
            """)
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_disciples_combat_disciple ON disciples_combat (discipleId)")
            
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS disciples_equipment (
                    discipleId TEXT NOT NULL PRIMARY KEY,
                    weaponId TEXT,
                    armorId TEXT,
                    bootsId TEXT,
                    accessoryId TEXT,
                    weaponNurture TEXT,
                    armorNurture TEXT,
                    bootsNurture TEXT,
                    accessoryNurture TEXT,
                    storageBagItems TEXT,
                    storageBagSpiritStones INTEGER NOT NULL,
                    spiritStones INTEGER NOT NULL,
                    soulPower INTEGER NOT NULL,
                    FOREIGN KEY (discipleId) REFERENCES disciples_core(id) ON DELETE CASCADE ON UPDATE CASCADE
                )
            """)
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_disciples_equipment_disciple ON disciples_equipment (discipleId)")
            
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS disciples_extended (
                    discipleId TEXT NOT NULL PRIMARY KEY,
                    manualIds TEXT,
                    talentIds TEXT,
                    manualMasteries TEXT,
                    statusData TEXT,
                    cultivationSpeedBonus REAL NOT NULL,
                    cultivationSpeedDuration INTEGER NOT NULL,
                    partnerId TEXT,
                    partnerSectId TEXT,
                    parentId1 TEXT,
                    parentId2 TEXT,
                    lastChildYear INTEGER NOT NULL,
                    griefEndYear INTEGER,
                    monthlyUsedPillIds TEXT,
                    usedExtendLifePillIds TEXT,
                    hasReviveEffect INTEGER NOT NULL,
                    hasClearAllEffect INTEGER NOT NULL,
                    FOREIGN KEY (discipleId) REFERENCES disciples_core(id) ON DELETE CASCADE ON UPDATE CASCADE
                )
            """)
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_disciples_extended_disciple ON disciples_extended (discipleId)")
            
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS disciples_attributes (
                    discipleId TEXT NOT NULL PRIMARY KEY,
                    intelligence INTEGER NOT NULL,
                    charm INTEGER NOT NULL,
                    loyalty INTEGER NOT NULL,
                    comprehension INTEGER NOT NULL,
                    artifactRefining INTEGER NOT NULL,
                    pillRefining INTEGER NOT NULL,
                    spiritPlanting INTEGER NOT NULL,
                    teaching INTEGER NOT NULL,
                    morality INTEGER NOT NULL,
                    salaryPaidCount INTEGER NOT NULL,
                    salaryMissedCount INTEGER NOT NULL,
                    FOREIGN KEY (discipleId) REFERENCES disciples_core(id) ON DELETE CASCADE ON UPDATE CASCADE
                )
            """)
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_disciples_attributes_disciple ON disciples_attributes (discipleId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_disciples_attributes_loyalty ON disciples_attributes (loyalty)")
            
            db.execSQL("PRAGMA foreign_keys = ON")
        }
    }
    
    val MIGRATION_58_59: Migration = object : Migration(58, 59) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migrating 58->59: Adding alchemy_slots and production_slots tables")
            
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS alchemy_slots (
                    id TEXT NOT NULL PRIMARY KEY,
                    slotIndex INTEGER NOT NULL,
                    recipeId TEXT,
                    recipeName TEXT NOT NULL,
                    pillName TEXT NOT NULL,
                    pillRarity INTEGER NOT NULL,
                    startYear INTEGER NOT NULL,
                    startMonth INTEGER NOT NULL,
                    duration INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    successRate REAL NOT NULL,
                    requiredMaterials TEXT NOT NULL
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS index_alchemy_slots_slotIndex ON alchemy_slots (slotIndex)")
            
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS production_slots (
                    id TEXT NOT NULL PRIMARY KEY,
                    slotIndex INTEGER NOT NULL,
                    buildingType TEXT NOT NULL,
                    status TEXT NOT NULL,
                    recipeId TEXT,
                    recipeName TEXT NOT NULL,
                    startYear INTEGER NOT NULL,
                    startMonth INTEGER NOT NULL,
                    duration INTEGER NOT NULL,
                    assignedDiscipleId TEXT,
                    assignedDiscipleName TEXT NOT NULL,
                    successRate REAL NOT NULL,
                    requiredMaterials TEXT NOT NULL,
                    outputItemId TEXT,
                    outputItemName TEXT NOT NULL,
                    outputItemRarity INTEGER NOT NULL
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_production_slots_building ON production_slots (buildingType)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_production_slots_building_index ON production_slots (buildingType, slotIndex)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_production_slots_status ON production_slots (status)")
        }
    }
    
    val MIGRATION_59_60: Migration = object : Migration(59, 60) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Log.i(TAG, "Migrating 59->60: Database consolidation - ensuring WAL mode and indices")
            
            db.execSQL("PRAGMA journal_mode = WAL")
            db.execSQL("PRAGMA foreign_keys = ON")
            
            db.execSQL("CREATE INDEX IF NOT EXISTS index_dungeons_isUnlocked ON dungeons (isUnlocked)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_recipes_isUnlocked ON recipes (isUnlocked)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_recipes_type ON recipes (type)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_forge_slots_slotIndex ON forge_slots (slotIndex)")
        }
    }
    
    val ALL_MIGRATIONS: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_50,
        MIGRATION_50_51,
        MIGRATION_51_52,
        MIGRATION_52_53,
        MIGRATION_53_54,
        MIGRATION_54_55,
        MIGRATION_55_56,
        MIGRATION_56_57,
        MIGRATION_57_58,
        MIGRATION_58_59,
        MIGRATION_59_60
    )
    
    fun getMigrationPath(from: Int, to: Int): List<Migration> {
        return ALL_MIGRATIONS.filter { migration ->
            migration.startVersion >= from && migration.endVersion <= to
        }
    }
    
    fun hasCompletePath(from: Int, to: Int): Boolean {
        var current = from
        while (current < to) {
            val hasMigration = ALL_MIGRATIONS.any { it.startVersion == current }
            if (!hasMigration) return false
            current++
        }
        return true
    }
}
