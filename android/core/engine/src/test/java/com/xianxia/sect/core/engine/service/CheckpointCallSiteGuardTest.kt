package com.xianxia.sect.core.engine.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * checkpoint 调用点守卫测试（2026-08-01，CLAUDE.md 9.5 守卫三要素）。
 *
 * 背景：修炼速率变化点必须调用 checkpointDisciple/checkpointAllDisciples 重新记账，
 * 否则 getEffectiveCultivation 投影（realtimeCultivation）会用旧速率推导错误值。
 * 历史遗漏：DiscipleFacadeImpl 服药路径曾缺失 checkpoint（2026-08-01 已补）。
 *
 * 锚点：速率变化入口常量表。新增影响修炼速率的代码路径时，若忘记 checkpoint，
 * 本测试失败并提示补齐点。
 */
class CheckpointCallSiteGuardTest {

    /**
     * 速率变化入口 → 期望的 checkpoint 调用签名（源码文本断言）。
     * 故意排除项：本表外的"纯速率读取"路径（CultivationRateCalculator 等只读不写）。
     */
    private data class Entry(val file: String, val expectedCall: String)

    private val entries = listOf(
        Entry(
            "service/AutoPillService.kt",
            "tables.checkpointDisciple(id, currentMonth)"
        ),
        Entry(
            "service/DiscipleBreakthroughHandler.kt",
            "tables.checkpointDisciple(it, currentMonth)"
        ),
        Entry(
            "domain/disciple/DiscipleFacadeImpl.kt",
            "discipleTables.checkpointDisciple(id, gameData.gameYear * 12 + gameData.gameMonth)"
        ),
        // 洗炼灵根确认替换：灵根影响修炼速率，替换瞬间必须重新记账
        // （GameEngineSpiritRootOps.confirmSpiritRootWash 事务内 remove+insert 后调用）
        Entry(
            "engine/GameEngineSpiritRootOps.kt",
            "discipleTables.checkpointDisciple(id, gameData.gameYear * 12 + gameData.gameMonth)"
        ),
        // 洗炼天赋/体质/词条确认替换：体质（cultivationSpeedBonus）与词条（CULT_SPEED）
        // 影响修炼速率，替换瞬间必须重新记账（GameEngineTraitWashOps.confirmTraitWash 事务内）
        Entry(
            "engine/GameEngineTraitWashOps.kt",
            "discipleTables.checkpointDisciple(id, gameData.gameYear * 12 + gameData.gameMonth)"
        )
    )

    @Test
    fun `所有速率变化入口已接 checkpoint`() {
        for (entry in entries) {
            val file = findSourceFile(entry.file)
            val source = file.readText()
            assertTrue(
                "${entry.file} 缺少 checkpoint 调用（期望包含 `" +
                    "${entry.expectedCall}`）——速率变化点不重新记账会导致 " +
                    "getEffectiveCultivation 投影错误。请在该速率变化分支末尾补 checkpointDisciple(id, currentMonth)。",
                source.contains(entry.expectedCall)
            )
        }
    }

    @Test
    fun `政策与长老入口已接全量 checkpoint`() {
        // 政策/长老变化影响全体弟子 → checkpointAllDisciples
        val sectPolicyFile = findSourceFile("usecase/SectPolicyToggleUseCase.kt")
        val sectPolicySource = sectPolicyFile.readText()
        assertTrue(
            "SectPolicyToggleUseCase 缺少 checkpointAllDisciples——政策切换影响全体弟子速率，必须全量重新记账",
            sectPolicySource.contains("checkpointAllDisciples")
        )
        val elderFile = findSourceFile("usecase/ElderManagementUseCase.kt")
        val elderSource = elderFile.readText()
        assertTrue(
            "ElderManagementUseCase 缺少 checkpointAllDisciples——长老变更影响全体弟子速率，必须全量重新记账",
            elderSource.contains("checkpointAllDisciples")
        )
    }

    /**
     * 从 core/engine 模块根目录（测试工作目录）解析源码文件。
     */
    private fun findSourceFile(relative: String): File {
        val file = File("src/main/java/com/xianxia/sect/core/$relative")
        if (!file.exists()) {
            throw AssertionError("找不到源码文件: $relative（工作目录=${File(".").absolutePath}）")
        }
        return file
    }
}
