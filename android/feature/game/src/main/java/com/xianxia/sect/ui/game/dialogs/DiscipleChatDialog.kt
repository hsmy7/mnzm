package com.xianxia.sect.ui.game.dialogs

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.SkillStats
import com.xianxia.sect.core.util.PortraitPool
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.PortraitImage
import com.xianxia.sect.ui.components.SpriteResRegistry
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.core.engine.chatDraw
import com.xianxia.sect.core.engine.chatDrawDouble
import com.xianxia.sect.core.engine.GameEngine
import com.xianxia.sect.core.util.PresentationRandom
import com.xianxia.sect.ui.game.GameViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import com.xianxia.sect.ui.theme.GameColors

private val GOLD = GameColors.Gold
private val RED = Color(0xFFFF4444)

data class ConversationEffect(
    val moralityDelta: Int = 0,
    val loyaltyDelta: Int = 0,
    val cultivationDelta: Double = 0.0,
    val intelligenceDelta: Int = 0
) {
    val isZero: Boolean
        get() = moralityDelta == 0 && loyaltyDelta == 0 && cultivationDelta == 0.0 && intelligenceDelta == 0

    val isPositive: Boolean get() = loyaltyDelta > 0 || moralityDelta > 0 || intelligenceDelta > 0 || cultivationDelta >
        0.0
    val isNegative: Boolean get() = loyaltyDelta < 0 || moralityDelta < 0 || intelligenceDelta < 0 || cultivationDelta <
        0.0

    fun toDisplayText(): String = buildString {
        if (loyaltyDelta != 0) append("忠诚 ${if (loyaltyDelta > 0) "+" else ""}$loyaltyDelta  ")
        if (moralityDelta != 0) append("道德 ${if (moralityDelta > 0) "+" else ""}$moralityDelta  ")
        if (intelligenceDelta != 0) append("智力 ${if (intelligenceDelta > 0) "+" else ""}$intelligenceDelta  ")
        if (cultivationDelta != 0.0) {
            val pct = (cultivationDelta * 100).toInt()
            append("修为 ${if (cultivationDelta > 0) "+" else ""}$pct%  ")
        }
    }.trimEnd()
}

data class Outcome(
    val replyVariants: List<String>,
    val nextNodeId: String,
    val endingTextVariants: List<String> = emptyList(),
    val effects: ConversationEffect? = null
)

data class ConversationOption(val text: String, val outcomes: List<Outcome>)
data class ConversationNode(val id: String, val options: List<ConversationOption>)
data class ConversationTree(
    val greetingVariants: List<String>,
    val rootNodeId: String,
    val nodes: Map<String, ConversationNode>
)

const val END_NODE = "__END__"

// ═══ 对话树 ═══

