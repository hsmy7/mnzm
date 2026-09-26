package com.xianxia.sect.ui.game

import androidx.compose.ui.graphics.Color
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.gacha.GachaCategorySpec
import com.xianxia.sect.core.engine.domain.gacha.GachaPoolSpec
import com.xianxia.sect.core.engine.domain.gacha.GachaPitySpec
import com.xianxia.sect.core.engine.domain.gacha.GachaPullRow
import com.xianxia.sect.core.engine.domain.gacha.GachaRarityWeightSpec
import com.xianxia.sect.core.model.CharacterTemplateDb
import com.xianxia.sect.core.model.GachaHistoryEntry
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import com.xianxia.sect.ui.components.GachaColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GachaRenderModelTest — 寻访渲染模型（D-8 纯函数层）的口径守卫（G11，无 JNI、无 Compose）。
 *
 * ## 看护的六条判据
 * 1. **格序 = 抽取序**：`resultCells` 保序，十连第 10 格在末位（历史环的「新在前」是另一个
 *    口径，G09 回退臂把两者混用导致十连整体倒置——这里用逐格可辨的 id 钉死）；
 * 2. **档位口径**：角色格读 512 档 `avatarKey`，绝不读 1024 档 `portraitKey`（G08 移交项）；
 * 3. **Q31 配色分界**：物品格 = 品阶色，角色/保底格 = 灵根数色；
 * 4. **稀疏账本语义**：图鉴「未解锁」判据是「星级账本里没有这个键」，有碎片但未足一星
 *    仍算未解锁（`starMap` 0 星不落键）；
 * 5. **跨星队列**：连升多星只出一条、无变化不出现、顺序按模板表固定；
 * 6. **历史年月**：绝对月序 `year*12+month`（月 1..12）反解不把 12 月读成次年 0 月。
 *
 * ## 判别力自证（把实现改回旧口径 ⇒ 哪条红）
 * | 构造反例（只改一处） | 变红的用例 / 消息片段 |
 * |---|---|
 * | `resultCells` 里改成 `rows.asReversed()` | `结果格序必须等于抽取序` |
 * | 角色格取键改成 `template.portraitKey` | `角色格取键必须是 512 档 avatarKey` |
 * | 角色格配色改用 `rarityColor` | `角色碎片格的色必须是灵根数色` |
 * | 图鉴「未解锁」判据改成 `star == 0` 且加 `fragmentCounts` 兜底 | `有碎片但未足一星仍算未解锁` |
 * | 满星仍展示「下一星 x/100」 | `满星格展示 MAX` |
 * | `starJumps` 按 map 迭代序输出 | `跨星清单顺序按模板表固定` |
 * | 年月反解去掉 `-1`（直接 `index % 12`） | `绝对月序反解成年月` |
 * | `resolveItem` 找不到时兜底成开局模板 | `物品 id 查不到时不兜底` |
 */
class GachaRenderModelTest {

    // ── ① 格序 ─────────────────────────────────────────────────────

    @Test
    fun `结果格序必须等于抽取序 - 十连第 10 格在末位`() {
        val rows = tenCharacterRows()
        val cells = GachaRenderModel.resultCells(rows)

        assertEquals("十连必须出十格", 10, cells.size)
        assertEquals(
            "每格的数量角标取自对应抽取行：出现倒序或错位说明把 gachaHistory 环的「新在前」" +
                "当成了格序——G09 的回退臂就是这么把十连十格整体反着的" +
                "（落点：GachaRenderModel.resultCells）",
            (1..10).toList(),
            cells.map { it.quantity },
        )
        assertEquals(
            "显示名序列必须与抽取序逐格相等",
            rows.map { requireNotNull(CharacterTemplateDb.byId(it.templateId)).name },
            cells.map { it.displayName },
        )
    }

    // ── ② 档位与 ③ 配色 ────────────────────────────────────────────

