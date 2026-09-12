package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.SectRelationLevel
import com.xianxia.sect.core.util.PortraitPool
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.SpriteResRegistry
import com.xianxia.sect.ui.theme.ButtonSizes
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*


/** 操作按钮行：结盟/散盟/附属/送礼 */
@Composable
internal fun DiplomacyActionButtons(
    isAlly: Boolean,
    isPlayerVassal: Boolean,
    canVassal: Boolean,
    hasGiftedThisYear: Boolean,
    callbacks: DiplomacyActionCallbacks
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
    ) {
        if (isAlly) {
            GameButton(
                text = "散盟",
                onClick = callbacks.onDissolveClick,
                modifier = Modifier.width(ButtonSizes.StandardWidth)
            )
        } else {
            GameButton(
                text = "结盟",
                onClick = callbacks.onAllianceClick,
                enabled = true,
                modifier = Modifier.width(ButtonSizes.StandardWidth)
            )
            if (isPlayerVassal) {
                GameButton(
                    text = "解除附属",
                    onClick = callbacks.onDissolveVassalClick,
                    modifier = Modifier.width(ButtonSizes.StandardWidth)
                )
            } else if (canVassal) {
                GameButton(
                    text = "附属",
                    onClick = callbacks.onVassalClick,
                    modifier = Modifier.width(ButtonSizes.StandardWidth)
                )
            }
        }
        GameButton(
            text = if (hasGiftedThisYear) "已送礼" else "送礼",
            onClick = callbacks.onGiftClick,
            enabled = !hasGiftedThisYear,
            modifier = Modifier.width(ButtonSizes.StandardWidth)
        )
    }
}

/** 聊天动画中的跳过按钮 */
@Composable
internal fun ChatSkipButton(onSkipClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        GameButton(
            text = "跳过",
            onClick = onSkipClick,
            modifier = Modifier.width(ButtonSizes.StandardWidth)
        )
    }
}

@Composable
internal fun AIAvatar(
    portraitRes: String,
    sectName: String
) {
    if (portraitRes.isNotEmpty()) {
        val portraitDrawableId = PortraitPool.getResourceId(portraitRes)
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
internal fun PlayerAvatar(
    portraitRes: String
) {
    if (portraitRes.isNotEmpty()) {
        val portraitDrawableId = PortraitPool.getResourceId(portraitRes)
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
                    contentDescription = "我方弟子",
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
internal fun DialogueBubble(
    text: String,
    isLeft: Boolean,
    modifier: Modifier = Modifier
) {
    val bubbleRes = SpriteResRegistry.resolve(
        if (isLeft) "dialogue_bubble_left" else "dialogue_bubble_right"
    ) ?: if (isLeft) R.drawable.dialogue_bubble_left
    else R.drawable.dialogue_bubble_right

    // containerSize 单位是像素，需经 LocalDensity 换算为 dp（勿直接 .dp 使用像素值）
    val bubbleMaxWidth = with(LocalDensity.current) {
        (LocalWindowInfo.current.containerSize.width * 0.65f).toDp()
    }

    Box(
        modifier = modifier
            .widthIn(max = bubbleMaxWidth)
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

internal fun getAiResponseText(favor: Int, success: Boolean): String {
    return if (success) {
        when {
            favor >= 90 -> "哈哈！得贵宗为盟实乃我宗之幸！从此你我二宗同气连枝，共进退！"
            favor >= 80 -> "善！道友诚意可嘉，我宗愿与贵宗结为盟友，共图大业！"
            favor >= 60 -> "哈哈，道友盛情相邀，我宗自然乐意之至！"
            favor >= 40 -> "贵宗既有此意，我宗也愿与贵宗携手共进，就此结盟。"
            favor >= 20 -> "...罢了，既然你们有此诚意，我宗便答应这次结盟。"
            else -> "哼...虽然你我两宗素无交情，但既然你们放低身段来求，本宗就勉为其难应了吧。"
        }
    } else {
        when {
            favor >= 90 -> "唉，道友厚爱本宗铭感五内。只是天意难违，结盟之缘未到，还望见谅。"
            favor >= 80 -> "道友盛情，本宗心领。然此事还需从长计议，非一时之功。"
            favor >= 60 -> "道友厚爱，只是此事关系重大，容我宗再作考虑。"
            favor >= 40 -> "贵宗好意心领，但我宗暂不考虑结盟之事。"
            favor >= 20 -> "...我宗对贵宗并无兴趣，请回吧。"
            else -> "哼！就凭你们也配与我宗结盟？速速离去！"
        }
    }
}
