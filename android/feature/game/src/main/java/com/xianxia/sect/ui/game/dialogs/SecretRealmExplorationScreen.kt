package com.xianxia.sect.ui.game.dialogs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xianxia.sect.core.engine.domain.battle.BattleLogData
import com.xianxia.sect.core.engine.domain.battle.BattleRoundData
import com.xianxia.sect.core.model.BattleLogRound
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.SecretRealmBackpack
import com.xianxia.sect.core.model.SecretRealmEventRecord
import com.xianxia.sect.core.model.SecretRealmExplorationSession
import com.xianxia.sect.core.model.SecretRealmMemberState
import com.xianxia.sect.core.util.PortraitPool
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.GameButton
import com.xianxia.sect.ui.components.SpriteImage
import com.xianxia.sect.ui.components.SpriteResRegistry
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.ui.game.SecretRealmViewModel
import com.xianxia.sect.ui.theme.ButtonSizes
import com.xianxia.sect.ui.theme.GameColors
import kotlinx.coroutines.delay

private val beastSpriteNames =
    listOf("tiger", "wolf", "snake", "bear", "eagle", "fox", "dragon", "turtle")

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
 * 布局：右上体力 / 中央事件内容区（事件视图或战斗播放视图）/ 选择区（结束探索+选择选项）
 * / 底部左侧背包按钮 + 4 弟子列（名称/圆形头像/血量条或濒死红字）。
 */
@Composable
fun SecretRealmExplorationScreen(
    viewModel: SecretRealmViewModel,
    onExit: () -> Unit,
    onFinished: () -> Unit
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val disciples by viewModel.disciples.collectAsStateWithLifecycle()

    var showOptions by remember { mutableStateOf(false) }
    var showBackpack by remember { mutableStateOf(false) }
    // 战斗播放数据（chooseOption 返回的战斗日志）
    var combatLog by remember { mutableStateOf<BattleLogData?>(null) }
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
    LaunchedEffect(session, combatLog) {
        if (session != null) {
            hasSessionBefore = true
        } else if (combatLog == null && hasSessionBefore) {
            showOptions = false
            onFinished()
        }
    }

    // 返回 = 暂存退出（会话保留，下次详情界面"继续探索"）
    BackHandler { onExit() }

    val memberUis = remember(session, disciples) {
        session?.members?.map { ms -> ms.toMemberHpUi(disciples) } ?: emptyList()
    }
    val event = session?.currentEvent
    val stamina = session?.stamina ?: 0

    // 战斗播放推进：每秒 2 回合（500ms/回合），播完或跳过 → 清除播放态显示衔接事件
    var playedRounds by remember(combatLog) { mutableIntStateOf(0) }
    LaunchedEffect(combatLog, skipCombat) {
        val log = combatLog ?: return@LaunchedEffect
        while (playedRounds < log.rounds.size && !skipCombat) {
            delay(500)
            playedRounds++
        }
        // 播放完成或跳过：结算已完成，直接进入衔接事件
        combatLog = null
        skipCombat = false
        playedRounds = 0
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = GameColors.PageBackground
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 背景
            Image(
                painter = painterResource(
                    id = com.xianxia.sect.ui.components.SpriteResRegistry.resolve("secret_realm_bg")
                        ?: 0
                ),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )

            Column(modifier = Modifier.fillMaxSize()) {
                // ===== 顶部：体力显示（右上角） =====
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "体力:$stamina",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (stamina <= 5) Color(0xFFF44336) else Color.White,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0x66000000))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                // ===== 中央事件内容区 =====
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(SecretRealmEventBackground)
                ) {
                    val currentCombatLog = combatLog
                    if (currentCombatLog != null) {
                        CombatPlaybackContent(
                            log = currentCombatLog,
                            playedRounds = playedRounds,
                            onSkip = { skipCombat = true }
                        )
                    } else if (event != null) {
                        EventContent(event = event, resultMessage = session?.resultMessage ?: "")
                    }
                }

                // ===== 选择区（战斗播放时隐藏） =====
                if (combatLog == null && event != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center
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
                        Spacer(modifier = Modifier.width(16.dp))
                        GameButton(
                            text = "选择选项",
                            width = ButtonSizes.StandardWidth,
                            height = ButtonSizes.StandardHeight,
                            onClick = { showOptions = true }
                        )
                    }
                }

                // ===== 底部左侧：背包按钮 + 4 弟子列 =====
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    // 背包按钮（左下角）
                    GameButton(
                        text = "背包",
                        width = ButtonSizes.StandardWidth,
                        height = ButtonSizes.StandardHeight,
                        onClick = { showBackpack = true }
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    // 4 弟子横排
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        memberUis.forEach { member ->
                            MemberColumn(member = member)
                        }
                    }
                }
            }
        }
    }

    // ===== 选项卡片弹窗 =====
    if (showOptions && event != null && combatLog == null) {
        SecretRealmOptionsDialog(
            options = event.options,
            onSelect = { index ->
                if (choosing) return@SecretRealmOptionsDialog
                choosing = true
                showOptions = false
                viewModel.chooseOption(index) { result ->
                    choosing = false
                    val success = result as? com.xianxia.sect.core.engine.domain.exploration.SecretRealmChoiceResult.Success
                    if (success != null && success.enteredCombat && success.combatLog != null) {
                        combatLog = success.combatLog
                        skipCombat = false
                    }
                }
            },
            onDismiss = { showOptions = false }
        )
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

/** 事件视图：标题 / 描述 / 上次结果反馈 */
@Composable
private fun EventContent(
    event: com.xianxia.sect.core.model.SecretRealmEventRecord,
    resultMessage: String
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = event.title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = event.description,
            fontSize = 14.sp,
            color = Color.White,
            lineHeight = 20.sp
        )
        if (resultMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = resultMessage,
                fontSize = 12.sp,
                color = Color(0xFFFFEB3B),
                lineHeight = 17.sp
            )
        }
    }
}

