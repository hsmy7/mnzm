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
 * 迁移 46→47 测试（game_data 新增"新增天赋/体质/词条"待确认产物列 pending_trait_adds）。
 *
 * 背景（2026-08-15 玉符消耗玩法扩展）：新增天赋/体质/词条的刷新产物必须持久化——
 * 刷新（消耗 1 玉符）后不确认直接关闭界面，下次打开仍显示该产物并可直接确认新增。
 * 产物为 List[PendingTraitAdd]，经 ProtobufConverters 序列化为 Base64 存 TEXT 列。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoomMigrationV46To47Test {

    companion object {
        /** Schema 文件所在目录（相对于模块根目录） */
        private val SCHEMA_DIR: File = File(
            "schemas",
            "com.xianxia.sect.data.local.GameDatabase"
        )

        private val M46_47 = MIGRATION_46_47
    }

    /**
     * 真实 Room 校验：v46 库升级到 v47，触发 onValidateSchema——
     * 任何列定义与实体注解不一致都会在此崩溃。47.json 由 ksp 自动导出。
     */
    @Test
    fun `MIGRATION_46_TO_47 passes real Room schema validation`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_46_47_room_validate"
        context.deleteDatabase(dbName)
        try {
            createDatabaseFromSchema(context, dbName, 46).close()
            val db = Room.databaseBuilder(context, GameDatabase::class.java, dbName)
                .addMigrations(M46_47)
                .build()
            db.openHelper.writableDatabase
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `MIGRATION_46_TO_47 adds pending_trait_adds column default empty keeps other data`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_46_47_columns"
        context.deleteDatabase(dbName)
        try {
            val db = createDatabaseFromSchema(context, dbName, 46)
            // v46 库 seed 一行 game_data（动态构建 INSERT：按 PRAGMA table_info 全部列填默认值）
            insertMinimalGameDataRow(db, "gd_46", 1)
            listOf(M46_47).forEach { it.migrate(db) }

            // 新列存在且旧行默认空字符串（空列表编码）
            assertTrue("pending_trait_adds 列应存在", columnExists(db, "game_data", "pending_trait_adds"))
            val pendingValue = db.query(
                "SELECT pending_trait_adds FROM game_data WHERE id = 'gd_46'"
            ).use { c -> c.moveToFirst(); c.getString(0) }
            assertEquals("旧行 pending_trait_adds 默认空字符串", "", pendingValue)

            // 旧数据保留
            val sectName = db.query(
                "SELECT sectName FROM game_data WHERE id = 'gd_46'"
            ).use { c -> c.moveToFirst(); c.getString(0) }
            assertEquals("旧 game_data 数据保留", "TestSect", sectName)
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    /** 从 schema JSON 文件创建数据库（创建所有表 + 索引） */
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
                    ) {
                        // schema 直开只触发 onValidateSchema 校验迁移链；onUpgrade 不应被调用
                        error("schema-only open should not upgrade")
                    }
                })
                .build()
        )
        return helper.writableDatabase
    }

    /** 插入最小 game_data 行（动态按列填默认值，适配任意版本列清单） */
    private fun insertMinimalGameDataRow(
        db: SupportSQLiteDatabase,
        id: String,
        slotId: Int
    ) {
        val columns = mutableListOf<String>()
        val values = mutableListOf<String>()
        val colInfo = db.query("PRAGMA table_info(game_data)", emptyArray())
        colInfo.use {
            while (it.moveToNext()) {
                val colName = it.getString(it.getColumnIndexOrThrow("name"))
                val colType = it.getString(it.getColumnIndexOrThrow("type"))
                columns.add(colName)
                values.add(when {
                    colType?.uppercase()?.contains("INT") == true -> "0"
                    colType?.uppercase()?.contains("REAL") == true -> "0.0"
                    else -> "''"
                })
            }
        }
        val idIdx = columns.indexOf("id"); if (idIdx >= 0) values[idIdx] = "'$id'"
        val sidIdx = columns.indexOf("slot_id"); if (sidIdx >= 0) values[sidIdx] = "$slotId"
        val snIdx = columns.indexOf("sectName"); if (snIdx >= 0) values[snIdx] = "'TestSect'"

        db.execSQL(
            "INSERT INTO game_data (${columns.joinToString(",")}) VALUES (${values.joinToString(",")})"
        )
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
