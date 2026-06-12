package com.xianxia.sect.ui.game.architecture

import com.lemonappdev.konsist.api.Konsist
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 :feature:game 模块的架构约束 — 仅扫描 feature:game 模块自身的源码。
 *
 * 规则来源: CLAUDE.md §4 (ViewModel 规范)
 *
 * 注意：GameViewModel 和 SaveLoadViewModel 作为核心 ViewModel 需要访问
 * GameStateStore，这是架构允许的特例。此测试仅为新代码设立规则。
 */
class ViewModelArchitectureTest {

    // 仅扫描 feature:game 模块源码目录
    private val scope = Konsist.scopeFromDirectory("feature/game/src/main")

    // GameViewModel / SaveLoadViewModel 是核心枢纽，需要 GameStateStore 访问权
    private val coreViewModels = setOf("GameViewModel", "SaveLoadViewModel")

    @Test
    fun `every ViewModel extends BaseViewModel`() {
        val viewModels = scope.classes()
            .filter { it.name.endsWith("ViewModel") }

        val violations = viewModels.filter { vm ->
            !vm.text.contains("BaseViewModel")
        }

        assertTrue(
            "新 ViewModel 必须继承 BaseViewModel，违规: ${violations.map { it.name }.joinToString(", ")}",
            violations.isEmpty()
        )
    }

    @Test
    fun `non-core ViewModels should not import GameStateStore`() {
        // GameViewModel/SaveLoadViewModel 是核心枢纽，需要访问 GameStateStore
        val nonCoreFiles = scope.files.filter { file ->
            !coreViewModels.any { file.name.startsWith(it) }
        }

        val violations = nonCoreFiles.filter { file ->
            file.text.contains("import com.xianxia.sect.core.state.GameStateStore")
        }

        assertTrue(
            "非核心 ViewModel 禁止直接 import GameStateStore: ${violations.map { it.name }.joinToString(", ")}",
            violations.isEmpty()
        )
    }

    @Test
    fun `feature game does not import app module entry classes`() {
        val violations = scope.files.filter { file ->
            val text = file.text
            text.contains("import com.xianxia.sect.XianxiaApplication") ||
            text.contains("import com.xianxia.sect.MainActivity")
        }

        assertTrue(
            "feature/game 禁止反向引用 :app 入口类: ${violations.map { it.name }.joinToString(", ")}",
            violations.isEmpty()
        )
    }
}
