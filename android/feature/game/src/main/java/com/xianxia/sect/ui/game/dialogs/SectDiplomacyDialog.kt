package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.util.GameUtils
import com.xianxia.sect.core.util.PortraitPool
import com.xianxia.sect.core.util.SectRelationLevel
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.sectIconRes
import com.xianxia.sect.ui.components.SpriteResRegistry
import com.xianxia.sect.ui.game.WorldMapInteractionViewModel
import com.xianxia.sect.ui.theme.ButtonSizes

@Composable
internal fun SectDiplomacyDialog(
    sect: WorldSect,
    relation: Int,
    gameData: GameData?,
    disciples: List<DiscipleAggregate>,
    interactionViewModel: WorldMapInteractionViewModel,
    onDismiss: () -> Unit
) {
    val currentYear = gameData?.gameYear ?: 1
    val isAlly = interactionViewModel.isAlly(sect.id)
    val lastGiftYear = gameData?.sectDetails?.get(sect.id)?.lastGiftYear
    val hasGiftedThisYear = (lastGiftYear ?: 0) == currentYear

    val relationLevel = GameUtils.getSectRelationLevel(relation)
    val dialogueText = dialogueTextForRelation(relationLevel, isAlly)

    // 对话背景图资源
    val bgRes = SpriteResRegistry.resolve("dialogue_bg")
        ?: R.drawable.dialogue_bg

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "",
        mode = DialogMode.Half,
        scrollableContent = false,
        backgroundRes = bgRes
    ) {
        Row(modifier = Modifier.fillMaxSize()) {

            // ═══════════ 左侧面板 (2/10) ═══════════
            LeftPanel(
                sect = sect,
                modifier = Modifier.weight(0.2f).fillMaxHeight()
            )

            // 垂直分割线 (1dp gray)
            VerticalDivider(
                modifier = Modifier.fillMaxHeight(),
                thickness = 1.dp,
                color = Color.Gray
            )

            // ═══════════ 右侧面板 (8/10) ═══════════
            RightPanel(
                dialogueText = dialogueText,
                portraitRes = gameData?.sectDetails?.get(sect.id)?.portraitRes ?: "",
                sectName = sect.name,
                isAlly = isAlly,
                hasGiftedThisYear = hasGiftedThisYear,
                relationLevel = relationLevel,
                onAllianceClick = {
                    interactionViewModel.openAllianceDialog(sect.id)
                },
                onGiftClick = {
                    interactionViewModel.openGiftDialog(sect.id)
                },
                modifier = Modifier.weight(0.8f).fillMaxHeight()
            )
        }
    }
}

@Composable
private fun LeftPanel(
    sect: WorldSect,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val iconResId = sectIconRes(sect.level)
        if (iconResId != null) {
            Image(
                painter = painterResource(id = iconResId),
                contentDescription = sect.levelName,
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = sect.name,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun RightPanel(
    dialogueText: String,
    portraitRes: String,
    sectName: String,
    isAlly: Boolean,
    hasGiftedThisYear: Boolean,
    relationLevel: SectRelationLevel,
    onAllianceClick: () -> Unit,
    onGiftClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(8.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 聊天对话区 — AI弟子发言 = 头像 + 对话框(左)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 24.dp),
            contentAlignment = Alignment.TopStart
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // AI弟子头像（圆形白框）
                AIAvatar(
                    portraitRes = portraitRes,
                    sectName = sectName
                )

                Spacer(modifier = Modifier.width(4.dp))

                // 对话框（左）— 根据文字大小自适应
                DialogueBubble(
                    text = dialogueText,
                    isLeft = true
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 操作按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(
                12.dp, Alignment.CenterHorizontally
            )
        ) {
            GameButton(
                text = if (isAlly) "盟约" else "结盟",
                onClick = onAllianceClick,
                enabled = relationLevel == SectRelationLevel.INTIMATE || isAlly,
                modifier = Modifier.width(ButtonSizes.StandardWidth)
            )

            GameButton(
                text = if (hasGiftedThisYear) "已送礼" else "送礼",
                onClick = onGiftClick,
                enabled = !hasGiftedThisYear,
                modifier = Modifier.width(ButtonSizes.StandardWidth)
            )
        }
    }
}

@Composable
private fun AIAvatar(
    portraitRes: String,
    sectName: String
) {
    if (portraitRes.isNotEmpty()) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val portraitDrawableId = PortraitPool.getResourceId(context, portraitRes)
        if (portraitDrawableId != 0) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(2.dp, Color(0xFFDDDDDD), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = portraitDrawableId),
                    contentDescription = "${sectName}弟子",
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            }
        }
    }
}

@Composable
private fun DialogueBubble(
    text: String,
    isLeft: Boolean,
    modifier: Modifier = Modifier
) {
    val bubbleRes = SpriteResRegistry.resolve(
        if (isLeft) "dialogue_bubble_left" else "dialogue_bubble_right"
    ) ?: if (isLeft) R.drawable.dialogue_bubble_left
    else R.drawable.dialogue_bubble_right

    Box(
        modifier = modifier
            .wrapContentWidth()
            .wrapContentHeight(),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = bubbleRes),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds
        )

        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            fontSize = 14.sp,
            color = Color.Black,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )
    }
}

internal fun dialogueTextForRelation(
    relationLevel: SectRelationLevel, isAlly: Boolean
): String = when {
    isAlly -> "盟友亲至，有何要事但说无妨。"
    else -> when (relationLevel) {
        SectRelationLevel.HOSTILE -> "......阁下竟敢踏足本宗地界？"
        SectRelationLevel.ANTAGONISTIC -> "哼，有话快说，本宗不欢迎你。"
        SectRelationLevel.NORMAL -> "贵宗来访，不知有何贵干？"
        SectRelationLevel.FRIENDLY -> "原来是友宗到访，快请一叙。"
        SectRelationLevel.INTIMATE -> "哈哈，老友来访，真是蓬荜生辉！"
    }
}
