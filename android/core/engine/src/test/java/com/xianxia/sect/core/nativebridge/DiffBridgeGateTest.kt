package com.xianxia.sect.core.nativebridge

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IN8 出厂门守卫（SR-7 C7 补口）——桌面 JNI 对拍桥不可用时**判红**，不得静默跳过。
 *
 * 方案 §3 IN8：「每批 `Diff*` 对拍 0 skip 是出厂门」。实测本批开工时该门是空的：
 * `Diff*Test.kt` 家族共 **50 个文件**，其中 **45 个**以
 * `assumeTrue(DiffRngBridge.isAvailable())` 打头——没带 `-Dgamecore.jni.path` 时全部
 * 按 JUnit "skipped" 计，而 Gradle 视 skip 为成功，`test` 任务照样绿。
 * 也就是说"273 例对拍通过"这句在缺桥时会退化成"0 例跑过、无人在意"。
 *
 * 本类刻意**不带 assumeTrue**：它是那道门的显式判据。
 * 让它变绿的唯一方式是按门禁口径跑：
 * `./gradlew.bat testReleaseUnitTest --max-workers=1 "-Dgamecore.jni.path=<Windows 原生路径>"`
 * （桌面 JNI 由 `app/src/main/cpp/gamecore` 的 `ninja -C build/desktop-test` 产出）。
 *
 * 与本类配套的其余 IN8 证据：完成报告须贴 `Diff*` 的 XML `skipped="0"` 计数
 * （口径见 `docs/parallel-batches-w5/report-B19-completion-2026-09-21.md`）。
 */
class DiffBridgeGateTest {

    @Test
    fun `IN8 桌面 JNI 对拍桥必须可用（不可用即红，不得让 Diff 家族静默 skip）`() {
        val path = System.getProperty("gamecore.jni.path")
        assertTrue(
            "IN8 出厂门失效：未配置 -Dgamecore.jni.path（当前值=$path）⇒ " +
                "50 个 Diff*Test 文件中 45 个会 assumeTrue 静默跳过，确定性对拍等于没跑。" +
                "先构建桌面 JNI（cd app/src/main/cpp/gamecore && ninja -C build/desktop-test），" +
                "再按门禁命令带上 -Dgamecore.jni.path=<绝对路径>/libgamecorejni.so 复跑。",
            DiffRngBridge.isAvailable()
        )
    }
}
