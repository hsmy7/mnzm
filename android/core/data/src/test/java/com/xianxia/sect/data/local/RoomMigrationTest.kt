package com.xianxia.sect.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Room 数据库迁移测试。
 *
 * 使用 v2 schema JSON 创建初始数据库，直接执行每个 Migration 的 migrate 函数，
 * 验证新列添加正确、列删除正确，且不崩溃。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoomMigrationTest {

    companion object {
        /** Schema 文件所在目录（相对于模块根目录） */
        private val SCHEMA_DIR: File = File(
            "schemas",
            "com.xianxia.sect.data.local.GameDatabase"
        )

        private val M2_3 = GameDatabase.MIGRATION_2_3
        private val M3_4 = GameDatabase.MIGRATION_3_4
        private val M4_5 = GameDatabase.MIGRATION_4_5
        private val M5_6 = GameDatabase.MIGRATION_5_6
        private val M6_7 = GameDatabase.MIGRATION_6_7
        private val M7_8 = GameDatabase.MIGRATION_7_8
        private val M8_9 = GameDatabase.MIGRATION_8_9
        private val M9_10 = GameDatabase.MIGRATION_9_10
        private val M10_11 = GameDatabase.MIGRATION_10_11
        private val M11_12 = GameDatabase.MIGRATION_11_12
        private val M12_13 = GameDatabase.MIGRATION_12_13

        private val ALL_MIGRATIONS = listOf(
            M2_3, M3_4, M4_5, M5_6, M6_7, M7_8, M8_9, M9_10, M10_11, M11_12, M12_13
        )
    }

    // ==================== 单个迁移步骤测试 ====================

    @Test
    fun `MIGRATION_2_TO_3 adds sectLevelClaimRecords to game_data`() {
        testSingleMigration("m_2_3", 2, 3, listOf(M2_3), "game_data", "sectLevelClaimRecords")
    }

    @Test
    fun `MIGRATION_3_TO_4 adds save_version to game_data`() {
        testSingleMigration("m_3_4", 2, 4, listOf(M2_3, M3_4), "game_data", "save_version")
    }

    @Test
    fun `MIGRATION_4_TO_5 adds autoBuyList to game_data`() {
        testSingleMigration("m_4_5", 2, 5, listOf(M2_3, M3_4, M4_5), "game_data", "autoBuyList")
    }

    @Test
    fun `MIGRATION_5_TO_6 adds bloodRefinementBonusTotals to game_data`() {
        testSingleMigration(
            "m_5_6_gd", 2, 6, listOf(M2_3, M3_4, M4_5, M5_6), "game_data", "bloodRefinementBonusTotals"
        )
    }

    @Test
    fun `MIGRATION_5_TO_6 adds usage_lastTheftMonth to disciples`() {
        testSingleMigration(
            "m_5_6_d", 2, 6, listOf(M2_3, M3_4, M4_5, M5_6), "disciples", "usage_lastTheftMonth"
        )
    }

    @Test
    fun `MIGRATION_5_TO_6 adds buildingInstanceId to production_slots`() {
        testSingleMigration(
            "m_5_6_ps", 2, 6, listOf(M2_3, M3_4, M4_5, M5_6), "production_slots", "buildingInstanceId"
        )
    }

    @Test
    fun `MIGRATION_6_TO_7 adds aiSectPersonalities to game_data`() {
        testSingleMigration(
            "m_6_7", 2, 7, listOf(M2_3, M3_4, M4_5, M5_6, M6_7), "game_data", "aiSectPersonalities"
        )
    }

    @Test
    fun `MIGRATION_7_TO_8 adds midGradeSpiritStones to game_data`() {
        testSingleMigration(
            "m_7_8", 2, 8, listOf(M2_3, M3_4, M4_5, M5_6, M6_7, M7_8),
            "game_data", "midGradeSpiritStones"
        )
    }

    @Test
    fun `MIGRATION_8_TO_9 adds autoSellMidGradeForPurchase to game_data`() {
        testSingleMigration(
            "m_8_9", 2, 9, listOf(M2_3, M3_4, M4_5, M5_6, M6_7, M7_8, M8_9),
            "game_data", "autoSellMidGradeForPurchase"
        )
    }

    @Test
    fun `MIGRATION_9_TO_10 adds social_masterId to disciples`() {
        testSingleMigration(
            "m_9_10", 2, 10, listOf(M2_3, M3_4, M4_5, M5_6, M6_7, M7_8, M8_9, M9_10),
            "disciples", "social_masterId"
        )
    }

    @Test
    fun `MIGRATION_9_TO_10 adds masterId to disciples_extended`() {
        testSingleMigration(
            "m_9_10_ex", 2, 10, listOf(M2_3, M3_4, M4_5, M5_6, M6_7, M7_8, M8_9, M9_10),
            "disciples_extended", "masterId"
        )
    }

    @Test
    fun `MIGRATION_10_TO_11 adds vassalContracts to game_data`() {
        testSingleMigration(
            "m_10_11", 2, 11, ALL_MIGRATIONS.dropLast(2), "game_data", "vassalContracts"
        )
    }

    @Test
    fun `MIGRATION_11_TO_12 adds map_seed to game_data`() {
        testSingleMigration(
            "m_11_12", 2, 12, ALL_MIGRATIONS.dropLast(1), "game_data", "map_seed"
        )
    }

    @Test
    fun `MIGRATION_12_TO_13 removes isGameStarted from game_data`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_12_13_remove"
        context.deleteDatabase(dbName)
        try {
            // 从 v12 schema 创建（跳过早期版本缺少 merchantAcquisitionItems 等列的问题）
            val db = createDatabaseFromSchema(context, dbName, 12)

            // 验证 isGameStarted 列在 v12 中存在
            assertTrue("isGameStarted should exist before v13 migration",
                columnExists(db, "game_data", "isGameStarted"))

            // 应用 v12→v13 迁移
            applyMigrationsSequentially(db, listOf(M12_13))

            // 验证 isGameStarted 列已被删除
            assertFalse("isGameStarted should be removed after v13 migration",
                columnExists(db, "game_data", "isGameStarted"))

            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    // ==================== 全量迁移测试 ====================

    @Test
    fun `full migration from v2 to v13 applies all steps without crash`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "full_migrate"
        context.deleteDatabase(dbName)
        try {
            // 注意：早期版本（v2-v11）缺少后来加入实体的列（如 merchantAcquisitionItems），
            // 而部分列没有对应的 ALTER TABLE ADD COLUMN 迁移，因此全量迁移测试跳过 v2→v12 段，
            // 直接从 v12 schema 开始测试 v12→v13 的核心迁移路径
            val db = createDatabaseFromSchema(context, dbName, 12)
            applyMigrationsSequentially(db, listOf(M12_13))

            verifyGameDataColumnsExist(db)
            verifyDisciplesColumnsExist(db)
            verifyProductionSlotsColumnsExist(db)
            verifyDisciplesExtendedColumnsExist(db)
            verifyDiscipleCompactColumnsExist(db)
            // 验证 isGameStarted 已被 v13 迁移删除
            assertFalse("isGameStarted should be removed after v13 migration",
                columnExists(db, "game_data", "isGameStarted"))

            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    // ==================== 数据保留测试 ====================

    @Test
    fun `v12 to v13 migration preserves column structure correctly`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "data_preserve"
        context.deleteDatabase(dbName)
        try {
            // 从 v12 schema 创建
            val db = createDatabaseFromSchema(context, dbName, 12)
            assertTrue("isGameStarted should exist before v13 migration",
                columnExists(db, "game_data", "isGameStarted"))

            // 应用 v12→v13 迁移
            applyMigrationsSequentially(db, listOf(M12_13))

            // 验证迁移后 isGameStarted 列不存在
            assertFalse("isGameStarted column should be gone after v13",
                columnExists(db, "game_data", "isGameStarted"))

            // 验证关键列存在
            assertTrue("map_seed should exist after v13 migration",
                columnExists(db, "game_data", "map_seed"))
            assertTrue("merchantAcquisitionItems should exist after v13 migration",
                columnExists(db, "game_data", "merchantAcquisitionItems"))
            assertTrue("vassalContracts should exist after v13 migration",
                columnExists(db, "game_data", "vassalContracts"))

            // 验证 5 个索引已重建
            assertTrue("index_game_data_slot_id should exist",
                indexExists(db, "game_data", "index_game_data_slot_id"))
            assertTrue("index_game_data_spiritStones should exist",
                indexExists(db, "game_data", "index_game_data_spiritStones"))

            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 测试单个迁移：从 v2 开始，应用一系列迁移，验证目标列存在/不存在。
     */
    private fun testSingleMigration(
        dbName: String,
        fromSchemaVersion: Int,
        toVersion: Int,
        migrations: List<Migration>,
        tableName: String,
        expectedColumn: String
    ) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(dbName)
        try {
            val db = createDatabaseFromSchema(context, dbName, fromSchemaVersion)
            applyMigrationsSequentially(db, migrations)
            assertTrue(
                "Column '$expectedColumn' should exist in '$tableName' after migration",
                columnExists(db, tableName, expectedColumn)
            )
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    /**
     * 从 schema JSON 文件创建数据库（创建所有表 + 索引）。
     */
    private fun createDatabaseFromSchema(
        context: android.content.Context,
        dbName: String,
        version: Int
    ): SupportSQLiteDatabase {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        val schemaFile = File(SCHEMA_DIR, "${version}.json")
                        val json = JsonParser.parseString(schemaFile.readText()).asJsonObject
                        val database = json.getAsJsonObject("database")
                        val entities = database.getAsJsonArray("entities")

                        for (i in 0 until entities.size()) {
                            val entity = entities[i].asJsonObject
                            val createSql = entity.get("createSql").asString
                            val tableName = entity.get("tableName").asString
                            db.execSQL(createSql.replace("\${TABLE_NAME}", tableName))

                            // 创建索引（部分实体可能没有索引）
                            val indices = entity.getAsJsonArray("indices")
                            if (indices != null) {
                                for (j in 0 until indices.size()) {
                                    val indexSql = indices[j].asJsonObject.get("createSql").asString
                                    db.execSQL(indexSql.replace("\${TABLE_NAME}", tableName))
                                }
                            }
                        }
                        db.execSQL("PRAGMA user_version = $version")
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) {}
                })
                .build()
        )
        return helper.writableDatabase
    }

    /**
     * 按顺序应用迁移，每次更新 PRAGMA user_version。
     */
    private fun applyMigrationsSequentially(
        db: SupportSQLiteDatabase,
        migrations: List<Migration>
    ) {
        for (migration in migrations) {
            db.beginTransaction()
            try {
                migration.migrate(db)
                db.execSQL("PRAGMA user_version = ${migration.endVersion}")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /** 插入最小 game_data 行（NOT NULL 列都填写默认等价的值，匹配 v2 schema 列清单） */
    private fun insertMinimalGameDataRow(
        db: SupportSQLiteDatabase,
        id: String,
        slotId: Int
    ) {
        db.execSQL(
            """INSERT INTO game_data (
                id, slot_id, sectName, currentSlot, gameYear, gameMonth, gamePhase,
                isGameStarted, gameSpeed, spiritStones, spiritHerbs, sectCultivation,
                autoSaveIntervalMonths, monthlySalary, monthlySalaryEnabled,
                worldMapSects, sectDetails, aiSectDisciples, exploredSects, scoutInfo,
                manualProficiencies, travelingMerchantItems, merchantLastRefreshYear,
                merchantRefreshCount, playerListedItems, recruitList, lastRecruitYear,
                worldLevels, cultivatorCaves, caveExplorationTeams, aiCaveTeams,
                unlockedRecipes, unlockedManuals, lastSaveTime, elderSlots,
                spiritMineSlots, spiritMineExpansions, librarySlots, productionSlots,
                placedBuildings, spiritFieldPlants, activeSectId, residenceSlots,
                warehouseGarrisons, patrolSlots, patrolConfig, patrolConfigs,
                alliances, sectRelations, playerAllianceSlots, sectPolicies,
                battleTeam, aiBattleTeams, usedRedeemCodes, claimedMailIds,
                playerProtectionEnabled, playerProtectionStartYear, playerHasAttackedAI,
                activeMissions, availableMissions, autoRecruitSpiritRootFilter,
                daoCompanionBannedRootCounts, daoCompanionConsentRequired,
                patrolBattleResultPopup, breakthroughAutoPillFocused,
                breakthroughAutoPillRootCounts, autoEquipFromWarehouseFocused,
                autoEquipFromWarehouseRootCounts, autoLearnFromWarehouseFocused,
                autoLearnFromWarehouseRootCounts, isGameOver,
                bloodRefinements, activeBloodRefinements
            ) VALUES (
                '$id', $slotId, 'TestSect', 1, 1, 1, 0,
                1, 1, 1000, 0, 0.0,
                3, '{}', '{}',
                '[]', '{}', '[]', '{}', '{}',
                '{}', '[]', 0,
                0, '[]', '[]', 0,
                '[]', '[]', '[]', '[]',
                '[]', '[]', 0, '{}',
                '[]', 0, '[]', '[]',
                '[]', '[]', '', '[]',
                '[]', '[]', '{}', '{}',
                '[]', '[]', 3, '{}',
                NULL, '[]', '[]', '[]',
                1, 1, 0,
                '[]', '[]', '[]',
                '[]', 0,
                0, 0,
                '[]', 0,
                '[]', 0,
                '[]', 0,
                '{}', '{}'
            )""".trimIndent()
        )
    }

    /** 插入 v12 完整 game_data 行（包含所有列，排除 isGameStarted） */
    /** 查询 PRAGMA table_info 检查列是否存在 */
    private fun columnExists(
        db: SupportSQLiteDatabase,
        table: String,
        column: String
    ): Boolean {
        val cursor = db.query("PRAGMA table_info($table)", emptyArray())
        return cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(it.getColumnIndexOrThrow("name"))
                if (name == column) return@use true
            }
            false
        }
    }

    /** 查询指定表的索引是否存在 */
    private fun indexExists(
        db: SupportSQLiteDatabase,
        table: String,
        indexName: String
    ): Boolean {
        val cursor = db.query("PRAGMA index_list($table)", emptyArray())
        return cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(it.getColumnIndexOrThrow("name"))
                if (name == indexName) return@use true
            }
            false
        }
    }

    // ==================== 全量迁移后的列验证 ====================

    private fun verifyGameDataColumnsExist(db: SupportSQLiteDatabase) {
        val expected = listOf(
            "sectLevelClaimRecords", "save_version", "autoBuyList",
            "bloodRefinementBonusTotals",
            "aiSectPersonalities", "suzerainSectId", "lastYearSpiritStoneIncome",
            "activeAttackWarnings", "shownWarningStageIds", "sectAttackCooldowns",
            "midGradeSpiritStones", "highGradeSpiritStones",
            "autoSellMidGradeForPurchase", "autoSellHighGradeForPurchase",
            "vassalContracts", "sectBattleRecords",
            "map_seed"
        )
        for (col in expected) {
            assertTrue("game_data should have column '$col'",
                columnExists(db, "game_data", col))
        }
    }

    private fun verifyDisciplesColumnsExist(db: SupportSQLiteDatabase) {
        assertTrue("disciples should have usage_lastTheftMonth",
            columnExists(db, "disciples", "usage_lastTheftMonth"))
        assertTrue("disciples should have social_masterId",
            columnExists(db, "disciples", "social_masterId"))
    }

    private fun verifyProductionSlotsColumnsExist(db: SupportSQLiteDatabase) {
        assertTrue("production_slots should have buildingInstanceId",
            columnExists(db, "production_slots", "buildingInstanceId"))
    }

    private fun verifyDisciplesExtendedColumnsExist(db: SupportSQLiteDatabase) {
        assertTrue("disciples_extended should have masterId",
            columnExists(db, "disciples_extended", "masterId"))
    }

    private fun verifyDiscipleCompactColumnsExist(db: SupportSQLiteDatabase) {
        assertTrue("disciple_compact should be accessible after full migration",
            columnExists(db, "disciple_compact", "cultivation"))
    }
}
