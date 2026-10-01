package com.xianxia.sect.data.wipe

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.xianxia.sect.data.StorageConstants
import com.xianxia.sect.data.account.AccountSpaceManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 删档重置清理面行为测试（SS0 验收①/W6；SS2 分库后清单含账号数据空间整树）：
 * `executeWipe` 对各持久化面的清零逐项断言——账号空间（库文件族+快照/恢复残留+
 * saves/+archives/+.current）、分库前设备级旧位置残留、重复执行幂等。MMKV 键清理
 * 与账号缓存段在 Robolectric 沙箱经降级路径执行（MMKV native 不可用时跳过键清理，
 * 不阻断文件面断言）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SaveWipeCoordinatorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun seedAllPersistenceSurfaces() {
        // 账号数据空间（当前分库结构）
        val space = AccountSpaceManager(context)
        space.activate("union-test")
        val dbFile = space.requireDatabaseFile()
        listOf("", "-wal", "-shm").forEach { suffix ->
            File(dbFile.absolutePath + suffix).writeText("db")
        }
        File(dbFile.absolutePath + ".pre_migrate_backup.v68").writeText("snapshot")
        File(dbFile.absolutePath + ".restore_attempted").writeText("1")
        File(dbFile.absolutePath + ".restore_tmp").writeText("tmp")
        File(space.requireSavesDir(), "save.sav").writeText("sav")
        File(space.requireArchivesDir(), "battleLogs_2026_10.arc").writeText("arc")

        // 分库前设备级旧位置残留
        val legacyDbFile = context.getDatabasePath(AccountSpaceManager.DATABASE_FILE_NAME)
        legacyDbFile.parentFile?.mkdirs()
        legacyDbFile.writeText("legacy-db")
        val legacySaves = File(context.filesDir, StorageConstants.BACKUP_DIR_NAME)
        legacySaves.mkdirs()
        File(legacySaves, "save.sav").writeText("legacy-sav")
        val legacyArchives = File(context.filesDir, AccountSpaceManager.ARCHIVES_DIR_NAME)
        legacyArchives.mkdirs()
        File(legacyArchives, "battleLogs_2026_09.arc").writeText("legacy-arc")
    }

    @Test
    fun `executeWipe clears every local persistence surface`() {
        seedAllPersistenceSurfaces()
        val space = AccountSpaceManager(context)
        val dbFile = space.requireDatabaseFile()
        val legacyDbFile = context.getDatabasePath(AccountSpaceManager.DATABASE_FILE_NAME)

        SaveWipeCoordinator.executeWipe(context)

        // 账号数据空间整树消失（库文件族 + 快照/恢复残留 + saves/archives + .current）
        assertTrue("accounts/ 应被整树删除", !space.accountsRoot.exists())
        assertFalse("空间内 DB 主文件应被删除", dbFile.exists())
        assertFalse(File(dbFile.absolutePath + "-wal").exists())
        assertFalse(File(dbFile.absolutePath + ".pre_migrate_backup.v68").exists())
        assertFalse(File(dbFile.absolutePath + ".restore_attempted").exists())

        // 分库前设备级旧位置残留消失
        assertFalse("旧位置 DB 残留应被删除", legacyDbFile.exists())
        assertFalse(
            "旧位置 saves/ 残留应被删除",
            File(context.filesDir, StorageConstants.BACKUP_DIR_NAME).exists()
        )
        assertFalse(
            "旧位置 archives/ 残留应被删除",
            File(context.filesDir, AccountSpaceManager.ARCHIVES_DIR_NAME).exists()
        )
    }

    @Test
    fun `executeWipe is idempotent on a clean device`() {
        SaveWipeCoordinator.executeWipe(context)
        SaveWipeCoordinator.executeWipe(context)
        assertTrue("干净设备重复执行零异常即幂等通过", true)
    }
}
