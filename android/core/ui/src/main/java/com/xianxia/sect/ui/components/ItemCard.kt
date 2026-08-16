package com.xianxia.sect.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.model.PillGrade
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.ui.theme.GameColors

val LocalItemSpriteCache = staticCompositionLocalOf<Map<Int, ImageBitmap>> { emptyMap() }

data class ItemCardData(
    val id: String = "",
    val name: String,
    val description: String = "",
    val rarity: Int,
    val quantity: Int = 1,
    val type: String? = null,
    val stats: Map<String, Int> = emptyMap(),
    val additionalInfo: String? = null,
    val price: Long = 0L,
    val grade: String? = null,
    val isLocked: Boolean = false,
    val isManual: Boolean = false,
    val isPill: Boolean = false,
    val isMaterial: Boolean = false,
    val spiritStoneGrade: SpiritStoneGrade? = null,
    val isBag: Boolean = false,
    val isHerb: Boolean = false,
    val isSeed: Boolean = false,
    val isDisciple: Boolean = false
)

@Composable
fun UnifiedItemCard(
    data: ItemCardData,
    modifier: Modifier = Modifier,
    size: Dp = 60.dp,
    isSelected: Boolean = false,
    selectedBorderColor: Color = Color.White,
    isFollowed: Boolean = false,
    showQuantity: Boolean = true,
    showPrice: Boolean = false,
    craftable: Boolean = true,
    onClick: () -> Unit = {},
    onLongPress: (() -> Unit)? = null,
    overlayButtonText: String? = null,
    onOverlayButtonClick: (() -> Unit)? = null,
    showPlaceholderText: Boolean = true,
    nameFontSize: androidx.compose.ui.unit.TextUnit = 9.sp
) {
    val rarityColor = getRarityColor(data.rarity)
    val spriteRes = itemCardSpriteRes(data)

    Box(
        modifier = modifier.wrapContentSize(Alignment.Center).size(size),
        contentAlignment = Alignment.Center
    ) {
        ItemCardBody(
            data = data,
            rarityColor = rarityColor,
            spriteRes = spriteRes,
            isSelected = isSelected,
            selectedBorderColor = selectedBorderColor,
            isFollowed = isFollowed,
            showQuantity = showQuantity,
            showPlaceholderText = showPlaceholderText,
            nameFontSize = nameFontSize,
            onClick = onClick,
            onLongPress = onLongPress
        )

        if (isSelected && overlayButtonText != null) {
            ItemCardOverlayButton(
                text = overlayButtonText,
                onClick = onOverlayButtonClick
            )
        }

        if (!craftable) {
            ItemCardNotCraftableMask()
        }
    }
}

/** 卡片精灵图解析（UnifiedItemCard 拆分）：按物品类型 → 对应精灵资源 */
private fun itemCardSpriteRes(data: ItemCardData): Int? = when {
    data.spiritStoneGrade != null -> spiritStoneSpriteRes(data.spiritStoneGrade)
    data.isBag -> storageBagSpriteRes(data.rarity)
    data.isManual -> manualSpriteRes(data.rarity)
    data.isPill -> pillSpriteRes(data.rarity)
    data.isMaterial -> materialSpriteRes(data.name)
    // 草药名解析失败（如 spiritHerbs 灵草资源无专属名）时回退丹药图占位，
    // 与 getRewardSprite 的 herb 兜底模式一致，避免显示"敬请期待"
    data.isHerb -> herbSpriteRes(data.name) ?: pillSpriteRes(data.rarity)
    data.isSeed -> seedSpriteRes(data.name)
    data.isDisciple -> SpriteResRegistry.resolve("disciple_portrait")
    else -> equipmentSpriteRes(data.name)
}

/** 卡片主体（UnifiedItemCard 拆分）：边框列 + 精灵区 + 名称区 */
@Composable
@Suppress("LongParameterList") // 拆分聚合：12 个平铺参数均为原公共函数参数的搬移（detekt 对 @Composable 不豁免）
private fun ItemCardBody(
    data: ItemCardData,
    rarityColor: Color,
    spriteRes: Int?,
    isSelected: Boolean,
    selectedBorderColor: Color,
    isFollowed: Boolean,
    showQuantity: Boolean,
    showPlaceholderText: Boolean,
    nameFontSize: androidx.compose.ui.unit.TextUnit,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(6.dp))
            .border(
                width = 2.dp,
                color = when {
                    isSelected -> selectedBorderColor
                    isFollowed -> GameColors.Gold
                    else -> GameColors.Border
                },
                shape = RoundedCornerShape(6.dp)
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            )
    ) {
        ItemCardSpriteArea(
            data = data,
            rarityColor = rarityColor,
            spriteRes = spriteRes,
            showQuantity = showQuantity,
            showPlaceholderText = showPlaceholderText
        )
        ItemCardNameArea(name = data.name, nameFontSize = nameFontSize)
    }
}

