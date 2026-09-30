package com.xianxia.sect.data.wipe

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.xianxia.sect.data.StorageConstants
import com.xianxia.sect.data.local.GameDatabase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 删档重置清理面行为测试（SS0 验收①/W6）：`executeWipe` 对五类持久化面的
 * 清零逐项断言——DB 文件族、启动前快照/恢复残留、`.sav` 文件层目录、归档
 * 目录、重复执行幂等。MMKV 键清理与账号缓存段在 Robolectric 沙箱经降级路径
 * 执行（MMKV native 不可用时跳过键清理，不阻断文件面断言）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SaveWipeCoordinatorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun seedAllPersistenceSurfaces() {
        val dbFile = GameDatabase.getUnifiedDatabaseFile(context)
        dbFile.parentFile?.mkdirs()
        listOf("", "-wal", "-shm").forEach { suffix ->
            File(dbFile.absolutePath + suffix).writeText("db")
        }
        File(dbFile.absolutePath + ".pre_migrate_backup.v65").writeText("snapshot")
        File(dbFile.absolutePath + ".restore_attempted").writeText("1")
        File(dbFile.absolutePath + ".restore_tmp").writeText("tmp")

        val savesDir = File(context.filesDir, StorageConstants.BACKUP_DIR_NAME)
        savesDir.mkdirs()
        File(savesDir, "slot_1.sav").writeText("sav")
        File(savesDir, "slot_1.bak").writeText("bak")

        val archivesDir = File(context.filesDir, "archives")
        archivesDir.mkdirs()
        File(archivesDir, "battleLogs_2026_10.arc").writeText("arc")
    }

    @Test
    fun `executeWipe clears every local persistence surface`() {
        seedAllPersistenceSurfaces()
        val dbFile = GameDatabase.getUnifiedDatabaseFile(context)

        SaveWipeCoordinator.executeWipe(context)

        // DB 文件族 + 快照/恢复残留全部消失
        assertTrue("DB 主文件应被删除", !dbFile.exists())
        assertFalse(File(dbFile.absolutePath + "-wal").exists())
        assertFalse(File(dbFile.absolutePath + "-shm").exists())
        assertFalse(File(dbFile.absolutePath + ".pre_migrate_backup.v65").exists())
        assertFalse(File(dbFile.absolutePath + ".restore_attempted").exists())
        assertFalse(File(dbFile.absolutePath + ".restore_tmp").exists())

        // 文件层存档目录与归档目录整树消失
        assertFalse("saves/ 应被删除", File(context.filesDir, StorageConstants.BACKUP_DIR_NAME).exists())
        assertFalse("archives/ 应被删除", File(context.filesDir, "archives").exists())
    }

    @Test
    fun `executeWipe is idempotent on a clean device`() {
        SaveWipeCoordinator.executeWipe(context)
        SaveWipeCoordinator.executeWipe(context)
        assertTrue("干净设备重复执行零异常即幂等通过", true)
    }
}
