package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.engine.domain.gacha.GachaPoolSpec
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.GachaColors
import com.xianxia.sect.ui.game.GachaPoolReadModel
import com.xianxia.sect.ui.game.GachaRenderModel
import com.xianxia.sect.ui.theme.GameColors

/**
 * 寻访概率公示（只读面，G11）。
 *
 * 权重、阈值、碎片数、保底选取方式与价格**全部**读 [GachaPoolSpec]——即产物
 * `android/app/src/main/assets/data/game-data.json` 的 `db.gachaPools`，与 C++ 权威臂
 * 注入的是同一张表；UI 侧零数值字面量、零 JSON 解析（解析在 `GachaPoolConfig`，
 * 由 ViewModel 注入后交给这里）。品阶色点取 Q31 单源 [GachaColors]。
 */
@Composable
fun GachaOddsPanel(
    pool: GachaPoolSpec?,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = ODDS_H_PADDING_DP.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (pool == null) {
            Text(text = POOL_UNAVAILABLE_TEXT, fontSize = 12.sp, color = GameColors.TextPrimary)
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                val readModel = GachaRenderModel.poolReadModel(pool)
                OddsRow(
                    label = SINGLE_PULL_LABEL,
                    valueText = "${GameUtils.formatNumber(pool.pricePerPull)} $STONE_UNIT_TEXT",
                    swatch = null,
                )
                GachaOddsSectionTitle(CATEGORY_SECTION_TITLE)
                readModel.categoryWeights.forEach { (kind, weightPct) ->
                    OddsRow(
                        label = GachaRenderModel.categoryLabel(kind),
                        valueText = categoryValueText(readModel, kind, weightPct),
                        swatch = null,
                    )
                }
                GachaOddsSectionTitle(RARITY_SECTION_TITLE)
                readModel.rarityWeights.forEach { (rarity, weightPct) ->
                    OddsRow(
                        label = "$rarity $RARITY_SUFFIX_TEXT",
                        valueText = percentText(weightPct),
                        swatch = GachaColors.rarityColor(rarity),
                    )
                }
                GachaOddsSectionTitle(PITY_SECTION_TITLE)
                OddsRow(
                    label = "每 ${pool.pity.pullThreshold} 次寻访",
                    valueText = "必得 ${pool.pity.fragmentCount} 片角色碎片",
                    swatch = null,
                )
                OddsRow(
                    label = PITY_PICK_LABEL,
                    valueText = pickModeLabel(pool.pity.pickMode),
                    swatch = null,
                )
            }
        }
        GameButton(text = BACK_TEXT, onClick = onBack)
    }
}

@Composable
private fun GachaOddsSectionTitle(title: String) {
    Text(
        text = title,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = GameColors.GoldDark,
        modifier = Modifier.padding(top = SECTION_GAP_DP.dp, bottom = SECTION_INSET_DP.dp),
    )
    HorizontalDivider(thickness = SECTION_RULE_DP.dp, color = GameColors.Border)
}

/** 一行「名称 —— 数值」，可选左侧品阶色圆点 */
@Composable
private fun OddsRow(label: String, valueText: String, swatch: Color?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = LINE_GAP_DP.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LINE_GAP_DP.dp)
        ) {
            if (swatch != null) {
                Box(
                    modifier = Modifier
                        .size(SWATCH_DP.dp)
                        .background(swatch, CircleShape)
                )
            }
            Text(text = label, fontSize = 11.sp, color = GameColors.TextPrimary)
        }
        Text(text = valueText, fontSize = 11.sp, color = GameColors.TextPrimary)
    }
}

/**
 * 权重百分比口径：两张权重表的和恒为 100（由 `GachaConfigGuardTest` 钉住），
 * 所以公示页可以直接把它当百分比展示，不做二次归一化——归一化会把配置漂移藏起来。
 */
private fun percentText(weightPct: Int): String = "$weightPct%"

/**
 * 类别行的数值文案：物品类附带该类别的品阶上限（配置 `maxRarity` 单源，
 * 与 C++ `clampedRarity` 的截断同源）——玩家据此能看出「为什么出货最高只到几阶」；
 * 角色类出货是碎片（星级制），没有品阶上限可言，只显示权重。
 */
private fun categoryValueText(readModel: GachaPoolReadModel, kind: String, weightPct: Int): String {
    val maxRarity = readModel.maxRarityPerKind[kind] ?: return percentText(weightPct)
    return "${percentText(weightPct)}（最高 $maxRarity 阶）"
}

/** 选取方式的可读称呼（当前配置只有随机；未知值原样显示，便于发现配置漂移） */
private fun pickModeLabel(mode: String): String =
    if (mode == PICK_MODE_RANDOM) "全部角色等概率随机" else mode

private const val ODDS_H_PADDING_DP = 16
private const val SECTION_GAP_DP = 10
private const val SECTION_INSET_DP = 2
private const val SECTION_RULE_DP = 1
private const val LINE_GAP_DP = 4
private const val SWATCH_DP = 8
private const val BACK_TEXT = "返回"
private const val SINGLE_PULL_LABEL = "单次寻访"
private const val STONE_UNIT_TEXT = "灵石"
private const val RARITY_SUFFIX_TEXT = "阶物品"
private const val CATEGORY_SECTION_TITLE = "出货类别权重"
private const val RARITY_SECTION_TITLE = "物品品阶权重"
private const val PITY_SECTION_TITLE = "保底"
private const val PITY_PICK_LABEL = "保底碎片的角色选取"
private const val PICK_MODE_RANDOM = "random"
private const val POOL_UNAVAILABLE_TEXT = "这个去处暂时寻访不了"
