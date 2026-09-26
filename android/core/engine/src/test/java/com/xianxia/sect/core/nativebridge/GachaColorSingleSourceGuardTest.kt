package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.SpiritRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GachaColorSingleSourceGuardTest — Q31 色表单一源的跨端守卫（G11，源码扫描 + 行为面）。
 *
 * ## 为什么必须扫源码而不是只测一个函数
 * 本仓「品阶色 / 灵根数色」一共散落着 8 张表（清单见
 * `docs/design/gacha-batches/TASKBOOK-G11.md` §2.2），而 Q31 只认一张。产品 §4.4 要求
 * 「角色碎片框 + 弟子卡/列表灵根徽章同一套」，于是本守卫钉五件事：
 * 1. **Q31 两张表的逐档值**（与任务书 §1 验收④ 的字面量一一对应）；
 * 2. **灵根数色四份副本同值**：Kotlin `SpiritRoot.countColor`（委托 Q31）+ C++ 三份同口径
 *    副本（`exploration_tx.h` / `year_settlement.h` / `sect_defense_battle.h`——它们写进
 *    持久化的 `discipleSpiritRootColor`，被灵矿/巅峰/世界地图的槽位边框消费）。
 *    只改 Kotlin 会让同一屏出现两套颜色，任务书 D-5 漏了这一面，本守卫补上；
 * 3. **寻访域零引用旧品阶色表**（`getRarityColor` / `getQualityColor` / `Rarity` 色 /
 *    `XianxiaColorScheme` / `UnifiedItemCard`——后者把品阶色只涂背景、边框恒灰，与 Q31
 *    「流光与底色同品阶色」直接冲突）；
 * 4. **`countColor` 的逐档行为值**（消费面拿到的就是 Q31）；
 * 5. **全仓生产面零旧品阶色字面量 + 品阶/丹药品质色委托 Q31**（G12 色板对齐）：
 *    `theme/Color.kt` 六常量、`GameColors.getRarityColor`、`ItemCard.getRarityColor`、
 *    丹药品质三档色与 `GameConfig.Rarity.CONFIGS[].color` 全部解析自 Q31——
 *    仓储、商人、详情、奖励弹窗与寻访同色。
 *
 * 故意不覆盖的面：同一批十六进制字面量在**境界色 / 灵根元素色**维度的合法用途
 * （`#95A5A6` / `#3498DB` / `#E74C3C` 在 `Realm*` / `SpiritRoot*` 上与品阶无关，
 * Q31 不覆盖那两个维度）——对丹药品质色的禁令因此按「文件 + 字面量」点扫
 * `ItemCard.kt`，不做全仓清除。
 *
 * ## 判别力自证（把实现改回旧口径 ⇒ 哪条红）
 * | 构造反例（只改一处） | 变红的用例 |
 * |---|---|
 * | `SpiritRoot.countColor` 改回 `when { 1 -> "#E74C3C" … }` | `四份灵根数色副本同值` + `countColor 逐档值` |
 * | 只改 Kotlin、把 `exploration_tx.h` 留在旧值 | `四份灵根数色副本同值`（缺值清单点名该文件） |
 * | `GachaRewardCell` 改用 `getRarityColor(...)` | `寻访域零引用旧品阶色表` |
 * | 寻访框改成复用 `UnifiedItemCard` | `寻访域零引用旧品阶色表` |
 * | 把 `GameConfig.Gacha.RARITY_COLORS[6]` 改成粉红 | `Q31 全表逐档值` |
 * | `Rarity.CONFIGS[6].color` 改回粉红字面量 / 生产文件出现旧表十六进制 | `全仓生产面零旧品阶色字面量` |
 * | 丹药品质色改回自写 `Color(0xFF95A5A6/3498DB/E74C3C)` | `品阶色与丹药品质色全部委托 Q31` |
 */
class GachaColorSingleSourceGuardTest {

