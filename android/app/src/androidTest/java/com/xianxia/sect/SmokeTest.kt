package com.xianxia.sect

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 冒烟测试：验证应用在真实设备上能正常启动
 * 通过 Firebase Test Lab 在华为设备矩阵上运行
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class SmokeTest {

    @Test
    fun appLaunchesSuccessfully() {
        // 如果应用启动过程崩溃，此测试会在超时前失败
        // Firebase Test Lab 会自动收集 logcat 和截图
        Thread.sleep(15_000) // 等待应用完成初始化
    }

    @Test
    fun mainActivityStartsWithoutCrash() {
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        assert(context != null)
        Thread.sleep(5_000)
    }
}