    @Test
    fun `角色格取键必须是 512 档 avatarKey - 配色必须是灵根数色`() {
        val template = requireNotNull(CharacterTemplateDb.byId(TEMPLATE_ID))
        val cell = GachaRenderModel.resultCells(listOf(characterRow(TEMPLATE_ID, count = 5)))
            .first() as GachaRewardCellModel.Character

        assertEquals(
            "头像位读 512 档 avatarKey（口径钉在 GachaPullRow 的 KDoc：小头像位不读全身像）",
            template.avatarKey,
            cell.spriteKey,
        )
        assertFalse(
            "spriteKey 不得等于 1024 档 portraitKey（结果页格子只有方框尺寸，全身像会糊成一团）",
            template.portraitKey == cell.spriteKey,
        )
        assertEquals(
            "角色碎片格的色必须是灵根数色（Q31：单金双红三紫四蓝五灰），与角色根数同源",
            GameConfig.Gacha.spiritRootCountColor(template.spiritRoots.size),
            cell.colorHex,
        )
        assertEquals("碎片数量原样带上角标", 5, cell.quantity)
        assertEquals("角色格显示名取模板姓名", template.name, cell.displayName)
    }

    @Test
    fun `保底格按角色碎片处理 - 仍走灵根数色并带保底标记`() {
        val cell = GachaRenderModel.resultCells(listOf(pityRow(TEMPLATE_ID)))
            .first() as GachaRewardCellModel.Character
        val template = requireNotNull(CharacterTemplateDb.byId(TEMPLATE_ID))

        assertTrue("保底抽本身即角色碎片，必须带保底标记", cell.isPity)
        assertEquals(
            "保底格的色同样走灵根数色（它不是物品，不用品阶表）",
            GameConfig.Gacha.spiritRootCountColor(template.spiritRoots.size),
            cell.colorHex,
        )
    }

    @Test
    fun `物品格配色走品阶表 - 名字与图标族从模板表反查`() {
        val herb = HerbDatabase.getAllHerbs().first()
        val cell = GachaRenderModel.resultCells(listOf(itemRow(herb.id, rarity = 6)))
            .first() as GachaRewardCellModel.Item

        assertEquals("物品格显示名取灵草表姓名", herb.name, cell.displayName)
        assertEquals("六阶物品必须用 Q31 的金色", GameConfig.Gacha.RARITY_COLORS.getValue(6), cell.colorHex)
        assertEquals("灵草族走 herbSpriteRes 入口", GachaItemSpriteFamily.HERB, cell.spriteFamily)
        assertFalse("命中的模板不算未解析", cell.unresolved)
    }

    @Test
    fun `物品 id 查不到时不兜底 - 显式未解析`() {
        val cell = GachaRenderModel.resultCells(listOf(itemRow(UNKNOWN_ITEM_ID, rarity = 1)))
            .first() as GachaRewardCellModel.Item

        assertTrue("查不到的 id 必须标未解析（禁止兜底到任意一个物品）", cell.unresolved)
        assertEquals("未解析时显示名是显式占位", GachaRenderModel.UNKNOWN_NAME, cell.displayName)
        assertEquals("未解析时图标族为 NONE", GachaItemSpriteFamily.NONE, cell.spriteFamily)
    }

    @Test
    fun `三张物品模板表都能反查到 - 种子与妖兽材料不走灵草分支`() {
        val seed = HerbDatabase.getAllSeeds().first()
        val beast = BeastMaterialDatabase.getAllMaterials().first()

        val seedCell = GachaRenderModel.resultCells(listOf(itemRow(seed.id, rarity = 2)))
            .first() as GachaRewardCellModel.Item
        val beastCell = GachaRenderModel.resultCells(listOf(itemRow(beast.id, rarity = 3)))
            .first() as GachaRewardCellModel.Item

        assertEquals("种子格走 seedSpriteRes 入口", GachaItemSpriteFamily.SEED, seedCell.spriteFamily)
        assertEquals("妖兽材料格走 materialSpriteRes 入口", GachaItemSpriteFamily.MATERIAL, beastCell.spriteFamily)
    }

