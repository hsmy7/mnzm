package com.xianxia.sect.core.architecture

import com.lemonappdev.konsist.api.Konsist
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 :core:engine 模块的架构约束 — 仅扫描 engine 模块自身的源码。
 *
 * 规则来源: CLAUDE.md §5 (引擎服务规范)
 */
class EngineServiceAnnotationTest {

    private val scope = Konsist.scopeFromDirectory("core/engine/src/main")

    // 已知缺少 @GameService 的遗留类（只缩不增，修复后移除）
    private val knownMissingAnnotation = emptySet<String>()

    @Test
    fun `new service classes must have GameService annotation`() {
        val serviceClasses = scope.classes()
            .filter { it.packagee?.name?.contains(".service") == true }
            .filter { clazz ->
                val name = clazz.name
                !name.endsWith("Data") && !name.endsWith("State") &&
                !name.endsWith("Result") && !name.endsWith("Event") &&
                !name.endsWith("Response") && !name.endsWith("Reward") &&
                !name.endsWith("Pool") && !name.endsWith("Pools") &&
                !name.endsWith("Entry") && !name.endsWith("Accumulator") &&
                !name.endsWith("Summary") && !name.endsWith("Snapshot") &&
                name != "Success" && name != "CapacityInsufficient" &&
                name != "DistributeFailed" &&
                name != "SuccessWithMilestones" &&
                name != "HighFrequencyData"
            }

        val newUnannotated = serviceClasses.filter { clazz ->
            !knownMissingAnnotation.contains(clazz.name) &&
            !clazz.text.contains("@GameService")
        }

        assertTrue(
            "新增 service 类必须有 @GameService: ${newUnannotated.map { it.name }.joinToString(", ")}",
            newUnannotated.isEmpty()
        )
    }

    @Test
    fun `known missing annotation list should only shrink`() {
        val serviceClasses = scope.classes()
            .filter { it.packagee?.name?.contains(".service") == true }
        val annotatedNow = serviceClasses
            .filter { clazz -> knownMissingAnnotation.contains(clazz.name) }
            .filter { clazz -> clazz.text.contains("@GameService") }

        // 如果已知遗留类现已标注，应从列表移除（stderr 告警，不失败）
        if (annotatedNow.isNotEmpty()) {
            System.err.println(
                "以下类已知缺少注解但现已标注，请从 knownMissingAnnotation 移除: " +
                annotatedNow.map { it.name }.joinToString(", ")
            )
        }
    }

    @Test
    fun `engine has no Compose UI runtime imports`() {
        val violations = scope.files.filter { file ->
            val text = file.text
            text.contains("import androidx.compose.foundation.") ||
            text.contains("import androidx.compose.material.") ||
            text.contains("import androidx.compose.ui.text.") ||
            text.contains("import androidx.compose.ui.platform.")
        }

        assertTrue(
            "engine 禁止引用 Compose UI 运行时: ${violations.map { it.name }.joinToString(", ")}",
            violations.isEmpty()
        )
    }

    @Test
    fun `engine does not import feature or app code`() {
        val violations = scope.files.filter { file ->
            val text = file.text
            text.contains("import com.xianxia.sect.feature.") ||
            text.contains("import com.xianxia.sect.ui.game.") ||
            text.contains("import com.xianxia.sect.di.AppModule") ||
            text.contains("import com.xianxia.sect.taptap.") ||
            text.contains("import com.xianxia.sect.network.")
        }

        assertTrue(
            "engine 禁止引用 feature/app 包: ${violations.map { it.name }.joinToString(", ")}",
            violations.isEmpty()
        )
    }
}
