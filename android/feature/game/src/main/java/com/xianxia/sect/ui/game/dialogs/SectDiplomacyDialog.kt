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
import com.xianxia.sect.core.config.GiftConfig
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

    // 送礼选项状态
    var showGiftOptions by remember { mutableStateOf(false) }

    // 送礼档位点击处理
    val onGiftTierClick: (Int) -> Unit = { tier ->
        showGiftOptions = false
        isChatDone = false
        isChatting = true
        visibleCount = 0
        skipped = false
        chatMessages = emptyList()
        scope.launch {
            val result = interactionViewModel.performGiftSpiritStones(sect.id, tier)
            val playerGiftText = buildPlayerGiftText(sect.name, tier)
            if (result != null) {
                val aiResponseText = if (result.success) {
                    getGiftAiAcceptText(relationLevel)
                } else {
                    getGiftAiRejectText(relationLevel)
                }
                val playerReplyText = buildPlayerReplyText(result.success)
                chatMessages = listOf(
                    ChatMessage(text = playerGiftText, isPlayer = true),
                    ChatMessage(text = aiResponseText, isPlayer = false),
                    ChatMessage(text = playerReplyText, isPlayer = true)
                )
            } else {
                chatMessages = listOf(
                    ChatMessage(text = playerGiftText, isPlayer = true)
                )
            }
        }
    }

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
                spiritStones = gameData?.spiritStones ?: 0,
                chatMessages = chatMessages,
                visibleCount = visibleCount,
                isChatting = isChatting,
                isChatDone = isChatDone,
                skipped = skipped,
                showGiftOptions = showGiftOptions,
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
                    showGiftOptions = true
                },
                onGiftTierClick = onGiftTierClick,
                onCancelGiftClick = { showGiftOptions = false },
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
    spiritStones: Long = 0,
    chatMessages: List<ChatMessage>,
    visibleCount: Int,
    isChatting: Boolean,
    isChatDone: Boolean,
    skipped: Boolean,
    showGiftOptions: Boolean = false,
    onAllianceClick: () -> Unit,
    onDissolveClick: () -> Unit,
    onSkipClick: () -> Unit,
    onGiftClick: () -> Unit,
    onGiftTierClick: (Int) -> Unit,
    onCancelGiftClick: () -> Unit,
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
        if (showGiftOptions && !isChatting) {
            // 送礼选项 → 显示四个档位 + 取消
            GiftOptionsPanel(
                spiritStones = spiritStones,
                onGiftTierClick = onGiftTierClick,
                onCancelClick = onCancelGiftClick
            )
        } else if (isChatting && !isChatDone) {
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

// ═══════════ 送礼选项面板 ═══════════

@Composable
private fun GiftOptionsPanel(
    spiritStones: Long,
    onGiftTierClick: (Int) -> Unit,
    onCancelClick: () -> Unit
) {
    val tiers = GiftConfig.SpiritStoneGiftConfig.getAllTiers().sortedByDescending { it.tier }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        tiers.forEachIndexed { index, tier ->
            if (index > 0) {
                HorizontalDivider(
                    thickness = 1.dp,
                    color = Color.Gray.copy(alpha = 0.3f)
                )
            }
            val canAfford = spiritStones >= tier.spiritStones
            val displayText = if (canAfford) {
                "${tier.name} - ${GameUtils.formatNumber(tier.spiritStones)}"
            } else {
                "${tier.name} - 灵石不足"
            }
            Text(
                text = displayText,
                fontSize = 16.sp,
                color = if (canAfford) Color.Black else Color.Gray,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = canAfford) { onGiftTierClick(tier.tier) }
                    .padding(vertical = 12.dp),
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        HorizontalDivider(
            thickness = 1.dp,
            color = Color.Gray.copy(alpha = 0.3f)
        )

        Spacer(modifier = Modifier.height(8.dp))

        GameButton(
            text = "取消",
            onClick = onCancelClick,
            modifier = Modifier.width(ButtonSizes.StandardWidth)
        )
    }
}

// ═══════════ 送礼聊天文本 ═══════════

/**
 * 玩家送礼描述文本
 * @param sectName 目标宗门名称
 * @param tier 送礼档位 (1-4)
 */
internal fun buildPlayerGiftText(sectName: String, tier: Int): String {
    val texts = GIFTS_TEMPLATES[tier] ?: listOf("${sectName}的道友，这是我宗的一点心意，还请笑纳。")
    return texts.random().replace("{S}", sectName)
}

/**
 * AI接受送礼文本
 * @param relationLevel 当前关系等级
 */
internal fun getGiftAiAcceptText(relationLevel: SectRelationLevel): String {
    return (GIFT_AI_ACCEPT_TEXTS[relationLevel] ?: listOf("多谢道友厚礼。")).random()
}

/**
 * AI拒绝送礼文本
 * @param relationLevel 当前关系等级
 */
internal fun getGiftAiRejectText(relationLevel: SectRelationLevel): String {
    return (GIFT_AI_REJECT_TEXTS[relationLevel] ?: listOf("本宗不能接受。")).random()
}

/**
 * 玩家回应送礼文本
 * @param success 送礼是否成功（接受=true，拒绝=false）
 */
internal fun buildPlayerReplyText(success: Boolean): String {
    return (if (success) PLAYER_REPLY_ACCEPT_TEXTS else PLAYER_REPLY_REJECT_TEXTS).random()
}

// ═══════════ 送礼文本常量表（只需创建一次） ═══════════

private val GIFTS_TEMPLATES = mapOf(
    1 to listOf(
        "{S}的道友，我宗备薄礼一份（20,000灵石），聊表心意，还望笑纳。",
        "{S}的道友，些许薄礼不成敬意，还望贵宗收下。",
        "道友，我宗备了一点薄礼（20,000灵石），望贵宗莫要嫌弃。"
    ),
    2 to listOf(
        "{S}的道友，我宗备厚礼一份（200,000灵石），愿贵宗收下，增进两宗情谊。",
        "道友，我宗备了份厚礼（200,000灵石），特来表达对贵宗的敬意。",
        "{S}的道友，这份厚礼是我宗的一点心意，还望贵宗笑纳。"
    ),
    3 to listOf(
        "{S}的诸位道友，我宗备重礼一份（800,000灵石），特来表达诚意，恳请收纳。",
        "诸位道友，我宗精心备置重礼（800,000灵石），以表诚心，万望收下。",
        "{S}的道友，这份重礼代表我宗对贵宗的重视，还请收下。"
    ),
    4 to listOf(
        "{S}的道友！我宗备大礼一份（4,000,000灵石），以表对贵宗的重视，万望收下！",
        "道兄！我宗备了一份大礼（4,000,000灵石），贵宗乃我宗最重要的朋友，请务必收下！",
        "{S}的诸位道兄！这份大礼是我宗倾力准备，愿两宗情谊天长地久！"
    )
)

private val GIFT_AI_ACCEPT_TEXTS = mapOf(
    SectRelationLevel.HOSTILE to listOf(
        "哼……既然你们这么诚恳，那我就代本宗收下了。",
        "……算你们有心，东西留下吧。"
    ),
    SectRelationLevel.ANTAGONISTIC to listOf(
        "……罢了，东西留下吧。",
        "哼，既然送来了，本宗也不好驳你面子。"
    ),
    SectRelationLevel.NORMAL to listOf(
        "道友客气了，这份礼物我宗就收下了。",
        "多谢道友美意，我宗便却之不恭了。"
    ),
    SectRelationLevel.FRIENDLY to listOf(
        "哈哈哈！道友太客气了！这份情谊我宗记下了！",
        "道友盛情难却，我宗便收下了，愿两宗友谊长存！"
    ),
    SectRelationLevel.INTIMATE to listOf(
        "哈哈哈！你我之间还送什么礼！不过既然是你送的，我宗自然欢喜收下！",
        "老友太见外了！不过这份心意我宗领了，哈哈哈！"
    )
)

private val GIFT_AI_REJECT_TEXTS = mapOf(
    SectRelationLevel.HOSTILE to listOf(
        "滚！本宗不稀罕！",
        "哼，带着你的东西滚出本宗地界！"
    ),
    SectRelationLevel.ANTAGONISTIC to listOf(
        "哼，拿回去，本宗不缺这个。",
        "不必了，本宗不领你们的情。"
    ),
    SectRelationLevel.NORMAL to listOf(
        "道友美意心领了，只是此礼我宗不便收下，还请见谅。",
        "多谢道友好意，但我宗有规矩，不能收此重礼。"
    ),
    SectRelationLevel.FRIENDLY to listOf(
        "唉，道友何必如此客气？这份礼太重了，我宗受之有愧啊。",
        "道友厚爱，我宗心领了。但此礼确实不便收下，还望见谅。"
    ),
    SectRelationLevel.INTIMATE to listOf(
        "你我之间何需这些俗物？快收回去，心意到了就行！",
        "哈哈哈！老友你这是做什么？快收回去，你我还用这些虚礼？"
    )
)

private val PLAYER_REPLY_ACCEPT_TEXTS = listOf(
    "哈哈，道友喜欢便好！愿两宗友谊长存！",
    "太好了！愿两宗情谊日久弥深！",
    "贵宗喜欢便好，日后还望多多往来！"
)

private val PLAYER_REPLY_REJECT_TEXTS = listOf(
    "既然贵宗不便收，那在下也不勉强，告辞。",
    "是在下唐突了，这便收回，告辞。",
    "既然贵宗看不上，那便算了，告辞。"
)
