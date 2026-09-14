package com.xianxia.sect.ui.game.dialogs

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.domain.FavorDomain
import com.xianxia.sect.core.model.SectRelationLevel
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.sectIconRes
import com.xianxia.sect.ui.components.SpriteResRegistry
import com.xianxia.sect.ui.game.WorldMapInteractionViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*



internal data class ChatMessage(
    val text: String,
    val isPlayer: Boolean
)

@Composable
@Suppress("UnusedParameter") // disciples: 弹窗/组件统一签名约定：保持调用点参数面一致并预留子组件扩展消费
internal fun SectDiplomacyDialog(
    sect: WorldSect,
    relation: Int,
    gameData: GameData?,
    disciples: List<DiscipleAggregate>,
    interactionViewModel: WorldMapInteractionViewModel,
    onDismiss: () -> Unit
) {
    // 聊天状态
    val uiState = remember { DiplomacyUiState() }
    val scope = rememberCoroutineScope()

    // 1秒延迟逐条显示，完成后保留聊天记录，恢复操作按钮
    LaunchedEffect(uiState.messages, uiState.skipped) {
        if (uiState.isChatting && uiState.messages.isNotEmpty()) {
            uiState.animateTyping()
        }
    }

    DiplomacyFrame(
        inputs = DiplomacyInputs(
            sect = sect,
            relation = relation,
            gameData = gameData,
            interactionViewModel = interactionViewModel
        ),
        uiState = uiState,
        frameCallbacks = DiplomacyFrameCallbacks(
            onDismiss = onDismiss,
            onAllianceClick = { uiState.start(scope) { performAllianceFlow(interactionViewModel, gameData, sect) } },
            onDissolveClick = { uiState.start(scope) { performDissolveFlow(interactionViewModel, sect.id) } },
            onVassalClick = { uiState.start(scope) { performVassalFlow(interactionViewModel, gameData, sect) } },
            onDissolveVassalClick = {
                uiState.start(scope) { performDissolveVassalFlow(interactionViewModel, sect.id) }
            },
            onSkipClick = { uiState.skipped = true }
        ),
        giftCallbacks = DiplomacyGiftCallbacks(
            onGiftClick = { uiState.showGiftOptions = true },
            onGiftTierClick = { tier ->
                uiState.showGiftOptions = false
                uiState.start(scope) {
                    performGiftFlow(interactionViewModel, sect.id, tier, sect.name, FavorDomain.getLevel(relation))
                }
            },
            onCancelGiftClick = { uiState.showGiftOptions = false }
        )
    )
}

/** 外交对话流程状态：聊天消息 + 逐条显示进度 + 送礼选项 */
private class DiplomacyUiState {
    var messages by mutableStateOf<List<ChatMessage>>(emptyList())
    var visibleCount by mutableIntStateOf(0)
    var isChatting by mutableStateOf(false)
    var isChatDone by mutableStateOf(false)
    var skipped by mutableStateOf(false)
    var showGiftOptions by mutableStateOf(false)

    /** 重置聊天状态并启动异步流程（消息生成在 IO 线程，完成后写回） */
    fun start(scope: CoroutineScope, produce: suspend () -> List<ChatMessage>) {
        isChatDone = false
        isChatting = true
        visibleCount = 0
        skipped = false
        messages = emptyList()
        scope.launch { messages = produce() }
    }

    /** 逐条显示聊天消息（1 秒/条，完成后标记结束；跳过时直接显示全部） */
    suspend fun animateTyping() {
        if (skipped) {
            visibleCount = messages.size
        } else {
            for (i in messages.indices) {
                delay(1000L)
                visibleCount = i + 1
            }
        }
        isChatDone = true
    }
}

/** 外交输入数据 */
private data class DiplomacyInputs(
    val sect: WorldSect,
    val relation: Int,
    val gameData: GameData?,
    val interactionViewModel: WorldMapInteractionViewModel
)

