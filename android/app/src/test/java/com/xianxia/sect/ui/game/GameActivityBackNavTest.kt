package com.xianxia.sect.ui.game

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.xianxia.sect.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T13（2026-08-05）：boot 失败弹窗"返回主菜单"导航测试。
 *
 * GameActivity 为 Hilt @AndroidEntryPoint 不便实例化，测试独立顶层函数
 * buildMainMenuIntent——复用 onLogout 的 MainActivity 重建模式（NEW_TASK|CLEAR_TASK）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameActivityBackNavTest {

    @Test
    fun `buildMainMenuIntent targets MainActivity`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val intent = buildMainMenuIntent(context)

        assertEquals(MainActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun `buildMainMenuIntent uses clear stack flags`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val intent = buildMainMenuIntent(context)

        assertTrue(
            "应带 NEW_TASK flag，实际 ${intent.flags}",
            intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0
        )
        assertTrue(
            "应带 CLEAR_TASK flag（清 Activity 栈防返回键回残留页面），实际 ${intent.flags}",
            intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0
        )
    }
}
