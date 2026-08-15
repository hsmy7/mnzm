package com.xianxia.sect.ui.game.components.detail

import com.xianxia.sect.ui.components.rememberChasingProgress
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.BoxWithConstraints
import com.xianxia.sect.ui.components.DialogMode
import com.xianxia.sect.ui.components.UnifiedGameDialog
import com.xianxia.sect.feature.game.R
import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.model.DiscipleAggregate
import com.xianxia.sect.core.model.ElderSlots
import com.xianxia.sect.core.model.SectPolicies
import com.xianxia.sect.core.model.currentHp
import com.xianxia.sect.core.model.currentMp
import com.xianxia.sect.core.model.teaching
import com.xianxia.sect.core.util.GameUtils

import com.xianxia.sect.ui.components.CloseButton
import com.xianxia.sect.ui.theme.GameColors



internal fun calculatePreachingBonusesForDisplay(
    disciple: DiscipleAggregate,
    elderSlots: ElderSlots?,
    allDisciples: List<DiscipleAggregate>,
    sectPolicies: SectPolicies? = null
): Triple<Double, Double, Double> {
    if (elderSlots == null) return Triple(0.0, 0.0, 0.0)
    val allDisciplesById = allDisciples.associateBy { it.id }
    val dtype = disciple.discipleType
    val dRealm = disciple.realm
    var elderBonus = 0.0
    var mastersBonus = 0.0

    if (dtype == "outer") {
        elderBonus += teachingBonus(
            elderId = elderSlots.preachingElder,
            dRealm = dRealm,
            allDisciplesById = allDisciplesById,
            minTeaching = ELDER_TEACHING_MIN,
            rate = ELDER_TEACHING_RATE,
            cap = ELDER_TEACHING_CAP
        )
        for (slot in elderSlots.preachingMasters) {
            mastersBonus += teachingBonus(
                elderId = slot.discipleId,
                dRealm = dRealm,
                allDisciplesById = allDisciplesById,
                minTeaching = MASTER_TEACHING_MIN,
                rate = MASTER_TEACHING_RATE,
                cap = MASTER_TEACHING_CAP
            )
        }
    }

    if (dtype == "inner") {
        elderBonus += teachingBonus(
            elderId = elderSlots.qingyunPreachingElder,
            dRealm = dRealm,
            allDisciplesById = allDisciplesById,
            minTeaching = ELDER_TEACHING_MIN,
            rate = ELDER_TEACHING_RATE,
            cap = ELDER_TEACHING_CAP
        )
        for (slot in elderSlots.qingyunPreachingMasters) {
            mastersBonus += teachingBonus(
                elderId = slot.discipleId,
                dRealm = dRealm,
                allDisciplesById = allDisciplesById,
                minTeaching = MASTER_TEACHING_MIN,
                rate = MASTER_TEACHING_RATE,
                cap = MASTER_TEACHING_CAP
            )
        }
    }

    var cultivationSubsidyBonus = 0.0
    if (sectPolicies != null && sectPolicies.cultivationSubsidy && dRealm > 5) {
        cultivationSubsidyBonus = GameConfig.PolicyConfig.CULTIVATION_SUBSIDY_EFFECT
    }

    return Triple(elderBonus, mastersBonus, cultivationSubsidyBonus)
}

/** 讲经/授徒加成常量（calculatePreachingBonusesForDisplay 拆分时提取原字面量） */
private const val ELDER_TEACHING_MIN = 80
private const val ELDER_TEACHING_RATE = 0.0025
private const val ELDER_TEACHING_CAP = 0.10
private const val MASTER_TEACHING_MIN = 60
private const val MASTER_TEACHING_RATE = 0.001
private const val MASTER_TEACHING_CAP = 0.05

