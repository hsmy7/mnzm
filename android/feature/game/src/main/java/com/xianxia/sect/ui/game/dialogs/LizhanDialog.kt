package com.xianxia.sect.ui.game.dialogs

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.ui.game.map.MapItemMapper
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.SpriteImage
import com.xianxia.sect.ui.components.SpriteResRegistry
import com.xianxia.sect.ui.components.StandardPromptDialog
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.components.clickableWithSound
import com.xianxia.sect.ui.game.GameViewModel
import com.xianxia.sect.ui.game.HeavenlyTrialViewModel
import com.xianxia.sect.ui.game.SecretRealmViewModel
import com.xianxia.sect.ui.theme.GameColors
import kotlinx.coroutines.launch

/** 卡片宽度占屏幕宽度比例 */
private const val CARD_WIDTH_FRACTION = 0.30f
/** 卡片宽高比（竖卡：高 = 宽 × 1.5，纯比例不依赖 maxHeight——真机约束不可靠） */
private const val CARD_ASPECT_RATIO = 1.5f
/** UnifiedGameDialog Full 模式内容区左右 padding（各 32dp） */
private val CONTENT_H_PADDING = 32.dp
/** 翻页动画时长（毫秒，放慢以清晰可见运动轨迹） */
private const val FLIP_ANIMATION_MS = 600
/** 主卡内容可见判定阈值（动画中内容渐显/渐隐过渡） */
private const val CONTENT_VISIBLE_THRESHOLD = 0.5f
/** 卡片图标尺寸（占竖卡宽度约 2/3） */
private val ICON_SIZE_DP = 100.dp
/**
 * 窗口垂直居中补偿：内容区中心在窗口中心下方 header/2
 * （header = CloseButton 布局 48dp + top padding 4dp = 52dp），
 * 上移 26dp 使卡片中心对齐屏幕几何中心
 */
private val VERTICAL_CENTER_COMPENSATION = (-26).dp
/** 翻页按钮素材尺寸 */
private val FLIP_BUTTON_SIZE = 44.dp

private val CardCorner = RoundedCornerShape(12.dp)

/** 历战活动卡片定义 */
private data class LizhanCard(
    val id: String,
    val icon: String,
    val title: String,
    val subtitle: String,
    /** 秘境卡：正下方显示开启状态（红字/进入秘境按钮） */
    val isRealmCard: Boolean = false
)

private val LIZHAN_CARDS = listOf(
    LizhanCard(
        id = "heavenly_trial",
        icon = "heavenly_trial_icon",
        title = "未知岛屿",
        subtitle = "挑战通关获得丰厚奖励"
    ),
    LizhanCard(
        id = "secret_realm",
        icon = "secret_realm",
        title = "远古秘境",
        subtitle = "无穷机缘的未知秘境",
        isRealmCard = true
    )
)

/**
 * 历战对话框：卡片轮转展示活动入口。
 *
 * 分支切换：默认卡片轮转 → 天道试炼半屏面板（原封不动
 * 复用 HeavenlyTrialPanel）/ 挑战全屏（BattlePrep/DiscipleSelect/
 * Combat）→ 远古秘境探索全屏。点击天道试炼卡片后标记活动界面
 * 隐藏该活动（仅显示层、会话内存态）。
 */
@Composable
fun LizhanDialog(
    viewModel: GameViewModel,
    onDismiss: () -> Unit
) {
    val trialViewModel = hiltViewModel<HeavenlyTrialViewModel>()
    val realmViewModel = hiltViewModel<SecretRealmViewModel>()
    val ui = remember { LizhanUiState() }

    val trialScreen by trialViewModel.currentScreen
        .collectAsStateWithLifecycle()
    val isTrialChallenge =
        trialScreen !is HeavenlyTrialViewModel.Screen.Panel

    // 防御：打开历战时复位上次会话残留的非面板状态
    LaunchedEffect(Unit) { if (isTrialChallenge) trialViewModel.dismiss() }

    LaunchedEffect(Unit) {
        trialViewModel.errorEvents.collect { message -> ui.trialError = message }
    }

    // 关闭整个历战：挑战中先放弃战斗复位，再关闭对话框
    val dismissAll: () -> Unit = {
        trialViewModel.dismiss()
        onDismiss()
    }

    BackHandler(
        onBack = if (isTrialChallenge) {
            { trialViewModel.dismiss() }
        } else {
            dismissAll
        }
    )

    LizhanDialogContent(
        ui = ui,
        dismissAll = dismissAll,
        trialScreen = trialScreen,
        trialViewModel = trialViewModel,
        viewModel = viewModel
    )
}

