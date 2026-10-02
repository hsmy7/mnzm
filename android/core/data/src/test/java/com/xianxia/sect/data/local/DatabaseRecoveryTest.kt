package com.xianxia.sect.data.local

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 启动前快照恢复链行为面测试（D-9「提供可恢复性的一律保留」的可恢复性锁定）。
 *
 * 覆盖两段：
 * ① `GameDatabase.restoreFromBackupIfNeeded(dbFile)` 端到端——版本化快照存在且
 *    当前库处于「不可用 / 版本升级待完成」态时，用快照覆盖恢复；
 * ② `GameDatabaseConfig.shouldRestoreFromBackup` 纯谓词的**全部分支**（恢复判定
 *    逻辑提取为纯函数后可脱离 SQLite 直接穷举）。
 *
 * 快照命名与扫描区间来自实现：`{db}.pre_migrate_backup.v{N}`，`findVersionedBackup`
 * 只扫 `N ∈ 2..DATABASE_VERSION-1` ⇒ 测试快照版本必须落在该区间内。
 *
 * 库文件落在模块 `build/` 下的短路径：Windows 上 SQLite 打开受 MAX_PATH(260)
 * 约束，Robolectric 沙箱目录名含完整测试方法名，叠加深度路径会超限导致
 * `SQLITE_CANTOPEN`（测试路径管理问题，与生产行为无关）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseRecoveryTest {

    private val spaceRoot = File("build/db-recovery-space/accounts/testkey")

    /** 快照版本：必须落在 `findVersionedBackup` 的扫描区间 `2..DATABASE_VERSION-1` */
    private val snapshotVersion = SNAPSHOT_VERSION

    private val snapshotSuffix = ".pre_migrate_backup.v$snapshotVersion"

    private fun resetSpace() {
        spaceRoot.deleteRecursively()
        spaceRoot.mkdirs()
    }

    private fun newDbFile(): File = File(spaceRoot, "xianxia_sect.db")

    /** 造库：`test_data` 放一行（value = null 表示清空），并设 user_version */
    private fun writeDb(file: File, value: String?, userVersion: Int) {
        SQLiteDatabase.openOrCreateDatabase(file.absolutePath, null).use { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS test_data (id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL("DELETE FROM test_data")
            if (value != null) db.execSQL("INSERT INTO test_data VALUES (1, '$value')")
            db.execSQL("PRAGMA user_version = $userVersion")
        }
    }

    private fun readValue(file: File): String? =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT value FROM test_data WHERE id = 1", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }

    /** 建立「好库的快照 + 被破坏的当前库」标准前置（marker 由调用方决定是否加） */
    private fun setupDamagedDbWithSnapshot(): Pair<File, File> {
        resetSpace()
        val dbFile = newDbFile()
        writeDb(dbFile, value = ORIGINAL_VALUE, userVersion = SNAPSHOT_VERSION)
        val snapshot = File(dbFile.absolutePath + snapshotSuffix)
        dbFile.copyTo(snapshot, overwrite = true)
        // 破坏当前库：清空数据并把版本推到与快照不同的陈旧值
        writeDb(dbFile, value = null, userVersion = STALE_USER_VERSION)
        return dbFile to snapshot
    }

    // ==================== ① 端到端恢复 ====================

    @Test
    fun `versioned snapshot recovers damaged database end to end`() {
        val (dbFile, snapshot) = setupDamagedDbWithSnapshot()
        assertTrue("快照文件必须已建立", snapshot.exists())
        assertNull("破坏后当前库应为空", readValue(dbFile))

        val restored = GameDatabase.restoreFromBackupIfNeeded(dbFile)

        assertTrue("满足恢复判定时必须执行快照恢复", restored)
        assertEquals("快照中的数据必须被还原", ORIGINAL_VALUE, readValue(dbFile))
    }

    @Test
    fun `restore attempt marker prevents repeated restore loop`() {
        val (dbFile, _) = setupDamagedDbWithSnapshot()
        File(dbFile.absolutePath + RESTORE_MARKER_SUFFIX).writeText("1")

        val restored = GameDatabase.restoreFromBackupIfNeeded(dbFile)

        assertFalse("marker 存在时不得重复恢复（防恢复-重建死循环）", restored)
        assertNull("跳过恢复时当前库保持原状", readValue(dbFile))
    }

    @Test
    fun `restore skipped when database already holds data at current version`() {
        resetSpace()
        val dbFile = newDbFile()
        writeDb(dbFile, value = ORIGINAL_VALUE, userVersion = SNAPSHOT_VERSION)
        dbFile.copyTo(File(dbFile.absolutePath + snapshotSuffix), overwrite = true)
        // 当前库已有数据且版本已达当前 ⇒ 判定谓词不恢复
        SQLiteDatabase.openOrCreateDatabase(dbFile.absolutePath, null).use { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS game_data (id TEXT NOT NULL)")
            db.execSQL("INSERT INTO game_data VALUES ('row')")
            db.execSQL("PRAGMA user_version = ${GameDatabaseConfig.DATABASE_VERSION}")
        }

        val restored = GameDatabase.restoreFromBackupIfNeeded(dbFile)

        assertFalse("有数据且版本达标时不得用快照覆盖", restored)
        assertEquals("当前库数据不得被覆盖", ORIGINAL_VALUE, readValue(dbFile))
    }

    @Test
    fun `no snapshot means no restore`() {
        resetSpace()
        val dbFile = newDbFile()
        writeDb(dbFile, value = ORIGINAL_VALUE, userVersion = STALE_USER_VERSION)

        assertFalse("无版本化快照时不做恢复", GameDatabase.restoreFromBackupIfNeeded(dbFile))
    }

    @Test
    fun `missing database file is not restored`() {
        resetSpace()
        val dbFile = newDbFile()
        val source = File(spaceRoot, "snapshot-source.db")
        writeDb(source, value = ORIGINAL_VALUE, userVersion = SNAPSHOT_VERSION)
        source.copyTo(File(dbFile.absolutePath + snapshotSuffix), overwrite = true)
        assertFalse("当前库不存在", dbFile.exists())

        assertFalse("当前库不存在时不做恢复", GameDatabase.restoreFromBackupIfNeeded(dbFile))
    }

    // ==================== ② 恢复判定谓词（全分支，纯逻辑） ====================

    @Test
    fun `shouldRestoreFromBackup - unusable database restores`() {
        // 库打不开 / game_data 表缺失（-1）→ 恢复（安全兜底）
        assertTrue(
            GameDatabaseConfig.shouldRestoreFromBackup(
                currentRowCount = -1, currentVersion = -1, backupVersion = SNAPSHOT_VERSION
            )
        )
    }

    @Test
    fun `shouldRestoreFromBackup - empty database restores`() {
        // destructive 重建后无数据（0 行）→ 恢复
        assertTrue(
            GameDatabaseConfig.shouldRestoreFromBackup(
                currentRowCount = 0, currentVersion = STALE_USER_VERSION, backupVersion = SNAPSHOT_VERSION
            )
        )
    }

    @Test
    fun `shouldRestoreFromBackup - pending upgrade restores`() {
        // 重建崩溃后 DB 行数仍 > 0、版本低于目标、快照与当前同版（快照建立后重建从未完成）→ 恢复
        assertTrue(
            GameDatabaseConfig.shouldRestoreFromBackup(
                currentRowCount = 1, currentVersion = SNAPSHOT_VERSION, backupVersion = SNAPSHOT_VERSION
            )
        )
    }

    @Test
    fun `shouldRestoreFromBackup - completed version skips`() {
        // 版本已达当前 → 跳过
        assertFalse(
            GameDatabaseConfig.shouldRestoreFromBackup(
                currentRowCount = 1,
                currentVersion = GameDatabaseConfig.DATABASE_VERSION,
                backupVersion = SNAPSHOT_VERSION
            )
        )
    }

    @Test
    fun `shouldRestoreFromBackup - stale backup version skips`() {
        // 有数据、待升级，但快照版本与当前库不符（快照过期/被覆盖）→ 跳过
        assertFalse(
            GameDatabaseConfig.shouldRestoreFromBackup(
                currentRowCount = 1,
                currentVersion = SNAPSHOT_VERSION,
                backupVersion = GameDatabaseConfig.DATABASE_VERSION - 1
            )
        )
    }

    @Test
    fun `shouldRestoreFromBackup - downgrade scenario restores`() {
        // 降级场景：当前库版本高于 App 支持版本（高版本 App 数据回退），
        // 快照版本在可达区间且更旧 → 恢复
        assertTrue(
            GameDatabaseConfig.shouldRestoreFromBackup(
                currentRowCount = 1,
                currentVersion = GameDatabaseConfig.DATABASE_VERSION + 1,
                backupVersion = GameDatabaseConfig.DATABASE_VERSION - 1
            )
        )
    }

    @Test
    fun `shouldRestoreFromBackup - downgrade with unusable backup skips`() {
        // 降级场景但快照不可用（版本读取失败 -1）→ 跳过（无法确认快照有效性）
        assertFalse(
            GameDatabaseConfig.shouldRestoreFromBackup(
                currentRowCount = 1,
                currentVersion = GameDatabaseConfig.DATABASE_VERSION + 1,
                backupVersion = -1
            )
        )
    }

    private companion object {
        const val ORIGINAL_VALUE = "original_data"

        /** 快照版本：落在 `2..DATABASE_VERSION-1` 内 */
        const val SNAPSHOT_VERSION = 5

        /** 与快照不同的陈旧版本号（模拟重建未完成） */
        const val STALE_USER_VERSION = 32

        /** 与 `GameDatabase.RESTORE_ATTEMPT_MARKER` 同名（防死循环 marker） */
        const val RESTORE_MARKER_SUFFIX = ".restore_attempted"
    }
}
