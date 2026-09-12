package com.xianxia.sect.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import com.xianxia.sect.data.local.RoomMigrationSupport.applyMigrationsSequentially
import com.xianxia.sect.data.local.RoomMigrationSupport.columnDefault
import com.xianxia.sect.data.local.RoomMigrationSupport.columnExists
import com.xianxia.sect.data.local.RoomMigrationSupport.columnIsNotNull
import com.xianxia.sect.data.local.RoomMigrationSupport.createDatabaseFromSchema
import com.xianxia.sect.data.local.RoomMigrationSupport.indexExists
import com.xianxia.sect.data.local.RoomMigrationSupport.primaryKeyExists
import com.xianxia.sect.data.local.RoomMigrationSupport.quotedColumnNamesExcluding
import com.xianxia.sect.data.local.RoomMigrationSupport.tableExists
import com.xianxia.sect.data.local.RoomMigrationSupport.testSingleMigration

// 早期迁移(v2→v33)单列/单表变更测试——从 RoomMigrationTest 拆出的同域测试类。
// 共享测试基建(建库/迁移链执行/单迁移断言)在 RoomMigrationTestKit。

/**
 * 早期迁移(v2→v33)逐版本 schema 变更测试。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoomMigrationLegacyTest {

    companion object {
        private val M2_3 = MIGRATION_2_3
        private val M3_4 = MIGRATION_3_4
        private val M4_5 = MIGRATION_4_5
        private val M5_6 = MIGRATION_5_6
        private val M6_7 = MIGRATION_6_7
        private val M7_8 = MIGRATION_7_8
        private val M8_9 = MIGRATION_8_9
        private val M9_10 = MIGRATION_9_10
        private val M10_11 = MIGRATION_10_11
        private val M11_12 = MIGRATION_11_12
        private val M12_13 = MIGRATION_12_13
        private val M13_14 = MIGRATION_13_14
        private val M14_15 = MIGRATION_14_15
        private val M15_16 = MIGRATION_15_16
        private val M22_23 = MIGRATION_22_23
        private val M23_24 = MIGRATION_23_24
        private val M24_25 = MIGRATION_24_25
    }

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
            "m_10_11", 2, 11,
            listOf(M2_3, M3_4, M4_5, M5_6, M6_7, M7_8, M8_9, M9_10, M10_11),
            "game_data", "vassalContracts"
        )
    }

    @Test
    fun `MIGRATION_11_TO_12 adds map_seed to game_data`() {
        testSingleMigration(
            "m_11_12", 2, 12,
            listOf(M2_3, M3_4, M4_5, M5_6, M6_7, M7_8, M8_9, M9_10, M10_11, M11_12),
            "game_data", "map_seed"
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

    @Test
    fun `MIGRATION_13_TO_14 adds cultivationCheckpoint to disciples`() {
        testSingleMigration(
            "m_13_14_cp", 13, 14, listOf(M13_14), "disciples", "cultivationCheckpoint"
        )
    }

    @Test
    fun `MIGRATION_13_TO_14 adds cultivationCheckpointGameMonth to disciples`() {
        testSingleMigration(
            "m_13_14_cpm", 13, 14, listOf(M13_14), "disciples", "cultivationCheckpointGameMonth"
        )
    }

    @Test
    fun `MIGRATION_14_TO_15 adds discipleDesertionPopup to game_data`() {
        testSingleMigration(
            "m_14_15", 14, 15, listOf(M14_15), "game_data", "discipleDesertionPopup"
        )
    }

    @Test
    fun `MIGRATION_15_TO_16 adds showAllAvailableDisciples to game_data`() {
        testSingleMigration(
            "m_15_16", 15, 16, listOf(M15_16), "game_data", "showAllAvailableDisciples"
        )
    }

    @Test
    fun `MIGRATION_22_TO_23 removes discipleDesertionPopup from game_data`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_22_23_remove"
        context.deleteDatabase(dbName)
        try {
            val db = createDatabaseFromSchema(context, dbName, 22)

            assertTrue("discipleDesertionPopup should exist before v23 migration",
                columnExists(db, "game_data", "discipleDesertionPopup"))
            // 验证 v22 schema 有 PRIMARY KEY
            assertTrue("PK should exist before v23 migration",
                primaryKeyExists(db, "game_data"))

            applyMigrationsSequentially(db, listOf(M22_23))

            assertFalse("discipleDesertionPopup should be removed after v23 migration",
                columnExists(db, "game_data", "discipleDesertionPopup"))
            // 验证修复后的 M22_23 仍保留约束
            assertTrue("PRIMARY KEY should be preserved after v23 migration",
                primaryKeyExists(db, "game_data"))
            assertTrue("NOT NULL should be preserved on sectName",
                columnIsNotNull(db, "game_data", "sectName"))
            assertEquals("DEFAULT should be preserved on save_version",
                "0", columnDefault(db, "game_data", "save_version"))
            // 验证索引重建
            assertTrue("index_game_data_slot_id should exist after v23 migration",
                indexExists(db, "game_data", "index_game_data_slot_id"))

            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `MIGRATION_23_TO_24 rebuilds storage_bags with composite primary key`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_23_24_pk"
        context.deleteDatabase(dbName)
        try {
            // 从 v23 schema 创建
            val db = createDatabaseFromSchema(context, dbName, 23)

            // 验证迁移前 storage_bags 表存在
            assertTrue("storage_bags should exist before v24 migration",
                tableExists(db, "storage_bags"))

            // 写入一条测试数据（旧 PK 模式下 slot_id 为 0）
            db.execSQL("INSERT INTO storage_bags (id, slot_id, name, rarity, description, quantity, isLocked) VALUES " +
                "('test_id_1', 0, '测试储物袋', 1, '', 1, 0)")

            // 应用 v23→v24 迁移
            applyMigrationsSequentially(db, listOf(M23_24))

            // 验证迁移后表存在
            assertTrue("storage_bags should exist after v24 migration",
                tableExists(db, "storage_bags"))

            // 验证 slot_id 从 0 被修复为 1
            val cursor = db.query("SELECT slot_id FROM storage_bags WHERE id = 'test_id_1'", emptyArray())
            cursor.use {
                assertTrue("Row should exist", it.moveToFirst())
                val slotId = it.getInt(it.getColumnIndexOrThrow("slot_id"))
                assertEquals("slot_id should be migrated from 0 to 1", 1, slotId)
            }

            // 验证复合主键：相同 id 不同 slot_id 可以同时存在
            db.execSQL("INSERT INTO storage_bags (id, slot_id, name, rarity, description, quantity, isLocked) VALUES " +
                "('test_id_1', 2, '跨槽位测试', 1, '', 1, 0)")
            val cursor2 = db.query("SELECT count(*) FROM storage_bags WHERE id = 'test_id_1'", emptyArray())
            cursor2.use {
                assertTrue("Row should exist", it.moveToFirst())
                val count = it.getInt(0)
                assertEquals("Two rows with same id but different slot_id should coexist", 2, count)
            }

            // 验证索引存在
            assertTrue("index_storage_bags_slot_id should exist after v24 migration",
                indexExists(db, "storage_bags", "index_storage_bags_slot_id"))

            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `MIGRATION_24_TO_25 fixes broken constraints from CTAS on game_data`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_24_25_fix"
        context.deleteDatabase(dbName)
        try {
            // Step 1: 从 v22 schema 创建数据库（包含 discipleDesertionPopup 列）
            val db = createDatabaseFromSchema(context, dbName, 22)

            // Step 2: 模拟旧版 MIGRATION_22_23 的 CTAS 行为（故意破坏约束）
            val columnsBefore = quotedColumnNamesExcluding(db, "game_data", "discipleDesertionPopup")
            val colList = columnsBefore.joinToString(", ")
            db.execSQL("ALTER TABLE game_data RENAME TO game_data_old")
            db.execSQL("CREATE TABLE game_data AS SELECT $colList FROM game_data_old")
            db.execSQL("DROP TABLE game_data_old")
            db.execSQL("PRAGMA user_version = 23")

            // Step 3: 确认 CTAS 后约束被破坏
            // 验证 PRIMARY KEY 不存在
            assertFalse("PK should be lost after CTAS",
                primaryKeyExists(db, "game_data"))
            // 验证一些列的 NOT NULL 被丢失
            assertTrue("game_data should still have sectName column",
                columnExists(db, "game_data", "sectName"))

            // Step 4: 应用 MIGRATION_23_24 和 MIGRATION_24_25
            applyMigrationsSequentially(db, listOf(M23_24, M24_25))

            // Step 5: 验证修复后约束已恢复
            // 验证 PRIMARY KEY 已重建
            assertTrue("PRIMARY KEY should be restored after v25 migration",
                primaryKeyExists(db, "game_data"))
            // 验证 NOT NULL 约束已恢复
            assertTrue("sectName should have NOT NULL after v25 migration",
                columnIsNotNull(db, "game_data", "sectName"))
            assertTrue("spiritStones should have NOT NULL after v25 migration",
                columnIsNotNull(db, "game_data", "spiritStones"))
            // 验证 DEFAULT 值已恢复
            assertEquals("save_version should have DEFAULT 0",
                "0", columnDefault(db, "game_data", "save_version"))
            assertEquals("bloodRefinements should have DEFAULT '{}'",
                "'{}'", columnDefault(db, "game_data", "bloodRefinements"))
            assertEquals("map_seed should have DEFAULT 0",
                "0", columnDefault(db, "game_data", "map_seed"))
            // 验证 discipleDesertionPopup 列仍被排除
            assertFalse("discipleDesertionPopup should not exist after v25 migration",
                columnExists(db, "game_data", "discipleDesertionPopup"))
            // 验证所有 5 个索引已重建
            assertTrue("index_game_data_slot_id should exist",
                indexExists(db, "game_data", "index_game_data_slot_id"))
            assertTrue("index_game_data_lastSaveTime should exist",
                indexExists(db, "game_data", "index_game_data_lastSaveTime"))
            assertTrue("index_game_data_gameYear_gameMonth should exist",
                indexExists(db, "game_data", "index_game_data_gameYear_gameMonth"))
            assertTrue("index_game_data_sectName should exist",
                indexExists(db, "game_data", "index_game_data_sectName"))
            assertTrue("index_game_data_spiritStones should exist",
                indexExists(db, "game_data", "index_game_data_spiritStones"))

            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    // ==================== 全量迁移测试 ====================
}