/** 单个长老/师父的讲经加成（calculatePreachingBonusesForDisplay 拆分）：境界达标且教学达标才计 */
// 拆分搬移:多出口与原函数一致
@Suppress("ReturnCount")
private fun teachingBonus(
    elderId: String,
    dRealm: Int,
    allDisciplesById: Map<String, DiscipleAggregate>,
    minTeaching: Int,
    rate: Double,
    cap: Double
): Double {
    if (elderId.isEmpty()) return 0.0
    val elder = allDisciplesById[elderId] ?: return 0.0
    if (!elder.isAlive || dRealm < elder.realm) return 0.0
    val t = elder.getBaseStats().teaching
    if (t < minTeaching) return 0.0
    return ((t - minTeaching) * rate).coerceAtMost(cap)
}

@Composable
fun HpMpBars(
    disciple: DiscipleAggregate,
    maxHpOverride: Int? = null,
    maxMpOverride: Int? = null,
    gameSpeed: Int = 1
) {
    val maxHp = maxHpOverride ?: disciple.maxHp
    val maxMp = maxMpOverride ?: disciple.maxMp
    val rawCurrentHp = disciple.currentHp
    val rawCurrentMp = disciple.currentMp
    val currentHpDisplay = if (rawCurrentHp < 0) maxHp else rawCurrentHp
    val currentMpDisplay = if (rawCurrentMp < 0) maxMp else rawCurrentMp
    val hpFraction = if (maxHp > 0) (currentHpDisplay.toFloat() / maxHp).coerceIn(0f, 1f) else 1f
    val mpFraction = if (maxMp > 0) (currentMpDisplay.toFloat() / maxMp).coerceIn(0f, 1f) else 1f

    // 动画状态 — 统一 100ms lerp 追赶，下降时自动 snap
    val animatedHpProgress by rememberChasingProgress(
        target = hpFraction,
        paused = gameSpeed == 0
    )
    val animatedMpProgress by rememberChasingProgress(
        target = mpFraction,
        paused = gameSpeed == 0
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        HpMpBarColumn(
            label = "气血",
            currentDisplay = currentHpDisplay,
            maxValue = maxHp,
            animatedProgress = animatedHpProgress,
            barColor = GameColors.HpBar
        )
        HpMpBarColumn(
            label = "灵力",
            currentDisplay = currentMpDisplay,
            maxValue = maxMp,
            animatedProgress = animatedMpProgress,
            barColor = GameColors.MpBar
        )
    }
}

/** 单条气血/灵力条（HpMpBars 拆分）：标签 + 进度条 + 数值 */
@Composable
private fun RowScope.HpMpBarColumn(
    label: String,
    currentDisplay: Int,
    maxValue: Int,
    animatedProgress: Float,
    barColor: Color
) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            color = Color.Black,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(1.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
        ) {
            drawRect(Color(0xFFE8E8E8))
            drawRect(
                barColor,
                size = Size(size.width * animatedProgress, size.height)
            )
        }
        Text(
            text = "$currentDisplay/$maxValue",
            fontSize = 7.sp,
            color = Color.Black,
            fontWeight = FontWeight.Bold,
            style = TextStyle(
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            )
        )
    }
}

@Composable
fun InfoItem(value: String, modifier: Modifier = Modifier, color: Color = Color.Black) {
    Text(
        text = value,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = modifier
    )
}

@Composable
fun BreakthroughDetailDialog(
    detail: DiscipleStatCalculator.BreakthroughBonusDetail,
    onDismiss: () -> Unit
) {
    val items = breakthroughDetailItems(detail = detail)

    UnifiedGameDialog(
        onDismissRequest = onDismiss,
        title = "",
        mode = DialogMode.Auto,
        scrimEnabled = false,
        showHeader = false,
        showCloseButton = false
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
        ) {
            Image(
                painter = painterResource(id = R.drawable.bg_horizontal),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop
            )
            val btWidth = maxWidth
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                BreakthroughDetailHeader(onDismiss = onDismiss)
                HorizontalDivider(color = Color(0xFFDDDDDD), thickness = 1.dp)
                if (items.isEmpty()) {
                    Text("无额外加成", fontSize = 13.sp, color = Color.Black)
                } else {
                    BreakthroughBonusGrid(
                        items = items,
                        availableWidth = btWidth
                    )
                    HorizontalDivider(
                        color = Color(0xFFDDDDDD),
                        thickness = 1.dp
                    )
                    BreakthroughSummary(detail = detail)
                }
            }
        }
    }
}

