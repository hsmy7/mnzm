package com.xianxia.sect.data.local

import android.database.sqlite.SQLiteDatabase
import java.io.File
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

// 存档恢复与迁移判定测试——从 RoomMigrationTest 拆出的同域测试类(备份恢复 /
// shouldRestoreFromBackup 判定谓词)。共享基建在 RoomMigrationSupport。

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoomMigrationRecoveryTest {

    // ==================== 备份恢复测试 ====================

    @Test
    fun `backup and restore recovers data from pre_migrate_backup`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        // 使用真实数据库名（restoreFromBackupIfNeeded 内部硬编码为此名）
        val realDbName = "xianxia_sect.db"
        context.deleteDatabase(realDbName)

        try {
            val dbPath = context.getDatabasePath(realDbName)
            dbPath.parentFile?.mkdirs()

            // 创建有数据的数据库
            SQLiteDatabase.openOrCreateDatabase(dbPath, null).use { db ->
                db.execSQL("CREATE TABLE test_data (id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
                db.execSQL("INSERT INTO test_data VALUES (1, 'original_data')")
                db.execSQL("PRAGMA user_version = 5")
            }

            // 验证数据已写入
            SQLiteDatabase.openDatabase(dbPath.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val cursor = db.rawQuery("SELECT value FROM test_data WHERE id = 1", null)
                assertTrue("data should exist", cursor.moveToFirst())
                assertEquals("original_data", cursor.getString(0))
                cursor.close()
            }

            // 创建备份（模拟 backupDatabaseForMigration 的产物）
            val backupPath = File(dbPath.absolutePath + ".pre_migrate_backup")
            dbPath.inputStream().use { input ->
                backupPath.outputStream().use { output -> input.copyTo(output) }
            }
            assertTrue("backup file should exist", backupPath.exists())

            // 篡改原数据库：清空数据
            SQLiteDatabase.openOrCreateDatabase(dbPath, null).use { db ->
                db.execSQL("DELETE FROM test_data")
                db.execSQL("PRAGMA user_version = 32")
            }
            SQLiteDatabase.openDatabase(dbPath.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val cursor = db.rawQuery("SELECT COUNT(*) FROM test_data", null)
                if (cursor.moveToFirst()) assertEquals(0, cursor.getInt(0))
                cursor.close()
            }

            // 调用 restoreFromBackupIfNeeded
            val restored = GameDatabase.restoreFromBackupIfNeeded(context)
            assertTrue("restoreFromBackupIfNeeded should return true", restored)

            // 验证数据已恢复
            SQLiteDatabase.openDatabase(dbPath.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val cursor = db.rawQuery("SELECT value FROM test_data WHERE id = 1", null)
                assertTrue("data should exist after restore", cursor.moveToFirst())
                assertEquals("original_data should be restored", "original_data", cursor.getString(0))
                cursor.close()
            }
        } finally {
            context.deleteDatabase(realDbName)
            val backupPath = File(context.getDatabasePath(realDbName).absolutePath + ".pre_migrate_backup")
            if (backupPath.exists()) backupPath.delete()
        }
    }

    /**
     * 迁移恢复判定谓词测试（纯逻辑，无 SQLite 依赖）。
     *
     * 判定语义：迁移崩溃（MigrationNotFoundException）后 DB 行数仍 > 0，
     * 不能仅凭行数跳过恢复——当前 user_version < DATABASE_VERSION 且备份与
     * 当前同版本（备份创建后迁移从未完成）→ 触发恢复。
     *
     * 注：restoreFromBackupIfNeeded 全流程在 Robolectric 下不稳定（时序相关），
     * 判定逻辑提取为纯函数后在此直接覆盖全部分支。
     */
    @Test
    fun `shouldRestoreFromBackup - migration pending restores`() {
        // 迁移崩溃场景：行数 > 0、版本低于目标、备份与当前同版 → 恢复
        assertTrue(
            GameDatabaseConfig.shouldRestoreFromBackup(currentRowCount = 1, currentVersion = 5, backupVersion = 5)
        )
    }

    @Test
    fun `shouldRestoreFromBackup - empty database restores`() {
        // 无数据（destructive fallback 后）→ 恢复（保留原语义）
        assertTrue(
            GameDatabaseConfig.shouldRestoreFromBackup(currentRowCount = 0, currentVersion = 32, backupVersion = 5)
        )
    }

    @Test
    fun `shouldRestoreFromBackup - unreadable database restores`() {
        // 当前库打不开/表缺失（-1）→ 恢复（安全兜底）
        assertTrue(
            GameDatabaseConfig.shouldRestoreFromBackup(currentRowCount = -1, currentVersion = -1, backupVersion = 5)
        )
    }

    @Test
    fun `shouldRestoreFromBackup - migration completed skips`() {
        // 迁移已完成（版本已达最新）→ 跳过
        assertFalse(
            GameDatabaseConfig.shouldRestoreFromBackup(
                currentRowCount = 1,
                currentVersion = GameDatabaseConfig.DATABASE_VERSION,
                backupVersion = 5
            )
        )
    }

    @Test
    fun `shouldRestoreFromBackup - backup version mismatch skips`() {
        // 有数据、待迁移但备份版本与当前不一致（备份过期/被覆盖）→ 跳过
        assertFalse(
            GameDatabaseConfig.shouldRestoreFromBackup(currentRowCount = 1, currentVersion = 5, backupVersion = 8)
        )
    }

    @Test
    fun `shouldRestoreFromBackup - future database version restores`() {
        // 降级场景——高版本 App 数据回退到低版本 App（currentVersion >
        // DATABASE_VERSION），Room 无法降级打开必然崩溃；备份版本迁移链可达
        //（2..DATABASE_VERSION 且比当前旧）→ 恢复
        assertTrue(
            GameDatabaseConfig.shouldRestoreFromBackup(
                currentRowCount = 1,
                currentVersion = GameDatabaseConfig.DATABASE_VERSION + 1,
                backupVersion = GameDatabaseConfig.DATABASE_VERSION - 1
            )
        )
    }

    @Test
    fun `shouldRestoreFromBackup - future version with unusable backup skips`() {
        // 降级场景但备份不可用（版本读取失败 -1）→ 跳过（无法确认备份有效性）
        assertFalse(
            GameDatabaseConfig.shouldRestoreFromBackup(
                currentRowCount = 1,
                currentVersion = GameDatabaseConfig.DATABASE_VERSION + 1,
                backupVersion = -1
            )
        )
    }


}
