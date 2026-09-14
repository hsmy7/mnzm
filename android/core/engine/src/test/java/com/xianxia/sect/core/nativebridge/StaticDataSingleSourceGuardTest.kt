package com.xianxia.sect.core.nativebridge

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * StaticDataSingleSourceGuardTest — 静态数据单一源守卫。
 *
 * 守护目标：`scripts/data/`（中性源，唯一权威）下各 `*_db_sample.json` 与
 * 生成器产出的测试快照（android/core/engine/src/test/resources/templates 下同名文件）
 * **逐字节一致**——任一侧被手改/漂移即失败，指引重跑生成器。
 *
 * 单一源链条（codegen 权威）：
 *   scripts/data 下 `*_db_sample.json`（权威）→ gen-*.mjs 读中性源 → C++ 表 + 测试快照
 *   Kotlin Registry ← 各 RegistryGuardTest 全量比对兜底（改数据须先改中性源）
 *
 * 数据类覆盖：equipment / herb / trait / recipe / beast_material / manual（6 类）。
 */
class StaticDataSingleSourceGuardTest {

    /** 从测试工作目录向上定位仓库根（含 scripts/data 的最近父目录）。 */
    private fun repoRoot(): File? {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            if (File(dir, "scripts/data/equipment_db_sample.json").exists()) return dir
            dir = dir.parentFile
        }
        return null
    }

    private val classes = listOf(
        "equipment_db", "herb_db", "trait_db", "recipe_db", "beast_material_db", "manual_db"
    )

    @Test
    fun `neutral sources are byte-identical to generated test snapshots`() {
        val root = repoRoot()
        assumeTrue("仓库根定位失败（工作目录: ${System.getProperty("user.dir")}）", root != null)
        root ?: return

        for (name in classes) {
            val neutral = File(root, "scripts/data/$name" + "_sample.json")
            val snapshot = javaClass.getResourceAsStream("/templates/$name" + "_sample.json")
            assumeTrue("测试快照缺失（先运行生成器）: $name", snapshot != null)
            assertNotNull("中性源缺失: $neutral", neutral.takeIf { it.exists() })
            snapshot ?: continue

            val neutralBytes = neutral.readBytes()
            val snapshotBytes = snapshot.readBytes()
            assertArrayEquals(
                "中性源与生成快照不一致: $name（修改数据须改 scripts/data/$name" +
                    "_sample.json 后重跑对应生成器）",
                neutralBytes,
                snapshotBytes
            )
        }
    }
}