    // ── ④ 图鉴六格 ─────────────────────────────────────────────────

    @Test
    fun `图鉴格顺序与模板表一致 - 未解锁判据是星级账本没有这个键`() {
        val cells = GachaRenderModel.codexCells(starMap = emptyMap(), fragmentCounts = emptyMap())

        assertEquals("图鉴格数 = 模板表条数", CharacterTemplateDb.ALL.size, cells.size)
        assertEquals(
            "图鉴顺序必须跟 CharacterTemplateDb.ALL 同序（玩家看到的次序要稳定）",
            CharacterTemplateDb.ALL.map { it.id },
            cells.map { it.templateId },
        )
        assertTrue("空账本 ⇒ 全部未解锁", cells.none { it.unlocked })
    }

    @Test
    fun `有碎片但未足一星仍算未解锁 - 稀疏账本里没有 0 星键`() {
        val cells = GachaRenderModel.codexCells(
            starMap = emptyMap(),
            fragmentCounts = mapOf(TEMPLATE_ID to GameConfig.Gacha.FRAGMENTS_PER_STAR - 1),
        )
        val cell = cells.first { it.templateId == TEMPLATE_ID }

        assertFalse(
            "星级账本是稀疏的：0 星不落键。有进度但未足一星只算「未解锁 + 有进度」，" +
                "判据写成 starMap[tid] == 0 永远为假（落点：GachaRenderModel.codexCells）",
            cell.unlocked,
        )
        assertEquals(
            "进度仍要显示出来，玩家才知道差多少",
            GameConfig.Gacha.FRAGMENTS_PER_STAR - 1,
            cell.fragmentsToNextStar,
        )
    }

    @Test
    fun `满星格展示 MAX - 碎片继续累加不折算`() {
        val cells = GachaRenderModel.codexCells(
            starMap = mapOf(TEMPLATE_ID to GameConfig.Gacha.MAX_STAR),
            fragmentCounts = mapOf(TEMPLATE_ID to GameConfig.Gacha.FRAGMENTS_PER_STAR + 7),
        )
        val cell = cells.first { it.templateId == TEMPLATE_ID }

        assertTrue("满星判据来自 GameConfig.Gacha.MAX_STAR", cell.isMaxStar)
        assertEquals("满星后星内进度原样保留（不截断、不折算）", 107, cell.fragmentsToNextStar)
    }

    @Test
    fun `已解锁格给出星级与下一星进度 - 配色与灵根数色同表`() {
        val cells = GachaRenderModel.codexCells(
            starMap = mapOf(TEMPLATE_ID to 2),
            fragmentCounts = mapOf(TEMPLATE_ID to 30),
        )
        val cell = cells.first { it.templateId == TEMPLATE_ID }

        assertTrue(cell.unlocked)
        assertEquals(2, cell.star)
        assertEquals(30, cell.fragmentsToNextStar)
        assertEquals(
            "图鉴角色色与弟子卡/长老槽灵根徽章同一张表（产品 §4.4「同一套」）",
            GameConfig.Gacha.spiritRootCountColor(cell.rootCount),
            cell.colorHex,
        )
        assertEquals("全身立绘读 portraitKey（1024 档，与结果页的 512 档互不替换）", "portrait_" + TEMPLATE_ID, cell.portraitKey)
    }

