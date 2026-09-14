package com.xianxia.sect

import com.xianxia.sect.core.engine.domain.building.BuildingFeature
import com.xianxia.sect.core.engine.domain.building.BuildingFeatureRegistry
import com.xianxia.sect.core.render.SpriteAtlasDef
import com.xianxia.sect.ui.game.building.registerDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * C++/Kotlin 足迹尺寸表同步守卫测试。
 *
 * `footprint_table.h`（由 `./gradlew generateFootprintHeader` 从
 * `SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX` 自动生成）与 Kotlin 表必须逐项一致——
 * 若生成任务失效或有人手改头文件，本测试失败。
 * 同时校验 BUILDING_NAMES 与足迹表数量一致（新增建筑时两侧同步）。
 */
class FootprintTableSyncTest {

    @Test
    fun `FP_W FP_H 与 FOOTPRINT_BY_NAME_INDEX 逐项一致`() {
        val (cppW, cppH) = parseFootprintArrays()
        val kotlinFootprints = SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX

        assertEquals(
            "C++ FP_W 数量(${cppW.size}) 与 Kotlin 足迹表数量(${kotlinFootprints.size})不一致——" +
                "新增/删除建筑时必须同步 NativeBridge.cpp 的 FP_W/FP_H 与 SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX",
            kotlinFootprints.size, cppW.size
        )
        assertEquals(
            "C++ FP_H 数量(${cppH.size}) 与 Kotlin 足迹表数量(${kotlinFootprints.size})不一致",
            kotlinFootprints.size, cppH.size
        )

        for (i in kotlinFootprints.indices) {
            val (kotlinW, kotlinH) = kotlinFootprints[i]
            assertEquals(
                "FP_W[$i] (${SpriteAtlasDef.BUILDING_NAMES.getOrNull(i) ?: "?"}) " +
                    "C++=${cppW[i]} ≠ Kotlin=$kotlinW —— 修改占地尺寸必须两端同步",
                kotlinW, cppW[i]
            )
            assertEquals(
                "FP_H[$i] (${SpriteAtlasDef.BUILDING_NAMES.getOrNull(i) ?: "?"}) " +
                    "C++=${cppH[i]} ≠ Kotlin=$kotlinH —— 修改占地尺寸必须两端同步",
                kotlinH, cppH[i]
            )
        }
    }

    @Test
    fun `BUILDING_NAMES 与足迹表数量一致`() {
        assertEquals(
            "BUILDING_NAMES 数量(${SpriteAtlasDef.BUILDING_NAMES.size}) 与 " +
                "FOOTPRINT_BY_NAME_INDEX 数量(${SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX.size}) 不一致——" +
                "新增建筑必须同时添加名称与占地尺寸",
            SpriteAtlasDef.BUILDING_NAMES.size,
            SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX.size
        )
    }

    @Test
    fun `BuildingFeatureRegistry 占地与 FOOTPRINT_BY_NAME_INDEX 逐项一致`() {
        // 索引精灵包围盒（BuildingSpatialIndex）按注册表取占地、
        // 渲染器按 FOOTPRINT_BY_NAME_INDEX 取占地，两表不一致会使精灵命中区整体偏移
        //（症状：部分区域点击无效）。registerDefaults 在测试环境不执行。
        BuildingFeatureRegistry.registerDefaults()

        val features = BuildingFeatureRegistry.all
        assertEquals(
            "注册表建筑数(${features.size}) 与图集名称数(${SpriteAtlasDef.BUILDING_NAMES.size}) 不一致——" +
                "新增建筑必须同时注册 BuildingFeature 与图集名称",
            SpriteAtlasDef.BUILDING_NAMES.size, features.size
        )

        for (feature: BuildingFeature in features) {
            // 精灵名经 effectiveSpriteName 解析（显示名可带分级前缀，图集精灵名保持历史名称）
            val nameIdx = SpriteAtlasDef.BUILDING_NAME_INDEX[feature.effectiveSpriteName()]
                ?: throw AssertionError("建筑 '${feature.displayName}' 未在图集 BUILDING_NAMES 中注册")
            val (fpW, fpH) = SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX[nameIdx]
            assertEquals(
                "'${feature.displayName}' gridWidth=${feature.gridWidth} ≠ 图集占地宽=$fpW——" +
                    "修改注册表占地必须同步 SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX（并重新生成 footprint_table.h）",
                feature.gridWidth, fpW
            )
            assertEquals(
                "'${feature.displayName}' gridHeight=${feature.gridHeight} ≠ 图集占地高=$fpH——" +
                    "同步维护点同上",
                feature.gridHeight, fpH
            )
        }
    }

    /**
     * 解析 footprint_table.h 的 FP_W[]/FP_H[] 整型字面量。
     * 数组是纯数字字面量单行格式，正则提取可靠。
     * 被测文件位于 build/generated/sprite/（codegen 产物）。
     */
    private fun parseFootprintArrays(): Pair<List<Int>, List<Int>> {
        val headerFile = File("build/generated/sprite/footprint_table.h")
        assertTrue(
            "footprint_table.h 不存在：${headerFile.absolutePath}——请运行 ./gradlew generateFootprintHeader",
            headerFile.exists()
        )
        val source = headerFile.readText()

        fun extractArray(name: String): List<Int> {
            val regex = Regex("""$name\[\]\s*=\s*\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(source)
                ?: throw AssertionError("footprint_table.h 中未找到 $name[] 数组")
            return match.groupValues[1]
                .split(",")
                .map { it.trim().toInt() }
        }

        return Pair(extractArray("FP_W"), extractArray("FP_H"))
    }
}