/** 突破率明细条目（BreakthroughDetailDialog 拆分） */
private fun breakthroughDetailItems(
    detail: DiscipleStatCalculator.BreakthroughBonusDetail
): List<Pair<String, Double>> = buildList {
    add("基础突破率" to detail.baseChance)
    if (detail.innerElderBonus > 0) add("内门长老加成" to detail.innerElderBonus)
    if (detail.outerElderBonus > 0) add("外门长老加成" to detail.outerElderBonus)
    if (detail.talentBonus != 0.0) add("天赋加成" to detail.talentBonus)
    if (detail.soulPowerBonus > 0) add("神魂加成" to detail.soulPowerBonus)
    if (detail.pillBonus > 0) add("丹药加成" to detail.pillBonus)
    if (detail.adBonus > 0) add("玉符加成" to detail.adBonus)
    if (detail.masterDiscipleBonus > 0) add("师徒加成" to detail.masterDiscipleBonus)
    if (detail.selfComprehensionBonus > 0) add("悟性加成" to detail.selfComprehensionBonus)
    if (detail.griefPenalty > 0) add("丧亲减益" to -detail.griefPenalty)
    if (detail.lifespanPenalty > 0) add("寿元将尽" to -detail.lifespanPenalty)
}

/** 突破率详情标题行（BreakthroughDetailDialog 拆分） */
@Composable
private fun BreakthroughDetailHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "突破率详情",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            modifier = Modifier.weight(1f)
        )
        CloseButton(onClick = onDismiss)
    }
}

/** 突破率加成网格（BreakthroughDetailDialog 拆分）：按可用宽度分列 */
@Composable
private fun BreakthroughBonusGrid(
    items: List<Pair<String, Double>>,
    availableWidth: Dp
) {
    val columnCount = maxOf(1, (availableWidth / 140.dp).toInt())
    val rows = items.chunked(columnCount)
    rows.forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            row.forEach { (label, value) ->
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                    Text(
                        text = "${if (value >= 0) "+" else ""}${GameUtils.formatPercent(value)}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (value >= 0) GameColors.Success else GameColors.Error
                    )
                }
            }
            repeat(columnCount - row.size) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

/** 突破率汇总 + 公式行（BreakthroughDetailDialog 拆分） */
@Composable
private fun BreakthroughSummary(detail: DiscipleStatCalculator.BreakthroughBonusDetail) {
    Text(
        text = "最终突破率 ${GameUtils.formatPercent(detail.total)}",
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = Color.Black
    )
    // 公式行：说明乘区乘法计算结果
    val positiveSum = detail.innerElderBonus + detail.outerElderBonus +
        detail.talentBonus + detail.soulPowerBonus +
        detail.pillBonus + detail.masterDiscipleBonus
    val penaltySum = detail.griefPenalty + detail.lifespanPenalty
    val basePct = GameUtils.formatPercent(detail.baseChance)
    val posPct = GameUtils.formatPercent(positiveSum)
    val penPct = GameUtils.formatPercent(penaltySum)
    val adPct = GameUtils.formatPercent(detail.adBonus)
    val totalPct = GameUtils.formatPercent(detail.total)
    val formulaParts = buildString {
        append(basePct)
        if (positiveSum > 0.0 || penaltySum > 0.0) {
            append(" × (1 + ${posPct})")
        }
        if (penaltySum > 0.0) {
            append(" × (1 - ${penPct})")
        }
        if (detail.adBonus > 0.0) {
            append(" + ${adPct}")
        }
        append(" = ${totalPct}")
    }
    Text(
        text = formulaParts,
        fontSize = 10.sp,
        color = Color(0xFF888888)
    )
}