    @Test
    fun `图鉴收益预览与升星层同一条派生链 - 口径 A 逐星值`() {
        fun cellAt(star: Int) = GachaRenderModel.codexCells(
            starMap = mapOf(TEMPLATE_ID to star),
            fragmentCounts = emptyMap(),
        ).first { it.templateId == TEMPLATE_ID }

        val baseline = cellAt(1)
        assertEquals("1 星是基线：战斗 ×1.00", "战斗威力 ×1.00", baseline.battleBonusText)
        assertEquals("1 星是基线：修炼 ×1.00", "修炼效率 ×1.00", baseline.cultivationBonusText)

        val twoStar = cellAt(2)
        assertEquals("2 星战斗 = 1 + 1 × 0.08", "战斗威力 ×1.08", twoStar.battleBonusText)
        assertEquals("2 星修炼 = 1 + 1 × 0.05", "修炼效率 ×1.05", twoStar.cultivationBonusText)

        val maxStar = cellAt(GameConfig.Gacha.MAX_STAR)
        assertEquals("满星战斗 = 1 + 4 × 0.08（与升星层同式）", "战斗威力 ×1.32", maxStar.battleBonusText)
        assertEquals("满星修炼 = 1 + 4 × 0.05", "修炼效率 ×1.20", maxStar.cultivationBonusText)

        val locked = cellAt(0)
        assertEquals("未解锁格 star=0，倍率按基线计算（面板侧置灰不展示）", "战斗威力 ×1.00", locked.battleBonusText)
    }

    // ── ⑤ 跨星队列 ─────────────────────────────────────────────────

    @Test
    fun `跨星清单连升多星只出一条 - 顺序按模板表固定`() {
        val jumps = GachaRenderModel.starJumps(
            before = mapOf(TEMPLATE_ID to 1, SECOND_TEMPLATE_ID to 3),
            after = mapOf(TEMPLATE_ID to 3, SECOND_TEMPLATE_ID to 3),
        )

        assertEquals("只有一个模板发生变化 ⇒ 只出一条", 1, jumps.size)
        assertEquals("跳变前星级", 1, jumps.first().starBefore)
        assertEquals("连升两星合并成一条", 3, jumps.first().starAfter)
        assertEquals("姓名取自模板表", requireNotNull(CharacterTemplateDb.byId(TEMPLATE_ID)).name, jumps.first().name)
    }

    @Test
    fun `新解锁进入跨星清单 - 零到一也是跳变`() {
        val jumps = GachaRenderModel.starJumps(
            before = mapOf(TEMPLATE_ID to 1),
            after = mapOf(TEMPLATE_ID to 1, SECOND_TEMPLATE_ID to 1),
        )

        assertEquals("新解锁（无键 → 1 星）必须提示", 1, jumps.size)
        assertEquals(0, jumps.first().starBefore)
        assertEquals(1, jumps.first().starAfter)
    }

    @Test
    fun `跨星清单顺序按模板表固定 - 不依赖 map 迭代序`() {
        val jumps = GachaRenderModel.starJumps(
            before = emptyMap(),
            after = mapOf(
                CharacterTemplateDb.ALL.last().id to 1,
                CharacterTemplateDb.ALL.first().id to 1,
            ),
        )

        assertEquals(
            "十连一次解锁多名时逐条展示的次序要可复现：按 CharacterTemplateDb.ALL 顺序，" +
                "而不是 after 这张 map 的迭代序",
            listOf(
                CharacterTemplateDb.ALL.first().name,
                CharacterTemplateDb.ALL.last().name,
            ),
            jumps.map { it.name },
        )
    }

    @Test
    fun `星级乘区文案按口径 A - 一星是基线不加成`() {
        val jump = GachaStarUpModel(name = "周明", starBefore = 0, starAfter = 1)

        assertEquals(
            "1 星是口径 A 的基线（×1.00），提示文案不得写成 +8%",
            "战斗威力 ×1.00",
            jump.battleBonusText,
        )
        assertEquals("修炼效率基线同样 ×1.00", "修炼效率 ×1.00", jump.cultivationBonusText)

        val fiveStar = GachaStarUpModel(name = "周明", starBefore = 1, starAfter = 5)
        assertEquals(
            "5 星 = 1 + 4 × 0.08 = ×1.32（系数单源 GameConfig.Gacha.STAR_BATTLE_PCT_PER_STAR）",
            "战斗威力 ×1.32",
            fiveStar.battleBonusText,
        )
        assertEquals("5 星修炼 = 1 + 4 × 0.05 = ×1.20", "修炼效率 ×1.20", fiveStar.cultivationBonusText)
    }

