package com.xianxia.sect.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.security.KeyStoreException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SessionManager 加密存储恢复/降级测试（Bugly #3107）。
 *
 * 主密钥损坏（ErrorCode -33 Invalid key blob）时 `MasterKey.Builder.build()`
 * 抛 KeyStoreException——旧实现无兜底导致 Hilt 注入即闪退。
 * 通过注入"必然失败的 builder"确定性触发失败路径：
 * 1. 降级明文并持久化标记（下次启动直接明文，不再重复失败的 Keystore 流程）
 * 2. 降级标记存在时不再尝试加密构建
 *
 * 注：成功路径分支（删密钥重建成功）依赖真实 AndroidKeyStore，Robolectric
 * 无法构造，由代码审查覆盖；本测试只验证失败路径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionManagerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `createSessionPrefs - 加密创建失败时降级明文并持久化标记`() {
        val prefs = SessionManager.createSessionPrefs(context) {
            throw KeyStoreException("master key exists but is unusable")
        }

        prefs.edit().putString("k", "v").apply()
        assertEquals("v", prefs.getString("k", null))

        val downgraded = context.getSharedPreferences(
            SessionManager.PREFS_NAME, Context.MODE_PRIVATE
        ).getBoolean(SessionManager.KEY_ENCRYPTION_DOWNGRADED, false)
        assertTrue("降级标记必须持久化，防止下次启动重复 Keystore 失败流程", downgraded)
    }

    @Test
    fun `createSessionPrefs - 降级标记存在时不再尝试加密`() {
        // 模拟上次启动已降级：明文 prefs 中已有降级标记
        context.getSharedPreferences(SessionManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(SessionManager.KEY_ENCRYPTION_DOWNGRADED, true).apply()

        var builderCalls = 0
        val prefs = SessionManager.createSessionPrefs(context) {
            builderCalls++
            throw KeyStoreException("should not be called")
        }

        assertEquals("降级后不得再尝试加密构建", 0, builderCalls)
        prefs.edit().putString("k", "v").apply()
        assertEquals("v", prefs.getString("k", null))
    }

    @Test
    fun `performanceMode - persists value and defaults to BALANCED`() {
        // 强制明文降级路径（Robolectric 无真实 AndroidKeyStore）
        context.getSharedPreferences(SessionManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(SessionManager.KEY_ENCRYPTION_DOWNGRADED, true).apply()
        val session = SessionManager(context)

        // 默认均衡
        assertEquals("BALANCED", session.performanceMode)

        // 写读回
        session.performanceMode = "ENERGY_SAVING"
        assertEquals("ENERGY_SAVING", session.performanceMode)

        session.performanceMode = "PERFORMANCE"
        assertEquals("PERFORMANCE", session.performanceMode)
    }
}
