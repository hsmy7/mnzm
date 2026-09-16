package com.xianxia.sect.core.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守卫测试：镜像只读契约（w3-13 反向通道删除后的防复发门禁，handover §2.82）。
 *
 * ## 存在理由
 * 2026-09-15 `w3-13-pre-delete` 归档后，Kotlin → C++ 的反向增量通道
 * （`captureReverseDirty` / `ReverseDirtyAccumulator` / `StateSyncService.applyDirtyToNative`
 * / `GameCore::applyReverseDirty` / `ReverseChannelPolicy` 本体与四个批次
 * closures 分片）已**整族删除**。AUTHORITATIVE 稳态下 Kotlin 对 C++ 状态只读，
 * 唯一合法的 C++ 状态写入路径是 `StateSyncService.importToNative` 全量导入
 * （读档/新档基线与 `rebaselineNativeMirror` 事件后重建）。
 *
 * 若本守卫变红 = 有人重新引入反向通道符号（或新增指向已删 JNI 导出的声明）
 * ——这等于把"删除步"整体回退，必须先走 ADR reverse-channel-elimination §4
 * 五步规程重新立项，**禁止**以局部理由恢复通道。
 *
 * ## 判据
 * 六模块**主源**（含 `app` 的 C++ JNI 面）对反向通道符号族零命中：
 * - Kotlin：`ReverseChannelPolicy` / `ReverseDirty` / `consumeReverseDirty` /
 *   `resetReverseAccumulator` / `applyDirtyToNative`
 * - JNI/C++（app 主源）：`nativeApplyReverseDirty` / `applyReverseDirty`
 *
 * 配套：稳态零写入断言（运行期面）在
 * `DiffAuthoritativeTickTest`（100 旬 AUTHORITATIVE 管线
 * `storeA.nonMirrorWriteCount == 0`）——两道守卫分别看护"符号面"与"行为面"。
 */
class MirrorReadOnlyGuardTest {

    /** 模块主源根目录（Gradle 测试工作目录 = android/core/engine，故用相对路径） */
    private val moduleMainDirs: List<Pair<String, File>> = listOf(
        "core/domain" to File(mainSourcePath("core", "domain")),
        "core/engine" to File(mainSourcePath("core", "engine")),
        "core/data" to File(mainSourcePath("core", "data")),
        "core/ui" to File(mainSourcePath("core", "ui")),
        "feature/game" to File(mainSourcePath("feature", "game")),
        "app" to File(mainSourcePath("app"))
    )

    /** 反向通道符号族（Kotlin + JNI/C++ 共用一版正则；出现即 = 通道复活） */
    private val forbiddenSymbols = Regex(
        """ReverseChannelPolicy|ReverseDirty|consumeReverseDirty|""" +
            """resetReverseAccumulator|applyDirtyToNative|applyReverseDirty"""
    )

    @Test
    fun `反向通道符号族在主源零命中`() {
        val violations = mutableListOf<String>()
        for ((moduleName, dir) in moduleMainDirs) {
            assertTrue("模块主源目录不存在: $moduleName -> $dir", dir.isDirectory)
            dir.walkTopDown()
                .filter {
                    it.isFile && it.extension in setOf("kt", "java", "h", "cpp")
                }
                .forEach { file ->
                    file.useLines { lines ->
                        lines.forEachIndexed { idx, line ->
                            if (forbiddenSymbols.containsMatchIn(line)) {
                                violations += "$moduleName | ${file.relativeTo(dir)}:${idx + 1} | ${line.trim()}"
                            }
                        }
                    }
                }
        }
        assertTrue(
            "镜像只读契约（w3-13 防复发）：反向通道已删除，主源不得再出现通道符号——\n" +
                "命中即 = 删除步被局部回退，须按 ADR reverse-channel-elimination §4 重新立项。\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    private fun mainSourcePath(vararg segments: String): String = (
        listOf("..", "..") + segments + listOf("src", "main")
        ).joinToString(File.separator)
}