    // ── ⑥ 公示与历史 ───────────────────────────────────────────────

    @Test
    fun `公示读面逐项来自池配置 - 未知类别原样显示`() {
        val spec = standardPool()
        val readModel = GachaRenderModel.poolReadModel(spec)

        assertEquals("类别条数与池配置一致", spec.categories.size, readModel.categoryWeights.size)
        assertEquals(
            "类别权重逐项等值（UI 不得重新归一化，那会把配置漂移藏起来）",
            spec.categories.map { it.kind to it.weightPct },
            readModel.categoryWeights,
        )
        assertEquals(
            "品阶权重表同样逐项等值",
            spec.itemRarityWeights.map { it.rarity to it.weightPct },
            readModel.rarityWeights,
        )
        assertEquals(
            "物品类别的品阶上限逐项来自池配置（角色类是碎片/星级制，不进上限表）",
            mapOf("herb" to 4),
            readModel.maxRarityPerKind,
        )
        assertEquals("已登记类别有可读名", "单灵根弟子", GachaRenderModel.categoryLabel("character_single"))
        assertEquals(
            "未登记类别原样显示（折叠成「其他」会让新加的 kind 静默消失）",
            "character_triple",
            GachaRenderModel.categoryLabel("character_triple"),
        )
    }

    @Test
    fun `绝对月序反解成年月 - 十二月不得读成次年零月`() {
        assertEquals("第1年1月", monthLabelOf(12 + 1))
        assertEquals("第1年12月", monthLabelOf(12 + 12))
        assertEquals("第2年1月", monthLabelOf(24 + 1))
    }

    @Test
    fun `历史行序与名称复用同一条派生链 - 不改序`() {
        val entries = listOf(
            GachaHistoryEntry(category = "character", templateId = TEMPLATE_ID, count = 2, gameMonthIndex = 13),
            GachaHistoryEntry(category = "item", itemId = HerbDatabase.getAllHerbs().first().id,
                rarity = 4, count = 1, gameMonthIndex = 25),
        )
        val rows = GachaRenderModel.historyRows(entries)

        assertEquals(
            "历史环下标 0 是最新一条：展示序 = 环序，不许再反一次（反了就与抽取序互相污染）",
            requireNotNull(CharacterTemplateDb.byId(TEMPLATE_ID)).name,
            rows.first().displayName,
        )
        assertEquals("第二行是物品名", HerbDatabase.getAllHerbs().first().name, rows[1].displayName)
        assertEquals("第一行年月", "第1年1月", rows.first().monthLabel)
        assertEquals("第二行年月", "第2年1月", rows[1].monthLabel)
        assertEquals(
            "物品行配色用品阶表、角色行用灵根数表（与结果页同一判据）",
            GameConfig.Gacha.RARITY_COLORS.getValue(4),
            rows[1].colorHex,
        )
        assertFalse("非保底抽不带保底标注", rows.first().isPity)
    }

    @Test
    fun `历史行带保底标注 - 保底行 isPity 为真`() {
        val rows = GachaRenderModel.historyRows(
            listOf(
                GachaHistoryEntry(
                    category = "pity",
                    templateId = TEMPLATE_ID,
                    count = GameConfig.Gacha.PITY_FRAGMENT_COUNT,
                    isPity = true,
                    gameMonthIndex = 13,
                )
            )
        )

        assertTrue(
            "保底抽的历史行必须带 isPity 标注（Q40「保底附着标注」），历史页据此挂「保底」角标",
            rows.first().isPity,
        )
        assertEquals(
            "保底行也是角色碎片，配色走灵根数色",
            GameConfig.Gacha.spiritRootCountColor(
                requireNotNull(CharacterTemplateDb.byId(TEMPLATE_ID)).spiritRoots.size
            ),
            rows.first().colorHex,
        )
    }