private val TREE_A = ConversationTree(
    greetingVariants = listOf("宗主，弟子修炼遇到瓶颈，始终无法突破当前境界。", "宗主，弟子日夜苦修，却感觉进境缓慢。"),
    rootNodeId = "A_2",
    nodes = mapOf(
        "A_2" to ConversationNode(id = "A_2", options = listOf(
            ConversationOption("——修炼之道贵在循序渐进，打好根基", outcomes = listOf(
                Outcome(replyVariants = listOf("宗主说的是，是弟子心急了。"), nextNodeId = "A_3"),
                Outcome(replyVariants = listOf("宗主一席话，弟子豁然开朗。"), nextNodeId = "A_3"))),
            ConversationOption("——我看是你不够刻苦，需加倍努力", outcomes = listOf(
                Outcome(replyVariants = listOf("宗主教训的是！弟子知错了。"), nextNodeId = "A_3"),
                Outcome(replyVariants = listOf("弟子明白了，定不负宗主期望。"), nextNodeId = "A_3"))),
            ConversationOption("——瓶颈是积累已够，该去藏经阁了", outcomes = listOf(
                Outcome(replyVariants = listOf("多谢宗主指点！弟子这就去藏经阁。"), nextNodeId = "A_3"),
                Outcome(replyVariants = listOf("宗主一语惊醒梦中人！"), nextNodeId = "A_3")))
        )),
        "A_3" to ConversationNode(id = "A_3", options = listOf(
            ConversationOption("——去吧，若还有疑惑随时来问", outcomes = listOf(
                Outcome(replyVariants = listOf("多谢宗主关怀！"), nextNodeId = END_NODE,
                    endingTextVariants = listOf("弟子铭记宗主恩情！", "宗主待弟子恩重如山！"),
                    effects = ConversationEffect(loyaltyDelta = 1)),
                Outcome(replyVariants = listOf("弟子知道了。"), nextNodeId = END_NODE,
                    endingTextVariants = listOf("弟子这便去了。"),
                    effects = ConversationEffect(moralityDelta = 1)))),
            ConversationOption("——修行之事不可假手于人，自己去悟", outcomes = listOf(
                Outcome(replyVariants = listOf("是，弟子明白了。"), nextNodeId = END_NODE,
                    endingTextVariants = listOf("弟子告退。", "弟子去了。"),
                    effects = ConversationEffect(loyaltyDelta = -1)),
                Outcome(replyVariants = listOf("宗主说得对，弟子受教了。"), nextNodeId = END_NODE,
                    endingTextVariants = listOf("弟子定当努力参悟。"),
                    effects = ConversationEffect(intelligenceDelta = 1)))),
            ConversationOption("——为师可指点你一二", outcomes = listOf(
                Outcome(replyVariants = listOf("多谢宗主指点！"), nextNodeId = END_NODE,
                    endingTextVariants = listOf("宗主之恩，弟子没齿难忘！"),
                    effects = ConversationEffect(intelligenceDelta = 1)),
                Outcome(replyVariants = listOf("宗主说的是，弟子记下了。"), nextNodeId = END_NODE,
                    endingTextVariants = listOf("弟子感激不尽。"),
                    effects = ConversationEffect(moralityDelta = 1))))
        ))
    )
)

private val TREE_B = ConversationTree(
    greetingVariants = listOf("宗主，弟子有一事不知当讲不当讲……", "宗主，弟子近日与一位师兄起了争执。"),
    rootNodeId = "B_2",
    nodes = mapOf(
        "B_2" to ConversationNode(id = "B_2", options = listOf(
            ConversationOption("——但说无妨，为师替你做主", outcomes = listOf(
                Outcome(replyVariants = listOf("那位师兄说我偷学他的功法……"), nextNodeId = "B_3"),
                Outcome(replyVariants = listOf("有宗主这句话，弟子就放心了。"), nextNodeId = "B_3"))),
            ConversationOption("——空口无凭，拿证据来", outcomes = listOf(
                Outcome(replyVariants = listOf("弟子这就去取记录来！"), nextNodeId = "B_3"),
                Outcome(replyVariants = listOf("宗主说得对，用证据说话。"), nextNodeId = "B_3"))),
            ConversationOption("——不必理会闲言，专心修炼", outcomes = listOf(
                Outcome(replyVariants = listOf("可是他们已经……宗主说的是。"), nextNodeId = "B_3"),
                Outcome(replyVariants = listOf("弟子明白了！实力才是最好的回应。"), nextNodeId = "B_3")))
        )),
        "B_3" to ConversationNode(id = "B_3", options = listOf(
            ConversationOption("——为师信你，不必在意他人之言", outcomes = listOf(
                Outcome(replyVariants = listOf("多谢宗主信任！"), nextNodeId = END_NODE,
                    endingTextVariants = listOf("宗主之恩铭记于心！"), effects = ConversationEffect(loyaltyDelta = 1)),
                Outcome(replyVariants = listOf("弟子知道了。"), nextNodeId = END_NODE,
                    endingTextVariants = listOf("弟子告退。"), effects = ConversationEffect(moralityDelta = 1)))),
            ConversationOption("——先反思自己有无过错", outcomes = listOf(
                Outcome(replyVariants = listOf("宗主教训的是……"), nextNodeId = END_NODE,
                    endingTextVariants = listOf("弟子明白了，这便去反省。", "弟子定当改过。"),
                    effects = ConversationEffect(moralityDelta = 1)),
                Outcome(replyVariants = listOf("弟子确实也有不对之处。"), nextNodeId = END_NODE,
                    endingTextVariants = listOf("多谢宗主指点。"), effects = ConversationEffect(loyaltyDelta = -1)))),
            ConversationOption("——为师去为你调解", outcomes = listOf(
                Outcome(replyVariants = listOf("怎敢劳烦宗主……"), nextNodeId = END_NODE,
                    endingTextVariants = listOf("宗主厚爱，弟子无以为报！"), effects = ConversationEffect(loyaltyDelta = 1)),
                Outcome(replyVariants = listOf("多谢宗主！"), nextNodeId = END_NODE,
                    endingTextVariants = listOf("弟子感激不尽。"), effects = ConversationEffect(intelligenceDelta = 1))))
        ))
    )
)

