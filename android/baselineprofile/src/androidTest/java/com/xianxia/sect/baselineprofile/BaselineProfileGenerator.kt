package com.xianxia.sect.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Baseline Profile 生成器
 *
 * 覆盖以下热路径，确保 AOT 编译：
 * - 游戏启动 + 初始化
 * - 游戏循环 tick（EngineCore、SystemManager、Compose 渲染）
 * - 弟子面板打开 + 列表渲染（LazyVerticalGrid）
 * - 仓库面板打开 + 列表渲染（LazyColumn + LazyVerticalGrid）
 * - 宗门地图渲染（NativeSurfaceView + Vulkan 管线）
 * - 设置面板打开 + 多 section 渲染
 * - Dialog 打开/关闭（AlchemyDialog）
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    companion object {
        private const val GAME_TICK_MS = 1200L
        private const val UI_WAIT_MS = 500L
    }

    @Test
    fun generate() {
        baselineProfileRule.collect(
            packageName = "com.xianxia.sect",
            includeInStartupProfile = true
        ) {
            pressHome()
            startActivityAndWait()
            waitForIdleSync()
        }
    }

    @Test
    fun gamePlayScenario() = baselineProfileRule.collect(
        packageName = "com.xianxia.sect",
        includeInStartupProfile = false
    ) {
        pressHome()
        startActivityAndWait()
        waitForIdleSync()

        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        // ── 阶段1: 游戏循环预热（引擎 tick + Compose 渲染 + StateFlow 链） ──
        repeat(6) {
            Thread.sleep(GAME_TICK_MS)
            waitForIdleSync()
        }

        // ── 阶段2: 打开弟子面板（LazyVerticalGrid 列表渲染） ──
        clickText(device, "弟子")
        Thread.sleep(UI_WAIT_MS)
        waitForIdleSync()

        // 返回主界面
        device.pressBack()
        Thread.sleep(UI_WAIT_MS)
        waitForIdleSync()

        // ── 阶段3: 打开仓库面板（LazyColumn + LazyVerticalGrid） ──
        clickText(device, "仓库")
        Thread.sleep(UI_WAIT_MS)
        waitForIdleSync()

        // 关闭仓库
        device.pressBack()
        Thread.sleep(UI_WAIT_MS)
        waitForIdleSync()

        // ── 阶段4: 打开建造面板 ──
        clickText(device, "建造")
        Thread.sleep(UI_WAIT_MS)
        waitForIdleSync()

        // 关闭建造
        device.pressBack()
        Thread.sleep(UI_WAIT_MS)
        waitForIdleSync()

        // ── 阶段5: 打开设置面板（多 section LazyColumn） ──
        clickText(device, "设置")
        Thread.sleep(UI_WAIT_MS)
        waitForIdleSync()
        device.pressBack()
        Thread.sleep(UI_WAIT_MS)
        waitForIdleSync()

        // ── 阶段6: 更多游戏循环预热（含上述面板的 Compose 节点缓存） ──
        repeat(3) {
            Thread.sleep(GAME_TICK_MS)
            waitForIdleSync()
        }

        // ── 阶段7: 再次打开弟子面板（验证缓存路径） ──
        clickText(device, "弟子")
        Thread.sleep(UI_WAIT_MS)
        waitForIdleSync()
        device.pressBack()
        waitForIdleSync()
    }

    /**
     * 通过文本查找并点击 UI 元素。
     * 游戏中使用 SpriteImage + Text 的 FloatingActionButton，
     * Text 内容可通过 UiAutomator By.text 定位。
     */
    private fun clickText(device: UiDevice, text: String) {
        val target = device.wait(
            Until.findObject(By.text(text)),
            3_000L
        )
        if (target != null) {
            target.click()
        }
    }
}
