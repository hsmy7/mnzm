package com.xianxia.sect.ui.game

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.gacha.GachaPoolSpec
import com.xianxia.sect.core.engine.domain.gacha.GachaPullRow
import com.xianxia.sect.core.model.CharacterTemplateDb
import com.xianxia.sect.core.model.GachaHistoryEntry
import com.xianxia.sect.core.registry.BeastMaterialDatabase
import com.xianxia.sect.core.registry.HerbDatabase
import java.util.Locale

/**
 * 寻访 UI 的渲染模型（G11 D-8）——两张账本 + 出货行 → 格子状态的**纯函数**层。
 *
 * Composable 只消费这里的产物做展示，取键、判色、判「未解锁 / 满星」的口径全部集中在
 * 本文件，于是 6 格图鉴语义与 Q31 色表口径能被单测钉死（`GachaRenderModelTest`），
 * 不必只靠真机看。
 */

/** 结果页一格的身份：角色碎片（保底格本质也是角色碎片）或物品 */
enum class GachaCellKind {
    CHARACTER,
    ITEM,
}

/** 物品格图标族——沿用本仓既有 sprite 解析入口的入参口径，不另起第二套图名拼接 */
enum class GachaItemSpriteFamily {
    HERB,
    SEED,
    MATERIAL,
    NONE,
}

/** 结果页一格的可渲染模型 */
sealed interface GachaRewardCellModel {
    /** 格内显示名（角色名 / 物品名）；模板查不到时为「未知」占位 */
    val displayName: String
    val quantity: Int
    val colorHex: String
    val isPity: Boolean

    /** 出货行没对上任何模板表（防御位：UI 显式提示，不兜底成别的角色或别的物品） */
    val unresolved: Boolean

    /** 角色碎片格：头像位读 512 档 `avatarKey`（禁读 `portraitKey`，档位口径见 [GachaPullRow]） */
    data class Character(
        override val displayName: String,
        val spriteKey: String,
        val rootCount: Int,
        override val quantity: Int,
        override val colorHex: String,
        override val isPity: Boolean,
        override val unresolved: Boolean,
    ) : GachaRewardCellModel

    /** 物品格：色走品阶表 */
    data class Item(
        override val displayName: String,
        val spriteFamily: GachaItemSpriteFamily,
        val spriteName: String,
        val rarity: Int,
        override val quantity: Int,
        override val colorHex: String,
        override val isPity: Boolean,
    ) : GachaRewardCellModel {
        override val unresolved: Boolean
            get() = spriteFamily == GachaItemSpriteFamily.NONE
    }
}

/** 图鉴一格的可渲染模型（六格 = [CharacterTemplateDb.ALL]，顺序与模板表一致） */
data class GachaCodexCellModel(
    val templateId: String,
    val name: String,
    val portraitKey: String,
    val unlocked: Boolean,
    val star: Int,
    val fragmentsToNextStar: Int,
    val rootCount: Int,
) {
    /** 满星（5 星）后碎片继续累加、不再折算，故不再展示「下一星 x/100」 */
    val isMaxStar: Boolean
        get() = star >= GameConfig.Gacha.MAX_STAR

    /** 角色徽章色与弟子卡/长老槽灵根徽章同表（Q31 灵根数色） */
    val colorHex: String
        get() = GameConfig.Gacha.spiritRootCountColor(rootCount)
}

object GachaRenderModel {

    /** 模板/物品表都查不到时的显示名（不猜、不兜底到开局模板） */
    const val UNKNOWN_NAME = "未知"

    /**
     * 出货行 → 结果页格子。
     *
     * 🔴 **返回序 = 抽取序**（[GachaPullRow] 的数组下标即格序，十连第 10 格在末位）；
     * `gachaHistory` 环是「新在前」的另一个口径，两者不得混用（G09 回退臂就踩过）。
     */
    fun resultCells(rows: List<GachaPullRow>): List<GachaRewardCellModel> = rows.map { row ->
        when (row.category) {
            CATEGORY_CHARACTER, CATEGORY_PITY -> characterCell(row)
            else -> itemCell(row)
        }
    }

