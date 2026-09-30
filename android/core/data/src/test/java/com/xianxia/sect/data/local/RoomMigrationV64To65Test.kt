package com.xianxia.sect.data.local

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 迁移 64→65 测试（AI 洞府探索队伍链退役：game_data/world_map_state 双表删
 * `aiCaveTeams` 零消费死列；判归取证见生产迁移 KDoc——写入方已随 W4-D/D5
 * 死代码清零删除，全仓零构造、零读写、C++ 零镜像）。
 *
 * ## 本类要证的四件事
 * 1. **删得准**：迁移后两表列集**精确等于**迁移前减 `aiCaveTeams`（多删一列红、少删也红）；
 * 2. **不丢玩家数据**：种子行迁移后行数不变，其余列值逐列保留；
 * 3. **Room 认账**：v64 种子库经真实 `Room.databaseBuilder` 升到 v65，触发
 *    `onValidateSchema` 的列级全等校验（Entity 已无此列，库里残留即崩）；
 * 4. **幂等**：列已删除时重放不抛错、不改列集。
 *
 * ⚠️ 读取按 `PRAGMA table_info` 显式列清单（Robolectric legacy cursor 缓存陷阱，
 * 先例见 `RoomMigrationV52To53Test` 类 KDoc）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoomMigrationV64To65Test {

    private fun columnsOf(db: SupportSQLiteDatabase, table: String): List<String> {
        val names = mutableListOf<String>()
        db.query("PRAGMA table_info($table)").use { cursor ->
            while (cursor.moveToNext()) {
                names += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
        }
        return names
    }

    /** v64 种子库：game_data 全列最小行（PRAGMA 动态列集，含 aiCaveTeams）+ world_map_state 全列标记行 */
    private fun withSeededV64Db(dbName: String, block: (SupportSQLiteDatabase) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(dbName)
        try {
            val db = RoomMigrationSupport.createDatabaseFromSchema(context, dbName, 64)
            insertMinimalGameDataV64(db)
            db.execSQL(
                "INSERT INTO world_map_state (slot_id, worldMapSects, aiSectDisciples, " +
                    "cultivatorCaves, caveExplorationTeams, aiCaveTeams, worldLevels) VALUES (" +
                    "1, 'V65_WORLDMAPSECTS', 'V65_AISECTDISCIPLES', 'V65_CULTIVATORCAVES', " +
                    "'V65_CAVEEXPLORATIONTEAMS', 'V65_AICAVETEAMS', 'V65_WORLDLEVELS')"
            )
            block(db)
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    /** v64 列集全量 INSERT（RoomMigrationV53To54Test.insertSeedGameData 同款 PRAGMA 动态习语） */
    private fun insertMinimalGameDataV64(db: SupportSQLiteDatabase) {
        data class Col(val name: String, val type: String, val dflt: String?)
        val cols = mutableListOf<Col>()
        db.query("PRAGMA table_info(game_data)").use { cursor ->
            while (cursor.moveToNext()) {
                cols += Col(
                    name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                    type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                    dflt = cursor.getString(cursor.getColumnIndexOrThrow("dflt_value"))
                )
            }
        }
        val values = cols.joinToString(", ") { col ->
            when {
                col.name == "id" -> "'game_data_1'"
                col.name == "slot_id" -> "1"
                col.name == "sectName" -> "'测试宗门'"
                col.type.equals("INTEGER", true) -> col.dflt ?: "0"
                else -> col.dflt ?: "''"
            }
        }
        db.execSQL("INSERT INTO game_data (${cols.joinToString(", ") { it.name }}) VALUES ($values)")
    }

    @Test
    fun `migration drops aiCaveTeams from exactly the two tables`() {
        withSeededV64Db("m_64_65_drop_column") { db ->
            val gdBefore = columnsOf(db, "game_data")
            val wmBefore = columnsOf(db, "world_map_state")
            assertTrue("v64 种子库必须含 aiCaveTeams 列（前置自证）", "aiCaveTeams" in gdBefore)
            assertTrue("v64 种子库必须含 aiCaveTeams 列（前置自证）", "aiCaveTeams" in wmBefore)

            RoomMigrationSupport.applyMigrationsSequentially(db, listOf(MIGRATION_64_65))

            assertEquals(
                "game_data 列集必须恰为迁移前减 aiCaveTeams",
                gdBefore - "aiCaveTeams",
                columnsOf(db, "game_data")
            )
            assertEquals(
                "world_map_state 列集必须恰为迁移前减 aiCaveTeams",
                wmBefore - "aiCaveTeams",
                columnsOf(db, "world_map_state")
            )
            assertFalse("aiCaveTeams" in columnsOf(db, "game_data"))
            assertFalse("aiCaveTeams" in columnsOf(db, "world_map_state"))
        }
    }

    @Test
    fun `migration preserves existing rows in both tables`() {
        withSeededV64Db("m_64_65_rows_survive") { db ->
            RoomMigrationSupport.applyMigrationsSequentially(db, listOf(MIGRATION_64_65))

            db.query("SELECT COUNT(*) FROM game_data").use { cursor ->
                cursor.moveToFirst()
                assertEquals("game_data 种子行必须保留", 1, cursor.getInt(0))
            }
            db.query("SELECT sectName FROM game_data WHERE slot_id = 1").use { cursor ->
                cursor.moveToFirst()
                assertEquals("玩家数据必须逐值保留", "测试宗门", cursor.getString(0))
            }
            db.query(
                "SELECT worldMapSects, aiSectDisciples, cultivatorCaves, " +
                    "caveExplorationTeams, worldLevels FROM world_map_state WHERE slot_id = 1"
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals("V65_WORLDMAPSECTS", cursor.getString(0))
                assertEquals("V65_AISECTDISCIPLES", cursor.getString(1))
                assertEquals("V65_CULTIVATORCAVES", cursor.getString(2))
                assertEquals("V65_CAVEEXPLORATIONTEAMS", cursor.getString(3))
                assertEquals("V65_WORLDLEVELS", cursor.getString(4))
            }
        }
    }

    @Test
    fun `migration passes real Room schema validation`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_64_65_room_validate"
        context.deleteDatabase(dbName)
        try {
            RoomMigrationSupport.createDatabaseFromSchema(context, dbName, 64).close()
            val db = Room.databaseBuilder(context, GameDatabase::class.java, dbName)
                // 迁移链取自单点登记表——`@Database(version)` 递增无需改本文件
                .addMigrations(*ALL_MIGRATIONS)
                .build()
            db.openHelper.writableDatabase
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `migration is idempotent when column already absent`() {
        withSeededV64Db("m_64_65_idempotent") { db ->
            RoomMigrationSupport.applyMigrationsSequentially(db, listOf(MIGRATION_64_65))
            val once = columnsOf(db, "game_data")
            // 二次执行：rebuildTableDroppingColumns 对不存在的列直接返回，不得抛错
            RoomMigrationSupport.applyMigrationsSequentially(db, listOf(MIGRATION_64_65))
            assertEquals("幂等重放不得改变列集", once, columnsOf(db, "game_data"))
        }
    }
}