private val TREE_C = ConversationTree(
    greetingVariants = listOf("宗主，弟子入门之前曾是一介散修……", "宗主可愿听弟子讲讲入宗前的经历？"),
    rootNodeId = "C_2",
    nodes = mapOf(
        "C_2" to ConversationNode(id = "C_2", options = listOf(
            ConversationOption("——说来听听，为师愿闻其详", outcomes = listOf(
                Outcome(replyVariants = listOf("弟子幼时父母皆被妖兽所杀，孤身漂泊多年……"), nextNodeId = "C_3"),
                Outcome(replyVariants = listOf("弟子本是农家之子，偶遇仙缘才踏上修行之路。"), nextNodeId = "C_3"))),
            ConversationOption("——过去的经历都过去了，莫要执念", outcomes = listOf(
                Outcome(replyVariants = listOf("宗主说的是，弟子不该执念于过去。"), nextNodeId = "C_3"),
                Outcome(replyVariants = listOf("弟子明白了，往事如烟。"), nextNodeId = "C_3"))),
            ConversationOption("——你的经历对宗门或许有用", outcomes = listOf(
                Outcome(replyVariants = listOf("弟子曾到过一处秘境，有不少天材地宝……"), nextNodeId = "C_3"),
                Outcome(replyVariants = listOf("弟子在各派之间周旋，对各宗门有所了解。"), nextNodeId = "C_3")))
        )),
        "C_3" to ConversationNode(id = "C_3", options = listOf(
            ConversationOption("——如今宗门便是你的家", outcomes = listOf(
                Outcome(replyVariants = listOf("宗主如此关怀，弟子……"), nextNodeId = END_NODE,
                    endingTextVariants = listOf("宗主恩情，弟子此生不负宗门！"), effects = ConversationEffect(loyaltyDelta = 1)),
                Outcome(replyVariants = listOf("弟子感激不尽。"), nextNodeId = END_NODE,
                    endingTextVariants = listOf("弟子愿为宗门效劳。"), effects = ConversationEffect(moralityDelta = 1)))),
            ConversationOption("——过去的苦难要化为前进的动力", outcomes = listOf(
                Outcome(replyVariants = listOf("宗主说得对！"), nextNodeId = END_NODE,
                    endingTextVariants = listOf("弟子这便去修炼！", "定不负宗主期望！"),
                    effects = ConversationEffect(loyaltyDelta = -1)),
                Outcome(replyVariants = listOf("弟子明白了！"), nextNodeId = END_NODE,
                    endingTextVariants = listOf("苦难皆为修行，弟子谨记。"),
                    effects = ConversationEffect(cultivationDelta = 0.01)))),
            ConversationOption("——将这些见闻记录下来献与宗门", outcomes = listOf(
                Outcome(replyVariants = listOf("是！弟子这便去整理。"), nextNodeId = END_NODE,
                    endingTextVariants = listOf("弟子从命！"), effects = ConversationEffect(intelligenceDelta = 1)),
                Outcome(replyVariants = listOf("宗主英明。"), nextNodeId = END_NODE,
                    endingTextVariants = listOf("弟子这便将所知整理成册。"), effects = ConversationEffect(loyaltyDelta = 1))))
        ))
    )
)

private val ALL_TREES = listOf(TREE_A, TREE_B, TREE_C)
internal fun getAllConversationTrees(): List<ConversationTree> = ALL_TREES