    /**
     * 两张账本 → 图鉴六格。
     *
     * 星级账本是**稀疏**的：0 星不落键，所以「未解锁」判据只能是 `tid !in starMap`，
     * 不能用 `starMap[tid] == 0`（那个键根本不存在），也不能用碎片数（有进度但未足一星
     * 的模板有碎片、无星级）。
     */
    fun codexCells(
        starMap: Map<String, Int>,
        fragmentCounts: Map<String, Int>,
    ): List<GachaCodexCellModel> = CharacterTemplateDb.ALL.map { template ->
        val star = starMap[template.id] ?: 0
        GachaCodexCellModel(
            templateId = template.id,
            name = template.name,
            portraitKey = template.portraitKey,
            unlocked = starMap.containsKey(template.id),
            star = star,
            fragmentsToNextStar = fragmentCounts[template.id] ?: 0,
            rootCount = template.spiritRoots.size,
        )
    }

    /**
     * 池规格 → 公示读面（类别权重表带玩家可读的类别名）。
     *
     * 概率、阈值、价格全部来自 [GachaPoolSpec]（`db.gachaPools` 单源），UI 侧零字面量；
     * 只有「类别怎么称呼」是本文件里的文案表（UI 归属，不是数值真源）。
     */
    fun poolReadModel(spec: GachaPoolSpec): GachaPoolReadModel = GachaPoolReadModel(
        categoryWeights = spec.categories.map { it.kind to it.weightPct },
        rarityWeights = spec.itemRarityWeights.map { it.rarity to it.weightPct },
    )

    /** 类别内部名 → 公示页显示名（未知类别原样显示，不折叠成「其他」以免掩盖配置漂移） */
    fun categoryLabel(kind: String): String = CATEGORY_LABELS[kind] ?: kind

    /**
     * 抽卡前后的两张星级账本 → 本轮跨星清单（升星层的队列）。
     *
     * 只认「有变化」的键：新解锁是 `无键 → 1`，连升多星只出一条（前后差值即跳变幅度）。
     * 顺序按模板表固定，不依赖 map 迭代序——十连一次解锁多名时逐条展示的次序要可复现。
     */
    fun starJumps(
        before: Map<String, Int>,
        after: Map<String, Int>,
    ): List<GachaStarUpModel> = CharacterTemplateDb.ALL.mapNotNull { template ->
        val from = before[template.id] ?: 0
        val to = after[template.id] ?: 0
        if (from == to) null else GachaStarUpModel(template.name, from, to)
    }

    /**
     * 历史环 → 历史页行。
     *
     * 环序即展示序（下标 0 最新，Q40）；🔴 这条序与结果页的**抽取序**是两个口径，
     * 谁也不许拿对方当自己的输入（G09 回退臂就因混用把十连十格整体倒置）。
     * 名称与配色复用出货格同一条派生链，不另立读面。
     */
    fun historyRows(history: List<GachaHistoryEntry>): List<GachaHistoryRow> =
        history.map { entry ->
            val cell = resultCells(listOf(rowFromHistory(entry))).first()
            GachaHistoryRow(
                monthLabel = monthLabel(entry.gameMonthIndex),
                displayName = cell.displayName,
                quantity = entry.count,
                colorHex = cell.colorHex,
            )
        }

    /** 历史条目 → 出货行形状（两处的字段口径一致，UI 只维护一条派生链） */
    private fun rowFromHistory(entry: GachaHistoryEntry): GachaPullRow = GachaPullRow(
        category = entry.category,
        templateId = entry.templateId,
        itemId = entry.itemId,
        rarity = entry.rarity,
        count = entry.count,
        isPity = entry.isPity,
    )

    /**
     * 绝对月序（`gameYear * 12 + gameMonth`，月为 1..12）→ 玩家可读年月。
     *
     * 反解先减一再取商余：`gameMonth` 从 1 起，直接 `index % 12` 会把 12 月读成次年 0 月。
     */
    private fun monthLabel(monthIndex: Int): String {
        val shifted = monthIndex - 1
        return "第${shifted / MONTHS_PER_YEAR}年${shifted % MONTHS_PER_YEAR + 1}月"
    }

    // ── 单格构造 ────────────────────────────────────────────────────

    /** 角色碎片格：色 = 灵根数色（Q31），头像 = 512 档 avatarKey */
    private fun characterCell(row: GachaPullRow): GachaRewardCellModel.Character {
        val template = CharacterTemplateDb.byId(row.templateId)
        return GachaRewardCellModel.Character(
            displayName = template?.name ?: UNKNOWN_NAME,
            spriteKey = template?.avatarKey ?: "",
            rootCount = template?.spiritRoots?.size ?: 0,
            quantity = row.count,
            colorHex = GameConfig.Gacha.spiritRootCountColor(template?.spiritRoots?.size ?: 0),
            isPity = row.isPity,
            unresolved = template == null,
        )
    }

