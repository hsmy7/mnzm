package com.xianxia.sect.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import org.junit.Assert.*
import java.io.File

// RoomMigration 系列测试的共享基建:按 schema 建库/迁移链执行/单迁移断言。
// 从 RoomMigrationTest 提取,供 RoomMigrationTest/RoomMigrationLegacyTest 共用。

internal object RoomMigrationSupport {

    /** Schema 文件所在目录（相对于模块根目录） */
    internal val SCHEMA_DIR: File = File(
        "schemas",
        "com.xianxia.sect.data.local.GameDatabase"
    )

    // ==================== 辅助方法 ====================

    // ==================== 辅助方法 ====================

    /**
     * 测试单个迁移：从 v2 开始，应用一系列迁移，验证目标列存在/不存在。
     */
    internal fun testSingleMigration(
        dbName: String,
        fromSchemaVersion: Int,
        @Suppress("UnusedParameter") // toVersion: 测试辅助签名对称形参：toVersion 表达迁移链区间语义（调用点可读性）
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
    internal fun createDatabaseFromSchema(
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
                    ) = Unit
                })
                .build()
        )
        return helper.writableDatabase
    }

    /**
     * 按顺序应用迁移，每次更新 PRAGMA user_version。
     */
    internal fun applyMigrationsSequentially(
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

    /** 查询指定表是否存在 */
    internal fun tableExists(
        db: SupportSQLiteDatabase,
        table: String
    ): Boolean {
        val cursor = db.query("PRAGMA table_info($table)", emptyArray())
        return cursor.use { it.moveToFirst() }
    }

    /**
     * 读取表全部列名（排除 excluded，逐个加引号）——供 CTAS 模拟块复制列清单用
     */
    internal fun quotedColumnNamesExcluding(
        db: SupportSQLiteDatabase,
        table: String,
        excluded: String
    ): List<String> {
        val columns = mutableListOf<String>()
        val cursor = db.query("PRAGMA table_info($table)", emptyArray())
        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(it.getColumnIndexOrThrow("name"))
                if (name != excluded) {
                    columns.add("\"$name\"")
                }
            }
        }
        return columns
    }

    /** 查询 PRAGMA table_info 返回列名有序列表 */
    internal fun tableColumns(db: SupportSQLiteDatabase, table: String): List<String> {
        val cursor = db.query("PRAGMA table_info($table)", emptyArray())
        return cursor.use {
            val cols = mutableListOf<String>()
            while (it.moveToNext()) {
                cols.add(it.getString(it.getColumnIndexOrThrow("name")))
            }
            cols
        }
    }


    /** 查询 PRAGMA table_info 检查列是否存在 */
    internal fun columnExists(
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

    /** 查询单列字符串结果（首行首列；无行返回空串） */
    internal fun queryString(db: SupportSQLiteDatabase, sql: String): String {
        return db.query(sql, emptyArray()).use { cursor ->
            var result = ""
            if (cursor.moveToFirst()) result = cursor.getString(0)
            result
        }
    }

    /** 查询指定表的索引是否存在 */
    internal fun indexExists(
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

    /** 验证 v2→v16 迁移后 game_data 的所有列存在 */
    internal fun verifyGameDataColumnsExist(db: SupportSQLiteDatabase) {
        val expected = listOf(
            "sectLevelClaimRecords", "save_version", "autoBuyList",
            "bloodRefinementBonusTotals",
            "aiSectPersonalities", "suzerainSectId", "lastYearSpiritStoneIncome",
            "activeAttackWarnings", "shownWarningStageIds", "sectAttackCooldowns",
            "midGradeSpiritStones", "highGradeSpiritStones",
            "autoSellMidGradeForPurchase", "autoSellHighGradeForPurchase",
            "vassalContracts", "sectBattleRecords",
            "map_seed", "spiritMineLastSettledMonth"
        )
        for (col in expected) {
            assertTrue("game_data should have column '$col'",
                columnExists(db, "game_data", col))
        }
    }

    /** 验证全量链式迁移（v21→v33）后 game_data 的所有列存在 */
    internal fun verifyGameDataColumnsExistFullChain(db: SupportSQLiteDatabase) {
        val expected = listOf(
            "sectLevelClaimRecords", "save_version", "autoBuyList",
            "bloodRefinementBonusTotals", "bloodRefinementPctTotals",
            "aiSectPersonalities", "suzerainSectId", "lastYearSpiritStoneIncome",
            "activeAttackWarnings", "shownWarningStageIds", "sectAttackCooldowns",
            "midGradeSpiritStones", "highGradeSpiritStones",
            "autoSellMidGradeForPurchase", "autoSellHighGradeForPurchase",
            "vassalContracts", "sectBattleRecords",
            "map_seed", "spiritMineLastSettledMonth",
            "gameEventRecords",
            "merchantRefreshChances", "merchantLastRefreshChanceGrantYear",
            "guideClaimedRewardIds", "guideCounters",
            "annual_income_by_source", "annual_expenditure_by_reason",
            "annual_total_income", "annual_total_expenditure",
            "annual_alchemy_count", "annual_forge_count", "annual_herb_count",
            "annual_new_disciples", "annual_deceased_disciples", "annual_deserted_disciples",
            "annual_equipment_by_source", "annual_pill_by_source", "annual_herb_by_source",
            "yearly_reports",
            "open_recruitment_last_paid_month",
            "annual_theft_count",
            "theft_judgements_this_month",
            "soundEnabled",
            "musicEnabled"
        )
        for (col in expected) {
            assertTrue("game_data should have column '$col' after full chain",
                columnExists(db, "game_data", col))
        }
    }

    internal fun verifyDisciplesColumnsExist(db: SupportSQLiteDatabase) {
        // usage_lastTheftMonth 已在 v31 删除（偷盗系统年上限重构）——v36 不应存在
        assertFalse("usage_lastTheftMonth should be removed after v31 migration",
            columnExists(db, "disciples", "usage_lastTheftMonth"))
        assertTrue("disciples should have social_masterId",
            columnExists(db, "disciples", "social_masterId"))
        // v14: 修炼 Checkpoint 列（修炼 VoidForge Checkpoint 快照法）
        assertTrue("disciples should have cultivationCheckpoint",
            columnExists(db, "disciples", "cultivationCheckpoint"))
        assertTrue("disciples should have cultivationCheckpointGameMonth",
            columnExists(db, "disciples", "cultivationCheckpointGameMonth"))
    }

    internal fun verifyProductionSlotsColumnsExist(db: SupportSQLiteDatabase) {
        assertTrue("production_slots should have buildingInstanceId",
            columnExists(db, "production_slots", "buildingInstanceId"))
    }

    /**
     * 历史形态断言：两条链用例（`verifyFullChainColumns` 的链尾在 **v40**）里
     * 镜像表尚未删除，故仍须存在且带当版列。
     * v53 起六表被删的终态断言在 `RoomMigrationV52To53Test`（表集合精确比对，非本清单）。
     */
    internal fun verifyDisciplesExtendedColumnsExist(db: SupportSQLiteDatabase) {
        assertTrue("disciples_extended should have masterId",
            columnExists(db, "disciples_extended", "masterId"))
    }

    internal fun verifyDiscipleCompactColumnsExist(db: SupportSQLiteDatabase) {
        assertTrue("disciple_compact should be accessible after full migration",
            columnExists(db, "disciple_compact", "cultivation"))
    }

    /** 查询指定表是否存在 PRIMARY KEY */
    internal fun primaryKeyExists(
        db: SupportSQLiteDatabase,
        table: String
    ): Boolean {
        val cursor = db.query("PRAGMA table_info($table)", emptyArray())
        return cursor.use {
            while (it.moveToNext()) {
                val pk = it.getInt(it.getColumnIndexOrThrow("pk"))
                if (pk > 0) return@use true
            }
            false
        }
    }

    /** 查询指定列是否有 NOT NULL 约束 */
    /** 查询指定列的 DEFAULT 值 */
    internal fun columnDefault(
        db: SupportSQLiteDatabase,
        table: String,
        column: String
    ): String? {
        val cursor = db.query("PRAGMA table_info($table)", emptyArray())
        return cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(it.getColumnIndexOrThrow("name"))
                if (name == column) {
                    return@use it.getString(it.getColumnIndexOrThrow("dflt_value"))
                }
            }
            null
        }
    }

    internal fun columnIsNotNull(
        db: SupportSQLiteDatabase,
        table: String,
        column: String
    ): Boolean {
        val cursor = db.query("PRAGMA table_info($table)", emptyArray())
        return cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(it.getColumnIndexOrThrow("name"))
                if (name == column) {
                    val notNull = it.getInt(it.getColumnIndexOrThrow("notnull"))
                    return@use notNull == 1
                }
            }
            false
        }
    }

    /** 在 v2 种子库插入最小 game_data 行（v2 createSql 列集），供全链迁移测试使用 */
    internal fun insertMinimalGameDataV2(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO game_data (" +
                "id, slot_id, sectName, currentSlot, gameYear, gameMonth, gamePhase, " +
                "isGameStarted, gameSpeed, spiritStones, spiritHerbs, sectCultivation, " +
                "autoSaveIntervalMonths, monthlySalary, monthlySalaryEnabled, " +
                "worldMapSects, sectDetails, aiSectDisciples, exploredSects, scoutInfo, " +
                "manualProficiencies, travelingMerchantItems, merchantLastRefreshYear, " +
                "merchantRefreshCount, playerListedItems, recruitList, lastRecruitYear, " +
                "worldLevels, cultivatorCaves, caveExplorationTeams, aiCaveTeams, " +
                "unlockedRecipes, unlockedManuals, lastSaveTime, elderSlots, spiritMineSlots, " +
                "spiritMineExpansions, librarySlots, productionSlots, placedBuildings, " +
                "spiritFieldPlants, activeSectId, residenceSlots, warehouseGarrisons, " +
                "patrolSlots, patrolConfig, patrolConfigs, alliances, sectRelations, " +
                "playerAllianceSlots, sectPolicies, battleTeam, aiBattleTeams, " +
                "usedRedeemCodes, claimedMailIds, playerProtectionEnabled, " +
                "playerProtectionStartYear, playerHasAttackedAI, activeMissions, " +
                "availableMissions, autoRecruitSpiritRootFilter, daoCompanionBannedRootCounts, " +
                "daoCompanionConsentRequired, patrolBattleResultPopup, " +
                "breakthroughAutoPillFocused, breakthroughAutoPillRootCounts, " +
                "autoEquipFromWarehouseFocused, autoEquipFromWarehouseRootCounts, " +
                "autoLearnFromWarehouseFocused, autoLearnFromWarehouseRootCounts, isGameOver" +
                ") VALUES (" +
                "'game_data_1', 1, '测试宗门', 0, 1, 1, 0, 1, 1, 100, 0, 0.0, 3, '0', '0', " +
                "'[]', '{}', '{}', '{}', '{}', '{}', '[]', 0, 0, '[]', '[]', 0, '[]', " +
                "'[]', '[]', '[]', '[]', '[]', 0, '{}', '[]', 0, '[]', '[]', '[]', '[]', " +
                "'sect_1', '[]', '[]', '[]', '{}', '{}', '[]', '[]', 0, '{}', NULL, '{}', " +
                "'[]', '[]', 0, 0, 0, '[]', '[]', '[]', '{}', 0, 0, 0, '{}', 0, '{}', 0, '{}', 0" +
                ")"
        )
    }

    /** 全链迁移后的各表列完整性验证 */
    internal fun verifyFullChainColumns(db: SupportSQLiteDatabase) {
        verifyGameDataColumnsExist(db)
        verifyDisciplesColumnsExist(db)
        verifyProductionSlotsColumnsExist(db)
        verifyDisciplesExtendedColumnsExist(db)
        verifyDiscipleCompactColumnsExist(db)
        // 验证 isGameStarted 已被 v13 迁移删除
        assertFalse("isGameStarted should be removed after v13 migration",
            columnExists(db, "game_data", "isGameStarted"))
        // 验证 v14 新增列存在
        assertTrue("cultivationCheckpoint should exist after v14 migration",
            columnExists(db, "disciples", "cultivationCheckpoint"))
        assertTrue("cultivationCheckpointGameMonth should exist after v14 migration",
            columnExists(db, "disciples", "cultivationCheckpointGameMonth"))
        // discipleDesertionPopup 在 v15 加入、v25 移除——全链终点（v39）应不存在
        assertFalse("discipleDesertionPopup should be removed by v25 migration",
            columnExists(db, "game_data", "discipleDesertionPopup"))
        // 验证 v16 新增列存在
        assertTrue("showAllAvailableDisciples should exist after v16 migration",
            columnExists(db, "game_data", "showAllAvailableDisciples"))
        // 验证 v36 新增列存在（体质/词条列）
        assertTrue("physiqueIds should exist after v36 migration",
            columnExists(db, "disciples", "physiqueIds"))
        assertTrue("affixIds should exist after v36 migration",
            columnExists(db, "disciples", "affixIds"))
    }
}

