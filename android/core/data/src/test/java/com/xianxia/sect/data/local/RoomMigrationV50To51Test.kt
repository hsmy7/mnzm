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
 * 迁移 50→51 测试（game_data 新增"地图冻结"地形段两列，WS-5b）。
 *
 * 背景：地形从"种子确定性重生"改为"生成即数据、存的地形恒优先"。
 * map_gen_version（INTEGER DEFAULT 0，生成器版本戳）+ terrain_tiles
 * （TEXT DEFAULT ''，行主序 flat 段经 CollectionConverters.intList 编码）。
 * 纯加法 ADD COLUMN，兼容旧行；`51.json` 由 ksp 自动导出。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoomMigrationV50To51Test {

    companion object {
        private val SCHEMA_DIR: File = File(
            "schemas",
            "com.xianxia.sect.data.local.GameDatabase"
        )

        private val M49_50 = MIGRATION_49_50
        private val M50_51 = MIGRATION_50_51
    }

    /** 真实 Room 校验：v50 库升级到 v51，触发 onValidateSchema——新列定义与实体注解一致才通过。 */
    @Test
    fun `MIGRATION_50_TO_51 passes real Room schema validation`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_50_51_room_validate"
        context.deleteDatabase(dbName)
        try {
            createDatabaseFromSchema(context, dbName, 50).close()
            val db = Room.databaseBuilder(context, GameDatabase::class.java, dbName)
                .addMigrations(M49_50, M50_51)
                .build()
            db.openHelper.writableDatabase
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    /** 迁移后两列存在且旧行默认值正确（旧档 ⇒ 无段 ⇒ 走"无段生成 + 回填"路径）。 */
    @Test
    fun `MIGRATION_50_TO_51 adds terrain columns with defaults and preserves data`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_50_51_terrain"
        context.deleteDatabase(dbName)
        try {
            val db = createDatabaseFromSchema(context, dbName, 50)
            // v50 旧档种子数据：按 PRAGMA 列清单生成 INSERT（v50 表几乎全部
            // 列 NOT NULL 无 SQL 默认），关键列写非默认值验证迁移零丢失
            insertSeedRow(db, id = "sect-1", overrides = mapOf(
                "sectName" to "'青云宗'",
                "gameYear" to "12",
                "spiritStones" to "99999",
                "map_seed" to "424242"
            ))
            M50_51.migrate(db)
            assertTrue(
                "map_gen_version 列应存在于 game_data",
                columnExists(db, "game_data", "map_gen_version")
            )
            assertTrue(
                "terrain_tiles 列应存在于 game_data",
                columnExists(db, "game_data", "terrain_tiles")
            )
            db.query("SELECT map_gen_version, terrain_tiles, sectName, spiritStones, map_seed " +
                "FROM game_data WHERE id = 'sect-1'").use { cursor ->
                assertTrue("迁移后既有行数据应保留", cursor.moveToFirst())
                assertEquals("map_gen_version 旧行默认 = 0（无段）", 0, cursor.getInt(0))
                assertEquals("terrain_tiles 旧行默认 = ''（无段）", "", cursor.getString(1))
                assertEquals("既有列 sectName 零丢失", "青云宗", cursor.getString(2))
                assertEquals("既有列 spiritStones 零丢失", 99999L, cursor.getLong(3))
                assertEquals("既有列 map_seed 零丢失", 424242, cursor.getInt(4))
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

    /**
     * v50 旧档种子行：按 PRAGMA table_info 的列清单生成 INSERT——
     * NOT NULL 无默认列按亲和性填 0/''，[overrides] 覆盖关键列。
     */
    private fun insertSeedRow(
        db: SupportSQLiteDatabase,
        id: String,
        overrides: Map<String, String>
    ) {
        data class Col(val name: String, val type: String, val notNull: Boolean, val dflt: String?)
        val cols = mutableListOf<Col>()
        db.query("PRAGMA table_info(game_data)").use { cursor ->
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
            when {
                col.name == "id" -> "'$id'"
                col.name == "slot_id" -> "1"
                overrides[col.name] != null -> overrides[col.name]!!
                col.type.equals("INTEGER", true) -> col.dflt ?: "0"
                else -> col.dflt ?: "''"
            }
        }
        db.execSQL("INSERT INTO game_data (${cols.joinToString(", ") { it.name }}) VALUES ($values)")
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
