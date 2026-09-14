package com.xianxia.sect.core.config

import com.xianxia.sect.core.platform.AssetSource
import com.xianxia.sect.core.render.SpriteAtlasDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * 立绘口径守卫（兜底默认配置）：建筑**精灵尺寸**必须满足
 * ① 精灵宽 == 占地宽（相邻建筑精灵不左右压盖）；
 * ② 精灵高 ≥ 占地深（精灵盖住底座，不露出空地）；
 * ③ 高建筑（塔/楼/殿）精灵必须**高于**占地（立绘按屏上不变形取值，向上多占格）。
 *
 * 背景：旧守卫强制 `sprite == grid`（1:1 贴地），把带高度的立绘硬压进正方形占地格
 * ——巡视楼 569×1024 只显示出约 31% 的高度。现行口径按素材纵横比取显示尺寸
 * （`H = W × 素材高 ÷ (0.75 × 素材宽)`，`0.75 = TOPDOWN_Y_SCALE`），
 * 由 `build-atlas.mjs` validateDisplaySizing 构建期校验、`SpriteSizingFidelityTest`
 * 测试期校验素材比例；本测试锁**兜底配置数值**（与 `config/buildings.json` 同为镜像，
 * 真实资产结构由 app 模块 `BuildingSpriteFootprintJsonGuardTest` 守卫）。
 */
class BuildingSpriteFootprintGuardTest {

    /** 显示名 → (占地宽, 占地深, 精灵宽, 精灵高) 期望表（与 Defaults.kt 同步）。 */
    private val expected = mapOf(
        "灵田" to intArrayOf(1, 1, 1, 1),
        "灵矿场" to intArrayOf(4, 4, 4, 5),
        "灵植阁" to intArrayOf(4, 3, 4, 5),
        "炼丹炉" to intArrayOf(4, 2, 4, 5),
        "锻造坊" to intArrayOf(5, 3, 5, 7),
        "仓库" to intArrayOf(6, 4, 6, 7),
        "藏经阁" to intArrayOf(6, 3, 6, 5),
        "问道塔" to intArrayOf(4, 2, 4, 8),
        "青云塔" to intArrayOf(4, 2, 4, 8),
        "天枢殿" to intArrayOf(18, 13, 18, 19),
        "执法堂" to intArrayOf(6, 3, 6, 5),
        "任务阁" to intArrayOf(4, 3, 4, 5),
        "巡视楼" to intArrayOf(4, 2, 4, 10),
        "监牢" to intArrayOf(4, 4, 4, 5),
        "初级单人住所" to intArrayOf(4, 4, 4, 6),
        "中级单人住所" to intArrayOf(6, 6, 6, 8),
        "初级多人住所" to intArrayOf(6, 4, 6, 7),
        "中级多人住所" to intArrayOf(6, 5, 6, 8),
        "血炼池" to intArrayOf(4, 3, 4, 3),
    )

    @Test
    fun `兜底默认配置建筑尺寸与期望全等`() {
        val assetSource = mock<AssetSource>()
        // 资产不存在（open 返回 null）→ 走 createDefaultConfig fallback
        whenever(assetSource.open(any())).thenReturn(null)
        val service = BuildingConfigService(assetSource)

        val configs = service.getAllBuildingConfigs()
        assertEquals("兜底默认建筑数量", expected.size, configs.size)

        val offenders = mutableListOf<String>()
        for (config in configs) {
            val want = expected[config.displayName]
                ?: throw AssertionError(
                    "新增建筑 ${config.displayName} 未在本守卫期望表登记" +
                        "（同步点：Defaults.kt / config/buildings.json / build-atlas.mjs LAYOUT）"
                )
            val actual = intArrayOf(
                config.gridWidth, config.gridHeight, config.spriteWidth, config.spriteHeight
            )
            if (want.toList() != actual.toList()) {
                offenders += "${config.displayName}: 期望 占地 ${want[0]}×${want[1]}、精灵 ${want[2]}×${want[3]}" +
                    "，实际 占地 ${actual[0]}×${actual[1]}、精灵 ${actual[2]}×${actual[3]}"
            }
        }
        assertEquals(
            "以下建筑尺寸与期望不一致（改尺寸须同步：Defaults.kt / config/buildings.json / " +
                "BuildingFeatureBoot.kt / build-atlas.mjs LAYOUT.footprints）：\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders
        )
    }

    @Test
    fun `兜底默认配置满足精灵宽等于占地宽且精灵高不低于占地深`() {
        val assetSource = mock<AssetSource>()
        whenever(assetSource.open(any())).thenReturn(null)
        val service = BuildingConfigService(assetSource)

        val offenders = service.getAllBuildingConfigs().mapNotNull { config ->
            when {
                config.spriteWidth != config.gridWidth ->
                    "${config.displayName}: 精灵宽 ${config.spriteWidth} ≠ 占地宽 ${config.gridWidth}"
                config.spriteHeight < config.gridHeight ->
                    "${config.displayName}: 精灵高 ${config.spriteHeight} < 占地深 ${config.gridHeight}"
                else -> null
            }
        }
        assertEquals(
            "以下建筑精灵/占地关系不合法（宽必须相等、高不得低于占地深）：\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders
        )
    }

    @Test
    fun `高建筑精灵高于占地 - 立绘不得再被压进占地格`() {
        val assetSource = mock<AssetSource>()
        whenever(assetSource.open(any())).thenReturn(null)
        val service = BuildingConfigService(assetSource)
        val configs = service.getAllBuildingConfigs().associateBy { it.displayName }

        // 塔/楼/殿类：素材高宽比决定精灵明显高于占地（旧 1:1 口径会把立绘压扁）
        for ((name, minHeight) in mapOf("问道塔" to 8, "青云塔" to 8, "巡视楼" to 10, "天枢殿" to 19)) {
            val config = configs[name] ?: throw AssertionError("缺少建筑配置: $name")
            assertTrue(
                "$name 精灵高 ${config.spriteHeight} 应 ≥ $minHeight（高建筑立绘须向上多占格，不得压进占地）",
                config.spriteHeight >= minHeight
            )
            assertTrue(
                "$name 精灵高 ${config.spriteHeight} 应大于占地深 ${config.gridHeight}（占地 = 底座）",
                config.spriteHeight > config.gridHeight
            )
        }
        // 贴地网格类：灵田与地格 1:1 平铺对齐（不作为立绘处理）
        val field = configs["灵田"] ?: throw AssertionError("缺少建筑配置: 灵田")
        assertEquals("灵田精灵宽", 1, field.spriteWidth)
        assertEquals("灵田精灵高", 1, field.spriteHeight)
        assertTrue(
            "灵田在图集贴地网格类名单中（build-atlas.mjs LAYOUT.gridAlignedBuildings）",
            SpriteAtlasDef.BUILDING_NAME_INDEX.containsKey("灵田")
        )
    }
}