/** 精灵区（UnifiedItemCard 拆分）：背景色 + 精灵/占位 + 锁定/品质/数量角标 */
// 拆分残余:函数体略超 60 行(原函数拆分后聚合)
@Suppress("LongMethod")
@Composable
private fun ColumnScope.ItemCardSpriteArea(
    data: ItemCardData,
    rarityColor: Color,
    spriteRes: Int?,
    showQuantity: Boolean,
    showPlaceholderText: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .background(rarityColor),
        contentAlignment = Alignment.Center
    ) {
        if (spriteRes != null) {
            val cachedBitmap = LocalItemSpriteCache.current[spriteRes]
            if (cachedBitmap != null) {
                Image(
                    bitmap = cachedBitmap,
                    contentDescription = data.name,
                    modifier = Modifier.fillMaxSize().padding(3.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                Image(
                    painter = painterResource(id = spriteRes),
                    contentDescription = data.name,
                    modifier = Modifier.fillMaxSize().padding(3.dp),
                    contentScale = ContentScale.Fit
                )
            }
        } else if (showPlaceholderText) {
            Text(
                text = "敬请期待",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(2.dp)
            )
        }

        if (data.isLocked) {
            Text(
                text = "锁定",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = GameColors.Gold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 3.dp, top = 2.dp)
            )
        }

        if (!data.grade.isNullOrEmpty()) {
            Text(
                text = data.grade,
                fontSize = 8.sp,
                color = getQualityColor(data.grade),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 3.dp, bottom = 2.dp)
            )
        }

        if (showQuantity) {
            Text(
                text = GameUtils.formatNumber(data.quantity),
                fontSize = 8.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 3.dp, bottom = 2.dp)
            )
        }
    }
}

/** 名称区（UnifiedItemCard 拆分）：白底黑字单行名称 */
@Composable
private fun ItemCardNameArea(name: String, nameFontSize: androidx.compose.ui.unit.TextUnit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp)
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name,
            fontSize = nameFontSize,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}

/** 覆盖操作按钮（UnifiedItemCard 拆分）：选中态右上角金色按钮 */
@Composable
private fun BoxScope.ItemCardOverlayButton(text: String, onClick: (() -> Unit)?) {
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .offset(x = (-2).dp, y = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(GameColors.Gold)
            .clickableWithSound(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onClick?.invoke() }
            )
            .padding(horizontal = 5.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

/** 不可制作遮罩（UnifiedItemCard 拆分） */
@Composable
private fun ItemCardNotCraftableMask() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "材料不足",
            fontSize = 8.sp,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun EmptyListMessage(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            fontSize = 14.sp,
            color = GameColors.TextSecondary
        )
    }
}

fun getRarityColor(rarity: Int): Color = when (rarity) {
    1 -> GameColors.RarityCommon
    2 -> GameColors.RaritySpirit
    3 -> GameColors.RarityTreasure
    4 -> GameColors.RarityMystic
    5 -> GameColors.RarityEarth
    6 -> GameColors.RarityHeaven
    else -> GameColors.RarityCommon
}

fun getRarityName(rarity: Int): String = when (rarity) {
    1 -> "凡品"
    2 -> "灵品"
    3 -> "宝品"
    4 -> "玄品"
    5 -> "地品"
    6 -> "天品"
    else -> "凡品"
}

/**
 * 根据丹药品质名称返回颜色。
 * 内部委托 [getQualityColor] 的 [PillGrade] 重载保持一致性。
 */
fun getQualityColor(quality: String?): Color {
    val grade = when (quality) {
        "下品" -> PillGrade.LOW
        "中品" -> PillGrade.MEDIUM
        "上品" -> PillGrade.HIGH
        else -> return Color(0xFF95A5A6) // 默认灰色，防止异常值导致不可见文字
    }
    return grade.getQualityColor()
}

/**
 * 根据丹药品质枚举返回颜色。
 * 新增 [PillGrade] 枚举值时编译器强制同步更新此映射。
 */
fun PillGrade.getQualityColor(): Color = when (this) {
    PillGrade.LOW -> Color(0xFF95A5A6)
    PillGrade.MEDIUM -> Color(0xFF3498DB)
    PillGrade.HIGH -> Color(0xFFE74C3C)
}
