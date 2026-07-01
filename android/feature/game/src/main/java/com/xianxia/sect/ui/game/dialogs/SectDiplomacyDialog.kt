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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class ChatMessage(
    val text: String,
    val isPlayer: Boolean
)

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
    val playerPortraitRes = interactionViewModel.getFirstPlayerDisciplePortrait()
    val aiPortraitRes = gameData?.sectDetails?.get(sect.id)?.portraitRes ?: ""

    // 初始问候语仅创建时计算一次，避免因 isAlly 变化导致问候语变化
    val initialDialogueText = remember { dialogueTextForRelation(relationLevel, isAlly) }

    // 聊天状态
    var chatMessages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var visibleCount by remember { mutableStateOf(0) }
    var isChatting by remember { mutableStateOf(false) }
    var isChatDone by remember { mutableStateOf(false) }
    var skipped by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 对话背景图资源
    val bgRes = SpriteResRegistry.resolve("dialogue_bg")
        ?: R.drawable.dialogue_bg

    // 1秒延迟逐条显示，完成后保留聊天记录，恢复操作按钮
    LaunchedEffect(chatMessages, skipped) {
        if (isChatting && chatMessages.isNotEmpty()) {
            if (skipped) {
                visibleCount = chatMessages.size
            } else {
                for (i in chatMessages.indices) {
                    delay(1000L)
                    visibleCount = i + 1
                }
            }
            isChatDone = true
        }
    }

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

            // 垂直分割线
            VerticalDivider(
                modifier = Modifier.fillMaxHeight(),
                thickness = 1.dp,
                color = Color.Gray
            )

            // ═══════════ 右侧面板 (8/10) ═══════════
            RightPanel(
                initialDialogueText = initialDialogueText,
                portraitRes = aiPortraitRes,
                playerPortraitRes = playerPortraitRes,
                sectName = sect.name,
                isAlly = isAlly,
                hasGiftedThisYear = hasGiftedThisYear,
                relationLevel = relationLevel,
                chatMessages = chatMessages,
                visibleCount = visibleCount,
                isChatting = isChatting,
                isChatDone = isChatDone,
                skipped = skipped,
                onAllianceClick = {
                    isChatDone = false
                    isChatting = true
                    visibleCount = 0
                    skipped = false
                    chatMessages = emptyList()
                    scope.launch {
                        val playerSect = gameData?.worldMapSects?.find { it.isPlayerSect }
                        val aiSectName = sect.name
                        val playerSectId = playerSect?.id ?: ""
                        val favor = if (playerSectId.isNotEmpty()) {
                            gameData?.sectRelations?.find {
                                (it.sectId1 == playerSectId && it.sectId2 == sect.id) ||
                                (it.sectId1 == sect.id && it.sectId2 == playerSectId)
                            }?.favor ?: 0
                        } else 0

                        val success = interactionViewModel.requestAllianceSimple(sect.id)
                        val aiText = getAiResponseText(favor, success)
                        val playerReply = if (success) {
                            "太好了！从今往后你我二宗同气连枝，守望相助！"
                        } else {
                            "既然贵宗无意，那我等也不便强求。告辞。"
                        }

                        chatMessages = listOf(
                            ChatMessage(
                                text = "尊敬的道友，我宗愿与贵宗结为同盟，共谋发展，不知尊意如何？",
                                isPlayer = true
                            ),
                            ChatMessage(text = aiText, isPlayer = false),
                            ChatMessage(text = playerReply, isPlayer = true)
                        )
                    }
                },
                onDissolveClick = {
                    isChatDone = false
                    isChatting = true
                    visibleCount = 0
                    skipped = false
                    chatMessages = emptyList()
                    scope.launch {
                        interactionViewModel.dissolveAllianceSimple(sect.id)
                        chatMessages = listOf(
                            ChatMessage(
                                text = "道友，我宗深思熟虑后决定解除盟约，日后各走各路，还望见谅。",
                                isPlayer = true
                            ),
                            ChatMessage(
                                text = "既如此，我宗也不强留。从此两清，各自珍重。",
                                isPlayer = false
                            ),
                            ChatMessage(
                                text = "多谢成全，后会有期。",
                                isPlayer = true
                            )
                        )
                    }
                },
                onSkipClick = { skipped = true },
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val iconResId = sectIconRes(sect.level)
            if (iconResId != null) {
                Image(
                    painter = painterResource(id = iconResId),
                    contentDescription = sect.levelName,
                    modifier = Modifier.size(26.dp)
                )
            }

            Text(
                text = sect.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun RightPanel(
    initialDialogueText: String,
    portraitRes: String,
    playerPortraitRes: String,
    sectName: String,
    isAlly: Boolean,
    hasGiftedThisYear: Boolean,
    relationLevel: SectRelationLevel,
    chatMessages: List<ChatMessage>,
    visibleCount: Int,
    isChatting: Boolean,
    isChatDone: Boolean,
    skipped: Boolean,
    onAllianceClick: () -> Unit,
    onDissolveClick: () -> Unit,
    onSkipClick: () -> Unit,
    onGiftClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier.padding(8.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // ═══════════ 对话区域（问候 + 追加消息） ═══════════
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(top = 24.dp, bottom = 8.dp)
        ) {
            // 初始问候（始终显示）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AIAvatar(portraitRes = portraitRes, sectName = sectName)
                DialogueBubble(text = initialDialogueText, isLeft = true)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 追加的聊天消息
            chatMessages.take(visibleCount).forEach { msg ->
                if (msg.isPlayer) {
                    // 玩家消息 — 气泡(右) + 头像
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        DialogueBubble(text = msg.text, isLeft = false)
                        Spacer(modifier = Modifier.width(4.dp))
                        PlayerAvatar(portraitRes = playerPortraitRes)
                    }
                } else {
                    // AI消息 — 头像 + 气泡(左)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        AIAvatar(portraitRes = portraitRes, sectName = sectName)
                        DialogueBubble(text = msg.text, isLeft = true)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 触发自动滚动到底部的占位
            if (visibleCount > 0) {
                LaunchedEffect(visibleCount) {
                    scrollState.animateScrollTo(scrollState.maxValue)
                }
            }
        }

        // ═══════════ 底部按钮区 ═══════════
        if (isChatting && !isChatDone) {
            // 聊天动画中 → 跳过按钮
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
        } else {
            // 初始状态或聊天完成 → 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
            ) {
                if (isAlly) {
                    GameButton(
                        text = "散盟",
                        onClick = onDissolveClick,
                        modifier = Modifier.width(ButtonSizes.StandardWidth)
                    )
                } else {
                    GameButton(
                        text = "结盟",
                        onClick = onAllianceClick,
                        enabled = true,
                        modifier = Modifier.width(ButtonSizes.StandardWidth)
                    )
                }
                GameButton(
                    text = if (hasGiftedThisYear) "已送礼" else "送礼",
                    onClick = onGiftClick,
                    enabled = !hasGiftedThisYear,
                    modifier = Modifier.width(ButtonSizes.StandardWidth)
                )
            }
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
private fun PlayerAvatar(
    portraitRes: String
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
