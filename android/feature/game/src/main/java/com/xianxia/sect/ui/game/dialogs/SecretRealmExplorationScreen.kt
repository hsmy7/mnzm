package com.xianxia.sect.ui.game.dialogs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.battle.BattleLogData
import com.xianxia.sect.core.engine.domain.battle.BattleRoundData
import com.xianxia.sect.core.model.BattleLogAction
import com.xianxia.sect.core.model.BattleLogRound
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.SecretRealmBackpack
import com.xianxia.sect.core.model.SecretRealmEventRecord
import com.xianxia.sect.core.model.SecretRealmExplorationSession
import com.xianxia.sect.core.model.SecretRealmMemberState
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.SpriteImage
import com.xianxia.sect.ui.components.SpriteResRegistry
import com.xianxia.sect.ui.game.SecretRealmViewModel
import com.xianxia.sect.ui.theme.ButtonSizes
import com.xianxia.sect.ui.theme.GameColors
import kotlinx.coroutines.delay

/** 末战回放写入缓冲（ms）：引擎回调写入 combatLog 晚于会话清空，等待写回窗口再关闭界面 */
private const val BATTLE_LOG_WRITE_GRACE_MS = 300L

/** 战斗回合播放间隔（ms）：每秒 2 回合 */
private const val BATTLE_ROUND_DELAY_MS = 500L

/** 全部回合播完后切换到衔接事件前的停顿（ms） */
private const val BATTLE_END_PAUSE_MS = 1000L

/** 妖兽类型名 → 精灵图名（与 GameConfig.Beast.TYPES 显式对应，防止索引错位/新增类型误配） */
private val beastSpriteNames = mapOf(
    "虎妖" to "tiger", "狼妖" to "wolf", "蛇妖" to "snake", "熊妖" to "bear",
    "鹰妖" to "eagle", "狐妖" to "fox", "龙妖" to "dragon", "龟妖" to "turtle"
)

/** 探索成员 UI 状态 */
@androidx.compose.runtime.Immutable
private data class MemberHpUi(
    val id: String,
    val name: String,
    val portraitRes: String,
    val realmName: String,
    val currentHp: Int,
    val maxHp: Int,
    val isDying: Boolean,
    val isDead: Boolean
)

/**
 * 远古秘境探索全屏界面。
 *
 * 布局：全屏背景图（secret_realm_bg）/ 米色纯色面板（与消息栏展开态同色）内含事件
 * 内容区（事件视图或战斗播放视图；右上角叠加体力与跳过按钮）与选择区（结束探索+
 * 选择选项）/ 选项卡片覆盖层（进入界面/新事件自动弹出，可收起）/ 底部左侧 4 弟子列
 * （间距 5dp）+ 右侧背包按钮。
 */