// ── 抽取源（W4-A·A5——RNG 阶段 3·弟子侧）───────────────────────────
// 决策类抽取（交谈树/结果分支/效果增量——结果写入弟子 cultivation/skills）
// 改为引擎侧 CHAT 分区签发（[GameEngine.chatDraw]/[chatDrawDouble]，R1+R3 达规，
// 用户时序独立流不扰动既有分区抽取序）；表现类文本变体（问候/回复/结束语）
// 走 [PresentationRandom]（不落盘）。全局 `Random.Default` 面归零。

/** 表现类文本变体抽取（随机源必传——⑤ 默认值陷阱同款规避）。 */
private fun <T> List<T>.randomOne(random: Random): T = this[random.nextInt(size)]

/**
 * 决策类效果随机化的算术骨架（抽取源形参化——整数/浮点抽取各一路）。
 *
 * 符号保持：原增量为正则抽正、为负则抽负、为 0 保持 0；抽取区间恒为
 * `[1, 6)`（整）与 `[0.01, 0.06)`（浮）。抽取源由调用方注入——生产传
 * [GameEngine.chatDraw]/[GameEngine.chatDrawDouble]（引擎上下文 + CHAT 分区），
 * 测试传确定性桩（本函数因此无需引擎实例即可覆盖）。
 */
internal suspend fun randomizeEffectWith(
    drawInt: suspend (Int, Int) -> Int,
    drawDouble: suspend (Double, Double) -> Double,
    effect: ConversationEffect
): ConversationEffect {
    if (effect.isZero) return effect
    suspend fun Int.signRandom(): Int = if (this > 0) drawInt(1, 6)
    else if (this < 0) -drawInt(1, 6) else 0
    return ConversationEffect(
        moralityDelta = effect.moralityDelta.signRandom(),
        loyaltyDelta = effect.loyaltyDelta.signRandom(),
        intelligenceDelta = effect.intelligenceDelta.signRandom(),
        cultivationDelta = if (effect.cultivationDelta > 0.0) drawDouble(0.01,
            0.06) else if (effect.cultivationDelta < 0.0) -drawDouble(0.01, 0.06) else 0.0
    )
}

/** 决策类效果随机化：抽取走引擎上下文 + CHAT 分区（抽取序不扰动既有分区）。 */
private suspend fun randomizeEffect(engine: GameEngine, effect: ConversationEffect): ConversationEffect =
    randomizeEffectWith(
        drawInt = { from, until -> engine.chatDraw(from, until) },
        drawDouble = { from, until -> engine.chatDrawDouble(from, until) },
        effect = effect
    )

/** 单项会话增量封顶：增幅达 100 上限/降幅触 1 谷底时归零并记录提示 */
private fun capDelta(
    blocked: MutableList<String>,
    delta: Int,
    value: Int?,
    capMessage: String,
    floorMessage: String
): Int = if (value != null && delta > 0 && value >= 100) {
    blocked.add(capMessage); 0
} else if (value != null && delta < 0 && value <= 1) {
    blocked.add(floorMessage); 0
} else {
    delta
}

/** 会话效果技能封顶：按弟子属性上下限截断增量并收集封顶提示文案 */
private fun capEffectBySkills(
    effect: ConversationEffect,
    skills: SkillStats?
): Pair<ConversationEffect, List<String>> {
    val blocked = mutableListOf<String>()
    val capped = effect.copy(
        loyaltyDelta = capDelta(
            blocked, effect.loyaltyDelta, skills?.loyalty,
            "弟子忠诚超群无法再提升", "弟子忠诚已至谷底无法再降低"
        ),
        moralityDelta = capDelta(
            blocked, effect.moralityDelta, skills?.morality,
            "弟子道德超群无法再提升", "弟子道德已至谷底无法再降低"
        ),
        intelligenceDelta = capDelta(
            blocked, effect.intelligenceDelta, skills?.intelligence,
            "弟子智力超群无法再提升", "弟子智力已至谷底无法再降低"
        )
    )
    return capped to blocked
}

