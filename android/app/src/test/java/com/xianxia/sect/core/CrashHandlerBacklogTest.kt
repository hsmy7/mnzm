package com.xianxia.sect.core

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * CrashHandlerBacklogTest — 崩溃日志积压重传闭环测试（R0.5）。
 *
 * 守护目标：崩溃时刻上传失败（进程退出/网络不可达）后，下次启动的
 * [CrashHandler.uploadPendingCrashLogs] 必须重传 crash_logs 积压：
 * 上传成功 → 本地清理；失败 → 保留待下次。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S]) // API 31（targetSdk 35 超 Robolectric 上限 34，需固定）
class CrashHandlerBacklogTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var handler: CrashHandler

    @Before
    fun setUp() {
        handler = CrashHandler(context)
        // 清空崩溃日志目录，测试互不污染
        handler.clearAllCrashLogs()
    }

    private fun crashLogDir(): File = File(context.filesDir, "crash_logs")

    private fun seedCrashLog(name: String): File {
        val dir = crashLogDir()
        dir.mkdirs()
        return File(dir, name).apply { writeText("=== Crash Log ===\nstack-for-$name") }
    }

    @Test
    fun `uploadPendingCrashLogs - no pending logs returns zero`() {
        assertEquals(0, handler.uploadPendingCrashLogs { true })
    }

    @Test
    fun `uploadPendingCrashLogs - success uploads and deletes local files`() {
        val f1 = seedCrashLog("crash_20260917_100000.log")
        val f2 = seedCrashLog("crash_20260917_110000.log")
        val uploadedContents = mutableListOf<String>()

        val uploaded = handler.uploadPendingCrashLogs { content ->
            uploadedContents.add(content)
            true
        }

        assertEquals(2, uploaded)
        assertEquals(2, uploadedContents.size)
        assertFalse("上传成功后本地文件必须删除", f1.exists())
        assertFalse(f2.exists())
    }

    @Test
    fun `uploadPendingCrashLogs - failure keeps files for next launch`() {
        seedCrashLog("crash_20260917_100000.log")

        val uploaded = handler.uploadPendingCrashLogs { false }

        assertEquals(0, uploaded)
        assertTrue("上传失败必须保留本地日志", crashLogDir().listFiles().isNotEmpty())
    }

    @Test
    fun `uploadPendingCrashLogs - mixed results only delete uploaded`() {
        val successFile = seedCrashLog("crash_20260917_100000.log")
        val failFile = seedCrashLog("crash_20260917_110000.log")

        val uploaded = handler.uploadPendingCrashLogs { content ->
            content.contains("crash_20260917_100000")
        }

        assertEquals(1, uploaded)
        assertFalse(successFile.exists())
        assertTrue(failFile.exists())
    }
}
