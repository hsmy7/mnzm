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
    // 以下类位于 service 包内但非服务类，均为内部 data class / sealed subclass：
    // - SomeDisabled — PolicyCostResult sealed interface 的子类
    // - SalaryPlan — 私有 data class
    // - DefensePreparation — CaveExplorationProcessor 内的私有 data class
    // - AdService — 接口（interface），非具体服务实现，@GameService(AnnotationTarget.CLASS) 不适用
    // - AdPurpose — 枚举（enum），非具体服务实现
    private val knownMissingAnnotation = setOf(
        "CultivationSharedState",
        "SomeDisabled",
        "SalaryPlan",
        "DefensePreparation",
        "AdService",
        "AdPurpose"
    )

    /** 非服务类后缀排除表（new service classes must have GameService annotation 拆分） */
    private val excludedNameSuffixes = listOf(
        "Data", "State", "Result", "Event", "Response", "Reward",
        "Pool", "Pools", "Entry", "Accumulator", "Summary", "Snapshot",
        "Context", "Params", "Zones",
        // Record/Queue 后缀：data class 数据载体（BereavementRecord）、
        // 内部队列基建（YearlyOpsQueue）——非服务类，同上方后缀排除
        "Record", "Queue",
        // Maps/Levels 后缀：service 内部私有数据载体（RecoveryMaps 恢复映射打包、
        // HpMpLevels HP/MP 四元组）——非服务类，同上方后缀排除
        "Maps", "Levels"
    )

    /** 非服务类特例名排除表（new service classes must have GameService annotation 拆分） */
    private val excludedExactNames = setOf(
        "Success", "CapacityInsufficient", "DistributeFailed",
        "SuccessWithMilestones", "HighFrequencyData"
    )

    /** 服务类候选排除判定（new service classes must have GameService annotation 拆分）：后缀/特例名命中即非服务类 */
    private fun isNonServiceClass(name: String): Boolean =
        excludedNameSuffixes.none { name.endsWith(it) } &&
            name !in excludedExactNames

    @Test
    fun `new service classes must have GameService annotation`() {
        val serviceClasses = scope.classes()
            .filter { it.packagee?.name?.contains(".service") == true }
            .filter { clazz -> isNonServiceClass(clazz.name) }

        val newUnannotated = serviceClasses.filter { clazz ->
            val simpleName = clazz.name.substringAfterLast(".")
            !knownMissingAnnotation.contains(simpleName) &&
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