@Composable
fun SecretRealmExplorationScreen(
    viewModel: SecretRealmViewModel,
    onExit: () -> Unit,
    onFinished: () -> Unit
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val disciples by viewModel.disciples.collectAsStateWithLifecycle()

    // 选项卡片：事件信息逐行显示完成后自动弹出（初始不弹，等待逐行播放）
    var showOptions by remember { mutableStateOf(false) }
    var showBackpack by remember { mutableStateOf(false) }
    // 战斗播放数据（chooseOption 返回的战斗日志）
    var combatLog by remember { mutableStateOf<BattleLogData?>(null) }
    // 战斗场景标题（如"远离妖兽被发现"/"发起战斗"/"尝试偷袭"，由所选选项决定）
    var combatTitle by remember { mutableStateOf<String?>(null) }
    var skipCombat by remember { mutableStateOf(false) }
    // 选项请求锁：引擎事务完成前禁止再次选择（对抗性审查 M2 连点防重）
    var choosing by remember { mutableStateOf(false) }
    // 首帧会话标记：避免初始 null 一帧误触发关闭（对抗性审查 B10）
    var hasSessionBefore by remember { mutableStateOf(false) }

    // 进入探索界面：暂停游戏时间；退出（暂存/结束）：恢复
    LaunchedEffect(Unit) { viewModel.enterExploration() }
    DisposableEffect(Unit) {
        onDispose { viewModel.exitExploration() }
    }

    // 探索会话结束（主动结束/体力耗尽/全灭）→ 通知宿主关闭
    // 末战回放保留：会话已清但战斗日志仍在播放时暂不关闭（对抗性审查 B5）
    // 竞态防护：引擎回调写入 combatLog 晚于会话清空（跨线程），等待写回窗口再决定关闭，
    // 避免末战回放偶发丢失、界面直接关闭
    LaunchedEffect(session, combatLog) {
        if (session != null) {
            hasSessionBefore = true
        } else if (combatLog == null && hasSessionBefore) {
            delay(BATTLE_LOG_WRITE_GRACE_MS)
            if (combatLog == null) {
                showOptions = false
                onFinished()
            }
        }
    }

    // 返回 = 暂存退出（会话保留，下次详情界面"继续探索"）
    BackHandler { onExit() }

    val memberUis = remember(session, disciples) {
        session?.members?.map { ms -> ms.toMemberHpUi(disciples) } ?: emptyList()
    }
    val event = session?.currentEvent
    val stamina = session?.stamina ?: 0

    // 当前事件逐行播放完成标记：新事件重置；收起卡片重新查看事件时直接全量显示（不重复播放）
    var eventLinesShown by remember { mutableStateOf(false) }
    LaunchedEffect(event) { eventLinesShown = false }

    // 战斗播放推进：每秒 2 回合；全部回合播完停顿 1 秒再切换衔接事件（跳过则不等待）
    var playedRounds by remember(combatLog) { mutableIntStateOf(0) }
    LaunchedEffect(combatLog, skipCombat) {
        val log = combatLog ?: return@LaunchedEffect
        while (playedRounds < log.rounds.size && !skipCombat) {
            delay(BATTLE_ROUND_DELAY_MS)
            playedRounds++
        }
        if (!skipCombat) {
            // 全部回合播放完成：停顿 1 秒展示战果，再切换到衔接事件
            delay(BATTLE_END_PAUSE_MS)
        }
        // 播放完成或跳过：结算已完成，显示衔接事件（逐行播放完成后自动弹出选项卡片）
        combatLog = null
        combatTitle = null
        skipCombat = false
        playedRounds = 0
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = GameColors.PageBackground
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 全屏背景图（保留）
            Image(
                painter = painterResource(
                    id = SpriteResRegistry.resolve("secret_realm_bg") ?: 0
                ),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )

            Column(modifier = Modifier.fillMaxSize()) {
                // ===== 米色纯色面板 + 面板下方操作行（左右留 10%、上方留 15%） =====
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    // 面板上方留 15%（提前捕获，供嵌套作用域使用）
                    val panelTopPadding = maxHeight * 0.15f
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth(0.8f)
                            .fillMaxHeight(1f)
                    ) {
                        // 米色纯色面板（与消息栏展开态同色）：上方留 15%
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(top = panelTopPadding)
                                .background(SecretRealmBackground)
                        ) {
                            // 中央事件内容区（右上角叠加体力与跳过按钮；卡片弹出时不显示事件文字）
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                val currentCombatLog = combatLog
                                if (currentCombatLog != null) {
                                    CombatPlaybackContent(
                                        title = combatTitle ?: "发生战斗",
                                        log = currentCombatLog,
                                        playedRounds = playedRounds
                                    )
                                } else if (!showOptions && event != null) {
                                    // 卡片弹出时事件内容区不显示任何内容（仅保留右上角体力）
                                    EventContent(
                                        event = event,
                                        alreadyShown = eventLinesShown,
                                        onLinesShown = {
                                            eventLinesShown = true
                                            showOptions = true
                                        }
                                    )
                                }

                                // ===== 右上角：体力（无背景）+ 战斗播放时的跳过按钮 =====
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(end = 10.dp, top = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (currentCombatLog != null) {
                                        GameButton(
                                            text = "跳过",
                                            width = ButtonSizes.StandardWidth,
                                            height = ButtonSizes.StandardHeight,
                                            onClick = { skipCombat = true }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text(
                                        // 篡改档防御：体力显示 clamp 到正常范围
                                        text = "体力:${stamina.coerceIn(0, GameConfig.SecretRealm.STAMINA_MAX)}",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (stamina <= 5) Color(0xFFF44336) else Color.Black
                                    )
                                }
                            }

                        }
                    }
                }

                // ===== 底部（背景图上）：弟子头像列（左下）+ 结束探索/选择选项（屏幕水平居中、面板与底部间垂直居中）+ 背包按钮（右下） =====
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                ) {
                    // 4 弟子横排（左下）
                    Row(
                        modifier = Modifier.align(Alignment.BottomStart),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        // 篡改档防御：最多显示队伍上限人数，防止底部行水平溢出
                        memberUis.take(GameConfig.SecretRealm.TEAM_SIZE).forEach { member ->
                            MemberColumn(member = member)
                        }
                    }
                    // 结束探索 / 选择选项（屏幕水平居中；卡片弹出时隐藏）
                    // 会话活跃时始终显示"结束探索"（含 event 异常的篡改档兜底，防软锁）
                    if (!showOptions && combatLog == null && (event != null || session != null)) {
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GameButton(
                                text = "结束探索",
                                width = ButtonSizes.StandardWidth,
                                height = ButtonSizes.StandardHeight,
                                onClick = {
                                    showOptions = false
                                    viewModel.endExploration(onDone = { /* 会话清空后 LaunchedEffect 触发 onFinished */ })
                                }
                            )
                            if (event != null) {
                                Spacer(modifier = Modifier.width(16.dp))
                                GameButton(
                                    text = "选择选项",
                                    width = ButtonSizes.StandardWidth,
                                    height = ButtonSizes.StandardHeight,
                                    onClick = {
                                        // 播放中断标记：收起后不再从头重播该事件
                                        eventLinesShown = true
                                        showOptions = true
                                    }
                                )
                            }
                        }
                    }
                    // 背包按钮（右下）
                    GameButton(
                        modifier = Modifier.align(Alignment.BottomEnd),
                        text = "背包",
                        width = ButtonSizes.StandardWidth,
                        height = ButtonSizes.StandardHeight,
                        onClick = { showBackpack = true }
                    )
                }
            }

            // ===== 选项卡片覆盖层（覆盖全屏，卡片+收起按钮整体居中与屏幕完全对称） =====
            if (showOptions && event != null && combatLog == null) {
                OptionsOverlay(
                    options = event.options,
                    onSelect = { index ->
                        if (!choosing) {
                            choosing = true
                            // 提前重置播放标记（新事件到达前），避免新事件内容闪现全量一帧
                            eventLinesShown = false
                            showOptions = false
                            viewModel.chooseOption(index) { result ->
                                choosing = false
                                val success = result as? com.xianxia.sect.core.engine.domain.exploration.SecretRealmChoiceResult.Success
                                if (success != null && success.enteredCombat && success.combatLog != null) {
                                    combatTitle = combatTitleFor(index, success.ambushSucceeded)
                                    combatLog = success.combatLog
                                    skipCombat = false
                                }
                            }
                        }
                    },
                    onCollapse = { showOptions = false }
                )
            }
        }
    }

    // ===== 背包弹窗 =====
    if (showBackpack) {
        SecretRealmBackpackDialog(
            backpack = session?.backpack ?: com.xianxia.sect.core.model.SecretRealmBackpack(),
            onDismiss = { showBackpack = false }
        )
    }
}