    /** 物品格：色 = 品阶色（Q31），图标族按命中的模板表判定 */
    private fun itemCell(row: GachaPullRow): GachaRewardCellModel.Item {
        val resolved = resolveItem(row.itemId)
        return GachaRewardCellModel.Item(
            displayName = resolved?.first ?: UNKNOWN_NAME,
            spriteFamily = resolved?.second ?: GachaItemSpriteFamily.NONE,
            spriteName = resolved?.first ?: "",
            rarity = row.rarity,
            quantity = row.count,
            colorHex = GameConfig.Gacha.rarityColor(row.rarity),
            isPity = row.isPity,
        )
    }

    /**
     * 物品模板 id → (显示名, 图标族)。
     *
     * 出货行只带 id（D-10：DTO 携带资源键就是第二真源），故按三张模板表反查；
     * 查不到不猜：返回 null ⇒ 格子进 `unresolved` 分支。
     */
    private fun resolveItem(itemId: String): Pair<String, GachaItemSpriteFamily>? {
        if (itemId.isEmpty()) return null
        HerbDatabase.getHerbById(itemId)?.let { return it.name to GachaItemSpriteFamily.HERB }
        HerbDatabase.getSeedById(itemId)?.let { return it.name to GachaItemSpriteFamily.SEED }
        return BeastMaterialDatabase.getMaterialById(itemId)?.let {
            it.name to GachaItemSpriteFamily.MATERIAL
        }
    }

    private const val CATEGORY_CHARACTER = "character"
    private const val CATEGORY_PITY = "pity"

    /** 一年月数（与 C++ `gacha_tx.h::absoluteMonth` 与回退臂同式） */
    private const val MONTHS_PER_YEAR = 12

    /** 卡池类别的玩家可读称呼（与 `db.gachaPools[].categories[].kind` 取值域对应） */
    private val CATEGORY_LABELS = mapOf(
        "character_single" to "单灵根弟子",
        "character_double" to "双灵根弟子",
        "herb" to "灵草",
        "seed" to "灵种",
        "beast_material" to "妖兽材料",
    )
}

/** 寻访历史页的一行（Q40 环缓冲的下标 0 = 最新） */
data class GachaHistoryRow(
    val monthLabel: String,
    val displayName: String,
    val quantity: Int,
    val colorHex: String,
)

/**
 * 概率公示的读面输入（把池规格压成 UI 友好的两张表，避免 Composable 里做映射）。
 *
 * @property categoryWeights `类别内部名 → 权重百分比`
 * @property rarityWeights `品阶 → 权重百分比`
 */
data class GachaPoolReadModel(
    val categoryWeights: List<Pair<String, Int>>,
    val rarityWeights: List<Pair<Int, Int>>,
)

/**
 * 本轮跨星的升星提示（Q30 全屏升星层的一条）。
 *
 * 加成数值走 G09 拍板的**口径 A**：1 星是基线 ×1.00，每多一星战斗 +8%、修炼 +5%
 * （系数单源 [GameConfig.Gacha.STAR_BATTLE_PCT_PER_STAR] /
 * [GameConfig.Gacha.STAR_CULT_PCT_PER_STAR]，与 C++ `star_zone.h` 同式）。
 */
data class GachaStarUpModel(
    val name: String,
    val starBefore: Int,
    val starAfter: Int,
) {
    val battleBonusText: String
        get() = bonusText(GameConfig.Gacha.STAR_BATTLE_PCT_PER_STAR, BATTLE_LABEL)

    val cultivationBonusText: String
        get() = bonusText(GameConfig.Gacha.STAR_CULT_PCT_PER_STAR, CULTIVATION_LABEL)

    private fun bonusText(pctPerStar: Double, label: String): String {
        val multiplier = 1.0 + (starAfter - STAR_ZONE_BASELINE).coerceAtLeast(0) * pctPerStar
        return "$label ×" + String.format(Locale.US, BONUS_FORMAT, multiplier)
    }

    private companion object {
        /** 口径 A 的基线星级：1 星不加成 */
        const val STAR_ZONE_BASELINE = 1
        const val BATTLE_LABEL = "战斗威力"
        const val CULTIVATION_LABEL = "修炼效率"
        const val BONUS_FORMAT = "%.2f"
    }
}
