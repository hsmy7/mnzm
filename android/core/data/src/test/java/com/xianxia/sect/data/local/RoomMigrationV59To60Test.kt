package com.xianxia.sect.data.local

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * 迁移 59→60 测试（双轨时间权威轴 5 列，结算改造 2026-09-27 B3）。
 *
 * game_data 新增 elapsedGameMs/lastSettleGameMs/spiritMineLastSettledGameMs
 * （INTEGER NOT NULL DEFAULT 0）；production_slots 新增
 * startedAtGameMs/completeAtGameMs（同上）。纯加法 ADD COLUMN 兼容旧行；
 * 60.json 由 KSP 自动导出。旧字段全部保留（判据切换随 B5/B6）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoomMigrationV59To60Test {

    companion object {
        private val SCHEMA_DIR: File = File(
            "schemas",
            "com.xianxia.sect.data.local.GameDatabase"
        )

        private val M59_60 = MIGRATION_59_60
    }

    /** 真实 Room 校验：v59 库升级到 v60，触发 onValidateSchema——新列定义与实体注解一致才通过。 */
    @Test
    fun `MIGRATION_59_TO_60 passes real Room schema validation`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_59_60_room_validate"
        context.deleteDatabase(dbName)
        try {
            createDatabaseFromSchema(context, dbName, 59).close()
            val db = Room.databaseBuilder(context, GameDatabase::class.java, dbName)
                .addMigrations(*ALL_MIGRATIONS)
                .build()
            db.openHelper.writableDatabase
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    /** game_data 三列存在、旧行默认 0、既有数据零丢失。 */
    @Test
    fun `MIGRATION_59_TO_60 adds game_data time axis columns with defaults and preserves data`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_59_60_game_data"
        context.deleteDatabase(dbName)
        try {
            val db = createDatabaseFromSchema(context, dbName, 59)
            insertSeedRow(db, id = "sect-1", overrides = mapOf(
                "sectName" to "'青云宗'",
                "gameYear" to "12",
                "spiritMineLastSettledMonth" to "145"
            ))
            M59_60.migrate(db)
            for (col in listOf("elapsedGameMs", "lastSettleGameMs", "spiritMineLastSettledGameMs")) {
                assertTrue("game_data 应有列 $col", columnExists(db, "game_data", col))
            }
            db.query("SELECT elapsedGameMs, lastSettleGameMs, spiritMineLastSettledGameMs, " +
                "sectName, gameYear, spiritMineLastSettledMonth FROM game_data WHERE id = 'sect-1'"
            ).use { cursor ->
                assertTrue("迁移后既有行数据应保留", cursor.moveToFirst())
                assertEquals("elapsedGameMs 旧行默认 = 0", 0L, cursor.getLong(0))
                assertEquals("lastSettleGameMs 旧行默认 = 0", 0L, cursor.getLong(1))
                assertEquals("spiritMineLastSettledGameMs 旧行默认 = 0", 0L, cursor.getLong(2))
                assertEquals("既有列 sectName 零丢失", "青云宗", cursor.getString(3))
                assertEquals("既有列 gameYear 零丢失", 12, cursor.getInt(4))
                assertEquals("旧字段 spiritMineLastSettledMonth 保留原值", 145, cursor.getInt(5))
            }
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    /** production_slots 两列存在、旧行默认 0、既有数据零丢失。 */
    @Test
    fun `MIGRATION_59_TO_60 adds production_slots ms columns and preserves data`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_59_60_production_slots"
        context.deleteDatabase(dbName)
        try {
            val db = createDatabaseFromSchema(context, dbName, 59)
            // v59 production_slots 种子行：按 PRAGMA 列清单生成 INSERT，
            // 关键列写非默认值验证迁移零丢失
            insertSeedProductionSlot(db, id = "slot-1", overrides = mapOf(
                "slotIndex" to "2",
                "startYear" to "3",
                "startMonth" to "7",
                "duration" to "5",
                "completionMonth" to "44"
            ))
            M59_60.migrate(db)
            assertTrue(
                "production_slots 应有列 startedAtGameMs",
                columnExists(db, "production_slots", "startedAtGameMs")
            )
            assertTrue(
                "production_slots 应有列 completeAtGameMs",
                columnExists(db, "production_slots", "completeAtGameMs")
            )
            db.query("SELECT startedAtGameMs, completeAtGameMs, startYear, startMonth, " +
                "duration, completionMonth FROM production_slots WHERE id = 'slot-1'"
            ).use { cursor ->
                assertTrue("迁移后既有行数据应保留", cursor.moveToFirst())
                assertEquals("startedAtGameMs 旧行默认 = 0", 0L, cursor.getLong(0))
                assertEquals("completeAtGameMs 旧行默认 = 0", 0L, cursor.getLong(1))
                assertEquals("既有列 startYear 零丢失", 3, cursor.getInt(2))
                assertEquals("既有列 startMonth 零丢失", 7, cursor.getInt(3))
                assertEquals("既有列 duration 零丢失", 5, cursor.getInt(4))
                assertEquals("既有列 completionMonth 零丢失", 44, cursor.getInt(5))
            }
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

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
                    ) {
                        error("schema-only open should not upgrade")
                    }
                })
                .build()
        )
        return helper.writableDatabase
    }

    /** 按当前表 PRAGMA 列清单生成 game_data 种子 INSERT（NOT NULL 无默认列按亲和性填 0/''） */
    private fun insertSeedRow(
        db: SupportSQLiteDatabase,
        id: String,
        overrides: Map<String, String>
    ) {
        insertSeedRowForTable(
            db, "game_data",
            mapOf("id" to "'$id'", "slot_id" to "1") + overrides
        )
    }

    private fun insertSeedProductionSlot(
        db: SupportSQLiteDatabase,
        id: String,
        overrides: Map<String, String>
    ) {
        insertSeedRowForTable(db, "production_slots", mapOf("id" to "'$id'", "slot_id" to "1") + overrides)
    }

    /** 按 PRAGMA table_info 列清单生成 INSERT；overrides 值若以引号开头视为已编码 SQL 字面量 */
    private fun insertSeedRowForTable(
        db: SupportSQLiteDatabase,
        table: String,
        overrides: Map<String, String>
    ) {
        data class Col(val name: String, val type: String, val notNull: Boolean, val dflt: String?)
        val cols = mutableListOf<Col>()
        db.query("PRAGMA table_info($table)").use { cursor ->
            while (cursor.moveToNext()) {
                cols += Col(
                    name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                    type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                    notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1,
                    dflt = cursor.getString(cursor.getColumnIndexOrThrow("dflt_value"))
                )
            }
        }
        val values = cols.joinToString(", ") { col ->
            val raw = overrides[col.name]
            when {
                raw != null && raw.startsWith("'") -> raw
                raw != null -> raw
                col.type.equals("INTEGER", true) -> col.dflt ?: "0"
                else -> col.dflt ?: "''"
            }
        }
        db.execSQL("INSERT INTO $table (${cols.joinToString(", ") { it.name }}) VALUES ($values)")
    }

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
}