/** 历战 UI 状态（Compose 状态集中持有，避免主函数参数爆炸） */
private class LizhanUiState {
    var showTrialPanel by mutableStateOf(false)
    var showRealmDetail by mutableStateOf(false)
    var showRealmExploration by mutableStateOf(false)
    var realmEntryError by mutableStateOf<String?>(null)
    var trialError by mutableStateOf<String?>(null)
    /** 轮转索引状态（跨分支保留：详情/探索返回后主卡仍为进入前的卡片） */
    val carousel = LizhanCarouselState(LIZHAN_CARDS.size)
}

/** 历战容器：全屏对话框 + 分支内容 + 覆层弹窗 */
@Composable
private fun LizhanDialogContent(
    ui: LizhanUiState,
    dismissAll: () -> Unit,
    trialScreen: HeavenlyTrialViewModel.Screen?,
    trialViewModel: HeavenlyTrialViewModel,
    viewModel: GameViewModel
) {
    val isTrialChallenge =
        trialScreen !is HeavenlyTrialViewModel.Screen.Panel

    UnifiedGameDialog(
        onDismissRequest = dismissAll,
        title = "历战",
        mode = DialogMode.Full,
        dismissOnBackPress = false,
        dismissOnClickOutside = false,
        // 遮罩由 GameOverlayHost 单例绘制（黑色半透明背景）
        scrimEnabled = false,
        // 移除背景图：仅显示卡片 + 黑色半透明遮罩
        backgroundRes = 0,
        // 挑战/秘境探索时隐藏标题栏，内容区零 padding 实现真全屏
        showHeader = !isTrialChallenge && !ui.showRealmExploration
    ) {
        LizhanDialogBranches(
            trialScreen = trialScreen,
            showTrialPanel = ui.showTrialPanel,
            showRealmDetail = ui.showRealmDetail,
            showRealmExploration = ui.showRealmExploration,
            carousel = ui.carousel,
            callbacks = LizhanBranchCallbacks(
                onOpenTrialPanel = { ui.showTrialPanel = true },
                onDismissTrialPanel = { ui.showTrialPanel = false },
                onOpenRealmDetail = { ui.showRealmDetail = true },
                onDismissRealmDetail = { ui.showRealmDetail = false },
                onRealmDetailFinished = {
                    ui.showRealmDetail = false
                    ui.showRealmExploration = true
                },
                onEnterExploration = { ui.showRealmExploration = true },
                onExitExploration = {
                    ui.showRealmExploration = false
                    viewModel.onUserInteraction()
                },
                onFinishedExploration = {
                    ui.showRealmExploration = false
                    viewModel.onUserInteraction()
                },
                onEntryError = { ui.realmEntryError = it }
            )
        )
    }

    // 覆层弹窗（通关奖励/错误提示，叠在历战内容之上）
    val showClearRewards =
        ui.showTrialPanel && trialViewModel.showClearRewardDialog
    LizhanFloatingDialogs(
        showClearRewards = showClearRewards,
        trialViewModel = trialViewModel,
        viewModel = viewModel,
        trialError = ui.trialError,
        realmEntryError = ui.realmEntryError,
        onDismissTrialError = { ui.trialError = null },
        onDismissRealmError = { ui.realmEntryError = null }
    )
}

/** 覆层弹窗：通关奖励 + 天道试炼/秘境错误提示 */
@Composable
private fun LizhanFloatingDialogs(
    showClearRewards: Boolean,
    trialViewModel: HeavenlyTrialViewModel,
    viewModel: GameViewModel,
    trialError: String?,
    realmEntryError: String?,
    onDismissTrialError: () -> Unit,
    onDismissRealmError: () -> Unit
) {
    if (showClearRewards) {
        val ts by trialViewModel.trialState.collectAsStateWithLifecycle()
        val cl by trialViewModel.claimableLevels.collectAsStateWithLifecycle()
        HeavenlyTrialClearRewardDialog(
            trialState = ts,
            claimableLevels = cl,
            viewModel = viewModel,
            onClaim = { levelIndex ->
                trialViewModel.claimClearReward(levelIndex) { cards ->
                    viewModel.enqueueRewardCards(cards)
                }
            },
            onDismiss = { trialViewModel.dismissClearRewards() }
        )
    }

    trialError?.let { message ->
        StandardPromptDialog(
            onDismissRequest = onDismissTrialError,
            title = "错误",
            text = message,
            confirmLabel = "确定"
        )
    }
    realmEntryError?.let { message ->
        StandardPromptDialog(
            onDismissRequest = onDismissRealmError,
            title = "提示",
            text = message,
            confirmLabel = "确定"
        )
    }
}