/** 战斗播放视图：第一行玩家槽位 / 第二行敌人槽位 / 第三行回合日志（右上角跳过） */
@Composable
private fun CombatPlaybackContent(
    log: BattleLogData,
    playedRounds: Int,
    onSkip: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Top
        ) {
            // 第一行：玩家队伍槽位
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                log.teamMembers.forEach { m ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(60.dp)
                    ) {
                        Text(
                            text = m.name,
                            fontSize = 9.sp,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        if (m.isAlive) {
                            val resId = portraitResId(m.portraitRes)
                            if (resId != 0) {
                                Image(
                                    painter = painterResource(id = resId),
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        } else {
                            Text(text = "阵亡", fontSize = 9.sp, color = Color(0xFFF44336))
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        BattleHpBar(hp = m.hp, maxHp = m.maxHp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            // 第二行：敌人槽位（妖兽）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                log.enemies.forEach { e ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(60.dp)
                    ) {
                        Text(
                            text = e.name,
                            fontSize = 9.sp,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        val beastIndex = e.portraitRes.removePrefix("beast_").toIntOrNull() ?: 0
                        SpriteImage(
                            name = beastSpriteNames.getOrElse(beastIndex) { "tiger" },
                            contentDescription = null,
                            modifier = Modifier.size(30.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        BattleHpBar(hp = e.hp, maxHp = e.maxHp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            // 第三行：战斗回合日志（滚动）
            val listState = rememberLazyListState()
            val visibleRounds = log.rounds.take(playedRounds)
            LaunchedEffect(playedRounds) {
                if (visibleRounds.isNotEmpty()) {
                    listState.animateScrollToItem(visibleRounds.size - 1)
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0x40000000))
            ) {
                itemsIndexed(visibleRounds) { _, round ->
                    Text(
                        text = buildRoundText(round),
                        fontSize = 11.sp,
                        color = Color.White,
                        lineHeight = 15.sp
                    )
                }
            }
        }
        // 右上角跳过按钮
        GameButton(
            text = "跳过",
            width = ButtonSizes.StandardWidth,
            height = ButtonSizes.StandardHeight,
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            onClick = onSkip
        )
    }
}

private fun buildRoundText(round: BattleRoundData): String {
    val actions = round.actions.joinToString("；") { a ->
        if (a.message.isNotBlank()) a.message
        else "${a.attacker} 攻击 ${a.target}（伤害 ${a.damage}）"
    }
    return "第${round.roundNumber}回合：$actions"
}

@Composable
private fun BattleHpBar(hp: Int, maxHp: Int) {
    val effectiveMax = if (maxHp > 0) maxHp else 1
    val percent = (hp.toFloat() / effectiveMax).coerceIn(0f, 1f)
    val color = when {
        percent > 0.5f -> Color(0xFF4CAF50)
        percent > 0.25f -> Color(0xFFFFEB3B)
        else -> Color(0xFFF44336)
    }
    Box(
        modifier = Modifier.width(44.dp).height(5.dp).clip(RoundedCornerShape(2.dp))
            .background(Color.DarkGray)
    ) {
        Box(
            modifier = Modifier.fillMaxHeight().fillMaxWidth(fraction = percent)
                .background(color, RoundedCornerShape(2.dp))
        )
    }
}

private fun portraitResId(portraitRes: String): Int {
    if (portraitRes.isNotBlank()) {
        val id = PortraitPool.getResourceId(portraitRes)
        if (id != 0) return id
    }
    return SpriteResRegistry.resolve("disciple_portrait") ?: 0
}

/** 弟子列：名称（上）→ 圆形头像 → 血量条（濒死红色文字代替） */
@Composable
private fun MemberColumn(member: MemberHpUi) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = member.name,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        SecretRealmPortrait(
            portraitRes = member.portraitRes,
            size = 40,
            isDead = member.isDead
        )
        Spacer(modifier = Modifier.height(2.dp))
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
        maxHp = d?.maxHp ?: 100,
        isDying = isDying,
        isDead = isDead
    )
}

/** 选项卡片弹窗（卡片居中显示） */
@Composable
private fun SecretRealmOptionsDialog(
    options: List<com.xianxia.sect.core.model.SecretRealmOption>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "选择行动",
        mode = DialogMode.Auto,
        scrollableContent = false
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEachIndexed { index, option ->
                SecretRealmOptionCard(
                    label = option.label,
                    description = option.description,
                    onClick = { onSelect(index) }
                )
            }
        }
    }
}
