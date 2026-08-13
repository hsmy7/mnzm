package com.xianxia.sect.core.loop

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * currentAlpha 确定性守卫（2026-08-13 批次 3，对标 9.5 守卫测试三要素）。
 *
 * 不变量：插值因子 [com.xianxia.sect.core.GameEngineCore.currentAlpha] 是
 * **纯渲染契约**——core/engine 主源码中，除 GameEngineCore（唯一写入点）、
 * RenderFrame（契约字段定义）与 JitterSmoother（滤波参数）外，任何引擎
 * 状态写入路径不得引用 currentAlpha（引用即可能把渲染时序混入确定性
 * 游戏状态/RNG 序列）。
 *
 * 锚点：`currentAlpha` 标识符；故意排除项显式声明；错误消息带操作指引。
 */
class CurrentAlphaDeterminismGuardTest {

    /** 合法引用白名单（文件尾名 → 允许的用途说明） */
    private val allowedFiles = mapOf(
        "GameEngineCore.kt" to "唯一写入点（循环内计算 + JitterSmoother 滤波）",
        "RenderFrame.kt" to "渲染契约字段定义（仅声明不读取）",
        "JitterSmoother.kt" to "滤波输入参数（raw alpha）",
    )

    @Test
    fun `currentAlpha 仅渲染契约 - 引擎状态写入路径不得引用`() {
        val engineMainDir = File("src/main/java/com/xianxia/sect/core")
        val offenders = engineMainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> "currentAlpha" in file.readText() }
            .filter { file -> file.name !in allowedFiles }
            .map { it.name }
            .sorted()
            .toList()

        assertTrue(
            "以下文件引用了 currentAlpha（渲染契约字段）：$offenders——" +
                "插值因子只允许 GameEngineCore 写入 / RenderFrame 声明 / JitterSmoother 滤波；" +
                "若确需引擎读取，先在 CurrentAlphaDeterminismGuardTest 白名单登记并说明确定性理由",
            offenders.isEmpty()
        )
    }
}
