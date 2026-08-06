package com.xianxia.sect.ui.game.components

import com.xianxia.sect.core.domain.dialog.DialogType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DialogType 渲染分支覆盖守卫测试（2026-08-01，CLAUDE.md 9.5 守卫三要素）。
 *
 * 背景：DialogType.SalaryConfig 曾是空渲染分支（GameOverlayHost when 分支为 {}），
 * 配合 anyDialogVisible 的全屏无关闭按钮遮罩 = 玩家黑屏软锁（2026-08-01 已移除）。
 * 本测试守卫：新增 DialogType 嵌套类型时，若忘记在 GameOverlayHost 渲染 when 分支添加
 * 渲染实现，测试失败并提示补齐。
 *
 * 锚点：DialogType 嵌套类型全量（反射枚举）。
 * 故意排除项：None（无对话框占位，不渲染）。
 */
class DialogTypeRenderCoverageTest {

    /**
     * 当前已在 GameOverlayHost 渲染 when 分支实现渲染的 DialogType。
     * 注意：新增 DialogType 时此集合必须同步更新（与 GameOverlayHost 的 when 分支一一对应），
     * 否则会变成空分支软锁。
     */
    private val renderedDialogTypes: Set<DialogType> = setOf(
        DialogType.Disciples,
        DialogType.Warehouse,
        DialogType.Settings,
        DialogType.Buildings,
        DialogType.Recruit,
        DialogType.Diplomacy,
        DialogType.Planting,
        DialogType.Merchant,
        DialogType.WorldMap,
        DialogType.BattleLog,
        DialogType.Mail,
        DialogType.Lizhan,
        DialogType.Leaderboard,
        DialogType.SpiritMine(""),
        DialogType.HerbGarden,
        DialogType.Alchemy(""),
        DialogType.Forge(""),
        DialogType.PatrolTower(""),
        DialogType.BloodRefiningPool(""),
        DialogType.Residence(""),
        DialogType.WarehouseBuilding(""),
        DialogType.Library,
        DialogType.WenDaoPeak,
        DialogType.QingyunPeak,
        DialogType.TianshuHall,
        DialogType.LawEnforcementHall,
        DialogType.MissionHall,
        DialogType.ReflectionCliff,
        DialogType.Guide,
        DialogType.GameOver,
        DialogType.RenameSect,
        DialogType.SectLevelDetail,
        DialogType.CloudSave,
        DialogType.BuildingSectLevelRequirement("")
    )

    /** 故意排除项：无对话框占位，不渲染任何内容 */
    private val intentionallyExcluded: Set<Class<*>> = setOf(DialogType.None::class.java)

    @Test
    fun `所有 DialogType 嵌套类型都有渲染分支`() {
        // sealed interface 无法 values() 枚举，用反射枚举嵌套类型
        val allTypes: Set<Class<*>> = DialogType::class.nestedClasses
            .filter { !it.isCompanion && it.simpleName != null }
            .map { it.java }
            .toSet()

        val renderedTypes: Set<Class<*>> = renderedDialogTypes.map { it::class.java }.toSet()
        val missing = allTypes - renderedTypes - intentionallyExcluded

        assertTrue(
            "新增 DialogType 未在 GameOverlayHost 渲染 when 分支实现：" +
                "${missing.map { it.simpleName }}。\n" +
                "修复指引：在 GameOverlayHost 的渲染 when 中添加对应分支（含实际 UI 内容），" +
                "并同步更新本测试的 renderedDialogTypes 集合——空分支会触发黑屏软锁" +
                "（anyDialogVisible 渲染无关闭按钮的全屏遮罩）。",
            missing.isEmpty()
        )
    }

    @Test
    fun `渲染集合不含故意排除项`() {
        val renderedTypes: Set<Class<*>> = renderedDialogTypes.map { it::class.java }.toSet()
        assertEquals(emptySet<Class<*>>(), renderedTypes intersect intentionallyExcluded)
    }
}