/** 对话框主体框架：左面板 + 右面板 */
@Composable
private fun DiplomacyFrame(
    inputs: DiplomacyInputs,
    uiState: DiplomacyUiState,
    frameCallbacks: DiplomacyFrameCallbacks,
    giftCallbacks: DiplomacyGiftCallbacks
) {
    val sect = inputs.sect
    val gameData = inputs.gameData
    val interactionViewModel = inputs.interactionViewModel
    val isAlly = interactionViewModel.isAlly(sect.id)
    val isPlayerVassal = interactionViewModel.isPlayerVassal(sect.id)
    val hasGiftedThisYear = (gameData?.sectDetails?.get(sect.id)?.lastGiftYear ?: 0) == (gameData?.gameYear ?: 1)
    val relationLevel = FavorDomain.getLevel(inputs.relation)
    val playerPortraitRes = interactionViewModel.getFirstPlayerDisciplePortrait()
    val aiPortraitRes = gameData?.sectDetails?.get(sect.id)?.portraitRes ?: ""

    // 初始问候语仅创建时计算一次，避免因 isAlly 变化导致问候语变化
    val initialDialogueText = remember { dialogueTextForRelation(relationLevel, isAlly) }
    val canVassal = !isPlayerVassal && !isAlly

    // 对话背景图资源
    val bgRes = SpriteResRegistry.resolve("dialogue_bg")
        ?: R.drawable.dialogue_bg

    UnifiedGameDialog(
        onDismissRequest = frameCallbacks.onDismiss,
        title = sect.name,
        mode = DialogMode.Full,
        scrollableContent = false,
        backgroundRes = bgRes
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // ═══════════ 左侧面板 (2/10) + 垂直分割线 ═══════════
            DiplomacyLeftPanel(sect = sect)

            // ═══════════ 右侧面板 (8/10) ═══════════
            DiplomacyRightPanel(
                frameData = DiplomacyFrameData(
                    initialDialogueText = initialDialogueText,
                    portraitRes = aiPortraitRes,
                    playerPortraitRes = playerPortraitRes,
                    sectName = sect.name,
                    isAlly = isAlly,
                    isPlayerVassal = isPlayerVassal,
                    canVassal = canVassal
                ),
                relationState = DiplomacyRelationState(
                    hasGiftedThisYear = hasGiftedThisYear,
                    relationLevel = relationLevel,
                    spiritStones = gameData?.spiritStones ?: 0,
                    showGiftOptions = uiState.showGiftOptions
                ),
                chat = uiState,
                frameCallbacks = frameCallbacks,
                giftCallbacks = giftCallbacks
            )
        }
    }
}

/** 左侧面板区：左面板 + 垂直分割线 */
@Composable
private fun RowScope.DiplomacyLeftPanel(sect: WorldSect) {
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
}

/** 右侧面板展示数据 */
private data class DiplomacyFrameData(
    val initialDialogueText: String,
    val portraitRes: String,
    val playerPortraitRes: String,
    val sectName: String,
    val isAlly: Boolean,
    val isPlayerVassal: Boolean,
    val canVassal: Boolean
)

/** 右侧面板关系数据 */
private data class DiplomacyRelationState(
    val hasGiftedThisYear: Boolean,
    val relationLevel: SectRelationLevel,
    val spiritStones: Long,
    val showGiftOptions: Boolean
)

/** 聊天/框架回调 */
private data class DiplomacyFrameCallbacks(
    val onDismiss: () -> Unit,
    val onAllianceClick: () -> Unit,
    val onDissolveClick: () -> Unit,
    val onVassalClick: () -> Unit,
    val onDissolveVassalClick: () -> Unit,
    val onSkipClick: () -> Unit
)

/** 送礼回调 */
private data class DiplomacyGiftCallbacks(
    val onGiftClick: () -> Unit,
    val onGiftTierClick: (Int) -> Unit,
    val onCancelGiftClick: () -> Unit
)