    @Test
    fun `Q31 全表逐档值 - 六金五红四紫三蓝二绿一灰与单金双红三紫四蓝五灰`() {
        val rarityExpected = mapOf(
            1 to "#b8b8b8", 2 to "#4caf50", 3 to "#2196f3",
            4 to "#9c27b0", 5 to "#f44336", 6 to "#ffd700",
        )
        val rootExpected = mapOf(
            1 to "#ffd700", 2 to "#f44336", 3 to "#9c27b0",
            4 to "#2196f3", 5 to "#b8b8b8",
        )

        assertEquals("物品品阶色表（Q31）逐档等值", rarityExpected, GameConfig.Gacha.RARITY_COLORS)
        assertEquals("灵根数色表（Q31）逐档等值", rootExpected, GameConfig.Gacha.SPIRIT_ROOT_COUNT_COLORS)
        rarityExpected.forEach { (rarity, hex) ->
            assertEquals("rarityColor($rarity) 必须等于表值", hex, GameConfig.Gacha.rarityColor(rarity))
        }
        rootExpected.forEach { (roots, hex) ->
            assertEquals("spiritRootCountColor($roots) 必须等于表值", hex, GameConfig.Gacha.spiritRootCountColor(roots))
        }
    }

    @Test
    fun `四份灵根数色副本同值 - Kotlin 委托加三份 C++ 头`() {
        val kotlinSource = readSource(DISCIPLE_SOURCE)
        assertTrue(
            "`SpiritRoot.countColor` 必须委托 GameConfig.Gacha.spiritRootCountColor（唯一真源）。" +
                "落点：android/$DISCIPLE_SOURCE",
            kotlinSource.contains(SPIRIT_ROOT_DELEGATION),
        )

        val problems = mutableListOf<String>()
        CPP_COLOR_SOURCES.forEach { relativePath ->
            val text = readSource(relativePath)
            GameConfig.Gacha.SPIRIT_ROOT_COUNT_COLORS.values.forEach { hex ->
                if (!text.contains("\"$hex\"")) problems += "$relativePath 缺 Q31 值 $hex"
            }
            LEGACY_ROOT_COLORS.forEach { hex ->
                if (text.contains(hex, ignoreCase = true)) problems += "$relativePath 仍留旧值 $hex"
            }
        }
        assertTrue(
            "C++ 侧三份灵根数色副本必须与 Q31 同值：它们写进持久化的 " +
                "discipleSpiritRootColor，被灵矿/巅峰/世界地图的槽位边框消费；" +
                "只改 Kotlin 会让同一屏出现两套颜色。\n" +
                problems.joinToString("\n") +
                "\n落点：android/app/src/main/cpp/gamecore/include/gamecore/system/" +
                "{exploration_tx,year_settlement,sect_defense_battle}.h" +
                "（改后同步 test/exploration_tx_test.cpp 的驻守色断言）。",
            problems.isEmpty(),
        )
    }

    @Test
    fun `寻访域零引用旧品阶色表与 UnifiedItemCard`() {
        val files = gachaDomainFiles()
        assertTrue(
            "寻访域源文件清单为空 ⇒ 本守卫的扫描判据在空转（新增/改名寻访文件时同步这里的根目录清单）",
            files.isNotEmpty(),
        )

        val hits = mutableListOf<String>()
        files.forEach { file ->
            file.useLines { lines ->
                lines.forEachIndexed { index, line ->
                    if (line.isCodeLine() && FORBIDDEN_COLOR_SOURCES.containsMatchIn(line)) {
                        hits += "${file.relativeTo(androidRoot())} 第 ${index + 1} 行 | ${line.trim()}"
                    }
                }
            }
        }
        assertTrue(
            "寻访域（名字以 Gacha 开头的主源文件）不得引用旧品阶色表，也不得复用 " +
                "UnifiedItemCard 做奖励框：旧表六阶是粉红，且 UnifiedItemCard 的品阶色" +
                "只进背景、边框恒为 GameColors.Border，与 Q31「流光与底色同品阶色」冲突。\n" +
                hits.joinToString("\n") +
                "\n落点：配色一律走 GameConfig.Gacha + GachaColors，奖励框用 GachaRewardCell。",
            hits.isEmpty(),
        )
    }

