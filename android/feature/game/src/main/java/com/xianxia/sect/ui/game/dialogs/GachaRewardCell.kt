package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.ui.components.GachaColors
import com.xianxia.sect.ui.components.LocalItemSpriteCache
import com.xianxia.sect.ui.components.SpriteImage
import com.xianxia.sect.ui.components.herbSpriteRes
import com.xianxia.sect.ui.components.materialSpriteRes
import com.xianxia.sect.ui.components.seedSpriteRes
import com.xianxia.sect.ui.game.GachaItemSpriteFamily
import com.xianxia.sect.ui.game.GachaRewardCellModel

/**
 * 寻访结果页的**一个奖励框**（Q30 正方形格）。
 *
 * 不复用 `UnifiedItemCard`：它的品阶色只进背景、边框恒为 `GameColors.Border`——
 * 与 Q31「流光与底色同品阶色」直接冲突。图标解析仍走本仓统一入口
 * （`herbSpriteRes` 一类 helper 内部经 `SpriteResRegistry`），
 * 配色走寻访域单源 [GachaColors]。
 *
 * 角色格读 512 档 `avatarKey`（不是 1024 档 `portraitKey`，档位口径见 `GachaPullRow` KDoc）。
 * 保底格「更亮一档」：底色提亮 + 描边加重（产品 §4.4，与历史页「保底」标注同口径）。
 * 点击整个格子只为「防误关」（Q39：点框不弹详情），关闭手势由结果层自己判定。
 */
@Composable
fun GachaRewardCell(
    cell: GachaRewardCellModel,
    shimmer: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val color = GachaColors.parse(cell.colorHex)
    Box(
        modifier = modifier
            .testTag(GACHA_CELL_TAG)
            .clip(gachaCellShape)
            .background(gachaCellFillColor(color, cell.isPity))
            .gachaShimmerBorder(color = color, animated = shimmer, emphasized = cell.isPity)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        CellIcon(cell)

        if (cell.isPity) {
            Text(
                text = PITY_MARK_TEXT,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                color = GachaColors.rarityColor(PITY_MARK_RARITY),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 3.dp, top = 2.dp)
            )
        }

        QuantityBadge(quantity = cell.quantity)
    }
}

/** 框内图标：角色走注册精灵名，物品走既有 sprite helper 解析出的资源 id */
@Composable
private fun CellIcon(cell: GachaRewardCellModel) {
    when (cell) {
        is GachaRewardCellModel.Character -> {
            if (cell.spriteKey.isEmpty()) {
                UnresolvedLabel(cell.displayName)
            } else {
                SpriteImage(
                    name = cell.spriteKey,
                    contentDescription = cell.displayName,
                    modifier = Modifier.fillMaxSize().padding(ICON_PADDING_DP.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }

        is GachaRewardCellModel.Item -> {
            val resId = itemSpriteResId(cell.spriteFamily, cell.spriteName)
            if (resId == null) {
                UnresolvedLabel(cell.displayName)
            } else {
                CachedItemIcon(resId = resId, description = cell.displayName)
            }
        }
    }
}

/** 右下角白字 `×n`（半透明深色底 + 细描边，保证压在彩色底上也读得清） */
@Composable
private fun BoxScope.QuantityBadge(quantity: Int) {
    if (quantity <= 0) return
    Text(
        text = "×${GameUtils.formatNumber(quantity)}",
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 3.dp, bottom = 2.dp)
            .background(QUANTITY_BACKING, CircleShape)
            .border(BADGE_BORDER_WIDTH_DP.dp, BADGE_BORDER_COLOR, CircleShape)
            .padding(horizontal = 2.dp)
    )
}

/** 模板表查不到时的显式提示（不猜图、不兜底成别的角色或别的物品） */
@Composable
private fun UnresolvedLabel(name: String) {
    Text(
        text = name,
        fontSize = 9.sp,
        color = Color.White,
        modifier = Modifier.padding(2.dp)
    )
}

/** 与 `UnifiedItemCard` 同一读图口径：预载位图命中则免解码，未命中走注册资源 */
@Composable
private fun CachedItemIcon(resId: Int, description: String) {
    val cached = LocalItemSpriteCache.current[resId]
    if (cached != null) {
        Image(
            bitmap = cached,
            contentDescription = description,
            modifier = Modifier.fillMaxSize().padding(ICON_PADDING_DP.dp),
            contentScale = ContentScale.Fit,
        )
    } else {
        val painter: Painter = painterResource(id = resId)
        Image(
            painter = painter,
            contentDescription = description,
            modifier = Modifier.fillMaxSize().padding(ICON_PADDING_DP.dp),
            contentScale = ContentScale.Fit,
        )
    }
}

/** 物品图标解析：沿用本仓既有的三族 helper（内部一律走 SpriteResRegistry） */
private fun itemSpriteResId(family: GachaItemSpriteFamily, name: String): Int? = when (family) {
    GachaItemSpriteFamily.HERB -> herbSpriteRes(name)
    GachaItemSpriteFamily.SEED -> seedSpriteRes(name)
    GachaItemSpriteFamily.MATERIAL -> materialSpriteRes(name)
    GachaItemSpriteFamily.NONE -> null
}

/**
 * 网格方格边长：取「按列均分」与「按行均分」的较小值 ⇒ 既铺满可用区又不溢出，
 * 且框一定是正方形（横竖屏、不同窗宽都只依赖可用区，不写死 dp）。
 *
 * 结果页与图鉴共用这一个口径：两处都是「权重分列 + 固定边长方框」，
 * 任何一处自算都会在小屏上把最后一列挤出屏幕。
 */
internal fun gachaSquareCellSize(
    columns: Int,
    rowLines: Int,
    availableWidth: Dp,
    availableHeight: Dp,
    gap: Dp,
): Dp {
    val widthPerColumn = (availableWidth - gap * (columns - 1)) / columns
    val heightPerRow = (availableHeight - gap * (rowLines - 1)) / rowLines
    return minOf(widthPerColumn, heightPerRow)
}

private const val ICON_PADDING_DP = 3
private const val PITY_MARK_TEXT = "保底"

/** 奖励框的测试标识（渲染与交互测试按它数格子；与结果层共用同一个常量，见 GachaResultLayer） */
const val GACHA_CELL_TAG = "gacha_reward_cell"

/** 保底角标用一阶金（与 Q31 六阶金同值），不再另立色 */
private const val PITY_MARK_RARITY = 6
private val QUANTITY_BACKING = Color(0x66000000)

/** 数量角标的细描边（深色低透明，保证白字在金/红一类的亮底上也有轮廓） */
private const val BADGE_BORDER_WIDTH_DP = 0.5
private val BADGE_BORDER_COLOR = Color(0x33000000)
