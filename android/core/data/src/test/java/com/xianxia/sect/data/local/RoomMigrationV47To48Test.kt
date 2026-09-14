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
 * 迁移 47→48 测试（overflow_mail_drafts 新增 item_id 列）。
 *
 * 背景（溢出邮件领取精确还原）：溢出邮件附件持久化物品模板 id（item_id），
 * drain 构建附件时透传，领取方据此精确还原原物品；不带 item_id 的草稿
 * 走按稀有度随机生成的回退逻辑。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoomMigrationV47To48Test {

    companion object {
        /** Schema 文件所在目录（相对于模块根目录） */
        private val SCHEMA_DIR: File = File(
            "schemas",
            "com.xianxia.sect.data.local.GameDatabase"
        )

        private val M47_48 = MIGRATION_47_48
        private val M48_49 = MIGRATION_48_49
        private val M49_50 = MIGRATION_49_50
    }

    /**
     * 真实 Room 校验：v47 库升级到 v48，触发 onValidateSchema——
     * 任何列定义与实体注解不一致都会在此崩溃。48.json 由 ksp 自动导出。
     */
    @Test
    fun `MIGRATION_47_TO_48 passes real Room schema validation`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_47_48_room_validate"
        context.deleteDatabase(dbName)
        try {
            createDatabaseFromSchema(context, dbName, 47).close()
            val db = Room.databaseBuilder(context, GameDatabase::class.java, dbName)
                .addMigrations(M47_48, M48_49, M49_50)
                .build()
            db.openHelper.writableDatabase
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `MIGRATION_47_TO_48 adds itemId column default empty keeps other data`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "m_47_48_columns"
        context.deleteDatabase(dbName)
        try {
            val db = createDatabaseFromSchema(context, dbName, 47)
            // v47 库 seed 一行溢出草稿（动态按列填默认值）
            insertMinimalOverflowDraftRow(db, "draft_47")
            listOf(M47_48).forEach { it.migrate(db) }

            assertTrue("itemId 列应存在", columnExists(db, "overflow_mail_drafts", "itemId"))
            val itemId = db.query(
                "SELECT itemId FROM overflow_mail_drafts WHERE id = 'draft_47'"
            ).use { c -> c.moveToFirst(); c.getString(0) }
            assertEquals("旧行 itemId 默认空字符串", "", itemId)

            // 旧数据保留
            val itemName = db.query(
                "SELECT itemName FROM overflow_mail_drafts WHERE id = 'draft_47'"
            ).use { c -> c.moveToFirst(); c.getString(0) }
            assertEquals("旧草稿数据保留", "回气丹", itemName)
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

    /** 插入最小 overflow_mail_drafts 行（动态按列填默认值，适配任意版本列清单） */
    private fun insertMinimalOverflowDraftRow(
        db: SupportSQLiteDatabase,
        id: String
    ) {
        val columns = mutableListOf<String>()
        val values = mutableListOf<String>()
        val colInfo = db.query("PRAGMA table_info(overflow_mail_drafts)", emptyArray())
        colInfo.use {
            while (it.moveToNext()) {
                val colName = it.getString(it.getColumnIndexOrThrow("name"))
                val colType = it.getString(it.getColumnIndexOrThrow("type"))
                columns.add(colName)
                values.add(when {
                    colType?.uppercase()?.contains("INT") == true -> "0"
                    else -> "''"
                })
            }
        }
        val idIdx = columns.indexOf("id"); if (idIdx >= 0) values[idIdx] = "'$id'"
        val itemNameIdx = columns.indexOf("itemName"); if (itemNameIdx >= 0) values[itemNameIdx] = "'回气丹'"

        db.execSQL(
            "INSERT INTO overflow_mail_drafts (${columns.joinToString(",")}) VALUES (${values.joinToString(",")})"
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