/** 历战分支回调集合（打包避免超参数限制） */
private class LizhanBranchCallbacks(
    val onOpenTrialPanel: () -> Unit,
    val onDismissTrialPanel: () -> Unit,
    val onOpenRealmDetail: () -> Unit,
    val onDismissRealmDetail: () -> Unit,
    val onRealmDetailFinished: () -> Unit,
    val onEnterExploration: () -> Unit,
    val onExitExploration: () -> Unit,
    val onFinishedExploration: () -> Unit,
    val onEntryError: (String) -> Unit
)

/** 历战内容分支切换：挑战全屏 / 半屏面板 / 秘境详情 / 探索全屏 / 卡片轮转 */
@Composable
private fun LizhanDialogBranches(
    trialScreen: HeavenlyTrialViewModel.Screen?,
    showTrialPanel: Boolean,
    showRealmDetail: Boolean,
    showRealmExploration: Boolean,
    carousel: LizhanCarouselState,
    callbacks: LizhanBranchCallbacks
) {
    val trialViewModel = hiltViewModel<HeavenlyTrialViewModel>()
    val realmViewModel = hiltViewModel<SecretRealmViewModel>()
    val gameViewModel = hiltViewModel<GameViewModel>()
    val scope = rememberCoroutineScope()
    val isTrialChallenge =
        trialScreen !is HeavenlyTrialViewModel.Screen.Panel
    val realmState by realmViewModel.realmState.collectAsStateWithLifecycle()

    when {
        isTrialChallenge -> TrialChallengeBranch(
            screen = trialScreen,
            trialViewModel = trialViewModel
        )
        showTrialPanel -> TrialPanelBranch(
            trialViewModel = trialViewModel,
            onDismissPanel = callbacks.onDismissTrialPanel
        )
        showRealmDetail -> RealmDetailBranch(
            realmState = realmState,
            realmViewModel = realmViewModel,
            gameViewModel = gameViewModel,
            scope = scope,
            callbacks = callbacks
        )
        showRealmExploration -> SecretRealmExplorationScreen(
            viewModel = realmViewModel,
            onExit = callbacks.onExitExploration,
            onFinished = callbacks.onFinishedExploration
        )
        else -> LizhanCarousel(
            // 轮转索引跨分支保留（详情/探索返回后主卡不变）
            carousel = carousel,
            // 秘境开启状态驱动卡片 footer（未开启：红字禁用；开启：进入秘境）
            realmOpen = realmState != null,
            onCardClick = { id ->
                when (id) {
                    "heavenly_trial" -> callbacks.onOpenTrialPanel()
                    // 未开启时卡片点击已被禁用（无反应），此处仅开启后可达
                    "secret_realm" -> callbacks.onOpenRealmDetail()
                }
            }
        )
    }
}

/** 远古秘境详情半屏（复用 WorldMapDialog 同款界面：选人/出发/继续） */
@Composable
private fun RealmDetailBranch(
    realmState: com.xianxia.sect.core.model.SecretRealmState?,
    realmViewModel: SecretRealmViewModel,
    gameViewModel: GameViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
    callbacks: LizhanBranchCallbacks
) {
    val realm = realmState?.let { MapItemMapper.fromSecretRealm(it) }
    if (realm != null) {
        // 仅在详情打开期间订阅（分支内条件收集，关闭即取消）
        val gameData by gameViewModel.gameData.collectAsStateWithLifecycle()
        SecretRealmDetailDialog(
            realm = realm,
            gameData = gameData,
            viewModel = realmViewModel,
            onStart = {
                // 引擎线程回调 → 主线程切界面
                scope.launch { callbacks.onRealmDetailFinished() }
            },
            onContinue = {
                scope.launch { callbacks.onRealmDetailFinished() }
            },
            onDismiss = callbacks.onDismissRealmDetail
        )
    }
}