// ── 子组件 ────────────────────────────────────────────────────────────

/** 事件视图：信息逐行显示（标题立即显示、内容延迟 1 秒），全部显示完后回调 [onLinesShown]（用于自动弹出选项卡片） */
@Composable
private fun EventContent(
    event: com.xianxia.sect.core.model.SecretRealmEventRecord,
    alreadyShown: Boolean,
    onLinesShown: () -> Unit
) {
    // 逐行显示：第 1 行（标题）立即显示，第 2 行（内容块）1 秒后显示；
    // 全部显示完毕后再延迟 1 秒自动弹出选项卡片
    // 未来新增事件类型沿用该逐行机制，只需扩展内容块（第 2 行）
    var visibleLines by remember(event, alreadyShown) {
        mutableIntStateOf(if (alreadyShown) EVENT_LINE_COUNT else 1)
    }
    LaunchedEffect(event, alreadyShown) {
        if (alreadyShown) return@LaunchedEffect
        while (visibleLines < EVENT_LINE_COUNT) {
            delay(1000)
            visibleLines++
        }
        // 完整信息显示完毕后延迟 1 秒，再触发自动弹出选项卡片
        delay(1000)
        onLinesShown()
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            // 第 1 行：事件标题（居中、置顶）
            if (visibleLines >= 1) {
                Text(
                    text = event.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            // 第 2 行：内容块（妖兽事件 = 精灵图+境界视为一行；其他 = 描述）
            if (visibleLines >= 2) {
                if (event.params.beastTypeName.isNotEmpty()) {
                    BeastEventContent(event = event)
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = event.description,
                        fontSize = 14.sp,
                        color = Color.Black,
                        lineHeight = 20.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/** 妖兽事件内容块：妖兽精灵图 + 境界（如"化神一层"），视为一行显示 */
@Composable
private fun BeastEventContent(event: com.xianxia.sect.core.model.SecretRealmEventRecord) {
    Spacer(modifier = Modifier.height(10.dp))
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        SpriteImage(
            name = beastSpriteNames[event.params.beastTypeName] ?: "tiger",
            contentDescription = null,
            modifier = Modifier.size(72.dp)
        )
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        // 篡改档防御：层数 clamp 到 1..9，避免显示"XX0层/XX99层"
        text = "${com.xianxia.sect.core.GameConfig.Realm.getName(event.params.beastRealm)}" +
            "${event.params.beastLayer.coerceIn(
                1, com.xianxia.sect.core.GameConfig.SecretRealm.BEAST_LAYER_VARIANT_COUNT
            )}层",
        fontSize = 12.sp,
        color = Color.Black,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

/** 事件信息行数：标题（第 1 行，立即显示）+ 内容块（第 2 行，延迟 1 秒） */
private const val EVENT_LINE_COUNT = 2

/** 战斗场景标题：选项索引（0=远离 / 1=战斗 / 2=偷袭，与妖兽事件选项顺序一致）→ 触发战斗的具体场景 */
private fun combatTitleFor(optionIndex: Int, ambushSucceeded: Boolean): String = when (optionIndex) {
    0 -> "远离妖兽被发现"
    1 -> "发起战斗"
    else -> if (ambushSucceeded) "偷袭成功" else "偷袭失败"
}

/** 战斗播放视图：场景标题 + 战斗消息栏（逐回合日志，与战斗日志弹窗显示一致；
 * 跳过按钮由外层与体力并排渲染；消息栏短内容居中、超长内容封顶滚动） */
@Composable
private fun CombatPlaybackContent(
    title: String,
    log: BattleLogData,
    playedRounds: Int
) {
    Column(
        // 顶部留出右上角"跳过 + 体力"按钮区（38dp 按钮 + 4dp 内边距）
        modifier = Modifier.fillMaxSize().padding(start = 10.dp, top = 48.dp, end = 10.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.Top
    ) {
        // 战斗场景标题（清晰显示触发原因，如"远离妖兽被发现"）
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        // 战斗消息栏：短内容按内容高度包裹并整体居中；超长时封顶剩余高度内部滚动
        val listState = rememberScrollState()
        val visibleRounds = log.rounds.take(playedRounds)
        LaunchedEffect(visibleRounds.size) {
            if (visibleRounds.isNotEmpty()) {
                listState.animateScrollTo(listState.maxValue)
            }
        }
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(Alignment.CenterVertically)
                    .verticalScroll(listState),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                visibleRounds.forEach { round ->
                    BattleRoundItem(round = round.toBattleLogRound())
                }
            }
        }
    }
}

/** 引擎战斗回合数据 → 战斗日志模型（复用战斗日志弹窗的回合显示组件） */
private fun BattleRoundData.toBattleLogRound(): BattleLogRound = BattleLogRound(
    roundNumber = roundNumber,
    actions = actions.map { a ->
        BattleLogAction(
            type = a.type, attacker = a.attacker, attackerType = a.attackerType,
            target = a.target, damage = a.damage, damageType = a.damageType,
            isCrit = a.isCrit, isKill = a.isKill, message = a.message,
            skillName = a.skillName
        )
    }
)

/** 弟子列：血量条/状态（上）→ 圆形头像 → 名称（下） */
@Composable
private fun MemberColumn(member: MemberHpUi) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            member.isDead -> Text(
                text = "已陨落",
                fontSize = 9.sp,
                color = Color(0xFFF44336)
            )
            member.isDying -> Text(
                text = "重伤濒死",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF44336)
            )
            else -> SecretRealmHpBar(
                currentHp = member.currentHp,
                maxHp = member.maxHp
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        SecretRealmPortrait(
            portraitRes = member.portraitRes,
            size = 40,
            isDead = member.isDead
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = member.name,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun SecretRealmMemberState.toMemberHpUi(
    disciples: List<DiscipleAggregate>
): MemberHpUi {
    val d = disciples.find { it.id == discipleId }
    return MemberHpUi(
        id = discipleId,
        name = name,
        portraitRes = portraitRes,
        realmName = realmName,
        currentHp = currentHp,
        // 战斗口径 maxHp 优先（与战斗写回/休整恢复同口径），旧档 0 回退基础装配值
        maxHp = maxHp.takeIf { it > 0 } ?: (d?.maxHp ?: 100),
        isDying = isDying,
        isDead = isDead
    )
}

/** 选项卡片覆盖层：卡片并排（间距 8dp）+ 收起按钮整体垂直居中（与屏幕完全对称），浮于面板之上 */
@Composable
private fun OptionsOverlay(
    options: List<com.xianxia.sect.core.model.SecretRealmOption>,
    onSelect: (Int) -> Unit,
    onCollapse: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // 卡片高度 = 覆盖区域高度 × 65%（提前捕获，供嵌套作用域使用）
        val cardHeight = maxHeight * OPTION_CARD_HEIGHT_RATIO
        // 卡片槽位宽 =（覆盖区宽 - 左右边距 - 卡片间距 × (n-1)）/ n
        // （防篡改档空列表除零 + 超长选项列表导致槽位为负）
        val slotWidth = ((maxWidth - OPTION_OVERLAY_PADDING * 2 -
            OPTION_CARD_SPACING * (maxOf(1, options.size) - 1)) / maxOf(1, options.size))
            .coerceAtLeast(0.dp)
        // 精灵图按 Fit 缩放：槽位宽高比超过图片宽高比时图形横向留白，卡片宽度
        // 限定为图形实际绘制宽度，文字换行宽度随之限定，永不出卡片左右
        val cardWidth = minOf(slotWidth, cardHeight * OPTION_CARD_IMG_ASPECT)
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = OPTION_OVERLAY_PADDING),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                // 卡片宽度小于槽位时整体居中分布
                horizontalArrangement = Arrangement.spacedBy(
                    OPTION_CARD_SPACING, Alignment.CenterHorizontally
                )
            ) {
                options.forEachIndexed { index, option ->
                    SecretRealmOptionCard(
                        modifier = Modifier
                            .width(cardWidth)
                            .height(cardHeight),
                        label = option.label,
                        description = option.description,
                        staminaCost = option.staminaCost,
                        onClick = { onSelect(index) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            // 收起选项按钮（中间卡片正下方）
            GameButton(
                text = "收起选项",
                width = ButtonSizes.StandardWidth,
                height = ButtonSizes.StandardHeight,
                onClick = onCollapse
            )
        }
    }
}

/** 选项卡片高度 = 覆盖区域高度 × 65% */
private const val OPTION_CARD_HEIGHT_RATIO = 0.65f

/** 选项卡片精灵图宽高比（secret_realm_option_card.webp = 796×1535），Fit 缩放横向留白阈值 */
private const val OPTION_CARD_IMG_ASPECT = 796f / 1535f

/** 选项覆盖层左右边距 */
private val OPTION_OVERLAY_PADDING = 16.dp

/** 选项卡片间距 */
private val OPTION_CARD_SPACING = 8.dp
