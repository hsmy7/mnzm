package com.xianxia.sect

import com.xianxia.sect.core.render.SpriteAtlasDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * C++/Kotlin 足迹尺寸表同步守卫测试（2026-08-01 新增）。
 *
 * `NativeBridge.cpp` 的 FP_W[]/FP_H[]（19 项）与 `SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX`
 * 是双份手写魔法表——新增建筑或调整索引顺序时一侧遗忘即导致建筑地砖错位/回退 2×2，
 * 且只在真机 Vulkan 路径暴露（软件路径读 Kotlin 表，两路径显示还会不一致）。
 * 本测试解析 C++ 源文件与 Kotlin 表逐项比对，双向防漂移（PR CI 常驻）。
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

    /**
     * 解析 NativeBridge.cpp 的 FP_W[]/FP_H[] 整型字面量。
     * 数组是纯数字字面量单行格式，正则提取可靠。
     */
    private fun parseFootprintArrays(): Pair<List<Int>, List<Int>> {
        val cppFile = File("src/main/cpp/NativeBridge.cpp")
        assertTrue("NativeBridge.cpp 不存在：${cppFile.absolutePath}", cppFile.exists())
        val source = cppFile.readText()

        fun extractArray(name: String): List<Int> {
            val regex = Regex("""$name\[\]\s*=\s*\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(source)
                ?: throw AssertionError("NativeBridge.cpp 中未找到 $name[] 数组")
            return match.groupValues[1]
                .split(",")
                .map { it.trim().toInt() }
        }

        return Pair(extractArray("FP_W"), extractArray("FP_H"))
    }
}