    // ── ⑦ GachaColors 换算 ─────────────────────────────────────────

    @Test
    fun `Q31 色串换算成 Compose Color - 解析失败回落一阶灰`() {
        assertEquals("六阶金", Color(0xFFFFD700), GachaColors.parse("#ffd700"))
        assertEquals("一阶灰", Color(0xFFB8B8B8), GachaColors.parse("#b8b8b8"))
        assertEquals(
            "坏值按一阶灰处理，与 GameConfig.Gacha.rarityColor 的「未知品阶回落 1 档」同口径",
            Color(0xFFB8B8B8),
            GachaColors.parse("不是色值"),
        )
        assertEquals("品阶接口与字符串表同值", GachaColors.parse("#2196f3"), GachaColors.rarityColor(3))
        assertEquals("灵根数接口与字符串表同值", GachaColors.parse("#f44336"), GachaColors.spiritRootCountColor(2))
    }

    // ── 夹具 ────────────────────────────────────────────────────────

    /** 十次抽取：模板按表序循环，数量 1..10 逐格可辨认自己的位置 */
    private fun tenCharacterRows(): List<GachaPullRow> = (0 until 10).map { index ->
        characterRow(TEMPLATE_IDS[index % TEMPLATE_IDS.size], count = index + 1)
    }

    private fun characterRow(templateId: String, count: Int) = GachaPullRow(
        category = "character",
        templateId = templateId,
        itemId = "",
        rarity = 0,
        count = count,
        isPity = false,
    )

    private fun pityRow(templateId: String) = GachaPullRow(
        category = "pity",
        templateId = templateId,
        itemId = "",
        rarity = 0,
        count = GameConfig.Gacha.PITY_FRAGMENT_COUNT,
        isPity = true,
    )

    private fun itemRow(itemId: String, rarity: Int) = GachaPullRow(
        category = "item",
        templateId = "",
        itemId = itemId,
        rarity = rarity,
        count = 1,
        isPity = false,
    )

    /** 历史行的年月取值走同一条私有链：用真实条目驱动，避免复制公式 */
    private fun monthLabelOf(monthIndex: Int): String = GachaRenderModel.historyRows(
        listOf(GachaHistoryEntry(category = "item", itemId = HERB_ID, rarity = 1, count = 1,
            gameMonthIndex = monthIndex))
    ).first().monthLabel

    private fun standardPool(): GachaPoolSpec = GachaPoolSpec(
        poolId = "standard",
        enabled = true,
        pricePerPull = GameConfig.Gacha.PRICE_PER_PULL,
        categories = listOf(
            GachaCategorySpec("character_single", 11, listOf("zhouming", "suqing"), "", 0),
            GachaCategorySpec("character_double", 10, listOf("linxuetang"), "", 0),
            GachaCategorySpec("herb", 26, emptyList(), "herbs", 4),
        ),
        itemRarityWeights = listOf(
            GachaRarityWeightSpec(4, 12),
            GachaRarityWeightSpec(3, 33),
            GachaRarityWeightSpec(2, 33),
            GachaRarityWeightSpec(1, 22),
        ),
        pity = GachaPitySpec(
            GameConfig.Gacha.PITY_PULL_THRESHOLD,
            GameConfig.Gacha.PITY_FRAGMENT_COUNT,
            "random",
        ),
    )

    private companion object {
        const val TEMPLATE_ID = "zhouming"
        const val SECOND_TEMPLATE_ID = "suqing"
        val TEMPLATE_IDS: List<String> = CharacterTemplateDb.ALL.map { it.id }
        val HERB_ID: String = HerbDatabase.getAllHerbs().first().id
        const val UNKNOWN_ITEM_ID = "no-such-item-at-all"
    }
}
