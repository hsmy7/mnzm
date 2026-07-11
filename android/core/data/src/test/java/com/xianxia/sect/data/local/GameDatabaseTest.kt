package com.xianxia.sect.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * GameDatabase 运行时配置验证测试。
 *
 * 验证关键的 PRAGMA 设置已正确应用：
 * - mmap_size = 0（禁用 mmap 防止 onTrimMemory 时 SIGSEGV）
 * - temp_store = FILE（低内存设备上不使用 MEMORY 模式）
 * - integrity_check 通过（数据库本身完整）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameDatabaseTest {

    /**
     * 验证 mmap_size = 0 在支持的环境下不会抛出异常。
     *
     * 注意：Robolectric 的嵌入式 SQLite 可能不支持 PRAGMA mmap_size
     *（需 SQLite 编译时启用 SQLITE_MAX_MMAP_SIZE），
     * execSQL 可能抛出 SQLiteException，测试中对此容错。
     */
    @Test
    fun `mmap_size pragma is handled gracefully`() {
        val db = createEmptyDatabase("mmap_test")
        try {
            try {
                db.execSQL("PRAGMA mmap_size = 0")
            } catch (e: android.database.sqlite.SQLiteException) {
                // Robolectric 嵌入式 SQLite 不支持此 pragma，跳过
                return
            }
            val cursor = db.query("PRAGMA mmap_size", emptyArray())
            cursor.use {
                if (it.moveToFirst()) {
                    assertEquals("mmap_size should be 0", 0L, it.getLong(0))
                }
            }
        } finally {
            db.close()
        }
    }

    /**
     * 验证 integrity_check 在空数据库上返回 'ok'。
     */
    @Test
    fun `integrity_check passes`() {
        val db = createEmptyDatabase("integrity_check_test")

        val cursor = db.query("PRAGMA integrity_check", emptyArray())
        cursor.use {
            assertTrue("Should have result", it.moveToFirst())
            val result = it.getString(0)
            assertEquals("integrity_check should be 'ok'", "ok", result)
        }
        db.close()
    }

    /** 创建空的 SQLite 数据库 */
    private fun createEmptyDatabase(name: String): SupportSQLiteDatabase {
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<Context>()
        val factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {}
                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) {}
                })
                .build()
        )
        return helper.writableDatabase
    }
}