/** ① 天道试炼挑战全屏（与 ActivityDialog 挑战分支同构） */
@Composable
private fun TrialChallengeBranch(
    screen: HeavenlyTrialViewModel.Screen?,
    trialViewModel: HeavenlyTrialViewModel
) {
    // GameViewModel 为 Activity 级共享实例，与历战对话框同一实例
    val gameViewModel = hiltViewModel<GameViewModel>()
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = GameColors.PageBackground
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(id = R.drawable.bg_horizontal),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            when (val s = screen) {
                is HeavenlyTrialViewModel.Screen.BattlePrep,
                is HeavenlyTrialViewModel.Screen.DiscipleSelect -> {
                    val levelIndex = when (s) {
                        is HeavenlyTrialViewModel.Screen.BattlePrep ->
                            s.levelIndex
                        is HeavenlyTrialViewModel.Screen.DiscipleSelect ->
                            s.levelIndex
                        else -> 0
                    }
                    HeavenlyTrialBattleDialog(
                        levelIndex = levelIndex,
                        viewModel = trialViewModel,
                        gameViewModel = gameViewModel,
                        onDismiss = { trialViewModel.dismiss() }
                    )
                }
                is HeavenlyTrialViewModel.Screen.Combat ->
                    HeavenlyTrialCombatScreen(trialViewModel) { won ->
                        trialViewModel.onCombatFinished(won)
                    }
                else -> Unit
            }
        }
    }
}

/**
 * ② 天道试炼半屏面板：背景图铺满半屏界面（无标题，关闭按钮
 * 在背景图右上角），内容上下左右各留 4dp 留白。
 */
@Composable
private fun TrialPanelBranch(
    trialViewModel: HeavenlyTrialViewModel,
    onDismissPanel: () -> Unit
) {
    UnifiedGameDialog(
        onDismissRequest = onDismissPanel,
        title = "",
        mode = DialogMode.Half,
        backgroundRes = SpriteResRegistry.resolve("heavenly_trial_challenge_bg")
            ?: 0
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
        ) {
            HeavenlyTrialPanel(
                viewModel = trialViewModel,
                onOpenClearRewards = { trialViewModel.openClearRewards() },
                // 背景由宿主对话框提供，关闭面板自带背景避免叠加
                showBackground = false
            )
        }
    }
}

/** ④ 卡片轮转：主卡片居中，副卡片轮廓，翻页带动画 */
@Composable
private fun LizhanCarousel(
    carousel: LizhanCarouselState,
    realmOpen: Boolean,
    onCardClick: (String) -> Unit
) {
    val anim = remember { Animatable(0f) }
    var pendingDirection by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

    // 先动画后提交索引：动画终态与静止布局逐像素一致，无跳变
    // （Animatable 模式参照 RewardCardHost）
    val flip: (Int) -> Unit = { direction ->
        if (pendingDirection == null && carousel.canFlip) {
            pendingDirection = direction
            scope.launch {
                anim.animateTo(
                    direction.toFloat(),
                    tween(FLIP_ANIMATION_MS, easing = FastOutSlowInEasing)
                )
                if (direction > 0) carousel.next() else carousel.prev()
                pendingDirection = null
                anim.snapTo(0f)
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // 内容区中心在窗口中心下方 header/2，上移对齐屏幕几何中心
            .offset(y = VERTICAL_CENTER_COMPENSATION)
    ) {
        // 卡宽 = 屏幕宽 × 30%（内容区宽 + 左右 padding 还原屏幕宽）
        val cardWidth =
            (maxWidth + CONTENT_H_PADDING * 2) * CARD_WIDTH_FRACTION
        // 竖卡：高 = 宽 × 固定宽高比（纯比例，不依赖 maxHeight）
        val cardHeight = cardWidth * CARD_ASPECT_RATIO
        val canFlipNow = pendingDirection == null && carousel.canFlip

        CarouselSlots(
            carousel = carousel,
            animValue = anim.value,
            pendingDirection = pendingDirection,
            layout = CardLayout(cardWidth, cardHeight),
            containerHeight = maxHeight,
            realmOpen = realmOpen,
            onCardClick = onCardClick
        )

        // 翻页按钮后渲染（z 序在卡片之上，保证可见可点）
        FlipButton(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 16.dp),
            spriteName = "ui_flip_left",
            enabled = canFlipNow,
            onClick = { flip(-1) }
        )
        FlipButton(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp),
            spriteName = "ui_flip_right",
            enabled = canFlipNow,
            onClick = { flip(1) }
        )
    }
}

/** 卡片布局尺寸（打包避免超参数限制） */
private data class CardLayout(
    val cardWidth: Dp,
    val cardHeight: Dp
)

/** 卡片视觉参数（整卡/内容透明度，打包避免超参数限制） */
private data class CardVisual(
    val cardAlpha: Float,
    val contentAlpha: Float
)

