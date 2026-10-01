package com.xianxia.sect.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * destructive 重建基线实测（SS0 验收⑥；SS2 分库后库文件走账号空间路径形态）。
 *
 * 迁移链已整体退役：旧版本库（user_version < DATABASE_VERSION）在新版本打开时
 * 无迁移路径，唯一合法行为是 `fallbackToDestructiveMigration(dropAllTables = true)`
 * **毁灭重建而非崩溃**。本测试用真实 v65 历史版本库文件走 `GameDatabase.create`
 * 全流程锁定该行为——重建失败（崩溃/未重建/残留旧行）即红。
 *
 * 库文件落在模块 `build/` 下的短路径：Windows 上 SQLite 打开受 MAX_PATH(260)
 * 约束，Robolectric 沙箱目录名含完整测试方法名，叠加深度路径会超限导致
 * SQLITE_CANTOPEN——与生产行为无关（真机 Linux 无此约束），仅测试路径管理。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DestructiveRebuildBaselineTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 账号空间路径形态的库文件（filesDir/accounts/<key>/xianxia_sect.db 的短路径等价） */
    private fun newSpaceDbFile(): File {
        val spaceRoot = File("build/rebuild-baseline-space/accounts/testkey")
        spaceRoot.mkdirs()
        return File(spaceRoot, "xianxia_sect.db")
    }

    /** 手工构造一个 v65 历史版本库：含 game_data 旧行与 Room 外残留表（影子表形态） */
    private fun createLegacyV65DbFile(): File {
        val dbFile = newSpaceDbFile()
        // WAL 副文件必须随主文件一并清除（残留 -wal 会让只读会话无法重建 -shm）
        listOf("", "-wal", "-shm", ".pre_migrate_backup.v65").forEach { suffix ->
            File(dbFile.absolutePath + suffix).delete()
        }
        SQLiteDatabase.openOrCreateDatabase(dbFile.absolutePath, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `game_data` (" +
                    "`id` TEXT NOT NULL, `slot_id` INTEGER NOT NULL, " +
                    "`sectName` TEXT NOT NULL, PRIMARY KEY(`id`, `slot_id`))"
            )
            db.execSQL("INSERT INTO `game_data` VALUES ('legacy_save', 1, '旧档宗门')")
            // Room schema 之外的表（历史上由迁移创建的装备影子表形态）——
            // 重建必须连它一起清（dropAllTables = true 的验收点）
            db.execSQL("CREATE TABLE IF NOT EXISTS `legacy_equipment_stacks` (`id` TEXT NOT NULL)")
            db.execSQL("INSERT INTO `legacy_equipment_stacks` VALUES ('legacy_stack')")
            db.version = 65
        }
        return dbFile
    }

    @Test
    fun `v65 database opens as current version via destructive rebuild without crashing`() {
        val dbFile = createLegacyV65DbFile()
        assertEquals(65, readUserVersion(dbFile))

        val db = GameDatabase.create(context, dbFile)
        // applySafetyPragmas 在 create 内访问 writableDatabase——库已打开并完成重建
        assertEquals(
            "create 返回时库必须已打开且完成 destructive 重建",
            GameDatabaseConfig.DATABASE_VERSION,
            db.openHelper.writableDatabase.version
        )

        try {
            // 重建后版本推进到当前版本（非崩溃即通过前半段）
            assertEquals(GameDatabaseConfig.DATABASE_VERSION, readUserVersion(dbFile))

            // 旧行随毁灭重建清零：game_data 表存在（新 schema）但无旧档数据
            val legacyRows = queryScalar(dbFile, "SELECT COUNT(*) FROM game_data WHERE id = 'legacy_save'")
            assertEquals("旧档行必须在毁灭重建中被清零", 0, legacyRows)

            // Room schema 之外的残留表必须一并清除（dropAllTables = true）
            val orphanTables = queryScalar(
                dbFile,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'legacy_equipment_stacks'"
            )
            assertEquals("Room schema 外的历史残留表必须在重建中清除", 0, orphanTables)
        } finally {
            db.close()
        }
    }

    @Test
    fun `version behind target triggers startup snapshot before rebuild`() {
        val dbFile = createLegacyV65DbFile()
        val snapshot = File(dbFile.absolutePath + ".pre_migrate_backup.v65")
        snapshot.delete()

        val db = GameDatabase.create(context, dbFile)
        try {
            assertTrue(
                "版本落后时必须在重建前落启动前快照（可恢复性判据）",
                snapshot.exists() && snapshot.length() > 0
            )
            assertEquals(65, readUserVersion(snapshot))
        } finally {
            db.close()
            snapshot.delete()
        }
    }

    @Test
    fun `fresh install does not produce snapshot files`() {
        val dbFile = newSpaceDbFile()
        dbFile.parentFile?.listFiles()?.forEach(File::delete)
        assertFalse(dbFile.exists())

        val db = GameDatabase.create(context, dbFile)
        try {
            val leftovers = dbFile.parentFile?.listFiles()
                ?.filter { it.name.startsWith(dbFile.name + ".pre_migrate_backup") }
                .orEmpty()
            assertTrue("首次安装不得产生快照文件：${leftovers.map { it.name }}", leftovers.isEmpty())
        } finally {
            db.close()
        }
    }

    private fun readUserVersion(file: File): Int =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA user_version", null).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
        }

    /** 文件级只读查询返回首行首列 Int */
    private fun queryScalar(file: File, sql: String): Int =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(sql, null).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
        }
}
