package com.xianxia.sect.core

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [VulkanPolicy.scanMapsForSandbox] 沙箱检测扫描测试（Bugly #11/#13006 相关）：
 *
 * 扫描读取 /proc/self/maps 的模拟内容（沙箱 hook 环境该 IO 被放大是 ANR 嫌疑点），
 * 验证命中/未命中/文件缺失三类路径。真实 /proc/self/maps 在测试环境不可用，
 * 通过临时文件注入模拟内容。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VulkanPolicyCloudGamingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mapsFile: File

    @Before
    fun setUp() {
        mapsFile = tempFolder.newFile("maps")
    }

    @Test
    fun `scanMapsForSandbox - 包含沙箱库时命中`() {
        mapsFile.writeText(
            """
            55f0a000-55f0b000 r-xp 00000000 fd:01 1000 /system/bin/app_process64
            7a000000-7a100000 r-xp 00000000 fd:01 2000 /data/data/com.taptap/files/tap_sandbox_core/core_v14064/libs/armeabi-v7a/libsandbox_ext.so
            7a100000-7a200000 rw-p 00100000 fd:01 3000 /system/lib64/libc.so
            """.trimIndent()
        )
        assertTrue("libsandbox_ext.so 属于沙箱库特征前缀，应命中",
            VulkanPolicy.scanMapsForSandbox(mapsFile.absolutePath))
    }

    @Test
    fun `scanMapsForSandbox - 包含 libtaptap_sandbox 时命中`() {
        mapsFile.writeText(
            """
            55f0a000-55f0b000 r-xp 00000000 fd:01 1000 /system/bin/app_process64
            7a200000-7a300000 r-xp 00000000 fd:01 4000 /data/app/libtaptap_sandbox.so
            """.trimIndent()
        )
        assertTrue("libtaptap_sandbox.so 应命中",
            VulkanPolicy.scanMapsForSandbox(mapsFile.absolutePath))
    }

    @Test
    fun `scanMapsForSandbox - 无沙箱库时未命中`() {
        mapsFile.writeText(
            """
            55f0a000-55f0b000 r-xp 00000000 fd:01 1000 /system/bin/app_process64
            7a100000-7a200000 rw-p 00100000 fd:01 3000 /system/lib64/libc.so
            """.trimIndent()
        )
        assertFalse("普通 maps 不应命中",
            VulkanPolicy.scanMapsForSandbox(mapsFile.absolutePath))
    }

    @Test
    fun `scanMapsForSandbox - 文件不存在时安全返回 false`() {
        assertFalse("缺失文件必须安全返回 false",
            VulkanPolicy.scanMapsForSandbox(tempFolder.root.absolutePath + "/no_such_file"))
    }

    @Test
    fun `scanMapsForSandbox - 空文件未命中`() {
        mapsFile.writeText("")
        assertFalse(VulkanPolicy.scanMapsForSandbox(mapsFile.absolutePath))
    }
}