/** 卡片槽位渲染：静止态 3 槽；动画态 4 槽，按 zIndex 排序主卡后画 */
@Composable
private fun BoxWithConstraintsScope.CarouselSlots(
    carousel: LizhanCarouselState,
    animValue: Float,
    pendingDirection: Int?,
    layout: CardLayout,
    containerHeight: Dp,
    realmOpen: Boolean,
    onCardClick: (String) -> Unit
) {
    val cardWidth = layout.cardWidth
    val cardHeight = layout.cardHeight
    val placements = if (pendingDirection == null) {
        buildStaticPlacements(carousel, cardWidth, containerHeight)
    } else {
        // 动画进度按方向归一化到 [0,1]（右翻 +1 / 左翻 -1）
        val dir = pendingDirection.toFloat()
        val progress = animValue * dir
        buildFlipPlacements(
            carousel, dir, progress, cardWidth, containerHeight
        )
    }

    placements.sortedBy { it.zIndex }.forEach { placement ->
        val card = LIZHAN_CARDS[placement.cardIndex]
        val isCenter = placement.contentAlpha >= CONTENT_VISIBLE_THRESHOLD
        // 未开启的秘境卡不可点击（点击无反应）
        val realmLocked = card.isRealmCard && !realmOpen
        CarouselCard(
            card = card,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = placement.offsetX, y = placement.offsetY),
            layout = layout,
            visual = CardVisual(placement.cardAlpha, placement.contentAlpha),
            realmOpen = realmOpen,
            clickable = isCenter && pendingDirection == null && !realmLocked,
            onClick = { onCardClick(card.id) }
        )
    }
}

/** 单个翻页按钮（素材图，由调用方通过 modifier 定位） */
@Composable
private fun FlipButton(
    modifier: Modifier,
    spriteName: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(modifier = modifier) {
        SpriteImage(
            name = spriteName,
            contentDescription = null,
            modifier = Modifier
                .size(FLIP_BUTTON_SIZE)
                .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
                .clickableWithSound(enabled = enabled, onClick = onClick)
        )
    }
}

/**
 * 单张卡片：背景精灵图 + 内容（图标/标题/副标题，
 * 透明度随槽位过渡）+ 轮廓；秘境卡**卡片内部底部**显示开启状态
 * （未开启红字 / 开启"进入秘境"按钮）。
 */
@Composable
private fun CarouselCard(
    card: LizhanCard,
    modifier: Modifier,
    layout: CardLayout,
    visual: CardVisual,
    realmOpen: Boolean,
    clickable: Boolean,
    onClick: () -> Unit
) {
    val cardAlpha = visual.cardAlpha
    val contentAlpha = visual.contentAlpha
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .width(layout.cardWidth)
                .height(layout.cardHeight)
                .clip(CardCorner)
                .graphicsLayer { alpha = cardAlpha }
                .then(
                    if (clickable) Modifier.clickableWithSound(onClick = onClick)
                    else Modifier
                )
        ) {
            SpriteImage(
                name = "li_zhan_card",
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = contentAlpha },
                horizontalAlignment = Alignment.CenterHorizontally,
                // 内容 + 秘境状态整体垂直居中（footer 紧贴内容下方，位置上移）
                verticalArrangement = Arrangement.Center
            ) {
                CardContent(card = card)
                // 秘境卡开启状态（仅主卡/接近主卡时显示，紧贴内容下方）
                if (card.isRealmCard && contentAlpha >= CONTENT_VISIBLE_THRESHOLD) {
                    RealmCardFooter(
                        realmOpen = realmOpen,
                        contentAlpha = contentAlpha,
                        onClick = onClick
                    )
                }
            }
        }
    }
}

/** 秘境卡正下方状态：未开启红字两行 / 开启"进入秘境"按钮 */
@Composable
private fun RealmCardFooter(
    realmOpen: Boolean,
    contentAlpha: Float,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .graphicsLayer { alpha = contentAlpha },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (realmOpen) {
            GameButton("进入秘境", onClick = onClick)
        } else {
            Text(
                text = "未开启",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Red,
                textAlign = TextAlign.Center
            )
            Text(
                text = "每50年开启一次",
                fontSize = 11.sp,
                color = Color.Red,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** 卡片内容：图标 + 标题 + 副标题（由外层 weight 区域垂直居中） */
@Composable
private fun CardContent(card: LizhanCard) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SpriteImage(
            name = card.icon,
            contentDescription = null,
            modifier = Modifier
                .width(ICON_SIZE_DP)
                .height(ICON_SIZE_DP)
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = card.title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = card.subtitle,
            fontSize = 12.sp,
            color = Color.Black,
            textAlign = TextAlign.Center
        )
    }
}