/** 构建会话结算效果注释文本：冷却期显示"无效果"，否则按正负着色并追加封顶提示 */
private fun buildConversationEffectText(
    hasCooldown: Boolean,
    effect: ConversationEffect,
    blocked: List<String>
): AnnotatedString = if (hasCooldown) {
    buildAnnotatedString { withStyle(SpanStyle(color = Color.White)) { append("无效果") } }
} else buildAnnotatedString {
    val effectText = effect.toDisplayText()
    if (effectText.isNotEmpty()) {
        val valueColor = if (effect.isPositive) GOLD else if (effect.isNegative) RED else Color.White
        withStyle(SpanStyle(color = valueColor)) { append(effectText) }
    }
    if (blocked.isNotEmpty()) {
        if (effectText.isNotEmpty()) append("\n")
        withStyle(SpanStyle(color = Color.White)) { append(blocked.joinToString("\n")) }
    }
}

// ═══ 主对话框 ═══

@Composable
fun DiscipleChatDialog(
    disciple: DiscipleAggregate, gameYear: Int, hasCooldown: Boolean,
    viewModel: GameViewModel?, onDismiss: () -> Unit
) {
    val bgRes = SpriteResRegistry.resolve("dialogue_bg") ?: R.drawable.dialogue_bg

    var greetingText by remember { mutableStateOf("") }
    var conversationTree by remember { mutableStateOf<ConversationTree?>(null) }
    var chatMessages by remember { mutableStateOf<List<ChatMsg>>(emptyList()) }
    var visibleCount by remember { mutableIntStateOf(0) }
    var isChatDone by remember { mutableStateOf(false) }
    var currentNode by remember { mutableStateOf<ConversationNode?>(null) }
    var currentEffectAnnotated by remember { mutableStateOf(AnnotatedString("")) }
    // 表现类流（文本变体，不落盘——LoadingScreen 同款 UI 位实例化）
    val presentationRandom = remember { PresentationRandom().asKotlinRandom() }
    val scope = rememberCoroutineScope()
    val engine = viewModel?.disciple?.gameEngine

    LaunchedEffect(disciple.id) {
        // 交谈树选择 = 决策类（决定可用结果分支）⇒ 引擎侧 CHAT 分区
        val tree = if (engine != null) ALL_TREES[engine.chatDraw(ALL_TREES.size)]
                   else ALL_TREES[presentationRandom.nextInt(ALL_TREES.size)]
        conversationTree = tree
        greetingText = tree.greetingVariants.randomOne(presentationRandom)
        currentNode = tree.nodes[tree.rootNodeId]
    }

    fun onOptionClick(option: ConversationOption) {
        if (isChatDone) return
        if (engine == null) return
        chatMessages += ChatMsg(text = option.text, isPlayer = true); visibleCount = chatMessages.size
        scope.launch {
            // 结果分支选择 = 决策类（效果模板）⇒ CHAT 分区
            val outcome = option.outcomes[engine.chatDraw(option.outcomes.size)]
            chatMessages += ChatMsg(text = outcome.replyVariants.randomOne(presentationRandom), isPlayer = false)
            visibleCount = chatMessages.size

            if (outcome.nextNodeId == END_NODE) {
                val rawEffect = outcome.effects ?: ConversationEffect()
                val randomized = if (hasCooldown) ConversationEffect() else randomizeEffect(engine, rawEffect)

                val skills = viewModel?.disciple?.getDiscipleById(disciple.id)?.sourceRef?.skills
                val (e, blocked) = capEffectBySkills(randomized, skills)

                currentEffectAnnotated = buildConversationEffectText(hasCooldown, e, blocked)

                if (!e.isZero) viewModel?.disciple?.applyConversationEffects(
                    discipleId = disciple.id, currentYear = gameYear,
                    moralityDelta = e.moralityDelta, loyaltyDelta = e.loyaltyDelta,
                    cultivationDelta = e.cultivationDelta, intelligenceDelta = e.intelligenceDelta
                )
                val ending = outcome.endingTextVariants.ifEmpty { listOf("多谢宗主。") }.randomOne(presentationRandom)
                chatMessages += ChatMsg(text = ending, isPlayer = false); visibleCount = chatMessages.size
                isChatDone = true; currentNode = null
            } else {
                val next = conversationTree?.nodes?.get(outcome.nextNodeId)
                if (next != null) {
                    currentNode = next
                } else {
                    isChatDone = true; currentNode = null
                }
            }
        }
    }

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = disciple.name,
        mode = DialogMode.Full,
        scrollableContent = false,
        backgroundRes = bgRes
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            ChatLeftPanel(disciple = disciple, modifier = Modifier.weight(0.2f).fillMaxHeight())
            VerticalDivider(modifier = Modifier.fillMaxHeight(), thickness = 1.dp, color = GameColors.ButtonDisabled)
            ChatRightPanel(greetingText = greetingText, chatMessages = chatMessages,
                visibleCount = visibleCount, currentNode = currentNode,
                currentEffectAnnotated = currentEffectAnnotated, isChatDone = isChatDone,
                onOptionClick = ::onOptionClick, modifier = Modifier.weight(0.8f).fillMaxHeight())
        }
    }
}

