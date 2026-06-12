package com.xianxia.sect.core.architecture

import com.lemonappdev.konsist.api.Konsist
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 :core:domain 模块的架构约束 — 仅扫描 domain 模块自身的源码。
 *
 * 规则来源: CLAUDE.md §2 (模块架构规范)
 * - 2.1: domain 零 Android 框架依赖
 * - 2.4: 禁止依赖 feature 或 app 模块
 */
class DomainDependencyTest {

    // 仅扫描 domain 模块源码目录，不包含其他模块
    private val scope = Konsist.scopeFromDirectory("core/domain/src/main")

    @Test
    fun `domain has kotlin files`() {
        assertTrue("domain 模块应包含 Kotlin 文件", scope.files.size > 0)
    }

    @Test
    fun `domain has no forbidden android framework imports`() {
        val violations = scope.files.filter { file ->
            val text = file.text
            text.contains("import android.app.") ||
            text.contains("import android.widget.") ||
            text.contains("import android.view.") ||
            text.contains("import android.os.Bundle") ||
            text.contains("import android.content.Context") ||
            text.contains("import android.content.Intent") ||
            text.contains("import android.provider.") ||
            text.contains("import android.database.")
        }

        assertTrue(
            "domain 模块禁止引用 Android 框架类，违规文件: ${violations.map { it.name }.take(10)}",
            violations.isEmpty()
        )
    }

    @Test
    fun `domain does not import feature or app code`() {
        val violations = scope.files.filter { file ->
            val text = file.text
            text.contains("import com.xianxia.sect.feature.") ||
            text.contains("import com.xianxia.sect.ui.game.") ||
            text.contains("import com.xianxia.sect.di.AppModule") ||
            text.contains("import com.xianxia.sect.taptap.") ||
            text.contains("import com.xianxia.sect.network.")
        }

        assertTrue(
            "domain 模块禁止引用 feature/app 包，违规文件: ${violations.map { it.name }.take(10)}",
            violations.isEmpty()
        )
    }
}
