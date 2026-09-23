package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.engine.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `memorySubsystem` 旗标守卫（memory-refactor P0.1，全局约束 8）。
 *
 * 锁两件事：
 * 1. **运行时入口与编译期注入一致**——`NativeEngineFlag.memorySubsystem`
 *    必须由 `BuildConfig.MEMORY_SUBSYSTEM_DEFAULT` 初始化；有人改成硬编码
 *    字面量（绕开 api.properties 本地覆盖通道）即红。
 * 2. **入库默认值 = 评审结论**——`core/engine/build.gradle` 的
 *    `getProperty` 回退字面量就是"经评审的默认"本体（api.properties 是
 *    gitignored 本地件不入库）：预发期必须为 `'false'`。根治验收
 *    （实施方案附录 A 真机清单 + P4.5 门禁全绿）后翻 `'true'` 时，
 *    **必须同步**改 build.gradle 回退值与本测试断言（登记进翻默认报告）。
 *
 * 本地开发者经 api.properties 覆盖为 true 不会打红本测试——两条断言
 * 分别锚定"接线一致"与"入库字面量"，均不受本地覆盖影响。
 */
class NativeEngineFlagMemorySubsystemTest {

    @Test
    fun `memorySubsystem_运行时入口由编译期注入初始化`() {
        assertEquals(
            "NativeEngineFlag.memorySubsystem 必须以 BuildConfig.MEMORY_SUBSYSTEM_DEFAULT " +
                "为初值（编译期/本地默认唯一来源；硬编码字面量会绕开 api.properties 覆盖通道）",
            BuildConfig.MEMORY_SUBSYSTEM_DEFAULT,
            NativeEngineFlag.memorySubsystem,
        )
    }

    @Test
    fun `build_gradle 入库默认字面量为评审值`() {
        val gradleSource = File("build.gradle").readText()
        val defaultLines = gradleSource.lines()
            .filter { it.contains("getProperty('MEMORY_SUBSYSTEM_DEFAULT'") }
        assertTrue(
            "core/engine/build.gradle 缺失 MEMORY_SUBSYSTEM_DEFAULT 注入行" +
                "（memory-refactor P0.1 被移除？）",
            defaultLines.isNotEmpty(),
        )
        assertEquals(
            "MEMORY_SUBSYSTEM_DEFAULT 注入行应恰一处（第二处 = 第二开关体系，全局约束 8 禁止）",
            1, defaultLines.size,
        )
        assertTrue(
            "MEMORY_SUBSYSTEM_DEFAULT 入库回退默认必须为 'false'（预发期评审值；" +
                "翻 true 前置 = 根治验收：实施方案附录 A + P4.5 全绿，翻默认须同步本断言）",
            defaultLines.single().contains("'false'"),
        )
    }
}