// ═══ 左侧面板 ═══

@Composable
private fun ChatLeftPanel(disciple: DiscipleAggregate, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        val resId = PortraitPool.getResourceId(disciple.portraitRes).takeIf { it != 0 }
            ?: (SpriteResRegistry.resolve("disciple_portrait") ?: 0)
        // 80dp = 240px@3x ≤ 256px 预载位图质量边界，命中缓存
        PortraitImage(
            name = disciple.portraitRes,
            resId = resId,
            modifier = Modifier.size(80.dp).clip(RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.height(12.dp))
        Text(disciple.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Black,
            textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(disciple.realmName, fontSize = 13.sp, color = Color(0xFF666666), textAlign = TextAlign.Center)
    }
}

// ═══ 右侧面板 ═══

@Composable
private fun ChatRightPanel(
    greetingText: String, chatMessages: List<ChatMsg>, visibleCount: Int,
    currentNode: ConversationNode?, currentEffectAnnotated: AnnotatedString, isChatDone: Boolean,
    onOptionClick: (ConversationOption) -> Unit, modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    Column(modifier = modifier.padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (greetingText.isNotEmpty()) ChatMsgBubble(ChatMsg(text = greetingText, isPlayer = false))
            chatMessages.take(visibleCount).forEach { ChatMsgBubble(it) }
            if (isChatDone && currentEffectAnnotated.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Box(modifier = Modifier
                    .wrapContentWidth()
                    .background(Color(0x80000000), RoundedCornerShape(percent = 50))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .align(Alignment.CenterHorizontally),
                    contentAlignment = Alignment.Center) {
                    Text(text = currentEffectAnnotated, fontSize = 12.sp, textAlign = TextAlign.Center)
                }
            }
            if (visibleCount > 0) LaunchedEffect(visibleCount) {
                delay(100); scrollState.animateScrollTo(scrollState.maxValue) }
        }
        if (currentNode != null && !isChatDone) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                currentNode.options.forEach { option ->
                    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        .background(Color(0x80000000))
                        .clickable { onOptionClick(option) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center) {
                        Text(option.text, fontSize = 13.sp, color = Color.White, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

// ═══ 聊天气泡 ═══

private data class ChatMsg(val text: String, val isPlayer: Boolean)

@Composable
private fun ChatMsgBubble(message: ChatMsg) {
    val bubbleRes = SpriteResRegistry.resolve(
        if (message.isPlayer) "dialogue_bubble_right" else "dialogue_bubble_left"
    ) ?: if (message.isPlayer) R.drawable.dialogue_bubble_right
    else R.drawable.dialogue_bubble_left

    // containerSize 单位是像素，需经 LocalDensity 换算为 dp（勿直接 .dp 使用像素值）
    val bubbleMaxWidth = with(LocalDensity.current) {
        (LocalWindowInfo.current.containerSize.width * 0.65f).toDp()
    }

    Row(modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isPlayer) Arrangement.End else Arrangement.Start) {
        Box(modifier = Modifier.widthIn(max = bubbleMaxWidth).wrapContentHeight(),
            contentAlignment = Alignment.Center) {
            Image(painter = painterResource(id = bubbleRes), contentDescription = null,
                modifier = Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
            Text(message.text, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                fontSize = 14.sp, color = Color.Black, textAlign = TextAlign.Center, lineHeight = 22.sp)
        }
    }
}