    @Test
    fun `countColor 逐档值就是 Q31 - 消费点拿不到旧口径`() {
        assertEquals("单灵根 = 金", "#ffd700", SpiritRoot(type = "metal").countColor)
        assertEquals("双灵根 = 红", "#f44336", SpiritRoot(type = "metal,wood").countColor)
        assertEquals("三灵根 = 紫", "#9c27b0", SpiritRoot(type = "metal,wood,fire").countColor)
        assertEquals("四灵根 = 蓝", "#2196f3", SpiritRoot(type = "metal,wood,fire,earth").countColor)
        assertEquals("五灵根 = 灰", "#b8b8b8", SpiritRoot(type = "metal,wood,fire,earth,water").countColor)
        assertEquals(
            "超出值域按五档灰兜底（与 GameConfig.Gacha.spiritRootCountColor 的兜底同口径）",
            "#b8b8b8",
            SpiritRoot(type = "a,b,c,d,e,f").countColor,
        )
    }

    @Test
    fun `全仓生产面零旧品阶色字面量 - 定义点收口后不得回流`() {
        val hits = mutableListOf<String>()
        productionSources().forEach { file ->
            file.useLines { lines ->
                lines.forEachIndexed { index, line ->
                    val normalized = line.lowercase()
                    if (line.isCodeLine() && LEGACY_RARITY_HEXES.any { normalized.contains(it) }) {
                        hits += "${file.relativeTo(androidRoot())} 第 ${index + 1} 行 | ${line.trim()}"
                    }
                }
            }
        }
        assertTrue(
            "生产面不得再出现 Q31 之前的物品品阶色/其文字变体字面量（六阶粉红一代目已收口，" +
                "品阶色一律解析自 GameConfig.Gacha.RARITY_COLORS）。\n" +
                hits.joinToString("\n") +
                "\n落点：配色一律走 GameConfig.Gacha + GachaColors；境界色/灵根元素色的" +
                "#95A5A6/#3498DB/#E74C3C 是另一维度，不在本禁令内。",
            hits.isEmpty(),
        )
    }

    @Test
    fun `品阶色与丹药品质色全部委托 Q31 - 消费点拿不到旧口径`() {
        (1..6).forEach { rarity ->
            assertEquals(
                "Rarity.getColor($rarity) 必须等于 Q31 同档值（仓储/商人/详情与寻访同色）",
                GameConfig.Gacha.rarityColor(rarity),
                GameConfig.Rarity.getColor(rarity),
            )
        }

        val itemCard = readSource(ITEM_CARD_SOURCE)
        assertTrue(
            "ItemCard 的品阶取色必须委托 Q31 单源（GachaColors.rarityColor）。落点：android/$ITEM_CARD_SOURCE",
            itemCard.contains(ITEM_CARD_DELEGATION),
        )
        val staleQualityLiterals = ITEM_CARD_STALE_QUALITY_REGEX.findAll(itemCard)
            .map { it.value }
            .toList()
        assertTrue(
            "丹药品质三档色不得自写字面量（下/中/上品 = Q31 一/三/五阶灰蓝红），" +
                "命中：$staleQualityLiterals。\n落点：android/$ITEM_CARD_SOURCE 的 getQualityColor",
            staleQualityLiterals.isEmpty(),
        )
    }

    // ── 夹具 ────────────────────────────────────────────────────────

    /** Gradle 测试工作目录 = `android/core/engine`，故仓库内路径要上溯两级 */
    private fun androidRoot(): File = File("..", "..").canonicalFile