/** 右侧面板装配：把分组数据解包传给 RightPanel */
@Composable
private fun RowScope.DiplomacyRightPanel(
    frameData: DiplomacyFrameData,
    relationState: DiplomacyRelationState,
    chat: DiplomacyUiState,
    frameCallbacks: DiplomacyFrameCallbacks,
    giftCallbacks: DiplomacyGiftCallbacks
) {
    RightPanel(
        initialDialogueText = frameData.initialDialogueText,
        portraitRes = frameData.portraitRes,
        playerPortraitRes = frameData.playerPortraitRes,
        sectName = frameData.sectName,
        isAlly = frameData.isAlly,
        isPlayerVassal = frameData.isPlayerVassal,
        canVassal = frameData.canVassal,
        hasGiftedThisYear = relationState.hasGiftedThisYear,
        relationLevel = relationState.relationLevel,
        spiritStones = relationState.spiritStones,
        chatMessages = chat.messages,
        visibleCount = chat.visibleCount,
        isChatting = chat.isChatting,
        isChatDone = chat.isChatDone,
        skipped = chat.skipped,
        showGiftOptions = relationState.showGiftOptions,
        onAllianceClick = frameCallbacks.onAllianceClick,
        onDissolveClick = frameCallbacks.onDissolveClick,
        onVassalClick = frameCallbacks.onVassalClick,
        onDissolveVassalClick = frameCallbacks.onDissolveVassalClick,
        onSkipClick = frameCallbacks.onSkipClick,
        onGiftClick = giftCallbacks.onGiftClick,
        onGiftTierClick = giftCallbacks.onGiftTierClick,
        onCancelGiftClick = giftCallbacks.onCancelGiftClick,
        modifier = Modifier.weight(0.8f).fillMaxHeight()
    )
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

@Suppress("LongParameterList", "UnusedParameter") // relationLevel: 弹窗/组件统一签名约定：保持调用点参数面一致并预留子组件扩展消费
@Composable
private fun RightPanel(
    initialDialogueText: String,
    portraitRes: String,
    playerPortraitRes: String,
    sectName: String,
    isAlly: Boolean,
    modifier: Modifier = Modifier,
    isPlayerVassal: Boolean = false,
    canVassal: Boolean = true,
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
    onVassalClick: () -> Unit = {},
    onDissolveVassalClick: () -> Unit = {},
    onSkipClick: () -> Unit,
    onGiftClick: () -> Unit,
    onGiftTierClick: (Int) -> Unit,
    onCancelGiftClick: () -> Unit
) {
    Column(
        modifier = modifier.padding(8.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // ═══════════ 对话区域（问候 + 追加消息） ═══════════
        DiplomacyChatArea(
            portraitRes = portraitRes,
            playerPortraitRes = playerPortraitRes,
            sectName = sectName,
            initialDialogueText = initialDialogueText,
            chatMessages = chatMessages,
            visibleCount = visibleCount
        )

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
            ChatSkipButton(onSkipClick = onSkipClick)
        } else {
            // 初始状态或聊天完成 → 操作按钮
            DiplomacyActionButtons(
                isAlly = isAlly,
                isPlayerVassal = isPlayerVassal,
                canVassal = canVassal,
                hasGiftedThisYear = hasGiftedThisYear,
                callbacks = DiplomacyActionCallbacks(
                    onAllianceClick = onAllianceClick,
                    onDissolveClick = onDissolveClick,
                    onVassalClick = onVassalClick,
                    onDissolveVassalClick = onDissolveVassalClick,
                    onGiftClick = onGiftClick
                )
            )
        }
    }
}

/** 对话区域：问候 + 消息流 + 自动滚动 */
@Composable
private fun ColumnScope.DiplomacyChatArea(
    portraitRes: String,
    playerPortraitRes: String,
    sectName: String,
    initialDialogueText: String,
    chatMessages: List<ChatMessage>,
    visibleCount: Int
) {
    val scrollState = rememberScrollState()
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
}

/** 操作按钮回调 */
internal data class DiplomacyActionCallbacks(
    val onAllianceClick: () -> Unit,
    val onDissolveClick: () -> Unit,
    val onVassalClick: () -> Unit,
    val onDissolveVassalClick: () -> Unit,
    val onGiftClick: () -> Unit
)

// ═══════════ 送礼文本常量表（只需创建一次） ═══════════

internal val GIFTS_TEMPLATES = mapOf(
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

internal val GIFT_AI_ACCEPT_TEXTS = mapOf(
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

internal val GIFT_AI_REJECT_TEXTS = mapOf(
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

internal val PLAYER_REPLY_ACCEPT_TEXTS = listOf(
    "哈哈，道友喜欢便好！愿两宗友谊长存！",
    "太好了！愿两宗情谊日久弥深！",
    "贵宗喜欢便好，日后还望多多往来！"
)

internal val PLAYER_REPLY_REJECT_TEXTS = listOf(
    "既然贵宗不便收，那在下也不勉强，告辞。",
    "是在下唐突了，这便收回，告辞。",
    "既然贵宗看不上，那便算了，告辞。"
)