    private fun readSource(relativeToAndroid: String): String =
        File(androidRoot(), relativeToAndroid).readText()

    /** 寻访域源文件：`:core:ui` 与 `:feature:game` 主源里名字以 Gacha 开头的 Kotlin 文件 */
    private fun gachaDomainFiles(): List<File> = listOf(
        File(androidRoot(), "core/ui/src/main"),
        File(androidRoot(), "feature/game/src/main"),
    ).flatMap { root ->
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name.startsWith("Gacha") }
            .toList()
    }

    /**
     * 全仓生产面源文件：六模块 `src/main` 的 Kotlin + gamecore 的 C++（含头）。
     * 字面量禁令是全仓的，任何一处回流都会造成同屏两套品阶色。
     */
    private fun productionSources(): List<File> = androidRoot().walkTopDown()
        .filter { it.isFile }
        .filter { file ->
            val path = file.invariantSeparatorsPath
            !path.contains("/build/") &&
                ((path.contains("/src/main/") && file.extension == "kt") ||
                    (path.contains("/src/main/cpp/") && file.extension in CPP_EXTENSIONS))
        }
        .toList()

    /** 注释行不参与扫描：文档必须能点名旧表，才解释得了这条禁令 */
    private fun String.isCodeLine(): Boolean {
        val trimmed = trim()
        return !trimmed.startsWith("//") && !trimmed.startsWith("*") && !trimmed.startsWith("/*")
    }

    private companion object {
        const val DISCIPLE_SOURCE = "core/domain/src/main/java/com/xianxia/sect/core/model/Disciple.kt"
        const val SPIRIT_ROOT_DELEGATION = "spiritRootCountColor(types.size)"

        /** 三份 C++ 副本：头文件里各写一份字面量，没有共享表可改 ⇒ 只能逐份钉 */
        val CPP_COLOR_SOURCES = listOf(
            "app/src/main/cpp/gamecore/include/gamecore/system/exploration_tx.h",
            "app/src/main/cpp/gamecore/include/gamecore/system/year_settlement.h",
            "app/src/main/cpp/gamecore/include/gamecore/system/sect_defense_battle.h",
        )

        /** Q31 之前的灵根数色五值（C++ 副本里出现即判红） */
        val LEGACY_ROOT_COLORS = listOf("#E74C3C", "#F39C12", "#9B59B6", "#27AE60", "#95A5A6")

        /** 旧色表与旧框的引用面（只扫寻访域自己的文件） */
        val FORBIDDEN_COLOR_SOURCES = Regex(
            "getRarityColor|getQualityColor|getSpiritRootCountColor|XianxiaColorScheme|" +
                "Rarity\\.CONFIGS|Rarity\\.getColor|UnifiedItemCard"
        )

        const val ITEM_CARD_SOURCE = "core/ui/src/main/java/com/xianxia/sect/ui/components/ItemCard.kt"
        const val ITEM_CARD_DELEGATION = "GachaColors.rarityColor"

        /** 丹药品质三档的旧自写字面量（下品灰/中品蓝/上品红的一代目），出现在 ItemCard 即判红 */
        val ITEM_CARD_STALE_QUALITY_REGEX = Regex(
            "Color\\(0xFF(95A5A6|3498DB|E74C3C)\\)",
            RegexOption.IGNORE_CASE,
        )

        /** Q31 之前的物品品阶色六值 + 其文字变体（六常量已随收口删除，出现即回流） */
        val LEGACY_RARITY_HEXES = listOf(
            "afcb8a", "9fc2ee", "c0a2dd", "e7c67d", "e3a0a0",
            "5b8c2a", "3b7dd8", "7b4faa", "c8960c", "cc4444",
        )

        /** 全仓扫描时的 C++ 源扩展名 */
        val CPP_EXTENSIONS = setOf("h", "hpp", "cpp", "cc")
    }
}
